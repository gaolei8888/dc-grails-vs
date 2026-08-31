const vscode = require('vscode');
const { spawn } = require('child_process');
const fs = require('fs');
const path = require('path');

// Dedicated processes for long‑running tasks (runApp and debug)
let gradleRunProcess = null;
let gradleDebugProcess = null;

// The JVM prints this once the JDWP agent is actually listening. Waiting for it
// is the only reliable attach trigger -- a fixed delay races Gradle's configure
// and compile phases, which routinely take far longer than any delay you'd pick.
const JDWP_READY_RE = /Listening for transport dt_socket at address:\s*(\d+)/;

// How long to wait for that line before giving up (cold start + full compile).
const JDWP_WAIT_MS = 300000;

/**
 * Absolute path to the project's Gradle wrapper.
 *
 * Absolute, not a bare `gradlew.bat`: cmd.exe does not reliably resolve a command
 * name against the working directory, so a bare name fails with "'gradlew.bat' is
 * not recognized" even when the file is sitting right there.
 */
function gradleWrapperPath(cwd) {
  return path.join(cwd, process.platform === 'win32' ? 'gradlew.bat' : 'gradlew');
}

function requireWorkspace(what) {
  if (!vscode.workspace.workspaceFolders) {
    vscode.window.showErrorMessage(`No workspace folder found. Open your ${what} first.`);
    return null;
  }
  return vscode.workspace.workspaceFolders[0].uri.fsPath;
}

/**
 * How the app is started.
 *
 * Plenty of Grails projects do not start through gradlew directly but through a
 * wrapper script that first exports what the app needs -- run_dev.sh and friends,
 * usually little more than `exec ./gradlew bootRun "$@"` behind a pile of export
 * lines. Running gradlew ourselves drops every one of those silently, and the
 * failure then surfaces deep inside the application instead of here. So both the
 * command and a set of variables to inject are configurable.
 */
function launchSettings() {
  const config = vscode.workspace.getConfiguration('grails');
  return {
    command: (config.get('run.command', '') || '').trim(),
    debugCommand: (config.get('run.debugCommand', '') || '').trim(),
    env: config.get('run.env', {}) || {},
    extraArgs: config.get('run.args', []) || []
  };
}

/** The parent environment plus the configured additions. */
function childEnvironment(extra) {
  const env = Object.assign({}, process.env);
  for (const key of Object.keys(extra)) {
    env[key] = String(extra[key]);
  }
  return env;
}

/**
 * Kills a build and everything it started.
 *
 * proc.kill() reaches only the process we spawned. Gradle forks the application
 * into a JVM of its own, so killing the wrapper leaves that JVM running and
 * holding the debug port and the HTTP port -- one leaked per Debug App, until the
 * next run fails with "transport error 202: bind failed: Address already in use"
 * and it looks like the port was never released.
 *
 * Windows has taskkill /T for the whole tree. Elsewhere the child leads its own
 * process group (see `detached` below) and a signal to the negated pid reaches
 * all of it.
 */
function killProcessTree(proc) {
  if (!proc || proc.killed || typeof proc.pid !== 'number') {
    return;
  }
  if (process.platform === 'win32') {
    spawn('taskkill', ['/pid', String(proc.pid), '/T', '/F'], { stdio: 'ignore' })
      .on('error', () => proc.kill());
    return;
  }
  try {
    process.kill(-proc.pid, 'SIGTERM');
  } catch (err) {
    proc.kill();
  }
}

/**
 * spawn() a build and stream its output into a channel.
 *
 * Deliberately NOT exec(): child_process.exec buffers the whole output in memory
 * and kills the child once it exceeds maxBuffer (1 MB by default). A Grails app
 * blows past that in minutes, so the server would die on its own with an error
 * that points nowhere near the cause. spawn() streams and has no such limit.
 *
 * @param {object} options
 * @param {string[]} [options.args]       arguments for the Gradle wrapper
 * @param {string} [options.commandLine]  a configured command line, used instead
 * @param {string} options.cwd            working directory
 * @param {string} options.channelName    output channel to create/show
 * @param {(line: string) => void} [options.onLine]  called per complete stdout line
 */
