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
package vip.xiaonuo.lh.modular.datasource.enums;

import lombok.Getter;
import vip.xiaonuo.common.exception.CommonException;

import java.util.Arrays;

/**
 * 数据源分类枚举（对齐 OpenMetadata 连接器分层 / 门户拓扑筛选）
 *
 * @author lakehouse
 * @date 2026/3/18
 */
@Getter
public enum LhDatasourceCategoryEnum {

    /** 关系型数据库 */
    RDB("rdb", "关系型数据库"),
    /** 数仓 / 湖仓 */
    DW("dw", "数仓/湖仓"),
    /** 消息队列 / 流 */
    MQ("mq", "消息队列/流"),
    /** NoSQL */
    NOSQL("nosql", "NoSQL"),
    /** 搜索引擎 */
    SEARCH("search", "搜索引擎"),
    /** 文件 / 对象存储 */
    STORAGE("storage", "文件/对象存储"),
    /** API / OpenAPI */
    API("api", "API"),
    /** BI / Dashboard */
    DASHBOARD("dashboard", "BI/Dashboard"),
    /** Pipeline / 编排 */
    PIPELINE("pipeline", "Pipeline/编排"),
    /** 出湖 / 下游 */
    OUTBOUND("outbound", "出湖/下游");

    private final String value;
    private final String label;

    LhDatasourceCategoryEnum(String value, String label) {
        this.value = value;
        this.label = label;
    }

    /**
     * 校验分类是否合法
     *
     * @param category 分类编码
     */
    public static void validate(String category) {
        boolean ok = Arrays.stream(values()).anyMatch(e -> e.value.equals(category));
        if (!ok) {
            throw new CommonException("不支持的数据源分类: {}", category);
        }
    }
}
