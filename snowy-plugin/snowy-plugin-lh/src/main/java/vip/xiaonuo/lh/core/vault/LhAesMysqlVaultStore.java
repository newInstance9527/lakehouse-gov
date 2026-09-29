package vip.xiaonuo.lh.core.vault;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import cn.hutool.crypto.symmetric.AES;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.modular.datasource.entity.LhSecretStore;
import vip.xiaonuo.lh.modular.datasource.mapper.LhSecretStoreMapper;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一期默认后端：AES 密文落 {@code ig_secret_store}。
 */
@Component
@ConditionalOnProperty(prefix = "lh.vault", name = "backend", havingValue = "aes", matchIfMissing = true)
public class LhAesMysqlVaultStore implements LhVaultStore {

    @Resource
    private LhSecretStoreMapper secretStoreMapper;
    @Resource
    private LhProperties lhProperties;

    private AES aes() {
        String material = lhProperties.getVault().getAesKey();
        if (StrUtil.isBlank(material)) {
            throw new CommonException("lh.vault.aes-key 未配置（aes 后端）");
        }
        byte[] key = SecureUtil.md5(material).substring(0, 16).getBytes(StandardCharsets.UTF_8);
        return SecureUtil.aes(key);
    }

    @Override
    public void write(String vaultPath, Map<String, Object> secret) {
        if (StrUtil.isBlank(vaultPath)) {
            throw new CommonException("vault_path 不能为空");
        }
        String cipher = aes().encryptBase64(JSONUtil.toJsonStr(secret));
        LhSecretStore exist = secretStoreMapper.selectById(vaultPath);
        if (exist == null) {
            LhSecretStore row = new LhSecretStore();
            row.setVaultPath(vaultPath);
            row.setSecretCipher(cipher);
            secretStoreMapper.insert(row);
        } else {
            exist.setSecretCipher(cipher);
            secretStoreMapper.updateById(exist);
        }
    }

    @Override
    public Map<String, Object> read(String vaultPath) {
        LhSecretStore row = secretStoreMapper.selectOne(new LambdaQueryWrapper<LhSecretStore>()
                .eq(LhSecretStore::getVaultPath, vaultPath));
        if (row == null || StrUtil.isBlank(row.getSecretCipher())) {
            throw new CommonException("凭证不存在: {}", vaultPath);
        }
        if ("PLACEHOLDER".equals(row.getSecretCipher())) {
            return new LinkedHashMap<>();
        }
        String plain = aes().decryptStr(row.getSecretCipher());
        return JSONUtil.parseObj(plain);
    }

    @Override
    public Map<String, Object> readOrEmpty(String vaultPath) {
        if (StrUtil.isBlank(vaultPath) || !exists(vaultPath)) {
            return Collections.emptyMap();
        }
        try {
            return read(vaultPath);
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    @Override
    public boolean exists(String vaultPath) {
        if (StrUtil.isBlank(vaultPath)) {
            return false;
        }
        LhSecretStore row = secretStoreMapper.selectById(vaultPath);
        return row != null && StrUtil.isNotBlank(row.getSecretCipher())
                && !"PLACEHOLDER".equals(row.getSecretCipher());
    }

    @Override
    public void delete(String vaultPath) {
        secretStoreMapper.deleteById(vaultPath);
    }

    @Override
    public String backendId() {
        return "aes";
    }
}
