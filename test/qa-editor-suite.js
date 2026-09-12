const Mocha = require('mocha');
const path = require('path');
exports.run = () => new Promise((resolve, reject) => {
  const mocha = new Mocha({ ui: 'tdd', timeout: 240000, reporter: 'spec' });
  if (process.env.QA_EDITOR_GREP) mocha.grep(process.env.QA_EDITOR_GREP);
  mocha.addFile(path.join(__dirname, 'qa-editor.test.js'));
  mocha.run(failures => failures ? reject(new Error(`${failures} QA editor test(s) failed`)) : resolve());
});
