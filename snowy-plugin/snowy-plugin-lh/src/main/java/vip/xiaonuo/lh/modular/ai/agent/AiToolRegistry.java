package vip.xiaonuo.lh.modular.ai.agent;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.core.ai.LhChatTools;
import vip.xiaonuo.lh.modular.ai.support.AiAssetReadAccess;
import vip.xiaonuo.lh.modular.ai.support.AiDocsSearch;
import vip.xiaonuo.lh.modular.aimodel.service.GovAiModelService;
import vip.xiaonuo.lh.modular.apply.entity.ApplyTicket;
import vip.xiaonuo.lh.modular.apply.param.ApplyTicketPageParam;
import vip.xiaonuo.lh.modular.apply.service.ApplyTicketService;
import vip.xiaonuo.lh.modular.catalog.entity.GovAsset;
import vip.xiaonuo.lh.modular.catalog.mapper.GovAssetMapper;
import vip.xiaonuo.lh.modular.catalog.param.GovAssetIdParam;
import vip.xiaonuo.lh.modular.catalog.param.GovAssetPreviewParam;
import vip.xiaonuo.lh.modular.catalog.result.GovAssetVo;
import vip.xiaonuo.lh.modular.catalog.service.GovAssetService;
import vip.xiaonuo.lh.modular.compliance.service.GovDelService;
import vip.xiaonuo.lh.modular.compute.service.CpDevelopService;
import vip.xiaonuo.lh.modular.contract.service.ContractService;
import vip.xiaonuo.lh.modular.dataapi.entity.DataapiApiBinding;
import vip.xiaonuo.lh.modular.dataapi.param.DataapiPageParam;
import vip.xiaonuo.lh.modular.dataapi.service.DataapiService;
import vip.xiaonuo.lh.modular.datasource.param.LhDatasourceIdParam;
import vip.xiaonuo.lh.modular.datasource.param.LhDatasourcePageParam;
import vip.xiaonuo.lh.modular.datasource.param.LhDatasourceTestParam;
import vip.xiaonuo.lh.modular.datasource.param.LhDsTablePageParam;
import vip.xiaonuo.lh.modular.datasource.result.LhDatasourceVo;
import vip.xiaonuo.lh.modular.datasource.result.LhDsTableVo;
import vip.xiaonuo.lh.modular.datasource.service.LhDatasourceService;
import vip.xiaonuo.lh.modular.etl.param.IgEtlPageParam;
import vip.xiaonuo.lh.modular.etl.service.IgEtlService;
import vip.xiaonuo.lh.modular.export.service.ExportBoardService;
import vip.xiaonuo.lh.modular.knowledge.param.GovKbSearchParam;
import vip.xiaonuo.lh.modular.knowledge.service.GovKbService;
import vip.xiaonuo.lh.modular.lifecycle.service.GovLcService;
import vip.xiaonuo.lh.modular.lineage.service.GovLineageService;
import vip.xiaonuo.lh.modular.metric.param.GovMetricPageParam;
import vip.xiaonuo.lh.modular.metric.result.GovMetricVo;
import vip.xiaonuo.lh.modular.metric.service.GovMetricService;
import vip.xiaonuo.lh.modular.moduleops.service.LhModuleOpsService;
import vip.xiaonuo.lh.modular.quality.param.GovDqPageParam;
import vip.xiaonuo.lh.modular.quality.result.GovDqRuleVo;
import vip.xiaonuo.lh.modular.quality.service.GovDqService;
import vip.xiaonuo.lh.modular.query.param.CpQueryHistoryParam;
import vip.xiaonuo.lh.modular.query.service.CpQueryService;
import vip.xiaonuo.lh.modular.sec.enums.LhOpsPrivilegeEnum;
import vip.xiaonuo.lh.modular.sec.enums.LhOpsResourceTypeEnum;
import vip.xiaonuo.lh.modular.sec.service.GravTableAccessService;
import vip.xiaonuo.lh.modular.sec.service.SecAuthGrantService;
import vip.xiaonuo.lh.modular.sec.service.SecBoardService;
import vip.xiaonuo.lh.modular.standard.param.GovStdPageParam;
import vip.xiaonuo.lh.modular.standard.result.GovStdCodeVo;
import vip.xiaonuo.lh.modular.standard.result.GovStdFieldVo;
import vip.xiaonuo.lh.modular.standard.result.GovStdNamingVo;
import vip.xiaonuo.lh.modular.standard.service.GovStdService;
import vip.xiaonuo.lh.modular.workspace.result.GovWsMemberVo;
import vip.xiaonuo.lh.modular.workspace.result.GovWsVo;
import vip.xiaonuo.lh.modular.workspace.service.GovWsService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 只读探索智能体工具白名单（服务端执行 + ACL）。
 */
@Slf4j
@Component
public class AiToolRegistry {

    private static final String NOT_DELETE = "NOT_DELETE";
    private static final String WS_DEFAULT = "default";
    private static final int MAX_TEXT = 4000;
    private static final int MAX_ITEMS = 20;

    @Resource
    private GovAssetMapper assetMapper;
    @Resource
    private AiAssetReadAccess aiAssetReadAccess;
    @Resource
    private SecAuthGrantService secAuthGrantService;
    @Resource
    private GovAssetService govAssetService;
    @Resource
    private GovKbService govKbService;
    @Resource
    private GovMetricService govMetricService;
    @Resource
    private GovDqService govDqService;
    @Resource
    private GovLineageService govLineageService;
    @Resource
    private ApplyTicketService applyTicketService;
    @Resource
    private LhDatasourceService lhDatasourceService;
    @Resource
    private GovWsService govWsService;
    @Resource
    private AiDocsSearch aiDocsSearch;
    @Resource
    private LhModuleOpsService lhModuleOpsService;
    @Resource
    private IgEtlService igEtlService;
    @Resource
    private CpQueryService cpQueryService;
    @Resource
    private DataapiService dataapiService;
    @Resource
    private GovStdService govStdService;
    @Resource
    private GravTableAccessService gravTableAccessService;
    @Resource
    private SecBoardService secBoardService;
    @Resource
    private CpDevelopService cpDevelopService;
    @Resource
    private GovLcService govLcService;
    @Resource
    private GovDelService govDelService;
    @Resource
    private ExportBoardService exportBoardService;
    @Resource
    private ContractService contractService;
    @Resource
    private GovAiModelService govAiModelService;

