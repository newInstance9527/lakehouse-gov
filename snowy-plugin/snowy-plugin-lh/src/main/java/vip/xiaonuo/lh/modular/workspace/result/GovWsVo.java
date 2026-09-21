package vip.xiaonuo.lh.modular.workspace.result;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Map;

@Getter
@Setter
@Schema(description = "工作空间 VO")
public class GovWsVo {

    private String id;
    private String wsCode;
    private String name;
    private String icon;
    private String domainCode;
    private String costCenter;
    private String trinoRg;
    private String preferredSchemas;
    private String owners;
    private String detail;
    private String status;
    private String remark;
    private List<Map<String, String>> tags;
    private Date createTime;

    private Boolean current;
    private Long memberCount;
    private Long assetCount;
    private String myRole;

    private BigDecimal storageUsedTb;
    private BigDecimal storageQuotaTb;
    private Integer cuUsed;
    private Integer cuQuota;
    private String quotaStatus;

    /** 共享 Catalog 提示（定型：非隔离） */
    private String sharedCatalog;
}
