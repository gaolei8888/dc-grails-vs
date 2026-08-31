const vscode = require('vscode');
const { exec, spawn } = require('child_process');

// Dedicated processes for long‑running tasks (runApp and debug)
let gradleRunProcess = null;
let gradleDebugProcess = null;

// The JVM prints this once the JDWP agent is actually listening. Waiting for it
// is the only reliable attach trigger -- a fixed delay races Gradle's configure
// and compile phases, which routinely take far longer than any delay you'd pick.
const JDWP_READY_RE = /Listening for transport dt_socket at address:\s*(\d+)/;

// How long to wait for that line before giving up (cold start + full compile).
const JDWP_WAIT_MS = 300000;

function gradleWrapper() {
  return process.platform === 'win32' ? 'gradlew.bat' : './gradlew';
}

function requireWorkspace(what) {
  if (!vscode.workspace.workspaceFolders) {
    vscode.window.showErrorMessage(`No workspace folder found. Open your ${what} first.`);
    return null;
  }
  return vscode.workspace.workspaceFolders[0].uri.fsPath;
}

/**
 * spawn() the Gradle wrapper and stream its output into a channel.
 *
 * Deliberately NOT exec(): child_process.exec buffers the whole output in memory
 * and kills the child once it exceeds maxBuffer (1 MB by default). A Grails app
 * blows past that in minutes, so the server would die on its own with an error
 * that points nowhere near the cause. spawn() streams and has no such limit.
 *
 * @param {string[]} args        arguments for the wrapper (fixed literals only)
 * @param {string} cwd           working directory
 * @param {string} channelName   output channel to create/show
 * @param {(line: string) => void} [onLine]  called for each complete stdout line
 */
function spawnGradle(args, cwd, channelName, onLine) {
  const channel = vscode.window.createOutputChannel(channelName);
  channel.show(true);
  channel.appendLine(`> ${gradleWrapper()} ${args.join(' ')}\n`);

  // shell:true so the .bat wrapper resolves on Windows. Safe here because every
  // argument is a hard-coded literal, never user input.
  const proc = spawn(gradleWrapper(), args, { cwd, shell: true });

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
    vscode.window.showErrorMessage(`Could not run ${gradleWrapper()}: ${err.message}`);
  });
  return proc;
}

/**
 * grails.debug currently hands the session to vscode-java-debug (type: 'java').
 * That extension is not a hard dependency of this one, so say so plainly instead
 * of letting startDebugging() fail with an opaque error.
 */
function javaDebugAvailable() {
  return !!vscode.extensions.getExtension('vscjava.vscode-java-debug');
}

/**
 * Helper function to run any Grails/Gradle command via the Gradle wrapper.
 * @param {string} commandName - The command to run (e.g. "clean", "bootRun", etc.).
 * @param {boolean} promptForArgs - Whether to prompt the user for additional arguments.
 */
function runGrailsCommandViaGradle(commandName, promptForArgs = true) {
  if (!vscode.workspace.workspaceFolders) {
    vscode.window.showErrorMessage('No workspace folder found. Open your project first.');
    return;
  }
  const workspaceFolder = vscode.workspace.workspaceFolders[0].uri.fsPath;
  const gradleCmd = process.platform === 'win32' ? 'gradlew.bat' : './gradlew';

  let argsPromise = Promise.resolve("");
  if (promptForArgs) {
    argsPromise = vscode.window.showInputBox({
      prompt: `Enter additional arguments for: ${commandName} (optional)`
    });
  }

  argsPromise.then(extraArgs => {
    let fullArgs = commandName;
    if (extraArgs && extraArgs.trim().length > 0) {
      fullArgs += " " + extraArgs;
    }
    // Build the Gradle command using the grailsCommand task pattern.
    const fullCmd = `${gradleCmd} -Pargs="${fullArgs}"`;
    const outputChannel = vscode.window.createOutputChannel(`Gradle: ${commandName}`);
    outputChannel.show(true);
    const proc = exec(fullCmd, { cwd: workspaceFolder });
    proc.stdout.on('data', data => outputChannel.append(data.toString()));
    proc.stderr.on('data', data => outputChannel.append(data.toString()));
    proc.on('close', code => {
      vscode.window.showInformationMessage(`Task '${commandName}' exited with code ${code}`);
    });
  });
}

function activate(context) {
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
    gradleRunProcess = spawnGradle(['bootRun'], workspaceFolder, 'Grails - Normal');
    gradleRunProcess.on('close', code => {
      vscode.window.showInformationMessage(`Grails (normal) exited with code ${code}`);
      gradleRunProcess = null;
    });
  });

  // 2) Grails (Gradle): Stop App (Normal Mode)
  const stopAppGradleCommand = vscode.commands.registerCommand('grails.stopApp', () => {
    if (!gradleRunProcess) {
      vscode.window.showInformationMessage('No Grails (normal) process is running.');
      return;
    }
    vscode.window.showInformationMessage('Stopping Grails app (normal mode)...');
    gradleRunProcess.kill();
    gradleRunProcess = null;
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

    if (!javaDebugAvailable()) {
      const pick = await vscode.window.showErrorMessage(
        'Debugging needs the "Debugger for Java" extension (vscjava.vscode-java-debug), which is not installed.',
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
        await vscode.debug.startDebugging(undefined, {
          name: 'Attach to Grails',
          type: 'java',
          request: 'attach',
          hostName: 'localhost',
          port
        });
        vscode.window.showInformationMessage(`Debugger attached to Grails on port ${port}.`);
      } catch (err) {
        vscode.window.showErrorMessage(`Failed to attach debugger: ${err.message}`);
      }
    };

    // Attach only once the JVM says it is listening. A fixed setTimeout races
    // Gradle's configure + compile phases, which on a cold start run well past
    // any delay you would pick -- the old 3s version failed most cold starts.
    gradleDebugProcess = spawnGradle(
      ['bootRun', '--debug-jvm'],
      workspaceFolder,
      'Grails - Debug',
      line => {
        const m = JDWP_READY_RE.exec(line);
        if (m) attach(Number(m[1]));
      }
    );

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
    });
  });

  // 4) Grails (Gradle): Stop Debug App
  const stopDebugGrailsCommand = vscode.commands.registerCommand('grails.stopDebug', () => {
    if (!gradleDebugProcess) {
      vscode.window.showInformationMessage('No Grails debug process is running.');
      return;
    }
    vscode.window.showInformationMessage('Stopping Grails debug process...');
    gradleDebugProcess.kill();
    gradleDebugProcess = null;
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
    gradleRunProcess.kill();
    gradleRunProcess = null;
  }
  if (gradleDebugProcess) {
    gradleDebugProcess.kill();
    gradleDebugProcess = null;
  }
}

module.exports = {
  activate,
  deactivate
};
