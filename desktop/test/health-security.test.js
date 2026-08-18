'use strict';

const assert = require('node:assert');
const { test } = require('node:test');

const { assertLoopbackUrl, waitForBackend, State } = require('../src/health');

test('assertLoopbackUrl accepts only http://127.0.0.1:<port>', () => {
  assert.strictEqual(assertLoopbackUrl('http://127.0.0.1:45678'), 'http://127.0.0.1:45678');
  assert.strictEqual(assertLoopbackUrl('http://127.0.0.1:45678/'), 'http://127.0.0.1:45678/');
});

test('assertLoopbackUrl rejects non-loopback hosts', () => {
  for (const bad of [
    'http://localhost:8080',
    'http://0.0.0.0:8080',
    'http://192.168.1.10:8080',
    'http://[::1]:8080',
    'https://127.0.0.1:8080',
    'file:///index.html',
    'http://example.com:8080'
  ]) {
    assert.throws(() => assertLoopbackUrl(bad), /must be http:\/\/127\.0\.0\.1/);
  }
});

test('assertLoopbackUrl rejects out-of-range ports', () => {
  assert.throws(() => assertLoopbackUrl('http://127.0.0.1:0'), /port out of range/);
  assert.throws(() => assertLoopbackUrl('http://127.0.0.1:70000'), /port out of range/);
});

test('waitForBackend fails fast on a non-loopback URL before any request', async () => {
  let requests = 0;
  const fakeHttp = {
    get() {
      requests += 1;
      return { setTimeout() {}, destroy() {} };
    }
  };
  assert.throws(
    () => waitForBackend('http://192.168.1.10:8080', { httpImpl: fakeHttp, timeoutMs: 200, intervalMs: 10 }),
    /must be http:\/\/127\.0\.0\.1/
  );
  assert.strictEqual(requests, 0, 'no HTTP request may be issued for a non-loopback URL');
});

test('waitForBackend resolves TIMEOUT and never hangs', async () => {
  const fakeHttp = {
    get(url, onResponse) {
      // a black hole: the request never answers and never errors
      const req = new (require('node:events').EventEmitter)();
      req.setTimeout = function () {};
      req.destroy = function () {};
      return req;
    }
  };
  const result = await waitForBackend('http://127.0.0.1:45678', {
    httpImpl: fakeHttp,
    timeoutMs: 100,
    intervalMs: 10
  });
  assert.strictEqual(result.state, State.TIMEOUT);
});
