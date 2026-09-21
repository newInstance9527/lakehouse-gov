package vip.xiaonuo.lh.modular.dataapi.param;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

@Getter
@Setter
public class DataapiBindingParam {

    private String id;

    @NotBlank(message = "名称不能为空")
    private String name;

    @NotBlank(message = "对外路径不能为空")
    private String publicPath;

    private String method = "GET";
    private String sqlrestApiId;
    private String sourceKind = "sql";
    private String sourceRef;
    /** 门户数据源 id（须已登记时投影；构建页不再临投影） */
    private String portalDsId;
    private String dsId;
    private String sqlrestDatasourceId;
    private String authMode = "Token";
    private Integer qpsLimit = 100;
    private Integer burstLimit = 200;
    private String domainCode;
    private String ownerUser;
    private String publishEnv = "stg";
    private String contentType;
    private String ws;
    private String remark;
    private String sql;
    /** 多段 SQL/脚本；优先于单段 sql */
    private List<String> contextList;
    private List<Map<String, Object>> params;
    private List<Map<String, Object>> responses;
    private List<Map<String, Object>> outputs;
    private List<Map<String, Object>> formatMap;
    private String responseFormat;
    private String responseShape;
    private String namingStrategy;
    private String description;
    /** SQL 或 GROOVY；写入 SQLREST assignment.engine */
    private String engine = "SQL";
    private Boolean open;
    private Boolean alarm;
    private Boolean flowStatus;
    private Integer flowGrade;
    private Integer flowCount;
    private String cacheKeyType;
    private String cacheKeyExpr;
    private Integer cacheExpireSeconds;
    private Long moduleId;
    private Long groupId;
}
