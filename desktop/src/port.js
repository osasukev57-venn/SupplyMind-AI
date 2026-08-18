'use strict';

/**
 * D9-T03 dynamic loopback port selection. Picks a free port on 127.0.0.1 by binding
 * port 0 and closing immediately. Pure and unit-testable.
 */

/** @returns {Promise<number>} a free port number on the loopback interface */
function pickFreePort(net) {
  return new Promise((resolve, reject) => {
    const server = net.createServer();
    server.unref();
    server.on('error', reject);
    server.listen(0, '127.0.0.1', () => {
      const address = server.address();
      const port = typeof address === 'object' && address ? address.port : 0;
      server.close((err) => {
        if (err) {
          reject(err);
        } else if (port <= 0) {
          reject(new Error('failed to allocate a loopback port'));
        } else {
          resolve(port);
        }
      });
    });
  });
}

module.exports = { pickFreePort };