function spawnBuild(options) {
  const args = options.args || [];
  const commandLine = options.commandLine || '';
  const settings = launchSettings();
  const env = childEnvironment(settings.env);
  const wrapper = gradleWrapperPath(options.cwd);

  // Name the folder that is wrong rather than letting the shell report a missing
  // command. Opening the directory *above* the project is an easy mistake, and
  // "'gradlew.bat' is not recognized" points nowhere near it.
  if (!commandLine && !fs.existsSync(wrapper)) {
    vscode.window.showErrorMessage(
      `No Gradle wrapper in ${options.cwd}. Open the folder that contains gradlew `
      + '(the Grails project root), or set grails.run.command.');
    return null;
  }

  const channel = vscode.window.createOutputChannel(options.channelName);
  channel.show(true);
  channel.appendLine('> ' + (commandLine || wrapper + ' ' + args.join(' ')));
  const injected = Object.keys(settings.env);
  if (injected.length > 0) {
    channel.appendLine('  (with grails.run.env: ' + injected.join(', ') + ')');
  }
  channel.appendLine('');

  // shell:true either way: on Windows the wrapper is a .bat that needs one, and a
  // configured command line is a shell command by definition. The only user input
  // that reaches here is grails.run.command, whose whole purpose is to be the
  // command that runs.
  // A configured command line is a shell command by definition, so it goes through
  // a shell. The wrapper does not: it is an absolute path, and cmd.exe runs the
  // .bat directly, which also keeps a path containing spaces intact.
  // detached only off Windows, and only to make the child a process group leader
  // so killProcessTree can signal the group. The parent still waits on it.
  const detached = process.platform !== 'win32';
  const proc = commandLine
    ? spawn(commandLine, { cwd: options.cwd, shell: true, env, detached })
    : process.platform === 'win32'
      ? spawn('cmd.exe', ['/d', '/s', '/c', wrapper].concat(args),
              { cwd: options.cwd, env })
      : spawn(wrapper, args, { cwd: options.cwd, env, detached });

  const onLine = options.onLine;
  let pending = '';
  const pump = chunk => {
    const text = chunk.toString();
    channel.append(text);
    if (!onLine) return;
    pending += text;
    const lines = pending.split(/\r?\n/);
    pending = lines.pop();
    lines.forEach(onLine);
  };
  proc.stdout.on('data', pump);
  proc.stderr.on('data', pump);
  proc.on('error', err => {
    channel.appendLine(`\n[failed to start] ${err.message}`);
    vscode.window.showErrorMessage(
      `Could not run ${commandLine || wrapper}: ${err.message}`);
  });
  return proc;
}

/**
 * With grails.debug.adapter set to 'java' the session goes to vscode-java-debug.
 * That extension is not a hard dependency of this one, so say so plainly instead
 * of letting startDebugging() fail with an opaque error.
 */
function javaDebugAvailable() {
  return !!vscode.extensions.getExtension('vscjava.vscode-java-debug');
}

/** 'groovy' (this extension's adapter) or 'java' (vscode-java-debug). */
function debugAdapterType() {
  return vscode.workspace.getConfiguration('grails').get('debug.adapter', 'groovy');
}

/**
 * The java to run the debug adapter with. It needs 17 or later, and it is not
 * necessarily the one VSCode itself runs on.
 */
function javaExecutable() {
  const configured = vscode.workspace.getConfiguration('grails').get('debug.javaPath', '');
  if (configured) return configured;
  const home = process.env.JAVA_HOME;
  if (home) {
    const candidate = path.join(home, 'bin', process.platform === 'win32' ? 'java.exe' : 'java');
    if (fs.existsSync(candidate)) return candidate;
  }
  return 'java';
}

/**
 * Where to look for the .groovy files behind a stack frame. The JVM reports a
 * frame's source as a package-relative path, so the adapter only needs the roots
 * those paths hang off -- for Grails, the artefact directories.
 */
function defaultSourcePaths(workspaceFolder) {
  const candidates = [
    'grails-app/controllers', 'grails-app/services', 'grails-app/domain',
    'grails-app/init', 'grails-app/utils', 'grails-app/jobs', 'grails-app/taglib',
    'src/main/groovy', 'src/main/java', 'src/test/groovy'
  ];
  return candidates
    .map(relative => path.join(workspaceFolder, relative))
    .filter(candidate => fs.existsSync(candidate));
}

/**
 * Starts the Groovy debug adapter -- a JVM of its own, because JDI is a Java API
 * and there is no way to speak it from Node.
 *
 * jdk.jdi is not in the default root module set, so without --add-modules the
 * adapter starts and then fails to find com.sun.jdi at all.
 */
