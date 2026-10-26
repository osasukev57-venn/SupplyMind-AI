'use strict';

const assert = require('node:assert');
const { test } = require('node:test');
const { EventEmitter } = require('node:events');

const { waitForBackend, State } = require('../src/health');

/** Minimal fake http.get: the returned request object carries a real EventEmitter so
 *  waitForBackend's error listener fires exactly like on a real socket. */
function fakeHttp(behavior) {
  return {
    get(url, onResponse) {
      behavior.requestCount = (behavior.requestCount || 0) + 1;
      const req = new EventEmitter();
      req.setTimeout = function (ms) { /* no-op */ };
      req.destroy = function () { /* no-op */ };
      const outcome = behavior.onRequest(behavior.requestCount);
      setImmediate(() => {
        if (outcome === 'network-error') {
          const err = new Error('ECONNREFUSED');
          err.code = 'ECONNREFUSED';
          req.emit('error', err);
          return;
        }
        if (outcome === 'response') {
          const res = new EventEmitter();
          res.statusCode = behavior.status;
          onResponse(res);
          setImmediate(() => {
            res.emit('data', Buffer.from(JSON.stringify(behavior.body || {})));
            res.emit('end');
          });
        }
      });
      return req;
    }
  };
}

test('waitForBackend resolves READY when the backend answers UP', async () => {
  const behavior = { onRequest: () => 'response', status: 200, body: { status: 'UP' } };
  const result = await waitForBackend('http://127.0.0.1:1', {
    httpImpl: fakeHttp(behavior),
    timeoutMs: 2000,
    intervalMs: 10
  });
  assert.strictEqual(result.state, State.READY);
});

test('waitForBackend resolves TIMEOUT when the backend never becomes healthy', async () => {
  const behavior = { onRequest: () => 'network-error' };
  const result = await waitForBackend('http://127.0.0.1:1', {
    httpImpl: fakeHttp(behavior),
    timeoutMs: 200,
    intervalMs: 10
  });
  assert.strictEqual(result.state, State.TIMEOUT);
});

test('waitForBackend keeps polling while the backend returns non-UP bodies', async () => {
  const behavior = {
    onRequest: (n) => {
      if (n < 3) {
        behavior.status = 200;
        behavior.body = { status: 'STARTING' };
      } else {
        behavior.status = 200;
        behavior.body = { status: 'UP' };
      }
      return 'response';
    }
  };
  const result = await waitForBackend('http://127.0.0.1:1', {
    httpImpl: fakeHttp(behavior),
    timeoutMs: 2000,
    intervalMs: 10
  });
  assert.strictEqual(result.state, State.READY);
});

test('default startup grace admits a healthy cold JVM at 45 seconds', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout', 'setInterval', 'Date'], now: 0 });
  t.after(() => t.mock.timers.reset());
  const httpImpl = {
    get(url, onResponse) {
      const request = new EventEmitter();
      request.setTimeout = () => {};
      request.destroy = () => {};
      if (Date.now() < 45_000) queueMicrotask(() => request.emit('error', new Error('starting')));
      else {
        const response = new EventEmitter();
        response.statusCode = 200;
        onResponse(response);
        queueMicrotask(() => { response.emit('data', '{"status":"UP"}'); response.emit('end'); });
      }
      return request;
    }
  };
  let settled = false;
  const pending = waitForBackend('http://127.0.0.1:45678', { httpImpl });
  pending.then(() => { settled = true; });
  t.mock.timers.tick(30_000);
  await Promise.resolve();
  assert.strictEqual(settled, false, 'an ongoing valid startup must survive the old 30-second cutoff');
  t.mock.timers.tick(15_000);
  assert.strictEqual((await pending).state, State.READY);
});

test('default startup grace still fails explicitly at 120 seconds', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout', 'setInterval', 'Date'], now: 0 });
  t.after(() => t.mock.timers.reset());
  const httpImpl = { get() { const request = new EventEmitter(); request.setTimeout = () => {}; request.destroy = () => {}; return request; } };
  const pending = waitForBackend('http://127.0.0.1:45678', { httpImpl });
  t.mock.timers.tick(120_000);
  assert.strictEqual((await pending).state, State.TIMEOUT);
});
