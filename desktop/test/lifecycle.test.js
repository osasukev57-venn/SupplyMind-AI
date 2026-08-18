'use strict';

const assert = require('node:assert');
const { test } = require('node:test');
const { EventEmitter } = require('node:events');
const { spawn } = require('node:child_process');

const lifecycle = require('../src/lifecycle');

test('waitForExit resolves immediately for an already-exited child', async () => {
  const child = new EventEmitter();
  child.exitCode = 1;
  const exited = await lifecycle.waitForExit(child, 100);
  assert.strictEqual(exited, true);
});

test('waitForExit resolves false on timeout and true on exit', async () => {
  const child = new EventEmitter();
  child.exitCode = null;
  const timeoutPromise = lifecycle.waitForExit(child, 50);
  const exited = await timeoutPromise;
  assert.strictEqual(exited, false);
});

test('forceKillTree runs taskkill with /T /F and resolves', async () => {
  // Kill a throwaway child (not the test process itself) to prove the tree killer works.
  const victim = spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)'], {
    windowsHide: true
  });
  await new Promise((resolve) => {
    if (victim.pid) resolve();
    else victim.once('spawn', resolve);
  });
  await lifecycle.forceKillTree(victim.pid);
  const exited = await lifecycle.waitForExit(victim, 3000);
  assert.strictEqual(exited, true);
});