function makeAdapterFactory(context) {
  return {
    createDebugAdapterDescriptor() {
      const jar = context.asAbsolutePath(path.join('dist', 'groovy-dap.jar'));
      if (!fs.existsSync(jar)) {
        vscode.window.showErrorMessage(
          `The Groovy debug adapter is missing (${jar}). Run "npm run build:server".`);
        return undefined;
      }
      return new vscode.DebugAdapterExecutable(
        javaExecutable(), ['--add-modules', 'jdk.jdi', '-jar', jar]);
    }
  };
}

/** Fills in the parts of a launch.json entry a user should not have to write. */
const groovyConfigurationProvider = {
  resolveDebugConfiguration(folder, config) {
    if (!config.type) {
      // Started from the Run view with no launch.json at all.
      config.type = 'groovy';
      config.request = 'attach';
      config.name = 'Attach to Grails (Groovy)';
      config.port = 5005;
    }
    if (!config.sourcePaths && folder) {
      config.sourcePaths = defaultSourcePaths(folder.uri.fsPath);
    }
    return config;
  }
};

/**
 * Builds the Gradle invocation for one command.
 *
 * A hyphenated name is a Grails CLI command, not a Gradle task. Grails' own
 * commands -- create-controller, dbm-update, s2-quickstart -- have no Gradle task
 * of their own; the Grails Gradle plugin exposes them through a `runCommand` task
 * that takes the whole command line in -Pargs. Everything else in the two lists
 * is a real task name and is passed as one.
 *
 * The previous version passed neither: it ran `gradlew -Pargs="<name>"`, which
 * sets a project property and names no task at all, so Gradle fell back to its
 * default task and none of the 199 commands did what its title said.
 */
function gradleArgsFor(commandName, extraArgs) {
  const extra = (extraArgs || '').trim();
  if (commandName.includes('-')) {
    const payload = extra ? `${commandName} ${extra}` : commandName;
    // One argument containing spaces, so it has to carry its own quotes. Double
    // quotes inside are dropped rather than escaped: cmd.exe and POSIX shells
    // disagree about how to escape them, and no Grails command needs one.
    return ['runCommand', `-Pargs="${payload.replace(/"/g, '')}"`];
  }
  return extra ? [commandName].concat(extra.split(/\s+/)) : [commandName];
}

/**
 * Helper function to run any Grails/Gradle command via the Gradle wrapper.
 * @param {string} commandName - The command to run (e.g. "clean", "bootRun", etc.).
 * @param {boolean} promptForArgs - Whether to prompt the user for additional arguments.
 */
function runGrailsCommandViaGradle(commandName, promptForArgs = true) {
  const workspaceFolder = requireWorkspace('Grails/Gradle project');
  if (!workspaceFolder) {
    return;
  }

  const argsPromise = promptForArgs
    ? Promise.resolve(vscode.window.showInputBox({
        prompt: `Enter additional arguments for: ${commandName} (optional)`
      }))
    : Promise.resolve('');

  argsPromise.then(extraArgs => {
    if (extraArgs === undefined) {
      return; // input box dismissed -- do not run the command anyway
    }
    const proc = spawnBuild({
      args: gradleArgsFor(commandName, extraArgs),
      cwd: workspaceFolder,
      channelName: `Gradle: ${commandName}`
    });
    if (!proc) {
      return; // spawnBuild already said what was wrong
    }
    proc.on('close', code => {
      vscode.window.showInformationMessage(`Task '${commandName}' exited with code ${code}`);
    });
  });
}

/**
 * The command tree in the sidebar.
 *
 * The extension registers 199 commands. In the palette they are only reachable by
 * remembering the name, which for create-domain-class or dbm-gorm-diff is most of
 * the work. Grouping the ones people actually reach for makes them clickable; the
 * rest stay in the palette.
 */
