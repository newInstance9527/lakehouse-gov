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
import lombok.Getter;
import lombok.Setter;

/**
 * 数据源分页查询参数
 *
 * @author lakehouse
 * @date 2026/3/18
 */
@Getter
@Setter
public class LhDatasourcePageParam {

    /** 名称模糊 */
    @Schema(description = "名称")
    private String name;

    /** 类型 */
    @Schema(description = "类型")
    private String type;

    /** 分类 */
    @Schema(description = "分类")
    private String category;

    /** 状态 */
    @Schema(description = "状态")
    private String status;

    /** 用途关键字 */
    @Schema(description = "用途")
    private String purpose;

    /** 综合关键词（对齐前端 filters.kw） */
    @Schema(description = "关键词")
    private String keyword;

    /** 前端常用 kw，与 keyword 等价 */
    @Schema(description = "关键词(kw)")
    private String kw;

    /** 分类（前端 filters.cat） */
    @Schema(description = "分类(cat)")
    private String cat;

    /** 是否仅返回 DAG 可用源（1=是） */
    @Schema(description = "DAG可用")
    private String usableInDag;

    /** 排序字段 */
    @Schema(description = "排序字段")
    private String sortField;

    /** 排序方式 */
    @Schema(description = "排序方式")
    private String sortOrder;
}
