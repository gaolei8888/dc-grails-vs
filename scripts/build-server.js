// Builds server/ into dist/groovy-dap.jar.
//
// The debug adapter has to run on a JVM -- JDI is a Java API with no Node
// binding -- so the vsix ships a jar next to the JavaScript. This runs on
// vscode:prepublish; run it by hand with `npm run build:server` before pressing
// F5, or the adapter will not be there to launch.
const { spawnSync } = require('child_process');
const path = require('path');
const fs = require('fs');

const serverDir = path.join(__dirname, '..', 'server');
const isWindows = process.platform === 'win32';
const wrapper = path.join(serverDir, isWindows ? 'gradlew.bat' : 'gradlew');

if (!fs.existsSync(wrapper)) {
  console.error(`No Gradle wrapper in ${serverDir}`);
  process.exit(1);
}

// An absolute path through cmd.exe, not a bare `gradlew.bat` with shell:true:
// cmd does not reliably resolve a wrapper sitting in the working directory, and
// the shell would mangle a path containing spaces anyway.
const result = isWindows
  ? spawnSync('cmd.exe', ['/d', '/s', '/c', wrapper, 'build'], {
      cwd: serverDir, stdio: 'inherit',
    })
  : spawnSync(wrapper, ['build'], { cwd: serverDir, stdio: 'inherit' });

if (result.status !== 0) {
  console.error('server build failed');
  process.exit(result.status === null ? 1 : result.status);
}

const jar = path.join(__dirname, '..', 'dist', 'groovy-dap.jar');
if (!fs.existsSync(jar)) {
  console.error(`build succeeded but ${jar} is missing`);
  process.exit(1);
}
console.log(`built ${jar}`);
