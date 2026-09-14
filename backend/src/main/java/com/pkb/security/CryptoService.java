package com.pkb.security;

import com.pkb.config.PkbProperties;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Base64;
import java.util.Set;

/**
 * 本地加密密钥管理：
 * 1. 优先使用环境变量 PKB_ENCRYPT_KEY（Base64，32 字节）；
 * 2. 否则读取 <data-dir>/.secret；
 * 3. 都不存在则自动生成并保存（权限 600）。
 */
@Component
public class CryptoService {

    private static final Logger log = LoggerFactory.getLogger(CryptoService.class);

    @Getter
    private byte[] key;

    private final PkbProperties props;

    public CryptoService(PkbProperties props) {
        this.props = props;
    }

    @PostConstruct
    public void init() {
        String env = System.getenv("PKB_ENCRYPT_KEY");
        if (env != null && !env.isBlank()) {
            try {
                key = Base64.getDecoder().decode(env.trim());
            } catch (Exception e) {
                log.warn("PKB_ENCRYPT_KEY 不是合法的 Base64，将回退到本地密钥文件");
            }
        }
        if (key == null) {
            Path dataDir = Paths.get(props.getDataDir()).toAbsolutePath().normalize();
            try {
                Files.createDirectories(dataDir);
                Path secret = dataDir.resolve(".secret");
                if (Files.exists(secret)) {
                    key = Base64.getDecoder().decode(Files.readString(secret).trim());
                } else {
                    key = com.pkb.util.EncryptUtil.randomKey();
                    Files.writeString(secret, Base64.getEncoder().encodeToString(key));
                    try {
                        Files.setPosixFilePermissions(secret, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
                    } catch (UnsupportedOperationException ignored) {
                        // 非 POSIX 文件系统忽略
                    }
                    log.info("已生成新的本地加密密钥: {}", secret);
                }
            } catch (Exception e) {
                throw new IllegalStateException("初始化加密密钥失败", e);
            }
        }
    }
}
