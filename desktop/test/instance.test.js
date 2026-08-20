'use strict';

const assert = require('node:assert');
const { test } = require('node:test');
const { EventEmitter } = require('node:events');

const { acquireSingleInstance } = require('../src/instance');

function fakeApp(lockResult) {
  const app = new EventEmitter();
  app.requestSingleInstanceLock = () => lockResult;
  app.quit = () => {};
  return app;
}

test('first instance owns the lock and registers second-instance handling', () => {
  const app = fakeApp(true);
  const owned = acquireSingleInstance(app, () => null);
  assert.strictEqual(owned, true);
  assert.strictEqual(app.listeners('second-instance').length, 1);
});

test('second instance does not own the lock', () => {
  const app = fakeApp(false);
  const owned = acquireSingleInstance(app, () => null);
  assert.strictEqual(owned, false);
});

test('second-instance event restores and focuses the existing window', () => {
  const app = fakeApp(true);
  let restored = 0;
  let focused = 0;
  let activation;
  const window = {
    isMinimized: () => true,
    restore: () => { restored += 1; },
    focus: () => { focused += 1; }
  };
  acquireSingleInstance(app, () => window, (event) => { activation = event; });
  app.emit('second-instance');
  assert.strictEqual(restored, 1);
  assert.strictEqual(focused, 1);
  assert.deepStrictEqual(activation, {
    event: 'SECOND_INSTANCE_ACTIVATED',
    windowExists: true,
    restored: true,
    focusCalled: true
  });
});

test('second-instance event focuses without restore when the window is not minimized', () => {
  const app = fakeApp(true);
  let restored = 0;
  let focused = 0;
  const window = {
    isMinimized: () => false,
    restore: () => { restored += 1; },
    focus: () => { focused += 1; }
  };
  acquireSingleInstance(app, () => window);
  app.emit('second-instance');
  assert.strictEqual(restored, 0);
  assert.strictEqual(focused, 1);
});

test('second-instance event is a no-op when no window exists yet', () => {
  const app = fakeApp(true);
  let activation;
  acquireSingleInstance(app, () => null, (event) => { activation = event; });
  assert.doesNotThrow(() => app.emit('second-instance'));
  assert.deepStrictEqual(activation, {
    event: 'SECOND_INSTANCE_ACTIVATED',
    windowExists: false,
    restored: false,
    focusCalled: false
  });
});