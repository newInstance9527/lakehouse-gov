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

/**
 * 平台级组件凭证路径约定（ig_secret_store.vault_path）
 * <p>业务数据源仍用 {@code datasource/{type}/{id}}；组件 SA/Bot 统一 {@code platform/{component}/...}</p>
 *
 * @author lakehouse
 * @date 2026/3/18
 */
public final class LhVaultPaths {

    private LhVaultPaths() {
    }

    /** Trino 查询服务账号 */
    public static final String TRINO = "platform/trino/query";

    /** DolphinScheduler API */
    public static final String DS = "platform/dolphinscheduler/api";

    /** Flink UI / REST（经 ui-auth） */
    public static final String FLINK = "platform/flink/ui";

    /** ClickHouse 平台写入 SA */
    public static final String CLICKHOUSE = "platform/clickhouse/default";

    /** APISIX Admin Key */
    public static final String APISIX = "platform/apisix/admin";

    /** 数据服务订阅 Key：platform/dataapi/keys/{keyMetaId} */
    public static String dataapiKey(String keyMetaId) {
        return "platform/dataapi/keys/" + keyMetaId;
    }

    /** OpenMetadata Bot / PAT */
    public static final String OPENMETADATA = "platform/openmetadata/bot";

    /** Marquez API（经 lh-ui-auth Basic） */
    public static final String MARQUEZ = "platform/marquez/api";

    /** Gravitino API（Basic Auth） */
    public static final String GRAVITINO = "platform/gravitino/api";

    /** Superset API */
    public static final String SUPERSET = "platform/superset/api";

    /** MinIO AK/SK */
    public static final String MINIO = "platform/minio/s3";

    /** AI 模型 API Key 前缀：{@code secret/lakehouse/ai/{modelId}} */
    public static final String AI_MODEL_PREFIX = "secret/lakehouse/ai/";

    public static String aiModel(String modelId) {
        return AI_MODEL_PREFIX + modelId;
    }

    /**
     * 合规删除主体 ID HMAC 密钥（平台级，全环境共用一把；轮换后旧 hash 不可验）。
     * 字段约定：{@code hmacKey}
     */
    public static final String COMPLIANCE_SUBJECT_HMAC = "platform/compliance/subject-hmac";

    /**
     * 合规删除专用 SA（CK mutation / 回流 sink JDBC·Redis）。
     * 字段约定：{@code username}/{@code password}；可选 {@code gravitinoUser}（审批发放写权限）。
     */
    public static final String COMPLIANCE_SA = "platform/compliance/sa_compliance";

    /**
     * 合规删除主体 ID 明文：{@code lh/compliance/subject/{reqNo}}
     * 字段约定：{@code subjectId} / {@code subjectType} / {@code reqNo}
     */
    public static String complianceSubject(String reqNo) {
        return "lh/compliance/subject/" + reqNo;
    }

    /**
     * crypto-shredding KEK（平台级信封加密主密钥）。
     * 字段约定：{@code kekMaterial}
     */
    public static final String COMPLIANCE_CRYPTO_KEK = "platform/compliance/crypto-kek";

    /**
     * 外部 DSR webhook 签名密钥。
     * 字段约定：{@code webhookSecret}
     */
    public static final String COMPLIANCE_INTAKE_WEBHOOK = "platform/compliance/intake-webhook";

    /**
     * 作业 SA 凭证：{@code lh/job-sa/{ws}/{saName}}
     * 字段约定：{@code password} / {@code generation} / {@code rotatedAt}；轮换时旧密进 {@code previousPassword}
     */
    public static String jobSa(String ws, String saName) {
        String w = (ws == null || ws.isBlank()) ? "default" : ws.trim();
        String n = saName == null ? "" : saName.trim().replace('/', '_');
        return "lh/job-sa/" + w + "/" + n;
    }
}
