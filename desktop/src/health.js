'use strict';

/**
 * D9-T03 backend health polling. The Electron shell waits until the Spring Boot child
 * process answers GET /api/health before opening the main window. Pure state machine,
 * unit-testable without a real server.
 */

const HEALTH_PATH = '/api/health';
const DEFAULT_TIMEOUT_MS = 30_000;
const DEFAULT_INTERVAL_MS = 500;

const State = Object.freeze({
  STARTING: 'STARTING',
  READY: 'READY',
  TIMEOUT: 'TIMEOUT',
  FAILED: 'FAILED'
});

/**
 * Poll http.get on the given loopback URL until it returns 200 with {"status":"UP"}.
 *
 * @param {string} baseUrl e.g. http://127.0.0.1:45678
 * @param {object} opts {timeoutMs, intervalMs, httpImpl, onTick}
 * @returns {Promise<{state: string, message: string}>}
 */
function waitForBackend(baseUrl, opts = {}) {
  const httpImpl = opts.httpImpl || require('http');
  const timeoutMs = opts.timeoutMs || DEFAULT_TIMEOUT_MS;
  const intervalMs = opts.intervalMs || DEFAULT_INTERVAL_MS;
  const onTick = opts.onTick || (() => {});

  return new Promise((resolve) => {
    const deadline = Date.now() + timeoutMs;
    const timer = setInterval(() => {
      const request = httpImpl.get(baseUrl + HEALTH_PATH, (response) => {
        let body = '';
        response.on('data', (chunk) => {
          body += chunk;
        });
        response.on('end', () => {
          if (response.statusCode === 200) {
            try {
              const parsed = JSON.parse(body);
              if (parsed && parsed.status === 'UP') {
                clearInterval(timer);
                resolve({ state: State.READY, message: 'backend ready' });
                return;
              }
            } catch (err) {
              // malformed body: keep polling until deadline
            }
          }
          onTick(response.statusCode || 0);
          if (Date.now() >= deadline) {
            clearInterval(timer);
            resolve({ state: State.TIMEOUT, message: 'backend did not become healthy in time' });
          }
        });
      });
      request.on('error', (err) => {
        onTick(err.code || String(err));
        if (Date.now() >= deadline) {
          clearInterval(timer);
          resolve({ state: State.TIMEOUT, message: `backend did not become healthy in time: ${err.code || ''}` });
        }
      });
      request.setTimeout(Math.max(1000, intervalMs * 2), () => {
        request.destroy(new Error('request timed out'));
      });
    }, intervalMs);
  });
}

module.exports = { waitForBackend, State, HEALTH_PATH };
