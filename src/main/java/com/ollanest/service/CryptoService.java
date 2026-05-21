package com.ollanest.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * AES-256-GCM encryption/decryption of API keys.
 * Format stored in DB: {iv_hex}:{auth_tag_hex}:{ciphertext_hex}
 * Compatible with the Node.js crypto service.
 */
@Service
public class CryptoService {

    private final byte[] key;

    public CryptoService(@Value("${encryption.key:change-me-in-production}") String encryptionKey) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            this.key = sha256.digest(encryptionKey.getBytes("UTF-8"));
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize CryptoService", e);
        }
    }

    public String encryptKey(String plaintext) {
        try {
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes("UTF-8"));
            // encrypted = ciphertext + 16-byte auth tag (GCM appends tag at end)
            int cipherLen = encrypted.length - 16;
            byte[] ciphertext = new byte[cipherLen];
            byte[] tag = new byte[16];
            System.arraycopy(encrypted, 0, ciphertext, 0, cipherLen);
            System.arraycopy(encrypted, cipherLen, tag, 0, 16);
            return toHex(iv) + ":" + toHex(tag) + ":" + toHex(ciphertext);
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }

    public String decryptKey(String stored) {
        try {
            if (stored == null || stored.isBlank()) return "";
            String[] parts = stored.split(":");
            if (parts.length < 3) return "";
            byte[] iv = fromHex(parts[0]);
            byte[] tag = fromHex(parts[1]);
            byte[] ciphertext = fromHex(parts[2]);
            // Java GCM expects ciphertext + tag concatenated
            byte[] combined = new byte[ciphertext.length + tag.length];
            System.arraycopy(ciphertext, 0, combined, 0, ciphertext.length);
            System.arraycopy(tag, 0, combined, ciphertext.length, tag.length);
            SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(combined), "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private byte[] fromHex(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2)
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4) + Character.digit(hex.charAt(i + 1), 16));
        return data;
    }
}
