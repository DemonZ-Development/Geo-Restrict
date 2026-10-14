// Reuse the protocol parser across independent real TCP clients.
const connect = require('./client.cjs');
const readline = require('node:readline');
readline.createInterface({ input: process.stdin }).on('line', line => {
  const request = JSON.parse(line);
  connect(request.options).then(result => console.log(JSON.stringify({ id: request.id, result })))
    .catch(error => console.log(JSON.stringify({ id: request.id,
      result: { username: request.options.username, joined: false, disconnect: null, error: error.message } })));
}).on('close', () => process.exit(0));
