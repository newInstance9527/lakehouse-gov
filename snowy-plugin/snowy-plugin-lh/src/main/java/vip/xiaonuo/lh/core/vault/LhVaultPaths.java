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

    /** OpenMetadata Bot / PAT */
    public static final String OPENMETADATA = "platform/openmetadata/bot";

    /** Gravitino API（Basic Auth） */
    public static final String GRAVITINO = "platform/gravitino/api";

    /** Superset API */
    public static final String SUPERSET = "platform/superset/api";

    /** MinIO AK/SK */
    public static final String MINIO = "platform/minio/s3";
}
