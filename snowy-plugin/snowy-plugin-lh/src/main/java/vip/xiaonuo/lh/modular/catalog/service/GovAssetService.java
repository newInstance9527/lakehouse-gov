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
package vip.xiaonuo.lh.modular.catalog.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import vip.xiaonuo.lh.modular.catalog.entity.GovAsset;
import vip.xiaonuo.lh.modular.catalog.param.GovAssetAddParam;
import vip.xiaonuo.lh.modular.catalog.param.GovAssetEditParam;
import vip.xiaonuo.lh.modular.catalog.param.GovAssetIdParam;
import vip.xiaonuo.lh.modular.catalog.param.GovAssetMetaParam;
import vip.xiaonuo.lh.modular.catalog.param.GovAssetPageParam;
import vip.xiaonuo.lh.modular.catalog.param.GovAssetPreviewParam;
import vip.xiaonuo.lh.modular.catalog.result.GovAssetSourceVo;
import vip.xiaonuo.lh.modular.catalog.result.GovAssetVo;

import java.util.List;
import java.util.Map;

/**
 * 资产目录 Service
 *
 * @author lakehouse
 * @date 2026/3/18
 */
public interface GovAssetService extends IService<GovAsset> {

    /**
     * 分页搜索
     */
    Page<GovAssetVo> page(GovAssetPageParam param);

    /**
     * 详情（含关联源；OM/质量/血缘占位）
     */
    GovAssetVo detail(GovAssetIdParam param);

    /**
     * 注册资产 + 主源绑定
     */
    GovAssetVo add(GovAssetAddParam param);

    /**
     * 更新门户字段
     */
    GovAssetVo edit(GovAssetEditParam param);

    /**
     * 关联数据源列表
     */
    List<GovAssetSourceVo> sources(GovAssetIdParam param);

    /**
     * 触发对齐（门户清单→OM soft-fail；Grav/质量待模块）
     */
    Map<String, Object> refresh(GovAssetIdParam param);

    /**
     * 列结构：Grav 优先，失败回退 OM
     */
    Map<String, Object> schema(GovAssetIdParam param);

    /**
     * 代理写 OM description / displayName / tags（人读 SoT）
     */
    Map<String, Object> updateMeta(GovAssetMetaParam param);

    /**
     * 按源类型 Reader 预览（超管/已授权；soft-fail；湖表可选 Trino 行样例）
     */
    Map<String, Object> preview(GovAssetPreviewParam param);

    /**
     * 分层 / 域字典（前端筛选用）
     */
    Map<String, Object> metaOptions();
}
