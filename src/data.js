const Enmap = require('enmap');
let data = {
  db: new Enmap({ name: 'guild-settings' }),
 dbg: new Enmap({ name: 'robux' })
};

module.exports = data;