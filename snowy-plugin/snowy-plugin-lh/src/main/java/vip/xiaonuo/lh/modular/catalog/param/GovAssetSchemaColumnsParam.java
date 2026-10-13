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
package vip.xiaonuo.lh.modular.catalog.param;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 写 OM 列级人读元数据（中文名/描述）；禁止结构字段。
 *
 * @author lakehouse
 * @date 2026/10/13
 */
@Getter
@Setter
public class GovAssetSchemaColumnsParam {

    @Schema(description = "资产ID")
    @NotBlank(message = "id不能为空")
    private String id;

    @Schema(description = "列人读变更；仅 cnName/description")
    @NotEmpty(message = "columns不能为空")
    private List<ColumnMeta> columns;

    @Getter
    @Setter
    public static class ColumnMeta {
        @Schema(description = "英文列名（结构键，不可改）")
        @NotBlank(message = "enName不能为空")
        private String enName;

        @Schema(description = "中文名 / OM displayName；null=不改")
        private String cnName;

        @Schema(description = "描述；null=不改，空串=清空")
        private String description;
    }
}
