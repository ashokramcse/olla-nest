/**
 * @file src/services/workspace.js
 * @description Workspace helpers: workspaceRoot, normalizePermissionMode, workspaceForUser,
 * listWorkspaceFiles, writeLocalArtifacts, extractArtifacts, artifactBaseName, extensionForFence,
 * slugify, cleanModelOutput.
 */

const fs = require("fs");
const path = require("path");
const { DEFAULT_WORKSPACE_ROOT } = require("../config");
const { setting } = require("../db/settings");

/**
 * Strips internal chain-of-thought `<think>…</think>` blocks from model output.
 * Some reasoning models (DeepSeek R1, QwQ) emit these blocks before their final
 * answer — they are not useful to users and should not appear in the chat UI.
 * If a `<think>` block is unclosed, the function falls back to finding the first
 * recognisable code/HTML/React marker and returns everything from that point.
 *
 * @param {string} content - Raw model response string.
 * @returns {string} Cleaned response with think blocks removed.
 */
function cleanModelOutput(content) {
  let output = String(content || "")
    .replace(/<think>[\s\S]*?<\/think>/gi, "")
    .replace(/^\s*<\/think>\s*/i, "")
    .trim();
  if (/^<think>/i.test(output)) {
    const lower = output.toLowerCase();
    const markers = ["```", "<!doctype html", "<html", "import react", "export default"].map((marker) => lower.indexOf(marker)).filter((index) => index > 0);
    output = markers.length ? output.slice(Math.min(...markers)).trim() : output.replace(/^<think>/i, "").trim();
  }
  return output;
}

function listWorkspaceFiles(workspaceRoot, maxFiles = 50) {
  try {
    const results = [];
    function walk(dir, rel) {
      const entries = fs.readdirSync(dir, { withFileTypes: true });
      for (const entry of entries) {
        if (entry.name.startsWith(".")) continue;
        const relPath = rel ? `${rel}/${entry.name}` : entry.name;
        if (entry.isDirectory()) {
          walk(path.join(dir, entry.name), relPath);
        } else {
          results.push(relPath);
          if (results.length >= maxFiles) return;
        }
      }
    }
    walk(workspaceRoot, "");
    return results;
  } catch {
    return [];
  }
}

function workspaceRoot(db) {
  const configured = setting(db, "workspaceRoot", DEFAULT_WORKSPACE_ROOT);
  return path.resolve(String(configured || DEFAULT_WORKSPACE_ROOT));
}

function normalizePermissionMode(mode) {
  return ["default", "review", "full"].includes(mode) ? mode : "default";
}

function workspaceForUser(db, userId) {
  const prefs = db.prepare("SELECT workspace_root, permission_mode FROM workspace_prefs WHERE user_id = ?").get(userId);
  const root = path.resolve(String(prefs?.workspace_root || workspaceRoot(db)));
  return {
    workspaceRoot: root,
    outputFolder: path.join(root, "olla-nest-output"),
    permissionMode: normalizePermissionMode(prefs?.permission_mode || setting(db, "localPermissionMode", "default")),
    localWritesEnabled: setting(db, "localWritesEnabled", true),
  };
}

function slugify(value, fallback = "artifact") {
  const slug = String(value || "")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, 60);
  return slug || fallback;
}

function artifactBaseName(message) {
  const text = String(message || "").toLowerCase();
  if (/sign[\s-]?in|login/.test(text)) return "signin-page";
  if (/dashboard/.test(text)) return "dashboard";
  if (/landing/.test(text)) return "landing-page";
  if (/component/.test(text)) return "component";
  return slugify(text.split(/\n/)[0], "generated-output");
}

