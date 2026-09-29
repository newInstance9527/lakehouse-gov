package vip.xiaonuo.lh.core.vault;

import java.util.Map;

/**
 * 凭证存储后端（AES@MySQL 或 HashiCorp Vault KV）。
 */
public interface LhVaultStore {

    void write(String vaultPath, Map<String, Object> secret);

    Map<String, Object> read(String vaultPath);

    Map<String, Object> readOrEmpty(String vaultPath);

    boolean exists(String vaultPath);

    void delete(String vaultPath);

    /** 后端标识：aes / hashicorp */
    String backendId();
}