const COMMAND_TREE = [
  {
    label: 'Run', icon: 'play-circle', items: [
      { label: 'Run App', command: 'grails.runApp', icon: 'play' },
      { label: 'Debug App', command: 'grails.debug', icon: 'debug-alt' },
      { label: 'Stop App', command: 'grails.stopApp', icon: 'debug-stop' },
      { label: 'Stop Debug App', command: 'grails.stopDebug', icon: 'debug-stop' }
    ]
  },
  {
    label: 'Create', icon: 'new-file', items: [
      { label: 'Controller', command: 'grails.create-controller' },
      { label: 'Domain Class', command: 'grails.create-domain-class' },
      { label: 'Service', command: 'grails.create-service' },
      { label: 'Taglib', command: 'grails.create-taglib' },
      { label: 'Interceptor', command: 'grails.create-interceptor' },
      { label: 'Scaffold Controller', command: 'grails.create-scaffold-controller' },
      { label: 'Unit Test', command: 'grails.create-unit-test' },
      { label: 'Integration Test', command: 'grails.create-integration-test' }
    ]
  },
  {
    label: 'Generate', icon: 'sparkle', items: [
      { label: 'All', command: 'grails.generate-all' },
      { label: 'Controller', command: 'grails.generate-controller' },
      { label: 'Service', command: 'grails.generate-service' },
      { label: 'Views', command: 'grails.generate-views' }
    ]
  },
  {
    label: 'Database Migration', icon: 'database', items: [
      { label: 'dbm-update', command: 'grails.dbm-update' },
      { label: 'dbm-status', command: 'grails.dbm-status' },
      { label: 'dbm-gorm-diff', command: 'grails.dbm-gorm-diff' }
    ]
  },
  {
    label: 'Gradle', icon: 'tools', items: [
      { label: 'build', command: 'gradle.build' },
      { label: 'test', command: 'gradle.test' },
      { label: 'clean', command: 'gradle.clean' },
      { label: 'war', command: 'gradle.war' },
      { label: 'integrationTest', command: 'gradle.integrationTest' }
    ]
  }
];

class GrailsCommandsProvider {
  getTreeItem(node) {
    return node;
  }

  getChildren(node) {
    if (!node) {
      return COMMAND_TREE.map(group => {
        const item = new vscode.TreeItem(
          group.label, vscode.TreeItemCollapsibleState.Expanded);
        item.iconPath = new vscode.ThemeIcon(group.icon);
        item.children = group.items;
        return item;
      });
    }
    return (node.children || []).map(entry => {
      const item = new vscode.TreeItem(entry.label, vscode.TreeItemCollapsibleState.None);
      item.command = { command: entry.command, title: entry.label };
      item.iconPath = new vscode.ThemeIcon(entry.icon || 'terminal');
      // The palette id, so the tree stays a shortcut to the same commands rather
      // than a second way of doing the same thing differently.
      item.tooltip = entry.command;
      return item;
    });
  }
}

// Status bar. Run/Debug/Stop are stateful -- whether the app is up is the thing
// you want to see without looking for it -- so they live here rather than in the
// tree, which is for actions that take an argument.
let statusRun = null;
let statusDebug = null;
let statusStop = null;

function refreshStatusBar() {
  if (!statusRun) {
    return;
  }
  const busy = !!gradleRunProcess || !!gradleDebugProcess;
  if (busy) {
    statusRun.hide();
    statusDebug.hide();
    statusStop.text = gradleDebugProcess
      ? '$(debug-stop) Stop Grails (debug)'
      : '$(debug-stop) Stop Grails';
    statusStop.command = gradleDebugProcess ? 'grails.stopDebug' : 'grails.stopApp';
    statusStop.show();
  } else {
    statusStop.hide();
    statusRun.show();
    statusDebug.show();
  }
}

function createStatusBar(context) {
  // Right-aligned, high priority so the pair stays together and near the left of
  // that group rather than drifting between other extensions' items.
  statusRun = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 100);
  statusRun.text = '$(play) Grails';
  statusRun.tooltip = 'Grails: Run App';
  statusRun.command = 'grails.runApp';

  statusDebug = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 99);
  statusDebug.text = '$(debug-alt) Debug';
  statusDebug.tooltip = 'Grails: Debug App';
  statusDebug.command = 'grails.debug';

  statusStop = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 98);
  statusStop.tooltip = 'Stop the running Grails app';

  context.subscriptions.push(statusRun, statusDebug, statusStop);
  refreshStatusBar();
}

