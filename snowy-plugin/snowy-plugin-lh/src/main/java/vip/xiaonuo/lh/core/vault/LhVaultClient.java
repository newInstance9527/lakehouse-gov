/*
 * Copyright [2022] [https://www.xiaonuo.vip]
 *
 * Snowy采用APACHE LICENSE 2.0开源协议，您在使用过程中，需要注意以下几点：
 *
 * 1.请不要删除和修改根目录下的LICENSE文件。
 * 2.请不要删除和修改Snowy源码头部的版权声明。
 * 3.本项目代码可免费商业使用，商业使用请保留源码和相关描述文件的项目出处，作者声明等。
 * 4.分发源码时候，请注明软件出处 https://www.xiaonuo.vip
 * 5.不可二次分发开源参与同类竞品，如有想法可联系团队xiaonuobase@qq.com商议合作。
 * 6.若您的项目无法满足以上几点，需要更多功能代码，获取Snowy商业授权许可，请在官网购买授权，地址为 https://www.xiaonuo.vip
 */
package vip.xiaonuo.lh.core.vault;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import cn.hutool.crypto.symmetric.AES;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
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
 * 本地 Vault 客户端（一期：AES 密文落 {@code ig_secret_store}）
 * <p>接口形态对齐 HashiCorp Vault：{@code write/read(path)}，后续可无感替换实现。</p>
 *
 * @author lakehouse
 * @date 2026/3/18
 */
@Component
public class LhVaultClient {

    @Resource
    private LhSecretStoreMapper secretStoreMapper;
    @Resource
    private LhProperties lhProperties;

    private AES aes() {
        byte[] key = SecureUtil.md5(lhProperties.getVault().getAesKey()).substring(0, 16)
                .getBytes(StandardCharsets.UTF_8);
        return SecureUtil.aes(key);
    }

    /**
     * 写入 / 覆盖凭证
     *
     * @param vaultPath 路径
     * @param secret    明文 Map（将被 JSON + AES）
     */
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

    /**
     * 路径不存在或为 PLACEHOLDER 时写入；已有真实密文则跳过（避免覆盖运维轮换结果）
     *
     * @param vaultPath 路径
     * @param secret    种子凭证
     * @return true=本次写入；false=已存在跳过
     */
    public boolean writeIfAbsent(String vaultPath, Map<String, Object> secret) {
        if (exists(vaultPath)) {
            return false;
        }
        write(vaultPath, secret);
        return true;
    }

    /**
     * 读取凭证（不存在抛错）
     *
     * @param vaultPath 路径
     * @return 明文 Map
     */
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

    /**
     * 读取凭证；不存在返回空 Map（不抛错）
     *
     * @param vaultPath 路径
     * @return 明文 Map 或空
     */
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

    /**
     * 读取字符串字段
     *
     * @param vaultPath 路径
     * @param key       字段名
     * @return 值，缺失返回 null
     */
    public String getString(String vaultPath, String key) {
        Object v = readOrEmpty(vaultPath).get(key);
        return v == null ? null : String.valueOf(v);
    }

    /**
     * 路径是否已有可用密文（非 PLACEHOLDER）
     *
     * @param vaultPath 路径
     * @return true=可用
     */
    public boolean exists(String vaultPath) {
        if (StrUtil.isBlank(vaultPath)) {
            return false;
        }
        LhSecretStore row = secretStoreMapper.selectById(vaultPath);
        return row != null && StrUtil.isNotBlank(row.getSecretCipher())
                && !"PLACEHOLDER".equals(row.getSecretCipher());
    }

    /**
     * 删除凭证
     *
     * @param vaultPath 路径
     */
    public void delete(String vaultPath) {
        secretStoreMapper.deleteById(vaultPath);
    }
}
