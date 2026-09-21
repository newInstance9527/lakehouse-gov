package vip.xiaonuo.lh.modular.query.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/**
 * Grav 登记 catalog → Trino 查询 catalog（即席查询面）
 */
@Getter
@Setter
@TableName("cb_trino_catalog_map")
public class CbTrinoCatalogMap {

    @TableId
    private String id;
    private Integer revision;
    private String status;
    private String ws;
    private String remark;
    private String gravCatalog;
    private String trinoCatalog;
    private Integer enabled;
    private String kind;
    private String deleteFlag;
    private Date createTime;
    private String createUser;
    private Date updateTime;
    private String updateUser;
}
