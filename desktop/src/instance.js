'use strict';

/**
 * D9-T04 single-instance control. Wrapped in a pure module so the behavior is
 * unit-testable without launching Electron: the second instance must never create
 * a second window - it activates the existing one and exits.
 *
 * @param {object} app electron app (or a test double with requestSingleInstanceLock/quit)
 * @param {object} win {restore, focus} or a getter returning the main window
 * @returns {boolean} true when this instance owns the lock and should continue
 */
function acquireSingleInstance(app, getWindow, onActivated) {
  const gotLock = app.requestSingleInstanceLock();
  if (!gotLock) {
    return false;
  }
  app.on('second-instance', () => {
    const win = typeof getWindow === 'function' ? getWindow() : getWindow;
    let restored = false;
    let focusCalled = false;
    if (win) {
      if (win.isMinimized && win.isMinimized()) {
        win.restore();
        restored = true;
      }
      if (win.focus) {
        win.focus();
        focusCalled = true;
      }
    }
    if (typeof onActivated === 'function') {
      onActivated({ event: 'SECOND_INSTANCE_ACTIVATED', windowExists: Boolean(win), restored, focusCalled });
    }
  });
  return true;
}

module.exports = { acquireSingleInstance };
