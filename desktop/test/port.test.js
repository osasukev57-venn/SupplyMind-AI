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
