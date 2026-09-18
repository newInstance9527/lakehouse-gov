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
package vip.xiaonuo.lh.modular.datasource.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import vip.xiaonuo.lh.modular.datasource.entity.LhConsumerBinding;
import vip.xiaonuo.lh.modular.datasource.entity.LhDatasource;
import vip.xiaonuo.lh.modular.datasource.param.*;
import vip.xiaonuo.lh.modular.datasource.result.LhDatasourceVo;
import vip.xiaonuo.lh.modular.datasource.result.LhDsTableVo;

import java.util.List;
import java.util.Map;

/**
 * 数据源中心 Service（对外 VO 对齐前端字段）
 *
 * @author lakehouse
 * @date 2026/3/18
 */
public interface LhDatasourceService extends IService<LhDatasource> {

    Page<LhDatasourceVo> page(LhDatasourcePageParam param);

    /** 新增，返回前端视图（含 id） */
    LhDatasourceVo add(LhDatasourceAddParam param);

    LhDatasourceVo edit(LhDatasourceEditParam param);

    void delete(List<LhDatasourceIdParam> ids);

    LhDatasourceVo detail(LhDatasourceIdParam param);

    Map<String, Object> test(LhDatasourceTestParam param);

    Map<String, Object> previewSchema(LhDatasourceIdParam param);

    void updatePurposes(LhDatasourcePurposesParam param);

    List<LhConsumerBinding> bindings(LhDatasourceIdParam param);

    void rotateCred(LhDatasourceIdParam param);

    List<LhDatasourceVo> listForDag();

    List<LhDatasourceVo> supersetProjection();

    Map<String, Object> resolveDs(String dsId, String mode);

    void batchImport(LhDatasourceBatchImportParam param);

    Map<String, Object> toggleStatus(LhDatasourceToggleParam param);

    Map<String, Object> kpi();

    List<Map<String, Object>> typeOptions();

    Map<String, Object> formSchema(String type);

    Page<LhDsTableVo> tablePage(LhDsTablePageParam param);

    LhDsTableVo tableAdd(LhDsTableAddParam param);

    LhDsTableVo tableEdit(LhDsTableEditParam param);

    void tableDelete(List<LhDsTableIdParam> ids);

    Map<String, Object> tableSync(LhDatasourceIdParam param);

    Map<String, Object> batchSyncTables(List<LhDatasourceIdParam> ids);
}
