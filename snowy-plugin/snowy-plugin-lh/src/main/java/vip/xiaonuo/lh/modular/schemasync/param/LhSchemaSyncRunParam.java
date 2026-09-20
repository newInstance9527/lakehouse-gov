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
package vip.xiaonuo.lh.modular.schemasync.param;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

/**
 * Schema Sync 触发参数（可选覆盖默认 metalake/catalog/schemas）
 *
 * @author lakehouse
 * @date 2026/3/18
 */
@Getter
@Setter
public class LhSchemaSyncRunParam {

    @Schema(description = "metalake，空则用配置")
    private String metalake;

    @Schema(description = "catalog，空则用配置")
    private String catalog;

    @Schema(description = "schema 列表逗号分隔；空则用配置或拉全部")
    private String schemas;

    @Schema(description = "强制全量（忽略 revision 跳过）")
    private Boolean force;

    @Schema(description = "OM DatabaseService 名，空则用配置 lake-service；按类型分类时如 lh_mysql")
    private String lakeService;

    @Schema(description = "OM Database 名，空则用配置 lake-database；登记投影建议用 ds catalog 名隔离")
    private String lakeDatabase;

    @Schema(description = "OM DatabaseService.serviceType，如 Mysql/Postgres；空则 CustomDatabase")
    private String omServiceType;

    @Schema(description = "OM DatabaseService 展示名")
    private String omServiceDisplayName;

    @Schema(description = "OM DatabaseService 描述")
    private String omServiceDescription;

    @Schema(description = "OM Database 展示名")
    private String omDatabaseDisplayName;

    @Schema(description = "OM Database 描述")
    private String omDatabaseDescription;
}
