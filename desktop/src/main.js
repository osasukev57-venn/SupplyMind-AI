'use strict';

/**
 * D9-T01 Electron main process: single instance lock, preflight, dynamic loopback
 * port, Spring Boot child process, health poll, main window, exit cleanup.
 *
 * The backend is spawned with the bundled JRE when present (D9-T02) or the system
 * java in dev mode. The LLM API key is never read, printed or exposed here - it is
 * only inherited by the child through the environment (SUPPLYMIND_LLM_*).
 */
const { app, BrowserWindow, dialog, Menu } = require('electron');
const { spawn } = require('child_process');
const http = require('http');
const path = require('path');
const fs = require('fs');

const paths = require('./paths');
const { pickFreePort } = require('./port');
const { waitForBackend } = require('./health');
const { backendArgs } = require('./backend');

const HEALTH_TIMEOUT_MS = 30_000;
const KILL_GRACE_MS = 5_000;

let childProcess = null;
let mainWindow = null;
let backendUrl = '';

const gotLock = app.requestSingleInstanceLock();
if (!gotLock) {
  app.quit();
} else {
  app.on('second-instance', () => {
    if (mainWindow) {
      if (mainWindow.isMinimized()) {
        mainWindow.restore();
      }
      mainWindow.focus();
    }
  });

  app.whenReady().then(async () => {
    Menu.setApplicationMenu(null);
    try {
      const dirs = paths.layout();
      const errors = paths.preflight();

      if (errors.length > 0) {
        dialog.showErrorBox(
          'SupplyMind AI 无法启动',
          '启动预检失败：\n\n' + errors.join('\n') + '\n\n请将应用复制到可写目录后重试。'
        );
        app.exit(1);
        return;
      }

      const port = await pickFreePort(http);
      backendUrl = `http://127.0.0.1:${port}`;

      // D9-T03: record the actual dynamic backend URL for diagnostics (loopback only).
      fs.mkdirSync(dirs.logs, { recursive: true });
      const backendUrlLog = path.join(dirs.logs, 'backend-url.txt');
      fs.writeFileSync(backendUrlLog, `${backendUrl}\n`, { flag: 'w' });

      childProcess = spawn(
        dirs.jreBin,
        backendArgs(port, dirs.data, dirs.jreBin, dirs.jar, dirs.web).args,
        {
          cwd: dirs.root,
          env: process.env,
          stdio: ['ignore', 'pipe', 'pipe'],
          windowsHide: true
        }
      );
      const logStream = fs.createWriteStream(path.join(dirs.logs, 'backend.log'), { flags: 'a' });
      childProcess.stdout.pipe(logStream);
      childProcess.stderr.pipe(logStream);

      // D9-T03: spawn failures (missing JRE, permission errors) must fail fast with a
      // diagnostic - never a silent hang or a leaked child.
      childProcess.on('error', async (err) => {
        await stopBackend();
        dialog.showErrorBox(
          'SupplyMind AI 启动失败',
          `无法启动后端进程：${err.code || err.message}\n日志：${path.join(dirs.logs, 'backend.log')}`
        );
        app.exit(1);
      });

      childProcess.on('exit', (code) => {
        if (mainWindow && !mainWindow.isDestroyed()) {
          dialog.showErrorBox(
            'SupplyMind AI 后端已停止',
            `后端进程已退出（exit code: ${code}）。\n日志：${path.join(dirs.logs, 'backend.log')}`
          );
        }
        childProcess = null;
        app.exit(0);
      });

      const result = await waitForBackend(backendUrl, { timeoutMs: HEALTH_TIMEOUT_MS });
      if (result.state !== 'READY') {
        await stopBackend();
        dialog.showErrorBox(
          'SupplyMind AI 启动失败',
          `后端健康检查超时：${result.message}\n日志：${path.join(dirs.logs, 'backend.log')}`
        );
        app.exit(1);
        return;
      }

      createWindow();
    } catch (error) {
      dialog.showErrorBox('SupplyMind AI 启动失败', String(error && error.message ? error.message : error));
      app.exit(1);
    }
  });
}

function createWindow() {
  // D9-T03: hand the renderer only the loopback backend URL. Set AFTER the Java child
  // was spawned so the child's environment snapshot is never affected; the renderer
  // never sees API keys, Java process details or the host environment.
  process.env.SUPPLYMIND_BACKEND_URL = backendUrl;
  mainWindow = new BrowserWindow({
    width: 1280,
    height: 800,
    title: 'SupplyMind AI',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true
    }
  });
  mainWindow.loadURL(backendUrl);
  mainWindow.on('closed', () => {
    mainWindow = null;
  });
}

async function stopBackend() {
  if (!childProcess) {
    return;
  }
  const child = childProcess;
  childProcess = null;
  child.kill();
  await new Promise((resolve) => {
    const timeout = setTimeout(resolve, KILL_GRACE_MS);
    child.once('exit', () => {
      clearTimeout(timeout);
      resolve();
    });
  });
  if (child.exitCode === null) {
    child.kill('SIGKILL');
  }
}

app.on('window-all-closed', async () => {
  await stopBackend();
  app.quit();
});

app.on('will-quit', async () => {
  await stopBackend();
});
