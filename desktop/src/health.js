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
 * Loopback-only guard (D9-T03): the backend URL must be an http://127.0.0.1:<port>
 * origin. Any other host - localhost, 0.0.0.0, LAN IPs, non-http schemes - is
 * rejected. This keeps the desktop app bound to the local machine.
 *
 * @param {string} baseUrl
 * @returns {string} the baseUrl when valid
 * @throws {Error} when the URL is not a loopback http origin
 */
function assertLoopbackUrl(baseUrl) {
  const match = /^http:\/\/127\.0\.0\.1:(\d{1,5})\/?$/.exec(baseUrl);
  if (!match) {
    throw new Error(`backend URL must be http://127.0.0.1:<port>, got: ${baseUrl}`);
  }
  const port = Number(match[1]);
  if (port <= 0 || port > 65535) {
    throw new Error(`backend URL port out of range: ${port}`);
  }
  return baseUrl;
}

/**
 * Poll http.get on the given loopback URL until it returns 200 with {"status":"UP"}.
 *
 * @param {string} baseUrl e.g. http://127.0.0.1:45678
 * @param {object} opts {timeoutMs, intervalMs, httpImpl, onTick}
 * @returns {Promise<{state: string, message: string}>}
 */
function waitForBackend(baseUrl, opts = {}) {
  assertLoopbackUrl(baseUrl);
  const httpImpl = opts.httpImpl || require('http');
  const timeoutMs = opts.timeoutMs || DEFAULT_TIMEOUT_MS;
  const intervalMs = opts.intervalMs || DEFAULT_INTERVAL_MS;
  const onTick = opts.onTick || (() => {});

  return new Promise((resolve) => {
    const deadline = Date.now() + timeoutMs;
    let settled = false;
    const hardTimer = setTimeout(() => {
      if (!settled) {
        settled = true;
        clearInterval(timer);
        resolve({ state: State.TIMEOUT, message: 'backend did not become healthy in time' });
      }
    }, timeoutMs);
    const timer = setInterval(() => {
      if (settled) {
        return;
      }
      const request = httpImpl.get(baseUrl + HEALTH_PATH, (response) => {
        if (settled) {
          return;
        }
        let body = '';
        response.on('data', (chunk) => {
          body += chunk;
        });
        response.on('end', () => {
          if (settled) {
            return;
          }
          if (response.statusCode === 200) {
            try {
              const parsed = JSON.parse(body);
              if (parsed && parsed.status === 'UP') {
                settled = true;
                clearInterval(timer);
                clearTimeout(hardTimer);
                resolve({ state: State.READY, message: 'backend ready' });
                return;
              }
            } catch (err) {
              // malformed body: keep polling until deadline
            }
          }
          onTick(response.statusCode || 0);
        });
      });
      request.on('error', (err) => {
        onTick(err.code || String(err));
      });
      request.setTimeout(Math.max(1000, intervalMs * 2), () => {
        request.destroy(new Error('request timed out'));
      });
    }, intervalMs);
  });
}

module.exports = { waitForBackend, assertLoopbackUrl, State, HEALTH_PATH };
