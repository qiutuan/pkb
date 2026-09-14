package com.pkb.util;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM 加解密：输出 base64(nonce + ciphertext + tag)。
 */
public final class EncryptUtil {

    private static final int GCM_TAG_BITS = 128;
    private static final int NONCE_BYTES = 12;
    private static final SecureRandom RANDOM = new SecureRandom();

    private EncryptUtil() {
    }

    public static byte[] randomKey() {
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        return key;
    }

    public static SecretKey key(byte[] raw) {
        return new SecretKeySpec(raw, "AES");
    }

    public static String encrypt(String plain, byte[] key) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(key), new GCMParameterSpec(GCM_TAG_BITS, nonce));
            byte[] enc = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[nonce.length + enc.length];
            System.arraycopy(nonce, 0, out, 0, nonce.length);
            System.arraycopy(enc, 0, out, nonce.length, enc.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new RuntimeException("加密失败: " + e.getMessage(), e);
        }
    }

    public static String decrypt(String encoded, byte[] key) {
        try {
            byte[] all = Base64.getDecoder().decode(encoded);
            byte[] nonce = new byte[NONCE_BYTES];
            System.arraycopy(all, 0, nonce, 0, NONCE_BYTES);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(key), new GCMParameterSpec(GCM_TAG_BITS, nonce));
            byte[] plain = cipher.doFinal(all, NONCE_BYTES, all.length - NONCE_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("解密失败（密钥可能已变更）", e);
        }
    }

    public static String mask(String secret) {
        if (secret == null || secret.isBlank()) {
            return "";
        }
        if (secret.length() <= 8) {
            return "****";
        }
        return secret.substring(0, 4) + "****" + secret.substring(secret.length() - 4);
    }
}
