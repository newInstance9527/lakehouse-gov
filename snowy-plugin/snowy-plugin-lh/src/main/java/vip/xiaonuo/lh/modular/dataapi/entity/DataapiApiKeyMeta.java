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
@TableName("dataapi_api_key_meta")
@Schema(description = "数据服务 API Key 元数据")
public class DataapiApiKeyMeta extends CommonEntity {

    @TableId
    private String id;
    private Integer revision;
    private String status;
    private String ws;
    private String remark;
    private String bindingId;
    private String consumerName;
    private String applicant;
    private String keyHint;
    private String vaultPath;
    private String ticketId;
    private Date expireAt;
    /** 订阅方 QPS 配额 */
    private Integer qpsLimit;
    /** 对外 AppKey（非密文） */
    private String appKey;
}
