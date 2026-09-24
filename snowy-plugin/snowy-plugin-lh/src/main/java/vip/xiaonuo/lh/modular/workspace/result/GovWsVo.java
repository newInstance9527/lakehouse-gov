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
    private String wsKind;
    private String name;
    private String icon;
    private String domainCode;
    private String costCenter;
    private String trinoRg;
    private String preferredSchemas;
    private String techNs;
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
    /**
     * Git 远程展示（已去 userinfo，永不含 token）。
     * 原始带凭证 URL 仅存库供服务端 push，不经本字段下发。
     */
    private String gitRemoteUrlDisplay;
    /** true=手填自定义公网 remote（同步保留）；false/null=平台托管或未绑定 */
    private Boolean gitRemoteCustom;
    /**
     * @deprecated 兼容旧前端；恒为脱敏展示或 null，勿当 raw。请用 {@link #gitRemoteUrlDisplay}。
     */
    @Deprecated
    private String gitRemoteUrl;
}