    /** OpenAI tools JSON schema 列表 */
    public List<Map<String, Object>> openaiToolDefinitions() {
        List<Map<String, Object>> tools = new ArrayList<>();
        tools.add(fn("catalog_stats",
                "同时返回：①当前空间目录登记数 wsAssetCount（与资产目录列表同口径）；"
                        + "②我可查数 myReadableCount（owner∪门户SELECT投影∪Grav实测，与预览/grants/check 同口径）；"
                        + "③目录样例 catalogVisibleSamples（含 canRead）。"
                        + "问「有几张表/目录里有什么」时优先调用本工具。",
                objectSchema(Map.of("ws", propStr("工作空间编码，可空则用会话 ws")), List.of())));
        tools.add(fn("list_ws_catalog",
                "列出当前空间在资产目录中可见的登记资产（可见平面，与目录页一致）；每条标注 canRead。"
                        + "用于回答目录有哪些表；canRead=false 表示需申请才能查数。",
                objectSchema(Map.of(
                        "ws", propStr("工作空间"),
                        "topN", propInt("最多返回条数，默认 12，上限 20")), List.of())));
        tools.add(fn("list_my_assets",
                "列出当前用户可查资产（owner / 门户 SELECT 投影 / Grav 实测可 SELECT，与目录预览同口径）。currentWs 仅软排序。",
                objectSchema(Map.of(
                        "ws", propStr("工作空间"),
                        "topN", propInt("最多返回条数，默认 12，上限 20")), List.of())));
        tools.add(fn("search_catalog",
                "按关键词搜索资产目录，结果经 ACL 过滤；无权限仅返回需申请提示。",
                objectSchema(Map.of(
                        "q", propStr("关键词"),
                        "ws", propStr("工作空间"),
                        "topN", propInt("条数")), List.of("q"))));
        tools.add(fn("get_asset_schema",
                "获取单表列摘要；须对资产可读（owner / 投影 SELECT / Grav 实测）。",
                objectSchema(Map.of(
                        "assetId", propStr("资产 id"),
                        "assetCode", propStr("与 assetId 二选一")), List.of())));
        tools.add(fn("get_asset_detail",
                "获取资产治理详情：分层/域/Owner/敏感级/OM·Grav 指针/源绑定摘要（目录详情同口径）。"
                        + "问「这张表是什么/谁负责」时用；抽样数据请用 preview_asset。",
                objectSchema(Map.of(
                        "assetId", propStr("资产 id"),
                        "assetCode", propStr("与 assetId 二选一")), List.of())));
        tools.add(fn("preview_asset",
                "抽样预览资产数据（复用目录预览，默认最多 5 行）；须可读。"
                        + "问「看看数据长什么样/样例行」时用；不要用本工具做全量统计。",
                objectSchema(Map.of(
                        "assetId", propStr("资产 id"),
                        "assetCode", propStr("与 assetId 二选一"),
                        "limit", propInt("行数，默认 5，上限 20")), List.of())));
        tools.add(fn("list_datasources",
                "列出当前空间数据源（名称/类型/状态/用途，不含密码）。问「有哪些源/源通不通配置」时用。",
                objectSchema(Map.of(
                        "ws", propStr("工作空间"),
                        "q", propStr("关键词可选"),
                        "topN", propInt("条数，默认 12")), List.of())));
        tools.add(fn("workspace_summary",
                "当前协作空间摘要：编码/名称/角色/成员数/资产数/配额提示。问「我在哪个空间」时用。",
                objectSchema(Map.of("ws", propStr("工作空间，可空则取门户当前空间")), List.of())));
        tools.add(fn("list_pending_approvals",
                "列出待我审批的申请单（超管/资产Owner/空间Owner 可见）。问「有什么要我审」时用。",
                objectSchema(Map.of(
                        "ws", propStr("工作空间"),
                        "topN", propInt("条数，默认 8")), List.of())));
        tools.add(fn("search_docs",
                "检索站内治理文档（doc/*.md + 内置索引），非外网。问「怎么用某模块/操作手册」时用；平台知识库用 kb_search。",
                objectSchema(Map.of(
                        "query", propStr("关键词"),
                        "topK", propInt("条数，默认 6")), List.of("query"))));
        tools.add(fn("ops_job_status",
                "运维摘要：治理模块探活 + 近期 ETL 运行（含失败）。问「昨晚任务挂了吗/平台健康」时用。",
                objectSchema(Map.of(
                        "ws", propStr("工作空间"),
                        "topN", propInt("最近运行条数，默认 8")), List.of())));
        tools.add(fn("kb_search",
                "检索平台知识库（优先当前 ws，不足全局软回退）。",
                objectSchema(Map.of(
                        "query", propStr("查询词"),
                        "ws", propStr("工作空间"),
                        "topK", propInt("条数")), List.of("query"))));
        tools.add(fn("list_metrics",
                "检索指标元信息（不含编译 SQL）。无读权时标记 needApply。",
                objectSchema(Map.of(
                        "q", propStr("关键词"),
                        "ws", propStr("工作空间"),
                        "topN", propInt("条数")), List.of("q"))));
        tools.add(fn("resolve_metric_meta",
                "按 metric_code 解析指标元信息（不含编译 SQL）。",
                objectSchema(Map.of(
                        "metricCode", propStr("指标编码"),
                        "ws", propStr("工作空间")), List.of("metricCode"))));
        tools.add(fn("quality_summary",
                "查询质量规则摘要（只读）。",
                objectSchema(Map.of(
                        "tableName", propStr("表名可选"),
                        "ws", propStr("工作空间")), List.of())));
        tools.add(fn("recent_dq_fails",
                "列出近期未通过/阻断的质量规则（诊断优先用本工具）。",
                objectSchema(Map.of(
                        "tableName", propStr("表名可选，空=当前空间近期失败"),
                        "ws", propStr("工作空间"),
                        "topN", propInt("条数，默认 8")), List.of())));
        tools.add(fn("lineage_upstream",
                "查询表上游血缘摘要（深度≤3）。",
                objectSchema(Map.of(
                        "tableName", propStr("表名"),
                        "ws", propStr("工作空间")), List.of("tableName"))));
        tools.add(fn("lineage_downstream",
                "查询表下游影响面摘要（深度≤3）；改表影响分析时用。",
                objectSchema(Map.of(
                        "tableName", propStr("表名"),
                        "ws", propStr("工作空间")), List.of("tableName"))));
        tools.add(fn("list_apply_tickets",
                "列出我的权限申请工单（状态/类型）；解释「为何不可查」或申请进度时用。",
                objectSchema(Map.of(
                        "status", propStr("可选 pending/approved/rejected，空=全部"),
                        "ws", propStr("工作空间"),
                        "topN", propInt("条数，默认 8")), List.of())));
        tools.add(fn("ds_connectivity",
                "测试已登记数据源连通性（复用门户 test，不含密码回传）。问「这个源通不通/连得上吗」时用；须有数据源使用权。",
                objectSchema(Map.of(
                        "dsId", propStr("数据源 id（list_datasources 返回）")), List.of("dsId"))));
        tools.add(fn("list_source_tables",
                "列出数据源已登记表清单（门户 inventory，非实时 JDBC 全库扫）。问「这个源里有哪些表」时用；须有数据源使用权。",
                objectSchema(Map.of(
                        "dsId", propStr("数据源 id"),
                        "q", propStr("表名/中文名关键词可选"),
                        "topN", propInt("条数，默认 12")), List.of("dsId"))));
        tools.add(fn("query_history_mine",
                "列出我的即席查询历史（SQL 摘要/耗时/状态，默认仅本人）。问「我最近跑过什么 SQL」时用。",
                objectSchema(Map.of(
                        "ws", propStr("工作空间"),
                        "topN", propInt("条数，默认 10，上限 20")), List.of())));
        tools.add(fn("api_catalog_summary",
                "数据服务 API 目录摘要：published/draft KPI + 绑定样例（路径/状态，不含密钥）。问「有哪些开放 API」时用。",
                objectSchema(Map.of(
                        "ws", propStr("工作空间"),
                        "q", propStr("名称/路径关键词可选"),
                        "topN", propInt("样例条数，默认 8")), List.of())));
        tools.add(fn("standard_lookup",
                "检索数据标准：字段标准 / 码值 / 命名规范（只读）。问「某字段标准口径/码表」时用。",
                objectSchema(Map.of(
                        "q", propStr("关键词"),
                        "ws", propStr("工作空间"),
                        "topN", propInt("每类条数，默认 6")), List.of("q"))));
        tools.add(fn("check_my_grant",
                "解释我对某资产的读权：门户 SELECT/owner、Grav 实测、综合 canRead；可选查 EDIT/DELETE/MANAGE。"
                        + "问「我对这张表有权限吗/为何预览失败」时用。",
                objectSchema(Map.of(
                        "assetId", propStr("资产 id"),
                        "assetCode", propStr("与 assetId 二选一"),
                        "privilege", propStr("可选 SELECT（默认）或 EDIT/DELETE/MANAGE")), List.of())));
        tools.add(fn("list_mask_policies",
                "列出脱敏策略投影（列/敏感级/算法，只读）。问「哪些列会打码/手机号怎么脱敏」时用。",
                objectSchema(Map.of(
                        "ws", propStr("工作空间"),
                        "q", propStr("表/列/敏感级关键词可选"),
                        "topN", propInt("条数，默认 10")), List.of())));
        tools.add(fn("security_overview",
                "安全中心概况：有效授权数/敏感列与资产/24h 审计 + 敏感分级桶。问「空间安全概况」时用。",
                objectSchema(Map.of("ws", propStr("工作空间")), List.of())));
        tools.add(fn("list_meta_drifts",
                "列出未关闭的元数据漂移（源结构与目录不一致）。问「有没有结构漂移/目录过期」时用。",
                objectSchema(Map.of(
                        "ws", propStr("工作空间"),
                        "topN", propInt("条数，默认 10")), List.of())));
        tools.add(fn("list_releases",
                "列出近期发布单（脚本/环境/门禁状态/标签）。问「最近上版怎么样」时用。",
                objectSchema(Map.of(
                        "ws", propStr("工作空间"),
                        "topN", propInt("条数，默认 8")), List.of())));
        tools.add(fn("release_precheck",
                "脚本上版门禁预检（只读，不落库）。问「这个脚本能上版吗/卡在哪」时用。",
                objectSchema(Map.of(
                        "scriptId", propStr("脚本 id"),
                        "engine", propStr("引擎可选"),
                        "env", propStr("目标环境可选")), List.of("scriptId"))));
        tools.add(fn("list_dev_scripts",
                "列出数据开发脚本（id/path/engine/env/status）；供 release_precheck 取 scriptId。"
                        + "问「空间里有哪些脚本」时用。",
                objectSchema(Map.of(
                        "ws", propStr("工作空间"),
                        "q", propStr("名称/路径关键词可选"),
                        "topN", propInt("条数，默认 12")), List.of())));
        tools.add(fn("quota_alerts",
                "列出配额告警（存储/CU/AI token·成本 ≥60% warn、≥80% alert）。问「配额快满了吗」时用。",
                objectSchema(Map.of("ws", propStr("可选：只看某空间；空=全部告警")), List.of())));
        tools.add(fn("lifecycle_overview",
                "生命周期存储概况：物理/活跃/可回收、告警表、compact。小文件/快照膨胀时看 diagnoseHints.href（/lifecycle?table=）。",
                objectSchema(Map.of("ws", propStr("工作空间")), List.of())));
        tools.add(fn("compliance_summary",
                "合规删除看板摘要：逾期/待审/执行中/覆盖率。问「删除请求积压怎么样」时用。",
                objectSchema(Map.of("ws", propStr("工作空间")), List.of())));
        tools.add(fn("export_audit",
                "出湖审计行（gov_export_audit；空则 soft 回落）。问「出库审批/审计记录」时用。",
                objectSchema(Map.of(
                        "ws", propStr("工作空间"),
                        "ticketNo", propStr("申请单号可选"),
                        "topN", propInt("条数，默认 10")), List.of())));
        tools.add(fn("list_saved_queries",
                "列出我保存的即席 SQL（仅本人，摘要非全文）。问「我收藏/保存过哪些查询」时用；执行历史用 query_history_mine。",
                objectSchema(Map.of(
                        "ws", propStr("工作空间"),
                        "topN", propInt("条数，默认 10")), List.of())));
        tools.add(fn("contract_overview",
                "数据契约概况：Schema 数/兼容通过/ breaking / blocked / CDC 配置数。问「契约健康吗」时用。",
                objectSchema(Map.of("ws", propStr("工作空间")), List.of())));
        tools.add(fn("aimodel_overview",
                "AI 模型管理概况：模型数/连通性/本月调用与成本/LiteLLM 探活。问「模型通不通/本月花了多少」时用。",
                objectSchema(Map.of("ws", propStr("工作空间")), List.of())));
        tools.add(fn("list_udfs",
                "列出已启用 UDF（名称/引擎/说明/snippet）。问「有哪些自定义函数」时用。",
                objectSchema(Map.of(
                        "engine", propStr("引擎过滤可选，如 flink/trino"),
                        "topN", propInt("条数，默认 12")), List.of())));
        tools.add(fn("kb_overview",
                "知识库 KPI：篇数/分类/引用/检索模式（≠ kb_search 命中）。问「知识库有多少内容」时用。",
                objectSchema(Map.of(
                        "ws", propStr("可空；当前为全局 KPI"),
                        "scope", propStr("可选 scope，透传 overview")), List.of())));
        return tools;
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        return schema;
    }

