/**
 * @file src/services/crypto.js
 * @description AES-256-GCM encryption/decryption of API keys: encryptKey, decryptKey.
 */

const crypto = require("crypto");
const { SECRET_KEY } = require("../config");

// ─── Encryption ───────────────────────────────────────────────────────────────

/**
 * Encrypts a plaintext API key using AES-256-GCM.
 *
 * Format stored in DB: `{iv_hex}:{auth_tag_hex}:{ciphertext_hex}`
 * A fresh random 12-byte IV is generated per encryption call so two encryptions
 * of the same plaintext produce different ciphertext (semantic security).
 *
 * @param {string} plaintext - The raw API key to encrypt.
 * @returns {string} Encoded string suitable for storage in api_providers.api_key_enc.
 */
function encryptKey(plaintext) {
  const iv = crypto.randomBytes(12);
  const key = crypto.createHash("sha256").update(SECRET_KEY).digest();
  const cipher = crypto.createCipheriv("aes-256-gcm", key, iv);
  const encrypted = Buffer.concat([cipher.update(plaintext, "utf8"), cipher.final()]);
  const tag = cipher.getAuthTag();
  return `${iv.toString("hex")}:${tag.toString("hex")}:${encrypted.toString("hex")}`;
}

/**
 * Decrypts an AES-256-GCM encrypted API key produced by encryptKey().
 * Returns an empty string (not an exception) on failure so callers always
 * get a string — an invalid/empty key will simply result in a 401 from the
 * provider, which surfaces a clear error to the user.
 *
 * @param {string} stored - The `iv:tag:ciphertext` hex string from the database.
 * @returns {string} Decrypted plaintext API key, or "" on failure.
 */
function decryptKey(stored) {
  try {
    const [ivHex, tagHex, encHex] = stored.split(":");
    const key = crypto.createHash("sha256").update(SECRET_KEY).digest();
    const decipher = crypto.createDecipheriv("aes-256-gcm", key, Buffer.from(ivHex, "hex"));
    decipher.setAuthTag(Buffer.from(tagHex, "hex"));
    return decipher.update(Buffer.from(encHex, "hex")) + decipher.final("utf8");
  } catch {
    return "";
  }
}

module.exports = { encryptKey, decryptKey };
