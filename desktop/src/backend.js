'use strict';

/**
 * D9-T03 Spring Boot child-process hosting. Builds the spawn arguments from the frozen
 * backend contract (dynamic loopback port, explicit data-root, loopback-only binding)
 * and owns the child lifecycle. Pure argument construction is unit-testable.
 *
 * Security boundary (D9-T03): the port MUST be dynamic (never the fixed 8080 default),
 * the address MUST stay 127.0.0.1, and the renderer never sees these - they only exist
 * in the main-process spawn command.
 */

/** Reject fixed/default ports: the desktop app must never hand the backend a fixed 8080. */
function requireDynamicPort(port) {
  if (!Number.isInteger(port) || port < 1024 || port > 65535 || port === 8080) {
    throw new Error(`backend port must be a dynamically allocated port in 1024-65535 (not 8080), got: ${port}`);
  }
  return port;
}

function backendArgs(port, dataRoot, jreBin, jar, webDir) {
  requireDynamicPort(port);
  const args = [
    '-jar',
    jar,
    `--server.port=${port}`,
    '--server.address=127.0.0.1',
    `--supplymind.data-root=${dataRoot}`,
    '--supplymind.current-acquisition.on-startup-enabled=true',
    '--supplymind.scheduler.guarded-enabled=true'
  ];
  if (webDir) {
    args.push(`--spring.web.resources.static-locations=file:${webDir}/`);
  }
  return { executable: jreBin, args };
}

module.exports = { backendArgs, requireDynamicPort };
