'use strict';

const path = require('path');
const fs = require('fs');

/**
 * D9-T02/D9-T01 portable path discovery. All business data must live in a visible
 * `data/` directory next to the application root (DEC-024): never fall back to a hidden
 * user directory. Path resolution is pure and unit-testable.
 *
 * Portable layout (frozen, docs/01-PROJECT-MASTER-PLAN.md 8.3):
 *   SupplyMindAI/
 *     SupplyMindAI.exe
 *     runtime/jre/                bundled JRE
 *     app/supplymind-backend.jar  Spring Boot JAR
 *     app/web/                    Vue static assets (served by the backend)
 *     data/                       visible business data
 *     logs/
 *     licenses/
 */

/**
 * Resolve the portable root. With an explicit override (tests/dev) use it directly;
 * otherwise walk up from the shell directory (app/shell) looking for the frozen layout
 * marker (runtime/jre + app/supplymind-backend.jar). This works both for the packaged
 * portable directory and for direct `electron .` dev runs from desktop/.
 *
 * @param {string} [explicitRoot] override for tests
 * @returns {string} absolute portable root path
 */
function portableRoot(explicitRoot) {
  if (explicitRoot) {
    return path.resolve(explicitRoot);
  }
  if (process.resourcesPath && fs.existsSync(path.join(process.resourcesPath, 'app'))) {
    return path.resolve(process.resourcesPath, '..');
  }
  let dir = __dirname;
  for (let depth = 0; depth < 4; depth += 1) {
    const candidate = path.resolve(dir);
    const hasJre = fs.existsSync(path.join(candidate, 'runtime', 'jre', 'bin', 'java.exe'));
    const hasJar = fs.existsSync(path.join(candidate, 'app', 'supplymind-backend.jar'));
    if (hasJre && hasJar) {
      return candidate;
    }
    const parent = path.dirname(dir);
    if (parent === dir) {
      break;
    }
    dir = parent;
  }
  return path.resolve(__dirname, '..');
}

function layout(base) {
  const root = portableRoot(base);
  return {
    root,
    jreBin: path.join(root, 'runtime', 'jre', 'bin', 'java.exe'),
    jar: path.join(root, 'app', 'supplymind-backend.jar'),
    web: path.join(root, 'app', 'web'),
    webIndex: path.join(root, 'app', 'web', 'index.html'),
    data: path.join(root, 'data'),
    logs: path.join(root, 'logs'),
    licenses: path.join(root, 'licenses')
  };
}

/** Fail-fast preflight: every required path must exist and data/logs must be writable. */
function preflight(base) {
  const dirs = layout(base);
  const errors = [];
  if (!fs.existsSync(dirs.jreBin)) {
    errors.push(`bundled JRE not found: ${dirs.jreBin}`);
  }
  if (!fs.existsSync(dirs.jar)) {
    errors.push(`backend JAR not found: ${dirs.jar}`);
  }
  if (!fs.existsSync(dirs.webIndex)) {
    errors.push(`frontend assets not found: ${dirs.webIndex}`);
  }
  for (const dir of [dirs.data, dirs.logs]) {
    if (!fs.existsSync(dir)) {
      errors.push(`directory missing: ${dir}`);
    } else if (!fs.statSync(dir).isDirectory()) {
      errors.push(`path is not a directory: ${dir}`);
    } else if (!isWritable(dir)) {
      errors.push(`directory is not writable: ${dir}`);
    }
  }
  return errors;
}

function isWritable(dir) {
  try {
    fs.accessSync(dir, fs.constants.W_OK);
    return true;
  } catch (err) {
    return false;
  }
}

module.exports = { portableRoot, layout, preflight, isWritable };
