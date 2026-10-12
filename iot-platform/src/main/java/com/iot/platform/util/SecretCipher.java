package com.iot.platform.util;

import com.iot.platform.config.SecretProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * device_secret 加解密（F3 设备密钥加密存储）。
 *
 * <p>算法：AES-256-GCM（认证加密），每次加密生成随机 12 字节 IV，
 * 密文格式 {@code v1:<Base64(IV ‖ ciphertext+tag)>}。
 *
 * <p>旧数据兼容：{@link #decrypt} 对不带 {@code v1:} 前缀的值视为历史明文原样返回，
 * 使存量明文设备无需停机即可平滑迁移（认证路径发现明文后自动转密回写，见 AuthServiceImpl）。
 */
@Slf4j
@Component
public class SecretCipher {

    private static final String ALGO = "AES/GCM/NoPadding";
    private static final String PREFIX = "v1:";
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    public SecretCipher(SecretProperties props) {
        byte[] raw = Base64.getDecoder().decode(props.getMasterKey());
        if (raw.length != 32) {
            throw new IllegalStateException(
                    "app.device-secret.master-key 必须是 Base64 编码的 32 字节（AES-256），实际 " + raw.length + " 字节");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    public String encrypt(String plain) {
        if (plain == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LEN];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGO);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array());
        } catch (Exception e) {
            throw new IllegalStateException("device_secret 加密失败", e);
        }
    }

    /** 解密；不带 {@code v1:} 前缀的历史明文原样返回（懒迁移兼容） */
    public String decrypt(String raw) {
        if (raw == null) {
            return null;
        }
        if (!raw.startsWith(PREFIX)) {
            return raw;
        }
        try {
            byte[] all = Base64.getDecoder().decode(raw.substring(PREFIX.length()));
            byte[] iv = Arrays.copyOfRange(all, 0, IV_LEN);
            byte[] ct = Arrays.copyOfRange(all, IV_LEN, all.length);
            Cipher cipher = Cipher.getInstance(ALGO);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("device_secret 解密失败（主密钥不匹配或数据损坏）", e);
        }
    }
}
