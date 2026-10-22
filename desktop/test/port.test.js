'use strict';

const assert = require('node:assert');
const { test } = require('node:test');
const net = require('node:net');

const { pickFreePort } = require('../src/port');
const { backendArgs, requireDynamicPort } = require('../src/backend');

test('pickFreePort returns a valid free port on the loopback interface', async () => {
  const port = await pickFreePort(net);
  assert.ok(port > 0 && port <= 65535);
});

test('pickFreePort never returns the fixed dev default 8080', async () => {
  for (let i = 0; i < 10; i++) {
    const port = await pickFreePort(net);
    assert.notStrictEqual(port, 8080);
  }
});

test('pickFreePort port can actually be bound on 127.0.0.1', async () => {
  const port = await pickFreePort(net);
  await new Promise((resolve, reject) => {
    const server = net.createServer();
    server.on('error', reject);
    server.listen(port, '127.0.0.1', () => {
      server.close(() => resolve());
    });
  });
});

test('backendArgs rejects fixed/default ports (never 8080)', () => {
  assert.throws(() => requireDynamicPort(8080), /dynamically allocated/);
  assert.throws(() => requireDynamicPort(80), /dynamically allocated/);
  assert.throws(() => requireDynamicPort(0), /dynamically allocated/);
  assert.throws(() => requireDynamicPort(NaN), /dynamically allocated/);
});

test('backendArgs always pins the loopback address', () => {
  const { args } = backendArgs(45678, 'D:/data', 'java', 'D:/app.jar');
  assert.ok(args.includes('--server.address=127.0.0.1'));
});

test('backendArgs never contains localhost, LAN or wildcard bindings', () => {
  const { args } = backendArgs(45678, 'D:/data', 'java', 'D:/app.jar');
  const joined = args.join(' ');
  assert.ok(!joined.includes('0.0.0.0'));
  assert.ok(!joined.includes('localhost'));
  assert.ok(!joined.includes('--server.address=*'));
});

function fakeNet(ports, failure) {
  const state = { opened: 0, closed: 0 };
  return { state, createServer() {
    const index = state.opened++;
    return {
      unref() {}, on(event, handler) { if (event === 'error') this.onError = handler; },
      listen(port, host, callback) {
        assert.strictEqual(port, 0); assert.strictEqual(host, '127.0.0.1');
        if (failure) this.onError(failure); else callback();
      },
      address() { return { port: ports[index % ports.length] }; },
      close(callback) { state.closed++; callback(); }
    };
  }};
}

test('selector closes forbidden allocations and uses the backend port contract', async () => {
  const net = fakeNet([8080, 80, 65536, 45678]);
  assert.strictEqual(await pickFreePort(net), 45678);
  assert.deepStrictEqual(net.state, { opened: 4, closed: 4 });
});

test('selector fails explicitly after ten forbidden allocations', async () => {
  const net = fakeNet([8080]);
  await assert.rejects(pickFreePort(net), /after 10 attempts/);
  assert.deepStrictEqual(net.state, { opened: 10, closed: 10 });
});

test('selector propagates a real listen failure without unbounded retry', async () => {
  const failure = new Error('EACCES');
  const net = fakeNet([45678], failure);
  await assert.rejects(pickFreePort(net), error => error === failure);
  assert.strictEqual(net.state.opened, 1);
});
