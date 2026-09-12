const Mocha = require('mocha');
const path = require('path');
exports.run = () => new Promise((resolve, reject) => {
  const mocha = new Mocha({ ui: 'tdd', timeout: 120000, reporter: 'spec' });
  mocha.addFile(path.join(__dirname, 'editor.test.js'));
  mocha.run(failures => failures ? reject(new Error(`${failures} editor test(s) failed`)) : resolve());
});
