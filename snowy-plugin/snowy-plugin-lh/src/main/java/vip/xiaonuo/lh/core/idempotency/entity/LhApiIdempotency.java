package vip.xiaonuo.lh.core.idempotency.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
@TableName("lh_api_idempotency")
public class LhApiIdempotency {
    @TableId
    private String id;
    private String scope;
    private String idemKey;
    private String subjectId;
    private String reqHash;
    private String status;
    private String resourceType;
    private String resourceId;
    private String responseJson;
    private String errorMsg;
    private String deleteFlag;
    private Date createTime;
    private Date updateTime;
    private Date expireAt;
}
