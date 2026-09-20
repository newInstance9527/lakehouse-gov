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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.config.LhProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 启动时将 yml bootstrap 凭证写入 Vault（仅 path 不存在时）
 * <p>生产环境首次导入后，应从配置中移除明文口令，仅保留 vault-path。</p>
 *
 * @author lakehouse
 * @date 2026/3/18
 */
@Component
@Order(50)
public class LhPlatformSecretBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LhPlatformSecretBootstrap.class);

    @Resource
    private LhVaultClient vaultClient;
    @Resource
    private LhProperties lhProperties;

    @Override
    public void run(ApplicationArguments args) {
        int n = 0;
        n += seedUserPass(lhProperties.getTrino().getVaultPath(),
                lhProperties.getTrino().getUser(), lhProperties.getTrino().getPassword(), null);
        n += seedUserPass(lhProperties.getDs().getVaultPath(),
                lhProperties.getDs().getUser(), lhProperties.getDs().getPassword(),
                lhProperties.getDs().getToken());
        n += seedUserPass(lhProperties.getFlink().getVaultPath(),
                lhProperties.getFlink().getUser(), lhProperties.getFlink().getPassword(), null);
        n += seedUserPass(lhProperties.getClickhouse().getVaultPath(),
                lhProperties.getClickhouse().getUser(), lhProperties.getClickhouse().getPassword(), null);
        n += seedUserPass(lhProperties.getGravitino().getVaultPath(),
                lhProperties.getGravitino().getUser(), lhProperties.getGravitino().getPassword(), null);
        n += seedUserPass(lhProperties.getSuperset().getVaultPath(),
                lhProperties.getSuperset().getUser(), lhProperties.getSuperset().getPassword(), null);

        if (StrUtil.isNotBlank(lhProperties.getApisix().getApiKey())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("apiKey", lhProperties.getApisix().getApiKey());
            if (vaultClient.writeIfAbsent(lhProperties.getApisix().getVaultPath(), m)) {
                n++;
                log.info("[LhVault] seeded {}", lhProperties.getApisix().getVaultPath());
            }
        }

        if (StrUtil.isNotBlank(lhProperties.getOpenmetadata().getToken())
                || StrUtil.isNotBlank(lhProperties.getOpenmetadata().getPassword())) {
            Map<String, Object> m = new LinkedHashMap<>();
            if (StrUtil.isNotBlank(lhProperties.getOpenmetadata().getToken())) {
                m.put("token", lhProperties.getOpenmetadata().getToken());
            }
            if (StrUtil.isNotBlank(lhProperties.getOpenmetadata().getEmail())) {
                m.put("email", lhProperties.getOpenmetadata().getEmail());
            }
            if (StrUtil.isNotBlank(lhProperties.getOpenmetadata().getPassword())) {
                m.put("password", lhProperties.getOpenmetadata().getPassword());
            }
            if (vaultClient.writeIfAbsent(lhProperties.getOpenmetadata().getVaultPath(), m)) {
                n++;
                log.info("[LhVault] seeded {}", lhProperties.getOpenmetadata().getVaultPath());
            } else {
                // path 已存在但可能缺 token：合并补 password/email，不覆盖已有 token
                try {
                    Map<String, Object> exist = new LinkedHashMap<>(
                            vaultClient.readOrEmpty(lhProperties.getOpenmetadata().getVaultPath()));
                    boolean changed = false;
                    for (Map.Entry<String, Object> e : m.entrySet()) {
                        if (!exist.containsKey(e.getKey()) || exist.get(e.getKey()) == null
                                || StrUtil.isBlank(String.valueOf(exist.get(e.getKey())))) {
                            exist.put(e.getKey(), e.getValue());
                            changed = true;
                        }
                    }
                    if (changed) {
                        vaultClient.write(lhProperties.getOpenmetadata().getVaultPath(), exist);
                        log.info("[LhVault] merged bootstrap fields into {}",
                                lhProperties.getOpenmetadata().getVaultPath());
                    }
                } catch (Exception ignored) {
                }
            }
        }

        LhProperties.Minio minio = lhProperties.getMinio();
        if (StrUtil.isNotBlank(minio.getAccessKey()) && StrUtil.isNotBlank(minio.getSecretKey())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("accessKey", minio.getAccessKey());
            m.put("secretKey", minio.getSecretKey());
            if (vaultClient.writeIfAbsent(minio.getVaultPath(), m)) {
                n++;
                log.info("[LhVault] seeded {}", minio.getVaultPath());
            }
        }

        log.info("[LhVault] platform secret bootstrap done, newly seeded={}", n);
    }

    private int seedUserPass(String path, String user, String password, String token) {
        if (StrUtil.isBlank(path)) {
            return 0;
        }
        if (StrUtil.isBlank(user) && StrUtil.isBlank(password) && StrUtil.isBlank(token)) {
            return 0;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        if (StrUtil.isNotBlank(user)) {
            m.put("username", user);
        }
        if (StrUtil.isNotBlank(password)) {
            m.put("password", password);
        }
        if (StrUtil.isNotBlank(token)) {
            m.put("token", token);
        }
        if (vaultClient.writeIfAbsent(path, m)) {
            log.info("[LhVault] seeded {}", path);
            return 1;
        }
        return 0;
    }
}
