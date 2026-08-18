'use strict';

const assert = require('node:assert');
const { test } = require('node:test');
const os = require('os');
const path = require('path');
const fs = require('fs');

const { portableRoot, layout, preflight } = require('../src/paths');
const { backendArgs } = require('../src/backend');

test('portableRoot resolves explicit override', () => {
  assert.strictEqual(portableRoot('C:/app'), 'C:\\app');
  assert.strictEqual(portableRoot('D:/portable dir'), 'D:\\portable dir');
});

test('layout builds the frozen portable directory shape', () => {
  const dirs = layout('D:/root');
  assert.ok(dirs.jreBin.endsWith(path.join('runtime', 'jre', 'bin', 'java.exe')));
  assert.ok(dirs.jar.endsWith(path.join('app', 'supplymind-backend.jar')));
  assert.ok(dirs.web.endsWith(path.join('app', 'web')));
  assert.ok(dirs.data.endsWith('data'));
  assert.ok(dirs.logs.endsWith('logs'));
});

test('backendArgs pins dynamic loopback port, data root and loopback address', () => {
  const { executable, args } = backendArgs(45678, 'D:/root/data', 'D:/root/runtime/jre/bin/java.exe', 'D:/root/app/supplymind-backend.jar', 'D:/root/app/web');
  assert.strictEqual(executable, 'D:/root/runtime/jre/bin/java.exe');
  assert.deepStrictEqual(args, [
    '-jar',
    'D:/root/app/supplymind-backend.jar',
    '--server.port=45678',
    '--server.address=127.0.0.1',
    '--supplymind.data-root=D:/root/data',
    '--spring.web.resources.static-locations=file:D:/root/app/web/'
  ]);
});

test('backendArgs omits static-locations when no web dir is provided', () => {
  const { args } = backendArgs(45678, 'D:/root/data', 'java', 'D:/root/app/supplymind-backend.jar');
  assert.deepStrictEqual(args, [
    '-jar',
    'D:/root/app/supplymind-backend.jar',
    '--server.port=45678',
    '--server.address=127.0.0.1',
    '--supplymind.data-root=D:/root/data'
  ]);
});

test('preflight reports every missing or unwritable path', () => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'supplymind-paths-'));
  const dirs = layout(tmp);
  fs.mkdirSync(path.join(tmp, 'runtime', 'jre', 'bin'), { recursive: true });
  fs.mkdirSync(path.join(tmp, 'app', 'web'), { recursive: true });
  fs.mkdirSync(path.join(tmp, 'data'), { recursive: true });
  fs.mkdirSync(path.join(tmp, 'logs'), { recursive: true });
  fs.writeFileSync(dirs.jreBin, 'dummy-java');
  fs.writeFileSync(dirs.jar, 'dummy-jar');
  fs.writeFileSync(dirs.webIndex, '<html/>');

  const errors = preflight(tmp);
  assert.deepStrictEqual(errors, []);
});

test('preflight fails when the JRE or JAR is missing', () => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'supplymind-paths-'));
  const errors = preflight(tmp);
  assert.ok(errors.some((e) => e.includes('JRE not found')));
  assert.ok(errors.some((e) => e.includes('JAR not found')));
  assert.ok(errors.some((e) => e.includes('frontend assets not found')));
});

test('preflight fails when index.html is missing from the web dir', () => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'supplymind-paths-'));
  const dirs = layout(tmp);
  fs.mkdirSync(path.join(tmp, 'runtime', 'jre', 'bin'), { recursive: true });
  fs.mkdirSync(path.join(tmp, 'app', 'web'), { recursive: true });
  fs.mkdirSync(path.join(tmp, 'data'), { recursive: true });
  fs.mkdirSync(path.join(tmp, 'logs'), { recursive: true });
  fs.writeFileSync(dirs.jreBin, 'dummy-java');
  fs.writeFileSync(dirs.jar, 'dummy-jar');

  const errors = preflight(tmp);
  assert.ok(errors.some((e) => e.includes('frontend assets not found')));
});

test('preflight fails when data is a file instead of a directory', () => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'supplymind-paths-'));
  const dirs = layout(tmp);
  fs.mkdirSync(path.join(tmp, 'runtime', 'jre', 'bin'), { recursive: true });
  fs.mkdirSync(path.join(tmp, 'app', 'web'), { recursive: true });
  fs.mkdirSync(path.join(tmp, 'logs'), { recursive: true });
  fs.writeFileSync(dirs.jreBin, 'dummy-java');
  fs.writeFileSync(dirs.jar, 'dummy-jar');
  fs.writeFileSync(dirs.webIndex, '<html/>');
  fs.writeFileSync(dirs.data, 'not-a-dir');

  const errors = preflight(tmp);
  assert.ok(errors.some((e) => e.includes('is not a directory')));
});

test('layout exposes licenses and webIndex paths', () => {
  const dirs = layout('D:/root');
  assert.ok(dirs.webIndex.endsWith(path.join('app', 'web', 'index.html')));
  assert.ok(dirs.licenses.endsWith('licenses'));
});
