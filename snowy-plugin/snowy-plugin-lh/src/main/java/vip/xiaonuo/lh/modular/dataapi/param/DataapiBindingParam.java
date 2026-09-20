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
    /** 门户数据源 id（构建/试跑前会投影到 SQLREST） */
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
    private List<Map<String, Object>> params;
    private List<Map<String, Object>> responses;
    private String responseFormat;
    private String responseShape;
    private String description;
}
