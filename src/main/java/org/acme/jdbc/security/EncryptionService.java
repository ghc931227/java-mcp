package org.acme.jdbc.security;

import io.quarkus.runtime.util.StringUtil;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jasypt.util.text.BasicTextEncryptor;

import java.util.Optional;

@ApplicationScoped
public class EncryptionService {

    /**
     * 密钥允许为空（未设置 JDBC_ENCRYPTION_KEY 时不加密，密码明文存储）。
     * 用 Optional 接收：属性缺失时注入 Optional.empty()，不会导致启动失败。
     */
    @Inject
    @ConfigProperty(name = "jdbc.encryption.key")
    Optional<String> encryptionKey;

    /** BasicTextEncryptor 非线程安全，使用 volatile + synchronized 保证安全发布与互斥访问 */
    private volatile BasicTextEncryptor textEncryptor;

    private synchronized void ensureInitialized() {
        if (textEncryptor == null) {
            String key = encryptionKey.orElse("");
            if (!StringUtil.isNullOrEmpty(key)) {
                BasicTextEncryptor encryptor = new BasicTextEncryptor();
                encryptor.setPassword(key);
                textEncryptor = encryptor;
            }
        }
    }

    public synchronized String encrypt(String plainText) {
        if (plainText == null || plainText.isEmpty()) {
            return plainText;
        }
        ensureInitialized();
        return textEncryptor == null ? plainText : textEncryptor.encrypt(plainText);
    }

    public synchronized String decrypt(String encryptedText) {
        if (encryptedText == null || encryptedText.isEmpty()) {
            return encryptedText;
        }
        ensureInitialized();
        try {
            return textEncryptor == null ? encryptedText : textEncryptor.decrypt(encryptedText);
        } catch (Exception e) {
            return encryptedText;
        }
    }
}
