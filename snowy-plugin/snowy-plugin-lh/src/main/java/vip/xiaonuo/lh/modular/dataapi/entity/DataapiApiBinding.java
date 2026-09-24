package vip.xiaonuo.lh.modular.dataapi.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import vip.xiaonuo.common.pojo.CommonEntity;

import java.util.Date;

@Getter
@Setter
@TableName("dataapi_api_binding")
@Schema(description = "数据服务 API 绑定")
public class DataapiApiBinding extends CommonEntity {

    @TableId
    private String id;
    private Integer revision;
    private String status;
    private String ws;
    private String remark;
    private String name;
    private String publicPath;
    private String method;
    private String sqlrestApiId;
    private String sqlrestCommitId;
    private Integer sqlrestVersion;
    private String sourceKind;
    private String sourceRef;
    /** 门户 ig_datasource.id */
    private String portalDsId;
    /** SQLREST datasource id（投影后） */
    private String sqlrestDatasourceId;
    private String state;
    private String apisixRouteId;
    /** 外部名绑定键 sqlrest_api:ws__code；全局唯一 */
    private String extId;
    private String authMode;
    private Integer qpsLimit;
    private Integer burstLimit;
    private String domainCode;
    private String ownerUser;
    private String publishEnv;
    private String contentType;
    private String paramJson;
    private String responseJson;
    private Date lastPublishAt;
    private String lastError;
    /** 发布审批单号 API-xxx */
    private String publishTicketNo;
}
