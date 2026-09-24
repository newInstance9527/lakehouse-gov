package vip.xiaonuo.lh.modular.apply.param;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ApplyTicketCreateParam {

    @Schema(description = "类型：table_read|lake_export|resource_manage|compliance_delete|api_publish|script_publish|api_subscribe|metric|scan_elevate|quality_fix；兼容前端 export/perm/manage/compliance/publish/api/elevated/quality")
    private String ticketType;

    @NotBlank
    @Schema(description = "标题")
    private String title;

    @Schema(description = "事由")
    private String reason;

    @Schema(description = "gov_asset.id（table_read MUST；lake_export 可选）")
    private String assetId;

    @Schema(description = "权限：table_read 默认 SELECT；resource_manage 为 EDIT|DELETE|MANAGE（默认 MANAGE）")
    private String privilege;

    @Schema(description = "时效文案，如 30天/长期")
    private String expireLabel;

    @Schema(description = "行过滤条件，原样交给 Gravitino（table_read 可选）")
    private String rowFilter;

    @Schema(description = "申请列（可选）")
    private String columns;

    @Schema(description = "出湖源表名/编码（lake_export MUST）")
    private String exportTable;

    @Schema(description = "出湖目标（lake_export MUST）")
    private String exportTarget;

    @Schema(description = "资源类型：asset|datasource|etl（resource_manage）")
    private String resourceType;

    @Schema(description = "资源 ID（resource_manage；asset 时亦可填 assetId；script_publish 填 cp_release.id）")
    private String resourceId;

    @Schema(description = "脚本 id（script_publish）")
    private String scriptId;

    @Schema(description = "发布目标环境（script_publish：dev/stg）")
    private String publishEnv;

    @Schema(description = "回滚预案（script_publish）")
    private String rollbackPlan;

    @Schema(description = "合规删除请求号 gov_del_request.req_no（compliance_delete MUST）")
    private String reqNo;

    @Schema(description = "掩码主体标识（compliance_delete；禁止传明文）")
    private String subjectMasked;

    @Schema(description = "计划载体数（compliance_delete 展示用）")
    private Integer targetCount;

    @Schema(description = "数据服务绑定 id（api_publish / api_subscribe）")
    private String apiBindingId;

    @Schema(description = "对外路径（api_publish / api_subscribe）")
    private String publicPath;

    @Schema(description = "HTTP 方法（api_publish / api_subscribe）")
    private String method;

    @Schema(description = "调用应用名（api_subscribe MUST）")
    private String consumerName;

    @Schema(description = "订阅方 QPS（api_subscribe）")
    private Integer qps;

    @Schema(description = "指标编码（metric：create/change/query MUST）")
    private String metricCode;

    @Schema(description = "指标申请子类型：create|change|query（metric；默认 query）")
    private String metricKind;

    @Schema(description = "口径变更说明（metric + change）")
    private String caliberDiff;

    @Schema(description = "幂等键；亦可放请求头 Idempotency-Key / X-Idempotency-Key")
    private String idempotencyKey;
}
