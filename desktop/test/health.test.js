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
