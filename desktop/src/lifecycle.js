'use strict';

/**
 * D9-T04 backend child-process lifecycle: graceful stop with a Windows taskkill /T /F
 * fallback. Pure enough to unit-test without a real Electron runtime.
 */
const { spawn } = require('child_process');

/**
 * Resolve true when the child exited, false when it is still alive after timeoutMs.
 */
function waitForExit(child, timeoutMs) {
  if (child.exitCode != null || child.signalCode != null) {
    return Promise.resolve(true);
  }
  return new Promise((resolve) => {
    const timeout = setTimeout(() => resolve(false), timeoutMs);
    child.once('exit', () => {
      clearTimeout(timeout);
      resolve(true);
    });
  });
}

/**
 * Kill the whole process tree on Windows (taskkill /T /F). Resolves on completion;
 * never rejects so cleanup always proceeds.
 */
function forceKillTree(pid) {
  return new Promise((resolve) => {
    const killer = spawn('taskkill', ['/pid', String(pid), '/T', '/F'], { windowsHide: true });
    killer.on('error', () => resolve());
    killer.on('exit', () => resolve());
  });
}

/**
 * Graceful stop: SIGTERM first, wait up to graceMs, then taskkill the tree. Returns
 * when the child is gone so no orphan Java process can survive a normal quit.
 */
async function stopChild(child, graceMs) {
  if (!child || child.exitCode !== null || child.signalCode !== null) {
    return;
  }
  child.kill();
  let exited = await waitForExit(child, graceMs);
  if (!exited) {
    await forceKillTree(child.pid);
    exited = await waitForExit(child, graceMs);
  }
  if (!exited) {
    child.kill('SIGKILL');
    await waitForExit(child, graceMs);
  }
}

module.exports = { waitForExit, forceKillTree, stopChild };
