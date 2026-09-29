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
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import vip.xiaonuo.common.exception.CommonException;

import java.util.Collections;
import java.util.Map;

/**
 * 凭证门面：委托 {@link LhVaultStore}（{@code lh.vault.backend=aes|hashicorp}）。
 * <p>调用方继续注入本类，无需感知后端切换。</p>
 */
@Component
public class LhVaultClient {

    @Resource
    private LhVaultStore vaultStore;

    public String backendId() {
        return vaultStore.backendId();
    }

    public void write(String vaultPath, Map<String, Object> secret) {
        vaultStore.write(vaultPath, secret);
    }

    public boolean writeIfAbsent(String vaultPath, Map<String, Object> secret) {
        if (exists(vaultPath)) {
            return false;
        }
        write(vaultPath, secret);
        return true;
    }

    public Map<String, Object> read(String vaultPath) {
        return vaultStore.read(vaultPath);
    }

    public Map<String, Object> readOrEmpty(String vaultPath) {
        if (StrUtil.isBlank(vaultPath)) {
            return Collections.emptyMap();
        }
        return vaultStore.readOrEmpty(vaultPath);
    }

    public String getString(String vaultPath, String key) {
        Object v = readOrEmpty(vaultPath).get(key);
        return v == null ? null : String.valueOf(v);
    }

    public boolean exists(String vaultPath) {
        return vaultStore.exists(vaultPath);
    }

    public void delete(String vaultPath) {
        if (StrUtil.isBlank(vaultPath)) {
            throw new CommonException("vault_path 不能为空");
        }
        vaultStore.delete(vaultPath);
    }
}
