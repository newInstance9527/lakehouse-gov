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
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.core.vault.LhVaultPaths;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 组件凭证解析：优先 Vault，缺失时回退 yml bootstrap（仅便于首次启动）
 *
 * @author lakehouse
 * @date 2026/3/18
 */
@Component
public class LhComponentCredentialResolver {

    @Resource
    private LhVaultClient vaultClient;
    @Resource
    private LhProperties lhProperties;

    /**
     * 按 vault_path 读取；空字段用 bootstrap 兜底
     *
     * @param vaultPath       路径
     * @param bootstrapUser   yml 用户
     * @param bootstrapPass   yml 密码
     * @param bootstrapToken  yml token（可选）
     * @return username/password/token
     */
    public Map<String, String> resolveUserPass(String vaultPath, String bootstrapUser,
                                               String bootstrapPass, String bootstrapToken) {
        Map<String, Object> secret = vaultClient.readOrEmpty(vaultPath);
        String user = firstNonBlank(str(secret.get("username")), str(secret.get("user")), bootstrapUser);
        String pass = firstNonBlank(str(secret.get("password")), bootstrapPass);
        String token = firstNonBlank(str(secret.get("token")), bootstrapToken);
        Map<String, String> out = new LinkedHashMap<>();
        out.put("username", user);
        out.put("password", pass);
        out.put("token", token);
        return out;
    }

    /**
     * 读取单一 apiKey / token
     *
     * @param vaultPath      路径
     * @param keyInSecret    secret 内字段名（apiKey / token）
     * @param bootstrapValue yml 兜底
     * @return 值
     */
    public String resolveSecretValue(String vaultPath, String keyInSecret, String bootstrapValue) {
        String fromVault = vaultClient.getString(vaultPath, keyInSecret);
        if (StrUtil.isBlank(fromVault) && !"token".equals(keyInSecret)) {
            fromVault = vaultClient.getString(vaultPath, "token");
        }
        if (StrUtil.isBlank(fromVault) && !"apiKey".equals(keyInSecret)) {
            fromVault = vaultClient.getString(vaultPath, "apiKey");
        }
        String value = firstNonBlank(fromVault, bootstrapValue);
        if (StrUtil.isBlank(value)) {
            throw new CommonException("组件凭证缺失: path={}, 请先写入 Vault 或配置 bootstrap", vaultPath);
        }
        return value;
    }

    /** Trino */
    public Map<String, String> trino() {
        LhProperties.Trino c = lhProperties.getTrino();
        return resolveUserPass(c.getVaultPath(), c.getUser(), c.getPassword(), null);
    }

    /** DolphinScheduler */
    public Map<String, String> ds() {
        LhProperties.Ds c = lhProperties.getDs();
        return resolveUserPass(c.getVaultPath(), c.getUser(), c.getPassword(), c.getToken());
    }

    /** Flink */
    public Map<String, String> flink() {
        LhProperties.Flink c = lhProperties.getFlink();
        return resolveUserPass(c.getVaultPath(), c.getUser(), c.getPassword(), null);
    }

    /** ClickHouse */
    public Map<String, String> clickhouse() {
        LhProperties.Clickhouse c = lhProperties.getClickhouse();
        return resolveUserPass(c.getVaultPath(), c.getUser(), c.getPassword(), null);
    }

    /**
     * 合规删除专用 SA。Vault 缺用户时回退平台 ClickHouse 凭证（联调）。
     */
    public Map<String, String> complianceSa() {
        LhProperties.Compliance c = lhProperties.getCompliance();
        String path = c == null || StrUtil.isBlank(c.getSaVaultPath())
                ? LhVaultPaths.COMPLIANCE_SA
                : c.getSaVaultPath().trim();
        String bootUser = c == null ? null : c.getSaUser();
        String bootPass = c == null ? null : c.getSaPassword();
        Map<String, String> sa = resolveUserPass(path, bootUser, bootPass, null);
        if (StrUtil.isBlank(sa.get("username")) && StrUtil.isBlank(sa.get("password"))) {
            return clickhouse();
        }
        return sa;
    }

    public String complianceSaVaultPath() {
        LhProperties.Compliance c = lhProperties.getCompliance();
        if (c != null && StrUtil.isNotBlank(c.getSaVaultPath())) {
            return c.getSaVaultPath().trim();
        }
        return LhVaultPaths.COMPLIANCE_SA;
    }

    /** APISIX Admin Key */
    public String apisixApiKey() {
        LhProperties.Apisix c = lhProperties.getApisix();
        return resolveSecretValue(c.getVaultPath(), "apiKey", c.getApiKey());
    }

    /** OpenMetadata Bot Token */
    public String openMetadataToken() {
        LhProperties.Openmetadata c = lhProperties.getOpenmetadata();
        return resolveSecretValue(c.getVaultPath(), "token", c.getToken());
    }

    /** Gravitino Basic */
    public Map<String, String> gravitino() {
        LhProperties.Gravitino c = lhProperties.getGravitino();
        return resolveUserPass(c.getVaultPath(), c.getUser(), c.getPassword(), null);
    }

    /** Superset */
    public Map<String, String> superset() {
        LhProperties.Superset c = lhProperties.getSuperset();
        return resolveUserPass(c.getVaultPath(), c.getUser(), c.getPassword(), null);
    }

    /** MinIO */
    public Map<String, String> minio() {
        LhProperties.Minio c = lhProperties.getMinio();
        Map<String, Object> secret = vaultClient.readOrEmpty(c.getVaultPath());
        Map<String, String> out = new LinkedHashMap<>();
        out.put("accessKey", firstNonBlank(str(secret.get("accessKey")), c.getAccessKey()));
        out.put("secretKey", firstNonBlank(str(secret.get("secretKey")), c.getSecretKey()));
        return out;
    }

    /** 夜莺：username/password；无凭证时返回空字段（调用方跳过） */
    public Map<String, String> nightingale() {
        LhProperties.Observability c = lhProperties.getObservability();
        if (c == null) {
            return Map.of("username", "", "password", "", "token", "");
        }
        String path = StrUtil.blankToDefault(c.getNightingaleVaultPath(), LhVaultPaths.NIGHTINGALE);
        return resolveUserPass(path, c.getNightingaleUser(), c.getNightingalePassword(), null);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String v : values) {
            if (StrUtil.isNotBlank(v)) {
                return v;
            }
        }
        return null;
    }
}
