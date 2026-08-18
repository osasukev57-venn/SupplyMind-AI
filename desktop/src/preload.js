'use strict';

/**
 * D9-T03 renderer bridge. Exposes only the backend base URL (dynamic loopback port)
 * to the Vue app. The LLM API key never reaches the renderer: it is inherited by the
 * Java child process through the environment only.
 */
const { contextBridge } = require('electron');

contextBridge.exposeInMainWorld('supplymindDesktop', {
  backendUrl: process.env.SUPPLYMIND_BACKEND_URL || '',
  isDesktop: true
});
