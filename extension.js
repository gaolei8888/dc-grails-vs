const vscode = require('vscode');
const { exec } = require('child_process');

// Keep track of two separate child processes: one for normal run, one for debug run
let gradleRunProcess = null;
let gradleDebugProcess = null;

function activate(context) {

  // 1) Grails (Gradle): Run App
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
    vscode.window.showInformationMessage('Starting Grails (Gradle) app...');

    // Determine the correct Gradle command for this platform
    const gradleCmd = process.platform === 'win32' ? 'gradlew.bat' : './gradlew';
    // Change "bootRun" to your actual normal run task if needed
    gradleRunProcess = exec(`${gradleCmd} bootRun`, { cwd: workspaceFolder });

    const outputChannel = vscode.window.createOutputChannel('Grails - Normal');
    outputChannel.show(true);

    gradleRunProcess.stdout.on('data', data => outputChannel.append(data.toString()));
    gradleRunProcess.stderr.on('data', data => outputChannel.append(data.toString()));

    gradleRunProcess.on('close', code => {
      vscode.window.showInformationMessage(`Grails process exited with code ${code}`);
      gradleRunProcess = null;
    });
  });

  // 2) Grails (Gradle): Stop App
  const stopAppGradleCommand = vscode.commands.registerCommand('grails.stopApp', () => {
    if (!gradleRunProcess) {
      vscode.window.showInformationMessage('Grails (normal) process is running.');
      return;
    }
    vscode.window.showInformationMessage('Stopping Grails app...');
    gradleRunProcess.kill();
    gradleRunProcess = null;
  });

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
    vscode.window.showInformationMessage('Starting Grails in debug mode...');

    // Instead of calling a custom "debugGrailsApp" task, pass run-app --debug-jvm to grailsCommand
    const gradleCmd = process.platform === 'win32' ? 'gradlew.bat' : './gradlew';
    gradleDebugProcess = exec(
      `${gradleCmd} bootRun --debug-jvm`,
      { cwd: workspaceFolder }
    );

    const debugChannel = vscode.window.createOutputChannel('Grails - Debug');
    debugChannel.show(true);

    gradleDebugProcess.stdout.on('data', data => debugChannel.append(data.toString()));
    gradleDebugProcess.stderr.on('data', data => debugChannel.append(data.toString()));

    gradleDebugProcess.on('close', code => {
      vscode.window.showInformationMessage(`Grails debug process exited with code ${code}`);
      gradleDebugProcess = null;
    });

    // (Optional) Auto-attach Java debugger after a short delay
    setTimeout(async () => {
      const debugConfig = {
        name: 'Attach to Grails',
        type: 'java',
        request: 'attach',
        hostName: 'localhost',
        port: 5005 // default for --debug-jvm
      };
      try {
        await vscode.debug.startDebugging(undefined, debugConfig);
        vscode.window.showInformationMessage('Debugger attached to Grails on port 5005.');
      } catch (err) {
        vscode.window.showErrorMessage(`Failed to attach debugger: ${err.message}`);
      }
    }, 3000);
  });

  // 4) Grails (Gradle): Stop Debug App
  const stopDebugGrailsCommand = vscode.commands.registerCommand('grails.stopDebug', () => {
    if (!gradleDebugProcess) {
      vscode.window.showInformationMessage('No Gradle-based Grails debug process is running.');
      return;
    }
    vscode.window.showInformationMessage('Stopping Grails (Gradle) debug process...');
    gradleDebugProcess.kill();
    gradleDebugProcess = null;
  });

  // Register all commands
  context.subscriptions.push(
    runAppGradleCommand,
    stopAppGradleCommand,
    debugGrailsAppCommand,
    stopDebugGrailsCommand
  );
}

function deactivate() {
  // Clean up any running processes if the extension is deactivated
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
