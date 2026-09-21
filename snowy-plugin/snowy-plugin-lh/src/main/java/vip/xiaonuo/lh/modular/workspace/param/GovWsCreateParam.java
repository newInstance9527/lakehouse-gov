package vip.xiaonuo.lh.modular.workspace.param;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Setter
public class GovWsCreateParam {

    @NotBlank
    @Schema(description = "展示名")
    private String name;

    @Schema(description = "空间编码；空则自动生成")
    private String wsCode;

    @Schema(description = "业务域")
    private String domainCode;

    @Schema(description = "成本中心")
    private String costCenter;

    @Schema(description = "Trino 资源组")
    private String trinoRg;

    @Schema(description = "图标")
    private String icon;

    @Schema(description = "描述")
    private String detail;

    @Schema(description = "常用 schema 提示")
    private String preferredSchemas;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "存储配额 TB")
    private BigDecimal storageQuotaTb;

    @Schema(description = "CU 配额")
    private Integer cuQuota;

    @Schema(description = "初始成员（可选）")
    private List<GovWsMemberItemParam> members;
}
