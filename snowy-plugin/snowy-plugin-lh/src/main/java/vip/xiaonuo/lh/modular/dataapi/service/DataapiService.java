package vip.xiaonuo.lh.modular.dataapi.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import vip.xiaonuo.lh.modular.dataapi.entity.DataapiApiBinding;
import vip.xiaonuo.lh.modular.dataapi.param.DataapiBindingParam;
import vip.xiaonuo.lh.modular.dataapi.param.DataapiIdParam;
import vip.xiaonuo.lh.modular.dataapi.param.DataapiPageParam;
import vip.xiaonuo.lh.modular.dataapi.param.DataapiTrialParam;

import java.util.List;
import java.util.Map;

public interface DataapiService {

    Map<String, Object> overview(String ws);

    Page<DataapiApiBinding> page(DataapiPageParam param);

    List<Map<String, Object>> listApis(DataapiPageParam param);

    Map<String, Object> detail(String id, boolean withSqlrest);

    DataapiApiBinding add(DataapiBindingParam param);

    DataapiApiBinding edit(DataapiBindingParam param);

    Map<String, Object> build(DataapiBindingParam param);

    Map<String, Object> trial(DataapiTrialParam param);

    Map<String, Object> publish(DataapiIdParam param);

    Map<String, Object> retire(DataapiIdParam param);

    void delete(DataapiIdParam param);

    Map<String, Object> routes();

    Map<String, Object> syncApisix(String ws);

    List<Map<String, Object>> keys(String ws);

    Map<String, Object> embedUrl();

    /** SQLREST 工作台聚合：外链 + 计数 + 趋势 + 接口列表 + 客户端 */
    Map<String, Object> workbench();

    /** 从 SQLREST assignment/list 同步/刷新绑定投影 */
    Map<String, Object> syncFromSqlrest(String ws);

    /** 登记已有 SQLREST 接口为门户绑定（不写 SQL） */
    Map<String, Object> register(DataapiBindingParam param);
}
