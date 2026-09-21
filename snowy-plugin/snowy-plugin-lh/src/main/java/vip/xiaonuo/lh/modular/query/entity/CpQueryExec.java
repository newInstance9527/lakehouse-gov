package vip.xiaonuo.lh.modular.query.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/**
 * 即席查询执行审计
 *
 * @author lakehouse
 * @date 2026/9/21
 */
@Getter
@Setter
@TableName("cp_query_exec")
@Schema(description = "即席查询执行")
public class CpQueryExec {

    @TableId
    private String id;
    private String queryId;
    private String ws;
    private String userId;
    private String userName;
    private String sqlText;
    private String sqlHash;
    private String sqlSummary;
    private String status;
    private String statusLabel;
    private String engine;
    private String source;
    private String metricCode;
    private String metricVer;
    private String catalogName;
    private String schemaName;
    private Integer rowCount;
    private Long scanBytes;
    private Long durMs;
    private String maskCols;
    private String errorMsg;
    private String trinoQueryId;
    private String deleteFlag;
    private Date createTime;
    private String createUser;
    private Date updateTime;
    private String updateUser;
}
