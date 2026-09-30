package vip.xiaonuo.lh.modular.metric.result;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 指标列表 / 详情 VO（对齐前端 MetricsView 字段）
 */
@Getter
@Setter
@Schema(description = "指标VO")
public class GovMetricVo {

    private String id;
    /** 对外编码，前端 id */
    private String metricCode;
    private String name;
    private String kind;
    private String type;
    /** 归属协作空间 */
    private String ws;
    private String domain;
    private String domainCode;
    private String status;
    private String statusLabel;
    private String caliber;
    private String pendingCaliber;
    private String bind;
    private String formula;
    private String atomRef;
    private String agg;
    private String table;
    private String field;
    private List<String> qualifierKeys;
    private String qualifier;
    private List<String> dimKeys;
    private String dim;
    private String time;
    private String unit;
    private String owner;
    private String ver;
    private String currentVerId;
    private String omFqn;
    private String gravAssetId;
    private Integer revision;
    private Date createTime;
    private Date updateTime;
    private List<String> depCodes;
    private List<Map<String, Object>> history;
    private Map<String, Object> formulaAst;
    private String compiledSql;
    private String dialect;
}
