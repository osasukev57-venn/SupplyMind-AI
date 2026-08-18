'use strict';

/**
 * D9-T01 Spring Boot child-process hosting. Builds the spawn arguments from the frozen
 * backend contract (dynamic loopback port, explicit data-root, loopback-only binding)
 * and owns the child lifecycle. Pure argument construction is unit-testable.
 */

function backendArgs(port, dataRoot, jreBin, jar) {
  const args = [
    '-jar',
    jar,
    `--server.port=${port}`,
    '--server.address=127.0.0.1',
    `--supplymind.data-root=${dataRoot}`
  ];
  return { executable: jreBin, args };
}

module.exports = { backendArgs };