    private static Map<String, Object> propStr(String description) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "string");
        p.put("description", description);
        return p;
    }

    private static Map<String, Object> propInt(String description) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "integer");
        p.put("description", description);
        return p;
    }

    /**
     * @return JSON 字符串结果（已截断）；未知工具返回 error JSON
     */
    public String execute(String name, String argumentsJson, String defaultWs) {
        Map<String, Object> args = LhChatTools.parseArgumentsObject(argumentsJson);
        String ws = StrUtil.blankToDefault(str(args.get("ws")), StrUtil.blankToDefault(defaultWs, WS_DEFAULT));
        try {
            Object result = switch (StrUtil.nullToEmpty(name)) {
                case "catalog_stats" -> catalogStats(ws);
                case "list_ws_catalog" -> listWsCatalog(ws, intArg(args.get("topN"), 12));
                case "list_my_assets" -> listMyAssets(ws, intArg(args.get("topN"), 12));
                case "search_catalog" -> searchCatalog(str(args.get("q")), ws, intArg(args.get("topN"), 12));
                case "get_asset_schema" -> getAssetSchema(str(args.get("assetId")), str(args.get("assetCode")));
                case "get_asset_detail" -> getAssetDetail(str(args.get("assetId")), str(args.get("assetCode")));
                case "preview_asset" -> previewAsset(str(args.get("assetId")), str(args.get("assetCode")),
                        intArg(args.get("limit"), 5));
                case "list_datasources" -> listDatasources(ws, str(args.get("q")), intArg(args.get("topN"), 12));
                case "workspace_summary" -> workspaceSummary(ws);
                case "list_pending_approvals" -> listPendingApprovals(ws, intArg(args.get("topN"), 8));
                case "search_docs" -> aiDocsSearch.search(str(args.get("query")), intArg(args.get("topK"), 6));
                case "ops_job_status" -> opsJobStatus(ws, intArg(args.get("topN"), 8));
                case "kb_search" -> kbSearch(str(args.get("query")), ws, intArg(args.get("topK"), 5));
                case "list_metrics" -> listMetrics(str(args.get("q")), ws, intArg(args.get("topN"), 8));
                case "resolve_metric_meta" -> resolveMetricMeta(str(args.get("metricCode")), ws);
                case "quality_summary" -> qualitySummary(str(args.get("tableName")), ws);
                case "recent_dq_fails" -> recentDqFails(str(args.get("tableName")), ws, intArg(args.get("topN"), 8));
                case "lineage_upstream" -> lineageUpstream(str(args.get("tableName")), ws);
                case "lineage_downstream" -> lineageDownstream(str(args.get("tableName")), ws);
                case "list_apply_tickets" -> listApplyTickets(str(args.get("status")), ws, intArg(args.get("topN"), 8));
                case "ds_connectivity" -> dsConnectivity(str(args.get("dsId")));
                case "list_source_tables" -> listSourceTables(str(args.get("dsId")), str(args.get("q")),
                        intArg(args.get("topN"), 12));
                case "query_history_mine" -> queryHistoryMine(ws, intArg(args.get("topN"), 10));
                case "api_catalog_summary" -> apiCatalogSummary(ws, str(args.get("q")), intArg(args.get("topN"), 8));
                case "standard_lookup" -> standardLookup(str(args.get("q")), ws, intArg(args.get("topN"), 6));
                case "check_my_grant" -> checkMyGrant(str(args.get("assetId")), str(args.get("assetCode")),
                        str(args.get("privilege")));
                case "list_mask_policies" -> listMaskPolicies(ws, str(args.get("q")), intArg(args.get("topN"), 10));
                case "security_overview" -> securityOverview(ws);
                case "list_meta_drifts" -> listMetaDrifts(ws, intArg(args.get("topN"), 10));
                case "list_releases" -> listReleases(ws, intArg(args.get("topN"), 8));
                case "release_precheck" -> releasePrecheck(str(args.get("scriptId")), str(args.get("engine")),
                        str(args.get("env")));
                case "list_dev_scripts" -> listDevScripts(ws, str(args.get("q")), intArg(args.get("topN"), 12));
                case "quota_alerts" -> quotaAlerts(ws);
                case "lifecycle_overview" -> lifecycleOverview(ws);
                case "compliance_summary" -> complianceSummary(ws);
                case "export_audit" -> exportAudit(ws, str(args.get("ticketNo")), intArg(args.get("topN"), 10));
                case "list_saved_queries" -> listSavedQueries(ws, intArg(args.get("topN"), 10));
                case "contract_overview" -> contractOverview(ws);
                case "aimodel_overview" -> aimodelOverview(ws);
                case "list_udfs" -> listUdfs(str(args.get("engine")), intArg(args.get("topN"), 12));
                case "kb_overview" -> kbOverview(ws, str(args.get("scope")));
                default -> Map.of("error", "unknown_tool", "name", StrUtil.nullToEmpty(name));
            };
            return truncateJson(result);
        } catch (Exception e) {
            log.warn("AI tool {} failed: {}", name, e.getMessage());
            return truncateJson(Map.of("error", StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName())));
        }
    }

    private Map<String, Object> catalogStats(String ws) {
        Long registered = assetMapper.selectCount(new QueryWrapper<GovAsset>().lambda()
                .eq(GovAsset::getDeleteFlag, NOT_DELETE)
                .eq(GovAsset::getWs, ws));
        List<GovAsset> layerRows = assetMapper.selectList(new QueryWrapper<GovAsset>().lambda()
                .eq(GovAsset::getDeleteFlag, NOT_DELETE)
                .eq(GovAsset::getWs, ws)
                .orderByDesc(GovAsset::getUpdateTime)
                .last("LIMIT 2000"));
        Map<String, Integer> byLayer = new LinkedHashMap<>();
        List<Map<String, Object>> visibleSamples = new ArrayList<>();
        int sampleN = 0;
        for (GovAsset a : layerRows) {
            if (a == null) {
                continue;
            }
            String layer = StrUtil.blankToDefault(a.getLayer(), "unknown");
            byLayer.merge(layer, 1, Integer::sum);
            if (sampleN < 8 && StrUtil.isNotBlank(a.getId())) {
                boolean canRead = aiAssetReadAccess.canRead(a.getId());
                Map<String, Object> sample = assetRow(a, canRead ? "readable" : "visible_only");
                sample.put("canRead", canRead);
                if (!canRead) {
                    sample.put("hint", "目录可见，查数需申请");
                }
                visibleSamples.add(sample);
                sampleN++;
            }
        }
        List<Map<String, Object>> mine = listMyAssetsInternal(ws, MAX_ITEMS);
        long wsCount = registered == null ? 0 : registered;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ws", ws);
        out.put("wsAssetCount", wsCount);
        out.put("catalogVisibleCount", wsCount);
        out.put("wsAssetCountNote", "与资产目录「当前空间」列表同口径：可见/登记数");
        out.put("byLayer", byLayer);
        out.put("catalogVisibleSamples", visibleSamples);
        out.put("myReadableCount", mine.size());
        out.put("myReadableCountNote", "owner ∪ 门户 SELECT 投影 ∪ Grav 实测（与预览/grants/check 同口径；最多 "
                + MAX_ITEMS + "）；为 0 不代表目录为空");
        out.put("gapHint", wsCount > 0 && mine.isEmpty()
                ? "目录有登记资产，但当前账号无可查授权；请到申请中心申请表读权限"
                : null);
        out.put("softPrefer", true);
        out.put("applyHref", "/apply");
        out.put("catalogHref", "/catalog");
        return out;
    }

    /** 资产目录可见列表（空间归属），逐条标注 canRead */
    private Map<String, Object> listWsCatalog(String ws, int topN) {
        int n = Math.max(1, Math.min(topN, MAX_ITEMS));
        List<GovAsset> rows = assetMapper.selectList(new QueryWrapper<GovAsset>().lambda()
                .eq(GovAsset::getDeleteFlag, NOT_DELETE)
                .eq(GovAsset::getWs, ws)
                .orderByDesc(GovAsset::getUpdateTime)
                .last("LIMIT " + n));
        List<Map<String, Object>> items = new ArrayList<>();
        int readable = 0;
        for (GovAsset a : rows) {
            if (a == null || StrUtil.isBlank(a.getId())) {
                continue;
            }
            boolean canRead = aiAssetReadAccess.canRead(a.getId());
            if (canRead) {
                readable++;
            }
            Map<String, Object> row = assetRow(a, canRead ? "readable" : "visible_only");
            row.put("canRead", canRead);
            if (!canRead) {
                row.put("hint", "目录可见，查数需到申请中心授权");
            }
            items.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ws", ws);
        out.put("plane", "visibility");
        out.put("count", items.size());
        out.put("readableInPage", readable);
        out.put("items", items);
        out.put("note", "与资产目录当前空间列表一致；canRead=false 仍可出现在目录，但不能查数");
        out.put("applyHref", "/apply");
        return out;
    }

    private Map<String, Object> listMyAssets(String ws, int topN) {
        int n = Math.max(1, Math.min(topN, MAX_ITEMS));
        List<Map<String, Object>> items = listMyAssetsInternal(ws, n);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ws", ws);
        out.put("count", items.size());
        out.put("items", items);
        out.put("note", "软排序偏好当前 ws；未授权表不会出现");
        return out;
    }

    List<Map<String, Object>> listMyAssetsInternal(String preferWs, int topN) {
        return aiAssetReadAccess.listMyAssets(preferWs, topN);
    }

    private Map<String, Object> searchCatalog(String q, String preferWs, int topN) {
        if (StrUtil.isBlank(q)) {
            return Map.of("error", "q_required");
        }
        int n = Math.max(1, Math.min(topN, MAX_ITEMS));
        String kw = q.trim();
        List<GovAsset> hits = assetMapper.selectList(new QueryWrapper<GovAsset>().lambda()
                .eq(GovAsset::getDeleteFlag, NOT_DELETE)
                .and(w -> w.like(GovAsset::getAssetCode, kw)
                        .or().like(GovAsset::getName, kw)
                        .or().like(GovAsset::getCnName, kw)
                        .or().like(GovAsset::getOmFqn, kw))
                .orderByDesc(GovAsset::getUpdateTime)
                .last("LIMIT 80"));
        List<Map<String, Object>> readable = new ArrayList<>();
        List<Map<String, Object>> needApply = new ArrayList<>();
        List<GovAsset> ordered = new ArrayList<>();
        for (GovAsset a : hits) {
            if (a != null && StrUtil.isNotBlank(preferWs) && preferWs.equals(a.getWs())) {
                ordered.add(a);
            }
        }
        for (GovAsset a : hits) {
            if (a != null && (StrUtil.isBlank(preferWs) || !preferWs.equals(a.getWs()))) {
                ordered.add(a);
            }
        }
        for (GovAsset a : ordered) {
            if (a == null || StrUtil.isBlank(a.getId())) {
                continue;
            }
            boolean can = aiAssetReadAccess.canRead(a.getId());
            if (can) {
                readable.add(assetRow(a, "readable"));
            } else if (needApply.size() < n) {
                Map<String, Object> row = assetRow(a, "need_apply");
                row.put("hint", "无读权限，请到申请中心申请");
                needApply.add(row);
            }
            if (readable.size() >= n) {
                break;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("q", kw);
        out.put("ws", preferWs);
        out.put("readable", readable.size() > n ? readable.subList(0, n) : readable);
        out.put("needApplySamples", needApply);
        return out;
    }

    private Map<String, Object> getAssetSchema(String assetId, String assetCode) {
        GovAsset asset = resolveAsset(assetId, assetCode);
        if (asset == null) {
            return Map.of("error", "asset_not_found");
        }
        if (!aiAssetReadAccess.canRead(asset.getId())) {
            return Map.of("error", "forbidden", "message", "无表读权限", "assetId", asset.getId(),
                    "assetCode", asset.getAssetCode(), "href", "/apply");
        }
        try {
            GovAssetIdParam p = new GovAssetIdParam();
            p.setId(asset.getId());
            Map<String, Object> schema = govAssetService.schema(p);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("assetId", asset.getId());
            out.put("assetCode", asset.getAssetCode());
            out.put("name", StrUtil.blankToDefault(asset.getCnName(), asset.getName()));
            out.put("ws", asset.getWs());
            out.put("columns", schema == null ? List.of()
                    : schema.getOrDefault("columns", schema.getOrDefault("fields", List.of())));
            return out;
        } catch (Exception e) {
            return Map.of("error", "schema_failed", "message", e.getMessage());
        }
    }

    private Map<String, Object> getAssetDetail(String assetId, String assetCode) {
        GovAsset asset = resolveAsset(assetId, assetCode);
        if (asset == null) {
            return Map.of("error", "asset_not_found");
        }
        try {
            GovAssetIdParam p = new GovAssetIdParam();
            p.setId(asset.getId());
            GovAssetVo vo = govAssetService.detail(p);
            boolean canRead = aiAssetReadAccess.canRead(asset.getId());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("assetId", asset.getId());
            out.put("assetCode", asset.getAssetCode());
            out.put("name", StrUtil.blankToDefault(asset.getCnName(), asset.getName()));
            out.put("cnName", asset.getCnName());
            out.put("description", asset.getDescription());
            out.put("ws", asset.getWs());
            out.put("layer", asset.getLayer());
            out.put("domainCode", asset.getDomainCode());
            out.put("sensitivity", asset.getSensitivity());
            out.put("status", asset.getStatus());
            out.put("assetKind", asset.getAssetKind());
            out.put("engine", asset.getEngine());
            out.put("omFqn", asset.getOmFqn());
            out.put("gravAssetId", asset.getGravAssetId());
            out.put("techOwner", asset.getTechOwner());
            out.put("bizOwner", asset.getBizOwner());
            out.put("createUser", asset.getCreateUser());
            out.put("canRead", canRead);
            out.put("href", "/catalog?asset=" + asset.getId());
            if (vo != null) {
                out.put("layerLabel", vo.getLayerLabel());
                out.put("techOwnerName", vo.getTechOwnerName());
                out.put("bizOwnerName", vo.getBizOwnerName());
                out.put("createUserName", vo.getCreateUserName());
                out.put("isGold", vo.getIsGold());
                out.put("visibility", vo.getVisibility());
                if (vo.getSources() != null) {
                    List<Map<String, Object>> sources = new ArrayList<>();
                    int n = 0;
                    for (var s : vo.getSources()) {
                        if (s == null || n++ >= 6) {
                            break;
                        }
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("dsId", s.getDsId());
                        row.put("dsName", s.getDsName());
                        row.put("objectName", s.getObjectName());
                        row.put("linkRole", s.getLinkRole());
                        sources.add(row);
                    }
                    out.put("sources", sources);
                }
            }
            if (!canRead) {
                out.put("hint", "元数据可见；抽样/列类型详查需可读权限");
                out.put("applyHref", "/apply");
            }
            return out;
        } catch (Exception e) {
            return Map.of("error", "detail_failed", "message", e.getMessage());
        }
    }

    private Map<String, Object> previewAsset(String assetId, String assetCode, int limit) {
        GovAsset asset = resolveAsset(assetId, assetCode);
        if (asset == null) {
            return Map.of("error", "asset_not_found");
        }
        if (!aiAssetReadAccess.canRead(asset.getId())) {
            return Map.of("error", "forbidden", "message", "无表读权限，无法预览",
                    "assetId", asset.getId(), "assetCode", asset.getAssetCode(), "href", "/apply");
        }
        int lim = Math.max(1, Math.min(limit, 20));
        try {
            GovAssetPreviewParam p = new GovAssetPreviewParam();
            p.setId(asset.getId());
            p.setLimit(lim);
            Map<String, Object> preview = govAssetService.preview(p);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("assetId", asset.getId());
            out.put("assetCode", asset.getAssetCode());
            out.put("name", StrUtil.blankToDefault(asset.getCnName(), asset.getName()));
            out.put("limit", lim);
            if (preview != null) {
                out.put("ok", preview.get("ok"));
                out.put("source", preview.get("source"));
                out.put("message", preview.get("message"));
                out.put("needApply", preview.get("needApply"));
                out.put("needPrincipal", preview.get("needPrincipal"));
                Object cols = preview.get("columns");
                Object rows = preview.get("rows");
                if (cols instanceof List<?> cl && cl.size() > 24) {
                    out.put("columns", cl.subList(0, 24));
                    out.put("columnsTruncated", true);
                } else {
                    out.put("columns", cols == null ? List.of() : cols);
                }
                if (rows instanceof List<?> rl && rl.size() > lim) {
                    out.put("rows", rl.subList(0, lim));
                } else {
                    out.put("rows", rows == null ? List.of() : rows);
                }
                out.put("rowCount", preview.getOrDefault("rowCount",
                        rows instanceof List<?> r2 ? r2.size() : 0));
            }
            out.put("note", "抽样预览，非全量；敏感列可能已脱敏");
            return out;
        } catch (Exception e) {
            return Map.of("error", "preview_failed", "message", e.getMessage());
        }
    }

    private GovAsset resolveAsset(String assetId, String assetCode) {
        if (StrUtil.isNotBlank(assetId)) {
            GovAsset asset = assetMapper.selectById(assetId.trim());
            if (asset != null && NOT_DELETE.equals(asset.getDeleteFlag())) {
                return asset;
            }
        }
        if (StrUtil.isNotBlank(assetCode)) {
            return assetMapper.selectOne(new QueryWrapper<GovAsset>().lambda()
                    .eq(GovAsset::getDeleteFlag, NOT_DELETE)
                    .and(w -> w.eq(GovAsset::getAssetCode, assetCode.trim())
                            .or().eq(GovAsset::getName, assetCode.trim())
                            .or().eq(GovAsset::getOmFqn, assetCode.trim()))
                    .last("LIMIT 1"));
        }
        return null;
    }

    private Map<String, Object> kbSearch(String query, String ws, int topK) {
        if (StrUtil.isBlank(query)) {
            return Map.of("error", "query_required");
        }
        int k = Math.max(1, Math.min(topK, 10));
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (StrUtil.isNotBlank(ws)) {
            GovKbSearchParam preferred = new GovKbSearchParam();
            preferred.setQuery(query);
            preferred.setWs(ws);
            preferred.setIncludePlatform(true);
            preferred.setTopK(k);
            appendKb(out, seen, preferred, ws, true);
        }
        if (out.size() < k) {
            GovKbSearchParam global = new GovKbSearchParam();
            global.setQuery(query);
            global.setTopK(Math.max(k * 2, 10));
            appendKb(out, seen, global, ws, false);
        }
        if (out.size() > k) {
            out = out.subList(0, k);
        }
        return Map.of("query", query, "ws", ws, "hits", out);
    }

    private void appendKb(List<Map<String, Object>> out, Set<String> seen, GovKbSearchParam param,
                          String preferWs, boolean forcePrefer) {
        try {
            for (Map<String, Object> h : govKbService.search(param)) {
                String key = String.valueOf(h.getOrDefault("chunkId", h.get("entryId")));
                if (!seen.add(key)) {
                    continue;
                }
                String hitWs = String.valueOf(h.getOrDefault("ws", "")).trim();
                boolean prefer = forcePrefer || (StrUtil.isNotBlank(preferWs) && preferWs.equals(hitWs));
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("entryId", h.get("entryId"));
                row.put("title", h.get("title"));
                row.put("text", StrUtil.maxLength(String.valueOf(h.getOrDefault("text", "")), 500));
                row.put("score", h.get("score"));
                row.put("ws", h.get("ws"));
                row.put("preferWs", prefer);
                out.add(row);
            }
        } catch (Exception e) {
            log.debug("kb_search soft-fail: {}", e.toString());
        }
    }

    private Map<String, Object> listMetrics(String q, String ws, int topN) {
        if (StrUtil.isBlank(q)) {
            return Map.of("error", "q_required");
        }
        int n = Math.max(1, Math.min(topN, MAX_ITEMS));
        List<Map<String, Object>> items = new ArrayList<>();
        try {
            GovMetricPageParam param = new GovMetricPageParam();
            param.setWs(ws);
            param.setQ(q.trim());
            Page<GovMetricVo> page = govMetricService.page(param);
            List<GovMetricVo> records = page == null || page.getRecords() == null ? List.of() : page.getRecords();
            if (records.isEmpty() && StrUtil.isNotBlank(ws)) {
                param.setWs(null);
                page = govMetricService.page(param);
                records = page == null || page.getRecords() == null ? List.of() : page.getRecords();
            }
            for (GovMetricVo m : records) {
                if (items.size() >= n) {
                    break;
                }
                items.add(metricMetaRow(m));
            }
        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        }
        return Map.of("q", q, "ws", ws, "items", items);
    }

    private Map<String, Object> resolveMetricMeta(String metricCode, String ws) {
        if (StrUtil.isBlank(metricCode)) {
            return Map.of("error", "metricCode_required");
        }
        try {
            GovMetricVo m;
            try {
                m = govMetricService.detail(metricCode.trim(), ws);
            } catch (Exception e) {
                m = govMetricService.detail(metricCode.trim(), null);
            }
            if (m == null) {
                return Map.of("error", "not_found", "metricCode", metricCode);
            }
            return metricMetaRow(m);
        } catch (Exception e) {
            return Map.of("error", e.getMessage(), "metricCode", metricCode);
        }
    }

    private Map<String, Object> metricMetaRow(GovMetricVo m) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", m.getId());
        row.put("metricCode", m.getMetricCode());
        row.put("name", m.getName());
        row.put("domain", m.getDomain());
        row.put("status", m.getStatus());
        boolean canRead = false;
        try {
            canRead = secAuthGrantService.canReadMetric(m.getId());
        } catch (Exception ignored) {
            canRead = false;
        }
        row.put("canRead", canRead);
        if (!canRead) {
            row.put("needApply", true);
            row.put("href", "/apply");
            row.put("note", "无指标读权限，不返回口径 SQL");
        }
        return row;
    }

    private Map<String, Object> qualitySummary(String tableName, String ws) {
        List<Map<String, Object>> rules = new ArrayList<>();
        try {
            GovDqPageParam qp = new GovDqPageParam();
            qp.setWs(ws);
            if (StrUtil.isNotBlank(tableName)) {
                qp.setTableName(tableName);
            }
            qp.setStatus("fail");
            collectRules(rules, govDqService.pageRules(qp));
            qp.setStatus("warn");
            collectRules(rules, govDqService.pageRules(qp));
            if (rules.isEmpty() && StrUtil.isNotBlank(tableName)) {
                GovDqPageParam loose = new GovDqPageParam();
                loose.setWs(ws);
                loose.setQ(tableName);
                collectRules(rules, govDqService.pageRules(loose));
            }
        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        }
        if (rules.size() > 8) {
            rules = rules.subList(0, 8);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ws", ws);
        out.put("tableName", tableName);
        out.put("rules", rules);
        out.put("count", rules.size());
        return out;
    }

    private void collectRules(List<Map<String, Object>> rules, Page<GovDqRuleVo> page) {
        if (page == null || page.getRecords() == null) {
            return;
        }
        for (GovDqRuleVo v : page.getRecords()) {
            boolean dup = rules.stream().anyMatch(r -> StrUtil.equals(String.valueOf(r.get("id")), v.getId()));
            if (dup) {
                continue;
            }
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("id", v.getId());
            c.put("ruleCode", v.getRuleCode());
            c.put("tableName", v.getTableName());
            c.put("pass", v.getPass());
            c.put("blocked", v.getBlocked());
            c.put("statusText", v.getStatusText());
            c.put("message", v.getMessage());
            rules.add(c);
        }
    }

    private Map<String, Object> recentDqFails(String tableName, String ws, int topN) {
        int n = Math.max(1, Math.min(topN, MAX_ITEMS));
        List<Map<String, Object>> rules = new ArrayList<>();
        try {
            Map<String, Object> summary = qualitySummary(tableName, ws);
            Object raw = summary.get("rules");
            if (raw instanceof List<?> list) {
                for (Object item : list) {
                    if (!(item instanceof Map<?, ?> m)) {
                        continue;
                    }
                    boolean pass = Boolean.TRUE.equals(m.get("pass"));
                    boolean blocked = Boolean.TRUE.equals(m.get("blocked"));
                    Object stObj = m.get("statusText");
                    String status = stObj == null ? "" : String.valueOf(stObj);
                    if (pass && !blocked && !status.toLowerCase().contains("fail")) {
                        continue;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> e : m.entrySet()) {
                        row.put(String.valueOf(e.getKey()), e.getValue());
                    }
                    rules.add(row);
                    if (rules.size() >= n) {
                        break;
                    }
                }
            }
        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ws", ws);
        out.put("tableName", tableName);
        out.put("fails", rules);
        out.put("count", rules.size());
        out.put("href", "/quality");
        return out;
    }

    private Map<String, Object> lineageUpstream(String tableName, String ws) {
        return lineageImpact(tableName, ws, true);
    }

    private Map<String, Object> lineageDownstream(String tableName, String ws) {
        return lineageImpact(tableName, ws, false);
    }

    private Map<String, Object> lineageImpact(String tableName, String ws, boolean upstream) {
        if (StrUtil.isBlank(tableName)) {
            return Map.of("error", "tableName_required");
        }
        try {
            Map<String, Object> impact = govLineageService.impact(null, tableName, null, 3, 3, ws);
            String key = upstream ? "up" : "down";
            List<Object> nodes = new ArrayList<>();
            if (impact != null && impact.get(key) instanceof List<?> list) {
                int n = 0;
                for (Object item : list) {
                    if (n++ >= 8) {
                        break;
                    }
                    nodes.add(item);
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("tableName", tableName);
            out.put("ws", ws);
            out.put(upstream ? "upstream" : "downstream", nodes);
            out.put("count", nodes.size());
            out.put("href", "/lineage");
            return out;
        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        }
    }

    private Map<String, Object> listDatasources(String ws, String q, int topN) {
        int n = Math.max(1, Math.min(topN, MAX_ITEMS));
        try {
            LhDatasourcePageParam param = new LhDatasourcePageParam();
            param.setWs(ws);
            if (StrUtil.isNotBlank(q)) {
                param.setKeyword(q.trim());
            }
            Page<LhDatasourceVo> page = lhDatasourceService.page(param);
            List<Map<String, Object>> items = new ArrayList<>();
            if (page != null && page.getRecords() != null) {
                for (LhDatasourceVo v : page.getRecords()) {
                    if (v == null || items.size() >= n) {
                        break;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", v.getId());
                    row.put("dsCode", v.getDsCode());
                    row.put("name", v.getName());
                    row.put("type", StrUtil.blankToDefault(v.getTypeCode(), v.getType()));
                    row.put("category", v.getCategory());
                    row.put("status", v.getStatus());
                    row.put("purpose", StrUtil.blankToDefault(v.getPurposes(), v.getPurpose()));
                    row.put("owner", StrUtil.blankToDefault(v.getOwnerName(), v.getOwner()));
                    row.put("host", v.getHost());
                    row.put("database", v.getDatabase());
                    items.add(row);
                }
            }
            Map<String, Object> kpi = null;
            try {
                kpi = lhDatasourceService.kpi(ws);
            } catch (Exception ignored) {
                // soft
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ws", ws);
            out.put("q", q);
            out.put("count", items.size());
            out.put("total", page == null ? items.size() : page.getTotal());
            out.put("items", items);
            if (kpi != null) {
                out.put("kpi", kpi);
            }
            out.put("href", "/datasource");
            out.put("note", "不含密码/凭证；详情见数据源页");
            return out;
        } catch (Exception e) {
            return Map.of("error", e.getMessage(), "href", "/datasource");
        }
    }

    private Map<String, Object> workspaceSummary(String ws) {
        try {
            Map<String, Object> overview = govWsService.overview();
            String code = StrUtil.blankToDefault(ws, null);
            if (StrUtil.isBlank(code) && overview != null) {
                code = String.valueOf(overview.getOrDefault("currentWs", WS_DEFAULT));
            }
            code = StrUtil.blankToDefault(code, WS_DEFAULT);
            GovWsVo detail = govWsService.detail(code);
            List<Map<String, Object>> members = new ArrayList<>();
            try {
                List<GovWsMemberVo> raw = govWsService.listMembers(code);
                if (raw != null) {
                    int i = 0;
                    for (GovWsMemberVo m : raw) {
                        if (m == null || i++ >= 12) {
                            break;
                        }
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("displayName", m.getDisplayName());
                        row.put("roleCode", m.getRoleCode());
                        row.put("subjectType", m.getSubjectType());
                        row.put("status", m.getStatus());
                        members.add(row);
                    }
                }
            } catch (Exception e) {
                log.debug("listMembers soft-fail ws={}: {}", code, e.toString());
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ws", code);
            out.put("overview", overview);
            if (detail != null) {
                out.put("name", detail.getName());
                out.put("wsKind", detail.getWsKind());
                out.put("myRole", detail.getMyRole());
                out.put("memberCount", detail.getMemberCount());
                out.put("assetCount", detail.getAssetCount());
                out.put("status", detail.getStatus());
                out.put("domainCode", detail.getDomainCode());
                out.put("quotaStatus", detail.getQuotaStatus());
                out.put("storageUsedTb", detail.getStorageUsedTb());
                out.put("storageQuotaTb", detail.getStorageQuotaTb());
                out.put("tags", detail.getTags());
                out.put("current", detail.getCurrent());
            }
            out.put("membersSample", members);
            out.put("href", "/workspace");
            return out;
        } catch (Exception e) {
            return Map.of("error", e.getMessage(), "href", "/workspace");
        }
    }

    private Map<String, Object> listPendingApprovals(String ws, int topN) {
        int n = Math.max(1, Math.min(topN, MAX_ITEMS));
        try {
            ApplyTicketPageParam param = new ApplyTicketPageParam();
            param.setCurrent(1);
            param.setSize(n);
            param.setWs(StrUtil.blankToDefault(ws, null));
            Page<ApplyTicket> page = applyTicketService.pagePending(param);
            List<Map<String, Object>> items = new ArrayList<>();
            if (page != null && page.getRecords() != null) {
                for (ApplyTicket t : page.getRecords()) {
                    if (t == null) {
                        continue;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", t.getId());
                    row.put("ticketNo", t.getTicketNo());
                    row.put("ticketType", t.getTicketType());
                    row.put("status", t.getStatus());
                    row.put("ws", t.getWs());
                    row.put("title", t.getTitle());
                    row.put("applicant", t.getApplicant());
                    row.put("createTime", t.getCreateTime());
                    items.add(row);
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ws", ws);
            out.put("count", items.size());
            out.put("total", page == null ? items.size() : page.getTotal());
            out.put("items", items);
            out.put("href", "/apply");
            out.put("note", "仅待我可审的单；我发起的申请请用 list_apply_tickets");
            return out;
        } catch (Exception e) {
            return Map.of("error", e.getMessage(), "href", "/apply");
        }
    }

    private Map<String, Object> opsJobStatus(String ws, int topN) {
        int n = Math.max(1, Math.min(topN, MAX_ITEMS));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ws", ws);
        try {
            out.put("modules", lhModuleOpsService.listModules("all"));
        } catch (Exception e) {
            out.put("modulesError", e.getMessage());
        }
        try {
            Page<Map<String, Object>> runs = igEtlService.pageRuns(null, ws);
            List<Map<String, Object>> recent = new ArrayList<>();
            List<Map<String, Object>> fails = new ArrayList<>();
            if (runs != null && runs.getRecords() != null) {
                for (Map<String, Object> r : runs.getRecords()) {
                    if (r == null) {
                        continue;
                    }
                    if (recent.size() < n) {
                        recent.add(trimRun(r));
                    }
                    String st = String.valueOf(r.getOrDefault("status", "")).toLowerCase();
                    if (("failed".equals(st) || "blocked".equals(st)) && fails.size() < n) {
                        fails.add(trimRun(r));
                    }
                }
            }
            out.put("recentRuns", recent);
            out.put("recentFailRuns", fails);
            out.put("runTotal", runs == null ? 0 : runs.getTotal());
        } catch (Exception e) {
            out.put("runsError", e.getMessage());
        }
        try {
            IgEtlPageParam dp = new IgEtlPageParam();
            dp.setWs(ws);
            Page<Map<String, Object>> dags = igEtlService.pageDags(dp);
            List<Map<String, Object>> dagBrief = new ArrayList<>();
            if (dags != null && dags.getRecords() != null) {
                int i = 0;
                for (Map<String, Object> d : dags.getRecords()) {
                    if (d == null || i++ >= 8) {
                        break;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", d.get("id"));
                    row.put("name", d.getOrDefault("name", d.get("dagName")));
                    row.put("status", d.get("status"));
                    row.put("ws", d.get("ws"));
                    dagBrief.add(row);
                }
            }
            out.put("dagsSample", dagBrief);
            out.put("dagTotal", dags == null ? 0 : dags.getTotal());
        } catch (Exception e) {
            out.put("dagsError", e.getMessage());
        }
        out.put("hrefOps", "/ops");
        out.put("hrefEtl", "/integration");
        return out;
    }

    private static Map<String, Object> trimRun(Map<String, Object> r) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (String k : List.of("runId", "dagId", "dagName", "status", "message", "ws",
                "startedAt", "finishedAt", "createTime", "triggerType")) {
            if (r.containsKey(k) && r.get(k) != null) {
                row.put(k, r.get(k));
            }
        }
        if (row.isEmpty()) {
            row.putAll(r);
        }
        return row;
    }

    private Map<String, Object> listApplyTickets(String status, String ws, int topN) {
        int n = Math.max(1, Math.min(topN, MAX_ITEMS));
        try {
            ApplyTicketPageParam param = new ApplyTicketPageParam();
            param.setCurrent(1);
            param.setSize(n);
            param.setWs(StrUtil.blankToDefault(ws, null));
            if (StrUtil.isNotBlank(status)) {
                param.setStatus(status.trim());
            }
            Page<ApplyTicket> page = applyTicketService.pageMine(param);
            List<Map<String, Object>> items = new ArrayList<>();
            if (page != null && page.getRecords() != null) {
                for (ApplyTicket t : page.getRecords()) {
                    if (t == null) {
                        continue;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", t.getId());
                    row.put("ticketNo", t.getTicketNo());
                    row.put("ticketType", t.getTicketType());
                    row.put("status", t.getStatus());
                    row.put("ws", t.getWs());
                    row.put("title", t.getTitle());
                    row.put("createTime", t.getCreateTime());
                    items.add(row);
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ws", ws);
            out.put("status", status);
            out.put("items", items);
            out.put("count", items.size());
            out.put("href", "/apply");
            return out;
        } catch (Exception e) {
            return Map.of("error", e.getMessage(), "href", "/apply");
        }
    }

    private Map<String, Object> dsConnectivity(String dsId) {
        if (StrUtil.isBlank(dsId)) {
            return Map.of("error", "dsId_required");
        }
        try {
            if (!secAuthGrantService.canUseDatasource(dsId)) {
                Map<String, Object> denied = new LinkedHashMap<>();
                denied.put("error", "forbidden");
                denied.put("message", "无权使用该数据源（须为拥有者或持有 EDIT/MANAGE）");
                denied.put("dsId", dsId);
                denied.put("href", "/apply");
                return denied;
            }
            LhDatasourceTestParam param = new LhDatasourceTestParam();
            param.setId(dsId);
            Map<String, Object> test = lhDatasourceService.test(param);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("dsId", dsId);
            if (test != null) {
                for (Map.Entry<String, Object> e : test.entrySet()) {
                    String k = e.getKey();
                    if (k == null) {
                        continue;
                    }
                    String lk = k.toLowerCase();
                    if (lk.contains("password") || lk.contains("secret") || lk.contains("token")
                            || "conn".equals(lk) || "vault".equals(lk)) {
                        continue;
                    }
                    out.put(k, e.getValue());
                }
            }
            try {
                LhDatasourceIdParam idp = new LhDatasourceIdParam();
                idp.setId(dsId);
                LhDatasourceVo vo = lhDatasourceService.detail(idp);
                if (vo != null) {
                    out.putIfAbsent("name", vo.getName());
                    out.putIfAbsent("type", StrUtil.blankToDefault(vo.getTypeCode(), vo.getType()));
                    out.putIfAbsent("status", vo.getStatus());
                }
            } catch (Exception ignored) {
                // soft
            }
            out.put("href", "/datasource");
            out.put("note", "连通性探测；不含密码/凭证");
            return out;
        } catch (Exception e) {
            return Map.of("error", StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()),
                    "href", "/datasource");
        }
    }

    private Map<String, Object> listSourceTables(String dsId, String q, int topN) {
        if (StrUtil.isBlank(dsId)) {
            return Map.of("error", "dsId_required");
        }
        int n = Math.max(1, Math.min(topN, MAX_ITEMS));
        try {
            if (!secAuthGrantService.canUseDatasource(dsId)) {
                Map<String, Object> denied = new LinkedHashMap<>();
                denied.put("error", "forbidden");
                denied.put("message", "无权使用该数据源");
                denied.put("dsId", dsId);
                denied.put("href", "/apply");
                return denied;
            }
            LhDsTablePageParam param = new LhDsTablePageParam();
            param.setDsId(dsId);
            if (StrUtil.isNotBlank(q)) {
                param.setKeyword(q.trim());
            }
            Page<LhDsTableVo> page = lhDatasourceService.tablePage(param);
            List<Map<String, Object>> items = new ArrayList<>();
            if (page != null && page.getRecords() != null) {
                for (LhDsTableVo v : page.getRecords()) {
                    if (v == null || items.size() >= n) {
                        break;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", v.getId());
                    row.put("name", v.getName());
                    row.put("cnName", v.getCnName());
                    row.put("comment", v.getComment());
                    row.put("engine", v.getEngine());
                    row.put("rowCount", v.getRowCount());
                    row.put("status", v.getStatus());
                    row.put("syncedAt", v.getSyncedAt());
                    items.add(row);
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("dsId", dsId);
            out.put("q", q);
            out.put("count", items.size());
            out.put("total", page == null ? items.size() : page.getTotal());
            out.put("items", items);
            out.put("href", "/datasource");
            out.put("note", "门户已登记表清单；非实时 JDBC 全库枚举");
            return out;
        } catch (Exception e) {
            return Map.of("error", StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()),
                    "href", "/datasource");
        }
    }

    private Map<String, Object> queryHistoryMine(String ws, int topN) {
        int n = Math.max(1, Math.min(topN, MAX_ITEMS));
        try {
            CpQueryHistoryParam param = new CpQueryHistoryParam();
            param.setWs(ws);
            param.setLimit(n);
            param.setMineOnly(true);
            List<Map<String, Object>> raw = cpQueryService.history(param);
            List<Map<String, Object>> items = new ArrayList<>();
            if (raw != null) {
                for (Map<String, Object> h : raw) {
                    if (h == null || items.size() >= n) {
                        break;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (String k : List.of("id", "queryId", "time", "summary", "duration", "durMs",
                            "rows", "status", "statusLabel", "scan", "errorMsg")) {
                        if (h.containsKey(k) && h.get(k) != null) {
                            row.put(k, h.get(k));
                        }
                    }
                    Object sql = h.get("sql");
                    if (sql != null) {
                        row.put("sqlPreview", StrUtil.maxLength(String.valueOf(sql), 240));
                    }
                    items.add(row);
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ws", ws);
            out.put("count", items.size());
            out.put("items", items);
            out.put("href", "/query");
            out.put("note", "仅本人历史；不含结果集");
            return out;
        } catch (Exception e) {
            return Map.of("error", StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()),
                    "href", "/query");
        }
    }

    private Map<String, Object> apiCatalogSummary(String ws, String q, int topN) {
        int n = Math.max(1, Math.min(topN, MAX_ITEMS));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ws", ws);
        try {
            out.put("overview", dataapiService.overview(ws));
        } catch (Exception e) {
            out.put("overviewError", e.getMessage());
        }
        try {
            DataapiPageParam param = new DataapiPageParam();
            param.setWs(ws);
            if (StrUtil.isNotBlank(q)) {
                param.setQ(q.trim());
            }
            Page<DataapiApiBinding> page = dataapiService.page(param);
            List<Map<String, Object>> items = new ArrayList<>();
            if (page != null && page.getRecords() != null) {
                for (DataapiApiBinding b : page.getRecords()) {
                    if (b == null || items.size() >= n) {
                        break;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", b.getId());
                    row.put("name", b.getName());
                    row.put("publicPath", b.getPublicPath());
                    row.put("method", b.getMethod());
                    row.put("state", b.getState());
                    row.put("domainCode", b.getDomainCode());
                    row.put("authMode", b.getAuthMode());
                    row.put("ownerUser", b.getOwnerUser());
                    row.put("publishEnv", b.getPublishEnv());
                    items.add(row);
                }
            }
            out.put("count", items.size());
            out.put("total", page == null ? items.size() : page.getTotal());
            out.put("items", items);
        } catch (Exception e) {
            out.put("listError", e.getMessage());
        }
        out.put("href", "/dataservice");
        out.put("note", "不含 API Key / 密钥；调用量见 overview 备注");
        return out;
    }

    private Map<String, Object> standardLookup(String q, String ws, int topN) {
        if (StrUtil.isBlank(q)) {
            return Map.of("error", "q_required");
        }
        int n = Math.max(1, Math.min(topN, MAX_ITEMS));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("q", q);
        out.put("ws", ws);
        GovStdPageParam param = new GovStdPageParam();
        param.setQ(q.trim());
        param.setWs(ws);
        try {
            Page<GovStdFieldVo> fields = govStdService.pageFields(param);
            List<Map<String, Object>> fieldItems = new ArrayList<>();
            if (fields != null && fields.getRecords() != null) {
                for (GovStdFieldVo v : fields.getRecords()) {
                    if (v == null || fieldItems.size() >= n) {
                        break;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", v.getId());
                    row.put("name", StrUtil.blankToDefault(v.getName(), v.getFieldName()));
                    row.put("type", StrUtil.blankToDefault(v.getType(), v.getDataType()));
                    row.put("unit", v.getUnit());
                    row.put("desc", StrUtil.blankToDefault(v.getDesc(), v.getDescription()));
                    row.put("domain", StrUtil.blankToDefault(v.getDomain(), v.getDomainCode()));
                    row.put("status", StrUtil.blankToDefault(v.getStatus(), v.getComplianceStatus()));
                    fieldItems.add(row);
                }
            }
            out.put("fields", fieldItems);
            out.put("fieldTotal", fields == null ? fieldItems.size() : fields.getTotal());
        } catch (Exception e) {
            out.put("fieldsError", e.getMessage());
        }
        try {
            Page<GovStdCodeVo> codes = govStdService.pageCodes(param);
            List<Map<String, Object>> codeItems = new ArrayList<>();
            if (codes != null && codes.getRecords() != null) {
                for (GovStdCodeVo v : codes.getRecords()) {
                    if (v == null || codeItems.size() >= n) {
                        break;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", StrUtil.blankToDefault(v.getId(), v.getPkId()));
                    row.put("name", v.getName());
                    row.put("field", StrUtil.blankToDefault(v.getField(), v.getFieldName()));
                    row.put("count", v.getCount());
                    codeItems.add(row);
                }
            }
            out.put("codes", codeItems);
            out.put("codeTotal", codes == null ? codeItems.size() : codes.getTotal());
        } catch (Exception e) {
            out.put("codesError", e.getMessage());
        }
        try {
            Page<GovStdNamingVo> namings = govStdService.pageNamings(param);
            List<Map<String, Object>> namingItems = new ArrayList<>();
            if (namings != null && namings.getRecords() != null) {
                for (GovStdNamingVo v : namings.getRecords()) {
                    if (v == null || namingItems.size() >= n) {
                        break;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", v.getId());
                    row.put("pattern", v.getPattern());
                    row.put("example", v.getExample());
                    row.put("layer", v.getLayer());
                    row.put("status", v.getStatus());
                    namingItems.add(row);
                }
            }
            out.put("namings", namingItems);
            out.put("namingTotal", namings == null ? namingItems.size() : namings.getTotal());
        } catch (Exception e) {
            out.put("namingsError", e.getMessage());
        }
        out.put("href", "/standard");
        return out;
    }

    private Map<String, Object> checkMyGrant(String assetId, String assetCode, String privilege) {
        GovAsset asset = resolveAsset(assetId, assetCode);
        if (asset == null) {
            return Map.of("error", "asset_not_found");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("assetId", asset.getId());
        out.put("assetCode", asset.getAssetCode());
        out.put("name", StrUtil.blankToDefault(asset.getCnName(), asset.getName()));
        out.put("ws", asset.getWs());
        out.put("sensitivity", asset.getSensitivity());

        boolean portalRead = false;
        boolean gravSelect = false;
        try {
            portalRead = secAuthGrantService.hasTableReadGrant(asset.getId());
        } catch (Exception e) {
            out.put("portalReadError", e.getMessage());
        }
        try {
            gravSelect = gravTableAccessService.canCurrentSelect(asset.getId());
        } catch (Exception e) {
            out.put("gravSelectError", e.getMessage());
        }
        boolean canRead = aiAssetReadAccess.canRead(asset.getId());
        out.put("portalRead", portalRead);
        out.put("gravSelect", gravSelect);
        out.put("canRead", canRead);

        String priv = StrUtil.blankToDefault(privilege, "SELECT").trim().toUpperCase();
        out.put("privilege", priv);
        if (!"SELECT".equals(priv)) {
            LhOpsPrivilegeEnum needed = LhOpsPrivilegeEnum.of(priv);
            if (needed == null) {
                out.put("opsError", "privilege 须为 SELECT 或 EDIT/DELETE/MANAGE");
            } else {
                try {
                    boolean ops = secAuthGrantService.hasOpsPrivilege(
                            LhOpsResourceTypeEnum.ASSET.getValue(), asset.getId(), needed);
                    out.put("opsAllowed", ops);
                } catch (Exception e) {
                    out.put("opsError", e.getMessage());
                }
            }
        }

        if (!canRead) {
            out.put("hint", "目录可能可见但不可查数；请到申请中心申请表读权");
            out.put("href", "/apply");
        } else {
            out.put("hint", "可读（与目录预览 / grants/check 同口径）");
            out.put("href", "/catalog?asset=" + asset.getId());
        }
        return out;
    }

    private Map<String, Object> listMaskPolicies(String ws, String q, int topN) {
        int n = Math.max(1, Math.min(topN, MAX_ITEMS));
        try {
            Map<String, Object> page = secBoardService.pageMasks(ws, q, 1, n);
            List<Map<String, Object>> items = new ArrayList<>();
            Object records = page == null ? null : page.get("records");
            if (records instanceof List<?> list) {
                for (Object o : list) {
                    if (!(o instanceof Map<?, ?> m) || items.size() >= n) {
                        break;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (String k : List.of("id", "ws", "status", "gravAssetId", "columnName",
                            "sensitivity", "maskAlgo", "omTagFqn", "updateTime")) {
                        if (m.get(k) != null) {
                            row.put(k, m.get(k));
                        }
                    }
                    items.add(row);
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ws", ws);
            out.put("q", q);
            out.put("count", items.size());
            out.put("total", page == null ? items.size() : page.get("total"));
            out.put("items", items);
            out.put("href", "/security");
            out.put("note", "脱敏投影只读；不含密钥");
            return out;
        } catch (Exception e) {
            return Map.of("error", StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()),
                    "href", "/security");
        }
    }

    private Map<String, Object> securityOverview(String ws) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ws", ws);
        try {
            out.put("overview", secBoardService.overview(ws));
        } catch (Exception e) {
            out.put("overviewError", e.getMessage());
        }
        try {
            out.put("classification", secBoardService.classification(ws));
        } catch (Exception e) {
            out.put("classificationError", e.getMessage());
        }
        out.put("href", "/security");
        return out;
    }

    private Map<String, Object> listMetaDrifts(String ws, int topN) {
        int n = Math.max(1, Math.min(topN, MAX_ITEMS));
        try {
            List<Map<String, Object>> raw = govAssetService.listMetaDrifts(ws, n);
            List<Map<String, Object>> items = new ArrayList<>();
            if (raw != null) {
                for (Map<String, Object> d : raw) {
                    if (d == null || items.size() >= n) {
                        break;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (String k : List.of("id", "assetId", "assetCode", "assetName", "name", "ws",
                            "driftType", "status", "summary", "message", "detectedAt", "updateTime")) {
                        if (d.containsKey(k) && d.get(k) != null) {
                            row.put(k, d.get(k));
                        }
                    }
                    if (row.isEmpty()) {
                        row.putAll(d);
                    }
                    items.add(row);
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ws", ws);
            out.put("count", items.size());
            out.put("items", items);
            out.put("href", "/catalog");
            out.put("note", "未关闭漂移；详情见资产目录");
            return out;
        } catch (Exception e) {
            return Map.of("error", StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()),
                    "href", "/catalog");
        }
    }

    private Map<String, Object> listReleases(String ws, int topN) {
        int n = Math.max(1, Math.min(topN, MAX_ITEMS));
        try {
            List<Map<String, Object>> raw = cpDevelopService.releases(ws);
            List<Map<String, Object>> items = new ArrayList<>();
            if (raw != null) {
                for (Map<String, Object> r : raw) {
                    if (r == null || items.size() >= n) {
                        break;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (String k : List.of("id", "pkg", "tag", "gitTag", "env", "result", "status",
                            "script", "scriptId", "engine", "time", "applyTicketNo", "prNumber", "prState")) {
                        if (r.containsKey(k) && r.get(k) != null) {
                            row.put(k, r.get(k));
                        }
                    }
                    Object gates = r.get("gates");
                    if (gates instanceof List<?> gl) {
                        row.put("gateCount", gl.size());
                        long blocked = gl.stream().filter(g -> {
                            if (!(g instanceof Map<?, ?> gm)) {
                                return false;
                            }
                            return "fail".equalsIgnoreCase(String.valueOf(gm.get("status")))
                                    || Boolean.TRUE.equals(gm.get("blocked"));
                        }).count();
                        row.put("blockedGateCount", blocked);
                    }
                    items.add(row);
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ws", ws);
            out.put("count", items.size());
            out.put("items", items);
            out.put("href", "/publish");
            return out;
        } catch (Exception e) {
            return Map.of("error", StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()),
                    "href", "/publish");
        }
    }

    private Map<String, Object> releasePrecheck(String scriptId, String engine, String env) {
        if (StrUtil.isBlank(scriptId)) {
            return Map.of("error", "scriptId_required");
        }
        try {
            Map<String, Object> raw = cpDevelopService.releasePrecheck(scriptId, engine, env);
            Map<String, Object> out = new LinkedHashMap<>();
            if (raw != null) {
                out.putAll(raw);
            }
            out.put("href", "/publish");
            out.put("note", "只读预检，不创建发布单");
            return out;
        } catch (Exception e) {
            return Map.of("error", StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()),
                    "href", "/publish");
        }
    }

    private Map<String, Object> listDevScripts(String ws, String q, int topN) {
        int n = Math.max(1, Math.min(topN, MAX_ITEMS));
        try {
            Map<String, Object> tree = cpDevelopService.tree(ws);
            Object nodesObj = tree == null ? null : tree.get("nodes");
            List<Map<String, Object>> items = new ArrayList<>();
            int fileTotal = 0;
            if (nodesObj instanceof List<?> nodes) {
                for (Object o : nodes) {
                    if (!(o instanceof Map<?, ?> m)) {
                        continue;
                    }
                    if (!"file".equals(String.valueOf(m.get("type")))) {
                        continue;
                    }
                    String name = String.valueOf(m.get("name") == null ? "" : m.get("name"));
                    String path = String.valueOf(m.get("path") == null ? "" : m.get("path"));
                    String id = String.valueOf(m.get("id") == null ? "" : m.get("id"));
                    if (StrUtil.isNotBlank(q)) {
                        String kw = q.trim();
                        if (!StrUtil.containsIgnoreCase(name, kw) && !StrUtil.containsIgnoreCase(path, kw)
                                && !StrUtil.containsIgnoreCase(id, kw)) {
                            continue;
                        }
                    }
                    fileTotal++;
                    if (items.size() >= n) {
                        continue;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("scriptId", m.get("id"));
                    row.put("name", m.get("name"));
                    row.put("path", m.get("path"));
                    row.put("status", m.get("status"));
                    row.put("engine", m.get("engine"));
                    row.put("env", m.get("env"));
                    row.put("version", m.get("version"));
                    row.put("author", m.get("author"));
                    row.put("editedAt", m.get("editedAt"));
                    items.add(row);
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ws", tree == null ? ws : tree.getOrDefault("ws", ws));
            out.put("q", q);
            out.put("count", items.size());
            out.put("fileTotal", fileTotal);
            out.put("items", items);
            out.put("href", "/develop");
            out.put("note", "不含 SQL 正文；上版预检请用 release_precheck(scriptId)");
            return out;
        } catch (Exception e) {
            return Map.of("error", StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()),
                    "href", "/develop");
        }
    }

    private Map<String, Object> quotaAlerts(String ws) {
        try {
            List<Map<String, Object>> raw = govWsService.listQuotaAlerts();
            List<Map<String, Object>> items = new ArrayList<>();
            if (raw != null) {
                for (Map<String, Object> a : raw) {
                    if (a == null) {
                        continue;
                    }
                    if (StrUtil.isNotBlank(ws)) {
                        String code = String.valueOf(a.getOrDefault("wsCode", ""));
                        if (!ws.equalsIgnoreCase(code)) {
                            continue;
                        }
                    }
                    items.add(a);
                    if (items.size() >= MAX_ITEMS) {
                        break;
                    }
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ws", ws);
            out.put("count", items.size());
            out.put("items", items);
            out.put("href", "/workspace");
            out.put("note", "仅 ≥60% 占用会入列；无告警时 items 为空属正常");
            return out;
        } catch (Exception e) {
            return Map.of("error", StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()),
                    "href", "/workspace");
        }
    }

    private Map<String, Object> lifecycleOverview(String ws) {
        try {
            Map<String, Object> raw = govLcService.overview(ws);
            Map<String, Object> out = new LinkedHashMap<>();
            if (raw != null) {
                out.putAll(raw);
            }
            out.put("href", "/lifecycle");
            // 小文件/快照膨胀 → 深链主台带 table=
            try {
                List<Map<String, Object>> top = govLcService.topStorage(ws, 5);
                List<Map<String, Object>> hints = new ArrayList<>();
                if (top != null) {
                    for (Map<String, Object> row : top) {
                        String fqn = String.valueOf(row.getOrDefault("fqtn",
                                row.getOrDefault("tableFqn", "")));
                        if (StrUtil.isBlank(fqn) || "null".equals(fqn)) {
                            continue;
                        }
                        String action = String.valueOf(row.getOrDefault("suggestedAction", "catalog"));
                        if (!"compact".equals(action) && !"expire".equals(action)) {
                            Object ratio = row.get("smallFileRatio");
                            if (ratio instanceof Number n && n.doubleValue() > 30) {
                                action = "compact";
                            } else if (Boolean.TRUE.equals(row.get("anomaly"))) {
                                action = "expire";
                            } else {
                                continue;
                            }
                        }
                        Map<String, Object> h = new LinkedHashMap<>();
                        h.put("table", fqn);
                        h.put("action", action);
                        h.put("href", "/lifecycle?table=" + fqn + "&action=" + action + "&from=ai");
                        hints.add(h);
                    }
                }
                out.put("diagnoseHints", hints);
                if (!hints.isEmpty()) {
                    out.put("hrefTable", hints.get(0).get("href"));
                }
            } catch (Exception ignored) {
                out.put("diagnoseHints", List.of());
            }
            return out;
        } catch (Exception e) {
            return Map.of("error", StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()),
                    "href", "/lifecycle");
        }
    }

    private Map<String, Object> complianceSummary(String ws) {
        try {
            Map<String, Object> raw = govDelService.summary(ws);
            Map<String, Object> out = new LinkedHashMap<>();
            if (raw != null) {
                out.putAll(raw);
            }
            out.put("href", "/compliance");
            return out;
        } catch (Exception e) {
            return Map.of("error", StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()),
                    "href", "/compliance");
        }
    }

    private Map<String, Object> exportAudit(String ws, String ticketNo, int topN) {
        int n = Math.max(1, Math.min(topN, MAX_ITEMS));
        try {
            Map<String, Object> raw = exportBoardService.audit(ws, ticketNo);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ws", raw == null ? ws : raw.getOrDefault("ws", ws));
            if (raw != null) {
                out.put("ok", raw.get("ok"));
                out.put("source", raw.get("source"));
                out.put("hint", raw.get("hint"));
                Object linesObj = raw.get("lines");
                List<Map<String, Object>> lines = new ArrayList<>();
                if (linesObj instanceof List<?> list) {
                    for (Object o : list) {
                        if (!(o instanceof Map<?, ?> m) || lines.size() >= n) {
                            break;
                        }
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (String k : List.of("time", "ticketNo", "eventType", "src", "target",
                                "purpose", "approver", "status", "dagCode", "message")) {
                            if (m.get(k) != null) {
                                row.put(k, m.get(k));
                            }
                        }
                        if (row.isEmpty()) {
                            for (Map.Entry<?, ?> e : m.entrySet()) {
                                if (e.getKey() != null && e.getValue() != null) {
                                    row.put(String.valueOf(e.getKey()), e.getValue());
                                }
                            }
                        }
                        lines.add(row);
                    }
                }
                out.put("count", lines.size());
                out.put("total", raw.getOrDefault("count", lines.size()));
                out.put("lines", lines);
            } else {
                out.put("count", 0);
                out.put("lines", List.of());
            }
            out.put("ticketNo", ticketNo);
            out.put("href", "/export");
            return out;
        } catch (Exception e) {
            return Map.of("error", StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()),
                    "href", "/export");
        }
    }

    private Map<String, Object> listSavedQueries(String ws, int topN) {
        int n = Math.max(1, Math.min(topN, MAX_ITEMS));
        try {
            List<Map<String, Object>> raw = cpQueryService.listSavedScripts(ws, n);
            List<Map<String, Object>> items = new ArrayList<>();
            if (raw != null) {
                for (Map<String, Object> s : raw) {
                    if (s == null || items.size() >= n) {
                        break;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", s.get("id"));
                    row.put("name", s.get("name"));
                    row.put("ws", s.get("ws"));
                    row.put("sqlSummary", s.get("sqlSummary"));
                    row.put("engine", s.get("engine"));
                    row.put("status", s.get("status"));
                    row.put("updateTime", s.get("updateTime"));
                    Object sql = s.get("sql");
                    if (sql != null) {
                        row.put("sqlPreview", StrUtil.maxLength(String.valueOf(sql), 200));
                    }
                    items.add(row);
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ws", ws);
            out.put("count", items.size());
            out.put("items", items);
            out.put("href", "/query");
            out.put("note", "仅本人保存的查询；不含完整长 SQL");
            return out;
        } catch (Exception e) {
            return Map.of("error", StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()),
                    "href", "/query");
        }
    }

    private Map<String, Object> contractOverview(String ws) {
        try {
            Map<String, Object> raw = contractService.overview(ws);
            Map<String, Object> out = new LinkedHashMap<>();
            if (raw != null) {
                out.putAll(raw);
            }
            out.put("href", "/contract");
            return out;
        } catch (Exception e) {
            return Map.of("error", StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()),
                    "href", "/contract");
        }
    }

    private Map<String, Object> aimodelOverview(String ws) {
        try {
            Map<String, Object> raw = govAiModelService.overview(ws);
            Map<String, Object> out = new LinkedHashMap<>();
            if (raw != null) {
                // 不回传可能敏感的内部路径细节以外的字段；overview 本身无 Key
                out.putAll(raw);
            }
            out.put("href", "/aimodel");
            out.put("note", "不含模型 Key；配额告警请用 quota_alerts");
            return out;
        } catch (Exception e) {
            return Map.of("error", StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()),
                    "href", "/aimodel");
        }
    }

    private Map<String, Object> listUdfs(String engine, int topN) {
        int n = Math.max(1, Math.min(topN, MAX_ITEMS));
        try {
            List<Map<String, Object>> raw = cpDevelopService.udfs(engine);
            List<Map<String, Object>> items = new ArrayList<>();
            if (raw != null) {
                for (Map<String, Object> u : raw) {
                    if (u == null || items.size() >= n) {
                        break;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", u.get("name"));
                    row.put("desc", u.get("desc"));
                    row.put("engine", u.get("engine"));
                    row.put("engines", u.get("engines"));
                    row.put("ver", u.get("ver"));
                    row.put("uses", u.get("uses"));
                    Object snip = u.get("snippet");
                    if (snip != null) {
                        row.put("snippet", StrUtil.maxLength(String.valueOf(snip), 160));
                    }
                    items.add(row);
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("engine", engine);
            out.put("count", items.size());
            out.put("total", raw == null ? items.size() : raw.size());
            out.put("items", items);
            out.put("href", "/develop");
            return out;
        } catch (Exception e) {
            return Map.of("error", StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()),
                    "href", "/develop");
        }
    }

    private Map<String, Object> kbOverview(String ws, String scope) {
        try {
            Map<String, Object> raw = StrUtil.isNotBlank(scope)
                    ? govKbService.overview(ws, scope)
                    : govKbService.overview(ws);
            Map<String, Object> out = new LinkedHashMap<>();
            if (raw != null) {
                out.putAll(raw);
            }
            out.put("href", "/knowledge");
            out.put("note", "KPI 总览；按词检索请用 kb_search");
            return out;
        } catch (Exception e) {
            return Map.of("error", StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()),
                    "href", "/knowledge");
        }
    }

    private static Map<String, Object> assetRow(GovAsset a, String access) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", a.getId());
        row.put("assetCode", a.getAssetCode());
        row.put("name", StrUtil.blankToDefault(a.getCnName(), a.getName()));
        row.put("ws", a.getWs());
        row.put("layer", a.getLayer());
        row.put("access", access);
        return row;
    }

    private static Map<String, Object> fn(String name, String description, Map<String, Object> parameters) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", name);
        function.put("description", description);
        function.put("parameters", parameters);
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("type", "function");
        tool.put("function", function);
        return tool;
    }

    private static String truncateJson(Object result) {
        String json = JSONUtil.toJsonStr(result);
        if (json.length() <= MAX_TEXT) {
            return json;
        }
        return JSONUtil.toJsonStr(Map.of(
                "truncated", true,
                "preview", StrUtil.maxLength(json, MAX_TEXT - 80)));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o).trim();
    }

    private static int intArg(Object o, int def) {
        if (o == null) {
            return def;
        }
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (Exception e) {
            return def;
        }
    }
}
