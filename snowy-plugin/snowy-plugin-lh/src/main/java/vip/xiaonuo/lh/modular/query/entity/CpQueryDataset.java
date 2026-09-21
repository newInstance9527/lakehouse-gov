package vip.xiaonuo.lh.modular.query.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
@TableName("cp_query_dataset")
@Schema(description = "即席结果数据集")
public class CpQueryDataset {

    @TableId
    private String id;
    private String dsCode;
    private String name;
    private String ws;
    private String userId;
    private String userName;
    private String queryId;
    private String sqlText;
    private String sqlHash;
    private String columnsJson;
    private String sampleJson;
    private Integer rowCount;
    private Long scanBytes;
    private String status;
    private String deleteFlag;
    private Date createTime;
    private String createUser;
    private Date updateTime;
    private String updateUser;
}
