package vip.xiaonuo.lh.modular.dataapi.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import vip.xiaonuo.lh.modular.dataapi.entity.DataapiApiBinding;
import vip.xiaonuo.lh.modular.dataapi.param.DataapiBindingParam;
import vip.xiaonuo.lh.modular.dataapi.param.DataapiGatewayProbeParam;
import vip.xiaonuo.lh.modular.dataapi.param.DataapiIdParam;
import vip.xiaonuo.lh.modular.dataapi.param.DataapiKeyRevealParam;
import vip.xiaonuo.lh.modular.dataapi.param.DataapiPageParam;
import vip.xiaonuo.lh.modular.dataapi.param.DataapiParseParam;
import vip.xiaonuo.lh.modular.dataapi.param.DataapiTagsParam;
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

    /**
     * 全量替换自定义标签（已发布亦可；仅改 tags_json，不触碰定义）。
     */
    Map<String, Object> updateTags(DataapiTagsParam param);

    Map<String, Object> build(DataapiBindingParam param);

    Map<String, Object> trial(DataapiTrialParam param);

    Map<String, Object> publish(DataapiIdParam param);

    /**
     * 取消发布：Gateway/SQLREST 下线，绑定回草稿；须重新申请发布后才能再上线。
     * 与 retire（永久下线）不同。
     */
    Map<String, Object> unpublish(DataapiIdParam param);

    Map<String, Object> retire(DataapiIdParam param);

    /** SQLREST 版本列表（含 commitId / version / description） */
    Map<String, Object> listVersions(String id);

    /** 回退到历史 commit 并 deploy；已发布时立即切流量，草稿仅更新指针 */
    Map<String, Object> rollback(DataapiIdParam param);

    void delete(DataapiIdParam param);

    Map<String, Object> routes();

    Map<String, Object> syncApisix(String ws);

    List<Map<String, Object>> keys(String ws);

    /** 订阅 Key 二次查看：从 Vault 读取 Bearer 密文（权限 + 审计） */
    Map<String, Object> revealKey(DataapiKeyRevealParam param);

    Map<String, Object> embedUrl();

    /** SQLREST 工作台聚合：外链 + 计数 + 趋势 + 接口列表 + 客户端 */
    Map<String, Object> workbench();

    /** 从 SQLREST assignment/list 同步/刷新绑定投影 */
    Map<String, Object> syncFromSqlrest(String ws);

    /** 登记已有 SQLREST 接口为门户绑定（不写 SQL） */
    Map<String, Object> register(DataapiBindingParam param);

    /** 入参解析：代理 SQLREST assignment/parse */
    Map<String, Object> parseParams(DataapiParseParam param);

    /** 命名策略 / 类型格式 / 补全片段聚合 */
    Map<String, Object> sqlrestOptions();

    /** Gateway 联调探针（edgeMode=gateway 时） */
    Map<String, Object> gatewayProbe(DataapiGatewayProbeParam param);

    /** 调用大盘：SQLREST overview counter / trend / topPath */
    Map<String, Object> callStats(Integer days);

    /**
     * OpenAPI 3.0 导出。
     *
     * @param ws 工作空间
     * @param id 可选：仅导出单个绑定；空则全部 published
     */
    Map<String, Object> openapi(String ws, String id);
}