function extensionForFence(language, content) {
  const lang = String(language || "").toLowerCase();
  if (["jsx", "tsx", "ts", "js", "html", "css", "json", "md"].includes(lang)) return lang;
  if (/^\s*<!doctype html|<html[\s>]/i.test(content)) return "html";
  if (/import\s+React|from\s+['"]react['"]|useState|className=|function\s+[A-Z]\w+|const\s+[A-Z]\w+\s*=/.test(content)) return "jsx";
  return "txt";
}

function extractArtifacts(content, message) {
  const artifacts = [];
  // Match ```lang:filename or ```lang filename or just ```lang
  const fencePattern = /```([a-zA-Z0-9_-]*)(?::([^\s`]+)|[ \t]+filename=["']?([^"'\s`]+)["']?)?\n([\s\S]*?)```/g;
  let match;
  while ((match = fencePattern.exec(content))) {
    const langPart = match[1];
    const filenameFromColon = match[2]; // ```jsx:src/App.jsx
    const filenameFromAttr = match[3];  // ```jsx filename="App.jsx"
    const body = match[4].trim();
    if (!body) continue;
    const parsedFilename = filenameFromColon || filenameFromAttr || null;
    const ext = extensionForFence(langPart, body);
    artifacts.push({ ext, content: body, parsedFilename });
  }
  if (!artifacts.length) {
    const htmlMatch = String(content).match(/(?:<!doctype html>\s*)?<html[\s\S]*?<\/html>/i);
    if (htmlMatch) artifacts.push({ ext: "html", content: htmlMatch[0].trim(), parsedFilename: null });
  }
  if (!artifacts.length && /<!doctype html|<html[\s>]|import React|from\s+['"]react['"]|useState|className=|function\s+\w+|const\s+\w+\s*=/.test(content)) {
    const ext = extensionForFence("", content);
    artifacts.push({ ext, content: content.trim(), parsedFilename: null });
  }
  const baseName = artifactBaseName(message);
  return artifacts.map((artifact, index) => {
    const name = artifact.parsedFilename
      ? artifact.parsedFilename
      : `${baseName}${artifacts.length > 1 ? `-${index + 1}` : ""}.${artifact.ext}`;
    return { ...artifact, name };
  });
}

/**
 * Extracts code artifacts from a model response and writes them to the user's
 * workspace folder on disk.
 *
 * Path traversal is prevented by checking that every resolved path starts with
 * the workspace root + path separator before writing.
 *
 * Returns an empty array if localWritesEnabled=false or no code blocks found.
 *
 * @param {DatabaseSync} db
 * @param {{ workspaceRoot: string }} workspace - Workspace descriptor from workspaceForUser().
 * @param {string} message - Original user message (for artifact base-name generation).
 * @param {string} mode - Chat mode.
 * @param {string} content - Full model response.
 * @returns {{ name: string, path: string, relativePath: string, bytes: number }[]}
 */
function writeLocalArtifacts(db, workspace, message, mode, content) {
  if (!setting(db, "localWritesEnabled", true)) return [];
  const artifacts = extractArtifacts(content, message);
  if (!artifacts.length) return [];
  const root = path.resolve(String(workspace?.workspaceRoot || workspaceRoot(db)));
  return artifacts.map((artifact) => {
    const filePath = path.resolve(path.join(root, artifact.name));
    // Prevent path traversal outside workspace root
    if (!filePath.startsWith(root + path.sep) && filePath !== root) {
      return null;
    }
    fs.mkdirSync(path.dirname(filePath), { recursive: true });
    fs.writeFileSync(filePath, `${artifact.content}\n`, "utf8");
    return {
      name: artifact.name,
      path: filePath,
      relativePath: path.relative(root, filePath),
      bytes: Buffer.byteLength(artifact.content, "utf8"),
    };
  }).filter(Boolean);
}

module.exports = {
  workspaceRoot,
  normalizePermissionMode,
  workspaceForUser,
  listWorkspaceFiles,
  writeLocalArtifacts,
  extractArtifacts,
  artifactBaseName,
  extensionForFence,
  slugify,
  cleanModelOutput,
};
