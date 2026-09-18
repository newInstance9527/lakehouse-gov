/*
 * Copyright [2022] [https://www.xiaonuo.vip]
 *
 * Snowy采用APACHE LICENSE 2.0开源协议，您在使用过程中，需要注意以下几点：
 *
 * 1.请不要删除和修改根目录下的LICENSE文件。
 * 2.请不要删除和修改Snowy源码头部的版权声明。
 * 3.本项目代码可免费商业使用，商业使用请保留源码和相关描述文件的项目出处，作者声明等。
 * 4.分发源码时候，请注明软件出处 https://www.xiaonuo.vip
 * 5.不可二次分发开源参与同类竞品，如有想法可联系团队xiaonuobase@qq.com商议合作。
 * 6.若您的项目无法满足以上几点，需要更多功能代码，获取Snowy商业授权许可，请在官网购买授权，地址为 https://www.xiaonuo.vip
 */
package vip.xiaonuo.lh.modular.datasource.param;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

/**
 * 数据源新增/提交参数（字段名对齐前端 RegisterSourceModal / DATA_SOURCES）
 *
 * @author lakehouse
 * @date 2026/3/18
 */
@Getter
@Setter
public class LhDatasourceAddParam {

    /** 前端可带 id（编辑时）；新建可空，由后端生成 */
    @Schema(description = "主键(编辑必填)")
    private String id;

    @NotBlank(message = "数据源名称不能为空")
    @Schema(description = "名称", requiredMode = Schema.RequiredMode.REQUIRED)
    private String name;

    /** 展示名 MySQL 或编码 mysql */
    @NotBlank(message = "数据源类型不能为空")
    @Schema(description = "类型(展示名或编码)", requiredMode = Schema.RequiredMode.REQUIRED)
    private String type;

    /** 用途文案：数据入湖 / 仅元数据采集(OM) / 出湖目标 */
    @Schema(description = "用途文案")
    private String purpose;

    @Schema(description = "负责人")
    private String owner;

    /** 前端 desc */
    @Schema(description = "描述")
    private String desc;

    @Schema(description = "主机")
    private String host;

    /** 前端多为字符串端口 */
    @Schema(description = "端口")
    private String port;

    @Schema(description = "库名/SID/namespace 等")
    private String database;

    @Schema(description = "用户名")
    private String user;

    @Schema(description = "密码")
    private String password;

    @Schema(description = "扩展参数")
    private String extra;

    /** 表/Topic/路径清单摘要 */
    @Schema(description = "表清单摘要")
    private String schema;

    /** 延迟/接入文案（前端 lag） */
    @Schema(description = "延迟文案")
    private String lag;

    /** 接入方式（前端 access） */
    @Schema(description = "接入方式")
    private String access;

    @Schema(description = "关联资产名")
    private String asset;

    @Schema(description = "工作空间")
    private String ws = "default";

    @Schema(description = "对外稳定编码")
    private String dsCode;

    @Schema(description = "分类")
    private String category;

    @Schema(description = "密级")
    private String level = "内部";

    /** 类型专属原始字段；也可把 host/user 等再放一份 */
    @Schema(description = "类型专属连接参数")
    private Map<String, Object> conn;

    /** 兼容：Kafka / S3 / HTTP 等扁平字段 */
    @Schema(description = "bootstrap")
    private String bootstrap;
    @Schema(description = "bootstrapServers")
    private String bootstrapServers;
    @Schema(description = "topics")
    private String topics;
    @Schema(description = "queues")
    private String queues;
    @Schema(description = "endpoint")
    private String endpoint;
    @Schema(description = "nameNode")
    private String nameNode;
    @Schema(description = "serviceUrl")
    private String serviceUrl;
    @Schema(description = "zkQuorum")
    private String zkQuorum;
    @Schema(description = "baseURL")
    private String baseURL;
    @Schema(description = "bucket")
    private String bucket;
    @Schema(description = "accessKey")
    private String accessKey;
    @Schema(description = "secretKey")
    private String secretKey;
    @Schema(description = "path")
    private String path;
    @Schema(description = "token")
    private String token;
    @Schema(description = "sid")
    private String sid;
    @Schema(description = "namespace")
    private String namespace;
    @Schema(description = "vhost")
    private String vhost;
    @Schema(description = "tenant")
    private String tenant;
    @Schema(description = "db")
    private String db;
    @Schema(description = "warehouse")
    private String warehouse;
    @Schema(description = "feNodes")
    private String feNodes;
    @Schema(description = "pollCycle")
    private String pollCycle;
    @Schema(description = "jdbcUrl")
    private String jdbcUrl;
}