function activate(context) {
  // The debug adapter that makes .groovy breakpoints bind at all. Registered
  // unconditionally so a hand-written launch.json of type "groovy" works whether
  // or not the app was started through this extension.
  context.subscriptions.push(
    vscode.debug.registerDebugAdapterDescriptorFactory('groovy', makeAdapterFactory(context)),
    vscode.debug.registerDebugConfigurationProvider('groovy', groovyConfigurationProvider),
    vscode.window.registerTreeDataProvider('grailsCommands', new GrailsCommandsProvider())
  );
  createStatusBar(context);

  // ===== Dedicated Commands for Running the App (Grails-specific) =====

  // 1) Grails (Gradle): Run App (Normal Mode)
  const runAppGradleCommand = vscode.commands.registerCommand('grails.runApp', () => {
    if (gradleRunProcess) {
      vscode.window.showWarningMessage('Grails app is already running.');
      return;
    }
    if (!vscode.workspace.workspaceFolders) {
      vscode.window.showErrorMessage('No workspace folder found. Open your Grails/Gradle project first.');
      return;
    }
    const workspaceFolder = vscode.workspace.workspaceFolders[0].uri.fsPath;
    vscode.window.showInformationMessage('Starting Grails app (normal mode)...');
    const runSettings = launchSettings();
    gradleRunProcess = spawnBuild({
      args: ['bootRun'].concat(runSettings.extraArgs),
      commandLine: runSettings.command,
      cwd: workspaceFolder,
      channelName: 'Grails - Normal'
    });
    if (!gradleRunProcess) {
      return; // spawnBuild already said what was wrong
    }
    refreshStatusBar();
    gradleRunProcess.on('close', code => {
      vscode.window.showInformationMessage(`Grails (normal) exited with code ${code}`);
      gradleRunProcess = null;
      refreshStatusBar();
    });
  });

  // 2) Grails (Gradle): Stop App (Normal Mode)
  const stopAppGradleCommand = vscode.commands.registerCommand('grails.stopApp', () => {
    if (!gradleRunProcess) {
      vscode.window.showInformationMessage('No Grails (normal) process is running.');
      return;
    }
    vscode.window.showInformationMessage('Stopping Grails app (normal mode)...');
    killProcessTree(gradleRunProcess);
    gradleRunProcess = null;
    refreshStatusBar();
  });

  // 3) Grails (Gradle): Debug App
  const debugGrailsAppCommand = vscode.commands.registerCommand('grails.debug', async () => {
    if (gradleDebugProcess) {
      vscode.window.showWarningMessage('A Grails debug process is already running.');
      return;
    }
    if (!vscode.workspace.workspaceFolders) {
      vscode.window.showErrorMessage('No workspace folder found. Open your Grails/Gradle project first.');
      return;
    }
    const workspaceFolder = vscode.workspace.workspaceFolders[0].uri.fsPath;

    const adapterType = debugAdapterType();
    if (adapterType === 'java' && !javaDebugAvailable()) {
      const pick = await vscode.window.showErrorMessage(
        'grails.debug.adapter is set to "java", which needs the "Debugger for Java" extension '
        + '(vscjava.vscode-java-debug). It is not installed.',
        'Show extension'
      );
      if (pick === 'Show extension') {
        vscode.commands.executeCommand('workbench.extensions.search', 'vscjava.vscode-java-debug');
      }
      return;
    }

    vscode.window.showInformationMessage('Starting Grails in debug mode...');

    let attached = false;
    let giveUp = null;
    const attach = async port => {
      if (attached) return;
      attached = true;
      clearTimeout(giveUp);
      try {
        // type 'groovy' is this extension's adapter; 'java' hands the session to
        // vscode-java-debug, which cannot bind breakpoints in .groovy files
        // because it resolves class names through JDT. Kept as an escape hatch.
        await vscode.debug.startDebugging(vscode.workspace.workspaceFolders[0], {
          name: 'Attach to Grails',
          type: adapterType,
          request: 'attach',
          hostName: 'localhost',
          port,
          sourcePaths: defaultSourcePaths(workspaceFolder)
        });
        vscode.window.showInformationMessage(
          `Debugger attached to Grails on port ${port} (${adapterType} adapter).`);
      } catch (err) {
        vscode.window.showErrorMessage(`Failed to attach debugger: ${err.message}`);
      }
    };

    // Attach only once the JVM says it is listening. A fixed setTimeout races
    // Gradle's configure + compile phases, which on a cold start run well past
    // any delay you would pick -- the old 3s version failed most cold starts.
    const debugSettings = launchSettings();
    // A wrapper script ending in `exec ./gradlew bootRun "$@"` forwards extra
    // arguments, so appending --debug-jvm to grails.run.command is the useful
    // default; grails.run.debugCommand overrides it when it is not.
    const debugCommandLine = debugSettings.debugCommand
      || (debugSettings.command ? `${debugSettings.command} --debug-jvm` : '');

    gradleDebugProcess = spawnBuild({
      args: ['bootRun', '--debug-jvm'].concat(debugSettings.extraArgs),
      commandLine: debugCommandLine,
      cwd: workspaceFolder,
      channelName: 'Grails - Debug',
      onLine: line => {
        const m = JDWP_READY_RE.exec(line);
        if (m) attach(Number(m[1]));
      }
    });

    if (!gradleDebugProcess) {
      return; // spawnBuild already said what was wrong
    }
    refreshStatusBar();

    giveUp = setTimeout(() => {
      if (attached) return;
      attached = true;
      vscode.window.showErrorMessage(
        `Gave up waiting for the JVM to open its debug port after ${JDWP_WAIT_MS / 1000}s. ` +
        'See the "Grails - Debug" output channel.'
      );
    }, JDWP_WAIT_MS);

    gradleDebugProcess.on('close', code => {
      clearTimeout(giveUp);
      vscode.window.showInformationMessage(`Grails debug process exited with code ${code}`);
      gradleDebugProcess = null;
      refreshStatusBar();
    });
  });

  // 4) Grails (Gradle): Stop Debug App
  const stopDebugGrailsCommand = vscode.commands.registerCommand('grails.stopDebug', () => {
    if (!gradleDebugProcess) {
      vscode.window.showInformationMessage('No Grails debug process is running.');
      return;
    }
    vscode.window.showInformationMessage('Stopping Grails debug process...');
    killProcessTree(gradleDebugProcess);
    gradleDebugProcess = null;
    refreshStatusBar();
  });

  // ===== Generic Grails CLI Commands (if needed) =====
  const genericCommands = [
    "assemble",
    "bug-report",
    "clean",
    "compile",
    "console",
    "create-command",
    "create-controller",
    "create-domain-class",
    "create-integration-test",
    "create-interceptor",
    "create-scaffold-controller",
    "create-script",
    "create-service",
    "create-taglib",
    "create-unit-test",
    "create-web-socket",
    "create-web-socket-config",
    "dbm-changelog-sync",
    "dbm-changelog-sync-sql",
    "dbm-changelog-to-groovy",
    "dbm-clear-checksums",
    "dbm-create-changelog",
    "dbm-db-doc",
    "dbm-diff",
    "dbm-drop-all",
    "dbm-future-rollback-count-sql",
    "dbm-future-rollback-sql",
    "dbm-generate-changelog",
    "dbm-generate-gorm-changelog",
    "dbm-gorm-diff",
    "dbm-list-locks",
    "dbm-mark-next-changeset-ran",
    "dbm-mark-next-changeset-ran-sql",
    "dbm-previous-changeset-sql",
    "dbm-release-locks",
    "dbm-rollback",
    "dbm-rollback-count",
    "dbm-rollback-count-sql",
    "dbm-rollback-sql",
    "dbm-rollback-to-date",
    "dbm-rollback-to-date-sql",
    "dbm-status",
    "dbm-tag",
    "dbm-update",
    "dbm-update-count",
    "dbm-update-count-sql",
    "dbm-update-sql",
    "dbm-validate",
    "dependency-report",
    "generate-all",
    "generate-async-controller",
    "generate-controller",
    "generate-rx-all",
    "generate-rx-controller",
    "generate-service",
    "generate-views",
    "gradle",
    "help",
    "install-joda-time-gorm-mappings",
    "install-templates",
    "list-plugins",
    "open",
    "plugin-info",
    "run-command",
    "run-script",
    "s2-create-persistent-token",
    "s2-create-role-hierarchy-entry",
    "s2-quickstart",
    "s2ui-create-challenge-questions",
    "s2ui-override",
    "schema-export",
    "shell",
    "stats",
    "stop-app",
    "test-app",
    "url-mappings-report"
  ];

  genericCommands.forEach(cmd => {
    // Create a unique command id like "grails.clean"
    const commandId = `grails.${cmd}`;
    const command = vscode.commands.registerCommand(commandId, () => {
      runGrailsCommandViaGradle(cmd, true);
    });
    context.subscriptions.push(command);
  });

  // ===== Gradle Tasks from Your List =====

  const gradleTasks = [
    // Application tasks
    "bootRun",
    "run",
    // Build tasks
    "assemble",
    "bootBuildImage",
    "bootJar",
    "bootJarMainClassName",
    "bootRunMainClassName",
    "bootWar",
    "bootWarMainClassName",
    "build",
    "buildDependents",
    "buildNeeded",
    "classes",
    "clean",
    "integrationTestClasses",
    "jar",
    "testClasses",
    "war",
    // Build Setup tasks
    "init",
    "wrapper",
    // Distribution tasks
    "assembleBootDist",
    "assembleDist",
    "bootDistTar",
    "bootDistZip",
    "distTar",
    "distZip",
    "installBootDist",
    "installDist",
    // Documentation tasks
    "groovydoc",
    "javadoc",
    // Help tasks
    "buildEnvironment",
    "dependencies",
    "dependencyInsight",
    "dependencyManagement",
    "help",
    "javaToolchains",
    "outgoingVariants",
    "projects",
    "properties",
    "resolvableConfigurations",
    "tasks",
    // IDE tasks
    "cleanIdea",
    "idea",
    "openIdea",
    // Node tasks
    "nodeSetup",
    // Npm tasks
    "npmInstall",
    "npmSetup",
    // Verification tasks
    "check",
    "integrationTest",
    "test",
    // Yarn tasks
    "yarn",
    "yarnSetup",
    // Other tasks
    "assetClean",
    "assetCompile",
    "assetPluginPackage",
    "bootStartScripts",
    "buildProperties",
    "cleanIdeaModule",
    "cleanIdeaProject",
    "cleanIdeaWorkspace",
    "compileGroovy",
    "compileGroovyPages",
    "compileGsonViews",
    "compileIntegrationTestGroovy",
    "compileIntegrationTestJava",
    "compileJava",
    "compileTestGroovy",
    "compileTestJava",
    "compileWebappGroovyPages",
    "components",
    "console",
    "dbmChangelogSync",
    "dbmChangelogSyncSql",
    "dbmClearChecksums",
    "dbmDbDoc",
    "dbmDiff",
    "dbmDropAll",
    "dbmFutureRollbackCountSql",
    "dbmFutureRollbackSql",
    "dbmGenerateChangelog",
    "dbmGenerateGormChangelog",
    "dbmGormDiff",
    "dbmListLocks",
    "dbmMarkNextChangesetRan",
    "dbmMarkNextChangesetRanSql",
    "dbmPreviousChangesetSql",
    "dbmReleaseLocks",
    "dbmRollback",
    "dbmRollbackCount",
    "dbmRollbackCountSql",
    "dbmRollbackSql",
    "dbmRollbackToDate",
    "dbmRollbackToDateSql",
    "dbmStatus",
    "dbmTag",
    "dbmUpdate",
    "dbmUpdateCount",
    "dbmUpdateCountSql",
    "dbmUpdateSql",
    "dbmValidate",
    "dependentComponents",
    "findMainClass",
    "ideaModule",
    "ideaProject",
    "ideaWorkspace",
    "mergeTestReports",
    "model",
    "pathingJar",
    "pathingJarCommand",
    "prepareKotlinBuildScriptModel",
    "processIntegrationTestResources",
    "processResources",
    "processTestResources",
    "runCommand",
    "runScript",
    "schemaExport",
    "shell",
    "startScripts",
    "urlMappingsReport"
  ];

  gradleTasks.forEach(task => {
    // Create a unique command id like "gradle.bootRun"
    const commandId = `gradle.${task}`;
    const command = vscode.commands.registerCommand(commandId, () => {
      runGrailsCommandViaGradle(task, true);
    });
    context.subscriptions.push(command);
  });

  // Register the dedicated Grails commands.
  context.subscriptions.push(
    runAppGradleCommand,
    stopAppGradleCommand,
    debugGrailsAppCommand,
    stopDebugGrailsCommand
  );
}

function deactivate() {
  if (gradleRunProcess) {
    killProcessTree(gradleRunProcess);
    gradleRunProcess = null;
  }
  if (gradleDebugProcess) {
    killProcessTree(gradleDebugProcess);
    gradleDebugProcess = null;
  }
}

module.exports = {
  activate,
  deactivate
};
