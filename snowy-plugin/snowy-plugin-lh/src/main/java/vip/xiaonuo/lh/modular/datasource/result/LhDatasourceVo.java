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
package vip.xiaonuo.lh.modular.datasource.result;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

/**
 * 数据源视图（对齐前端 DATA_SOURCES / 卡片 / 抽屉字段）
 *
 * @author lakehouse
 * @date 2026/3/18
 */
@Getter
@Setter
@Schema(description = "数据源(前端视图)")
public class LhDatasourceVo {

    @Schema(description = "主键")
    private String id;

    @Schema(description = "类型展示名")
    private String type;

    @Schema(description = "类型编码")
    private String typeCode;

    @Schema(description = "分类编码")
    private String category;

    @Schema(description = "图标")
    private String icon;

    @Schema(description = "背景色")
    private String bg;

    @Schema(description = "主题色")
    private String color;

    @Schema(description = "名称")
    private String name;

    @Schema(description = "主机")
    private String host;

    @Schema(description = "端口")
    private String port;

    @Schema(description = "库名")
    private String database;

    @Schema(description = "用户")
    private String user;

    @Schema(description = "密码脱敏")
    private String password;

    @Schema(description = "扩展参数")
    private String extra;

    @Schema(description = "表清单摘要")
    private String schema;

    @Schema(description = "延迟/接入文案")
    private String lag;

    @Schema(description = "接入方式")
    private String access;

    @Schema(description = "状态")
    private String status;

    @Schema(description = "健康分")
    private Integer health;

    @Schema(description = "关联资产")
    private String asset;

    @Schema(description = "负责人")
    private String owner;

    @Schema(description = "版本")
    private String ver;

    @Schema(description = "创建日期 yyyy-MM-dd")
    private String created;

    @Schema(description = "描述")
    private String desc;

    @Schema(description = "用途文案")
    private String purpose;

    @Schema(description = "用途JSON数组原文")
    private String purposes;

    @Schema(description = "乐观锁")
    private Integer revision;

    @Schema(description = "脱敏连接原文Map")
    private Map<String, Object> conn;
}
