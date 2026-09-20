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
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 代理写 OpenMetadata 人读元数据
 *
 * @author lakehouse
 * @date 2026/3/18
 */
@Getter
@Setter
public class GovAssetMetaParam {

    @Schema(description = "资产ID或assetCode")
    @NotBlank(message = "id不能为空")
    private String id;

    @Schema(description = "描述；null=不改，空串=清空")
    private String description;

    @Schema(description = "展示名；null=不改")
    private String displayName;

    @Schema(description = "标签 FQN 列表；null=不改，空列表=清空。如 Tier.Gold")
    private List<String> tags;

    @Schema(description = "是否同步回写门户 description 草稿")
    private Boolean syncPortalDraft;

    @Schema(description = "是否按 OM 金标回写门户 is_gold；默认 true")
    private Boolean syncGold;

    @Schema(description = "便捷：在 tags 基础上增删黄金标签；null=不改 tags 中的金标")
    private Boolean gold;
}
