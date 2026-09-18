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
package vip.xiaonuo.lh.modular.datasource.result;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

/**
 * 表清单行（对齐前端 schemaList 表元数据）
 *
 * @author lakehouse
 * @date 2026/3/18
 */
@Getter
@Setter
@Schema(description = "表清单行")
public class LhDsTableVo {

    @Schema(description = "行主键")
    private String id;

    @Schema(description = "数据源ID")
    private String dsId;

    @Schema(description = "表名")
    private String name;

    @Schema(description = "中文名")
    private String cnName;

    @Schema(description = "注释")
    private String comment;

    @Schema(description = "编码")
    private String encoding;

    @Schema(description = "引擎")
    private String engine;

    @Schema(description = "行数")
    private Long rowCount;

    @Schema(description = "状态")
    private String status;
}
