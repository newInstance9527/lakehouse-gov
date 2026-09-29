package vip.xiaonuo.lh.modular.etl.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.xiaonuo.common.enums.CommonSortOrderEnum;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.common.page.CommonPageRequest;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.core.auth.LhLoginUsers;
import vip.xiaonuo.lh.core.engine.DsClient;
import vip.xiaonuo.lh.core.engine.DsWorkflowBuilder;
import vip.xiaonuo.lh.core.engine.EngineResolver;
import vip.xiaonuo.lh.core.engine.IgEtlNodeTypes;
import vip.xiaonuo.lh.modular.etl.entity.CbEtlDeployWatermark;
import vip.xiaonuo.lh.modular.etl.entity.IgEtlDag;
import vip.xiaonuo.lh.modular.etl.entity.IgEtlEdge;
import vip.xiaonuo.lh.modular.etl.entity.IgEtlNode;
import vip.xiaonuo.lh.modular.etl.entity.IgEtlRun;
import vip.xiaonuo.lh.modular.etl.entity.IgEtlRunNode;
import vip.xiaonuo.lh.modular.etl.mapper.CbEtlDeployWatermarkMapper;
import vip.xiaonuo.lh.modular.etl.mapper.IgEtlDagMapper;
import vip.xiaonuo.lh.modular.etl.mapper.IgEtlEdgeMapper;
import vip.xiaonuo.lh.modular.etl.mapper.IgEtlNodeMapper;
import vip.xiaonuo.lh.modular.etl.mapper.IgEtlRunMapper;
import vip.xiaonuo.lh.modular.etl.mapper.IgEtlRunNodeMapper;
import vip.xiaonuo.lh.modular.etl.param.IgEtlBackfillParam;
import vip.xiaonuo.lh.modular.etl.param.IgEtlDagAddParam;
import vip.xiaonuo.lh.modular.etl.param.IgEtlDagEditParam;
import vip.xiaonuo.lh.modular.etl.param.IgEtlDeployParam;
import vip.xiaonuo.lh.modular.etl.param.IgEtlEngineResolveParam;
import vip.xiaonuo.lh.modular.etl.param.IgEtlGraphSaveParam;
import vip.xiaonuo.lh.modular.etl.param.IgEtlIdParam;
import vip.xiaonuo.lh.modular.etl.param.IgEtlNodeConfigParam;
import vip.xiaonuo.lh.modular.etl.param.IgEtlPageParam;
import vip.xiaonuo.lh.modular.etl.param.IgEtlTrialParam;
import vip.xiaonuo.lh.modular.etl.service.IgEtlService;
import vip.xiaonuo.lh.modular.etl.support.IgEtlLineageSyncHelper;
import vip.xiaonuo.lh.modular.etl.support.IgEtlLocalTrialExecutor;
import vip.xiaonuo.lh.modular.etl.support.IgEtlOpenLineageProjector;
import vip.xiaonuo.lh.modular.etl.support.IgEtlPublishSideEffects;
import vip.xiaonuo.lh.modular.etl.support.IgEtlRunAlertBuilder;
import vip.xiaonuo.lh.modular.etl.support.IgEtlRunResultPreview;
import vip.xiaonuo.lh.modular.etl.support.IgEtlSinkTargetChecker;
import vip.xiaonuo.lh.modular.contract.service.ContractService;
import vip.xiaonuo.lh.modular.etl.support.IgEtlVaultInjector;
import vip.xiaonuo.lh.modular.apply.service.ApplyTicketService;
import vip.xiaonuo.lh.modular.compliance.support.GovDelProcessingGate;
import vip.xiaonuo.lh.modular.sec.service.SecAuthGrantService;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * ETL 编排：门户图 SoT；试跑/发布经 EngineResolver + DS soft-fail
 *
 * @author lakehouse
 * @date 2026/9/19
 */
@Service
public class IgEtlServiceImpl implements IgEtlService {

    private static final String WS_DEFAULT = "default";
    private static final Set<String> SECRET_KEYS = Set.of(
            "password", "authPass", "authToken", "apiKey", "accessKey", "token", "secret");

    @Resource
    private IgEtlDagMapper dagMapper;
    @Resource
    private IgEtlNodeMapper nodeMapper;
    @Resource
    private IgEtlEdgeMapper edgeMapper;
    @Resource
    private IgEtlRunMapper runMapper;
    @Resource
    private IgEtlRunNodeMapper runNodeMapper;
    @Resource
    private CbEtlDeployWatermarkMapper watermarkMapper;
    @Resource
    private EngineResolver engineResolver;
    @Resource
    private DsClient dsClient;
    @Resource
    private IgEtlPublishSideEffects publishSideEffects;
    @Resource
    private IgEtlVaultInjector vaultInjector;
    @Resource
    private IgEtlSinkTargetChecker sinkTargetChecker;
    @Resource
    private IgEtlRunResultPreview resultPreviewHelper;
    @Resource
    private IgEtlOpenLineageProjector openLineageProjector;
    @Resource
    private IgEtlRunAlertBuilder runAlertBuilder;
    @Resource
    private IgEtlLineageSyncHelper lineageSyncHelper;
    @Resource
    private IgEtlLocalTrialExecutor localTrialExecutor;
    @Resource
    private ApplyTicketService applyTicketService;
    @Resource
    private GovDelProcessingGate govDelProcessingGate;
    @Resource
    private SecAuthGrantService secAuthGrantService;
    @Resource
    private vip.xiaonuo.lh.core.user.LhUserNameResolver userNameResolver;
    @Resource
    private vip.xiaonuo.lh.modular.observability.service.LhObsSpanService lhObsSpanService;
    @Resource
    private LhProperties lhProperties;
    @Resource
    private ContractService contractService;

    @Override
    public Page<Map<String, Object>> pageDags(IgEtlPageParam param) {
        QueryWrapper<IgEtlDag> qw = new QueryWrapper<>();
        // 空间优先：缺省 scope=workspace；all=特权巡检
        String scope = StrUtil.blankToDefault(StrUtil.trim(param.getScope()), "workspace").toLowerCase(Locale.ROOT);
        String ws = StrUtil.trim(param.getWs());
        if (!"all".equals(scope)) {
            qw.lambda().eq(IgEtlDag::getWs, StrUtil.blankToDefault(ws, WS_DEFAULT));
        }
        if (StrUtil.isNotBlank(param.getStatus())) {
            qw.lambda().eq(IgEtlDag::getStatus, param.getStatus());
        }
        String q = StrUtil.blankToDefault(param.getQ(), param.getKeyword());
        if (StrUtil.isNotBlank(q)) {
            qw.and(w -> w.like("name", q).or().like("dag_code", q)
                    .or().like("description", q).or().like("owner", q)
                    .or().like("default_engine", q));
        }
        if (StrUtil.isNotBlank(param.getSortField())) {
            CommonSortOrderEnum.validate(param.getSortOrder());
            qw.orderBy(true, CommonSortOrderEnum.ASC.getValue().equalsIgnoreCase(param.getSortOrder()),
                    StrUtil.toUnderlineCase(param.getSortField()));
        } else {
            qw.lambda().orderByDesc(IgEtlDag::getUpdateTime).orderByDesc(IgEtlDag::getCreateTime);
        }
        Page<IgEtlDag> page = dagMapper.selectPage(CommonPageRequest.defaultPage(), qw);
        Page<Map<String, Object>> out = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        List<Map<String, Object>> briefs = page.getRecords().stream().map(this::dagBriefRaw).collect(Collectors.toList());
        userNameResolver.fillDagBriefs(briefs);
        out.setRecords(briefs);
        return out;
    }

    @Override
    public Map<String, Object> detail(String id) {
        IgEtlDag dag = requireDag(id);
        Map<String, Object> m = dagBrief(dag);
        long nodeCnt = nodeMapper.selectCount(new QueryWrapper<IgEtlNode>().lambda().eq(IgEtlNode::getDagId, id));
        long edgeCnt = edgeMapper.selectCount(new QueryWrapper<IgEtlEdge>().lambda().eq(IgEtlEdge::getDagId, id));
        m.put("nodeCount", nodeCnt);
        m.put("edgeCount", edgeCnt);
        return m;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> addDag(IgEtlDagAddParam param) {
        String ws = StrUtil.blankToDefault(param.getWs(), WS_DEFAULT);
        String code = param.getDagCode().trim();
        Long exist = dagMapper.selectCount(new QueryWrapper<IgEtlDag>().lambda()
                .eq(IgEtlDag::getWs, ws).eq(IgEtlDag::getDagCode, code));
        if (exist != null && exist > 0) {
            throw new CommonException("dagCode 已存在: " + code);
        }
        IgEtlDag dag = new IgEtlDag();
        dag.setId(IdUtil.getSnowflakeNextIdStr());
        dag.setRevision(1);
        dag.setWs(ws);
        dag.setDagCode(code);
        dag.setName(param.getName());
        dag.setDescription(param.getDescription());
        dag.setCron(StrUtil.blankToDefault(param.getCron(), "0 2 * * *"));
        String userId = LhLoginUsers.requireUserId();
        dag.setOwner(StrUtil.blankToDefault(param.getOwner(), userId));
        dag.setCreateUser(userId);
        dag.setStatus("draft");
        dag.setVer("v0.1");
        dag.setEnv(StrUtil.blankToDefault(param.getEnv(), "dev"));
        dag.setDefaultEngine(StrUtil.blankToDefault(param.getDefaultEngine(), "flink").toLowerCase());
        dag.setSla(param.getSla());
        dagMapper.insert(dag);
        return dagBrief(dag);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> editDag(IgEtlDagEditParam param) {
        IgEtlDag dag = requireDag(param.getId());
        assertCanEditDag(dag);
        String prevStatus = dag.getStatus();
        if (StrUtil.isNotBlank(param.getName())) {
            dag.setName(param.getName());
        }
        if (param.getDescription() != null) {
            dag.setDescription(param.getDescription());
        }
        if (param.getCron() != null) {
            dag.setCron(param.getCron());
        }
        if (param.getOwner() != null) {
            dag.setOwner(param.getOwner());
        }
        if (StrUtil.isNotBlank(param.getDefaultEngine())) {
            dag.setDefaultEngine(param.getDefaultEngine().toLowerCase());
        }
        if (param.getSla() != null) {
            dag.setSla(param.getSla());
        }
        if (StrUtil.isNotBlank(param.getEnv())) {
            dag.setEnv(param.getEnv());
        }
        Map<String, Object> dsSchedule = null;
        if (StrUtil.isNotBlank(param.getStatus())) {
            if (!Set.of("draft", "prod", "paused").contains(param.getStatus())) {
                throw new CommonException("非法 status: " + param.getStatus());
            }
            dag.setStatus(param.getStatus());
            // D1：prod ↔ paused 同步 DS release；draft 仅门户态
            if (!StrUtil.equals(prevStatus, param.getStatus())
                    && ("prod".equals(param.getStatus()) || "paused".equals(param.getStatus()))) {
                String release = "paused".equals(param.getStatus()) ? "OFFLINE" : "ONLINE";
                dsSchedule = dsClient.setScheduleState(dag.getDsWorkflowCode(), release);
            }
        }
        dag.setRevision(dag.getRevision() == null ? 1 : dag.getRevision() + 1);
        dagMapper.updateById(dag);
        Map<String, Object> brief = dagBrief(dag);
        if (dsSchedule != null) {
            brief.put("dsSchedule", dsSchedule);
        }
        return brief;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> deleteDag(IgEtlIdParam param) {
        IgEtlDag dag = requireDag(param.getId());
        assertCanDeleteDag(dag);
        Long running = runMapper.selectCount(new QueryWrapper<IgEtlRun>().lambda()
                .eq(IgEtlRun::getDagId, dag.getId())
                .in(IgEtlRun::getStatus, "running", "submitted", "pending"));
        if (running != null && running > 0) {
            throw new CommonException("任务运行中，请等待完成或终止后再删除");
        }
        Map<String, Object> dsSchedule = null;
        if (StrUtil.isNotBlank(dag.getDsWorkflowCode())
                && ("prod".equals(dag.getStatus()) || "paused".equals(dag.getStatus()))) {
            dsSchedule = dsClient.setScheduleState(dag.getDsWorkflowCode(), "OFFLINE");
        }
        // 级联软删节点 / 边（保留运行记录作审计）
        List<IgEtlNode> nodes = nodeMapper.selectList(new QueryWrapper<IgEtlNode>().lambda()
                .eq(IgEtlNode::getDagId, dag.getId()));
        for (IgEtlNode n : nodes) {
            nodeMapper.deleteById(n.getId());
        }
        List<IgEtlEdge> edges = edgeMapper.selectList(new QueryWrapper<IgEtlEdge>().lambda()
                .eq(IgEtlEdge::getDagId, dag.getId()));
        for (IgEtlEdge e : edges) {
            edgeMapper.deleteById(e.getId());
        }
        // 释放 uq_ig_etl_dag(ws,dag_code)，便于同编码重建
        String freed = truncateKey(dag.getDagCode() + "__del_" + dag.getId(), 128);
        dag.setDagCode(freed);
        dag.setStatus("draft");
        dag.setRevision(dag.getRevision() == null ? 1 : dag.getRevision() + 1);
        dagMapper.updateById(dag);
        dagMapper.deleteById(dag.getId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", dag.getId());
        out.put("deleted", true);
        out.put("nodeCount", nodes.size());
        out.put("edgeCount", edges.size());
        if (dsSchedule != null) {
            out.put("dsSchedule", dsSchedule);
        }
        return out;
    }

    @Override
    public Map<String, Object> graph(String id) {
        IgEtlDag dag = requireDag(id);
        List<IgEtlNode> nodes = nodeMapper.selectList(new QueryWrapper<IgEtlNode>().lambda()
                .eq(IgEtlNode::getDagId, id).orderByAsc(IgEtlNode::getNodeKey));
        List<IgEtlEdge> edges = edgeMapper.selectList(new QueryWrapper<IgEtlEdge>().lambda()
                .eq(IgEtlEdge::getDagId, id).orderByAsc(IgEtlEdge::getSortNo));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dag", dagBrief(dag));
        m.put("nodes", nodes.stream().map(this::nodeVo).collect(Collectors.toList()));
        m.put("edges", edges.stream().map(this::edgeVo).collect(Collectors.toList()));
        return m;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> saveGraph(IgEtlGraphSaveParam param) {
        IgEtlDag dag = requireDag(param.getId());
        assertCanEditDag(dag);
        List<Map<String, Object>> nodeMaps = param.getNodes() == null ? List.of() : param.getNodes();
        List<Map<String, Object>> edgeMaps = param.getEdges() == null ? List.of() : param.getEdges();

        Set<String> incomingKeys = new HashSet<>();
        for (Map<String, Object> n : nodeMaps) {
            String key = str(n.get("id"), str(n.get("nodeKey"), null));
            String type = str(n.get("type"), str(n.get("nodeType"), null));
            if (StrUtil.isBlank(key) || StrUtil.isBlank(type)) {
                throw new CommonException("节点缺少 id/type");
            }
            if (!IgEtlNodeTypes.isKnown(type)) {
                throw new CommonException("未知节点类型: " + type);
            }
            if (!incomingKeys.add(key)) {
                throw new CommonException("重复 nodeKey: " + key);
            }
        }
        // 边去重：(from,to,label) 与 uq_ig_etl_edge 对齐，保留首次出现顺序
        List<Map<String, Object>> uniqueEdges = new ArrayList<>();
        Set<String> edgeSeen = new HashSet<>();
        for (Map<String, Object> e : edgeMaps) {
            String from = str(e.get("from"), str(e.get("fromNodeKey"), null));
            String to = str(e.get("to"), str(e.get("toNodeKey"), null));
            String label = StrUtil.blankToDefault(str(e.get("label"), null), "");
            if (StrUtil.isBlank(from) || StrUtil.isBlank(to)) {
                throw new CommonException("边缺少 from/to");
            }
            if (!incomingKeys.contains(from) || !incomingKeys.contains(to)) {
                throw new CommonException("边端点不在节点集合: " + from + " -> " + to);
            }
            if (from.equals(to)) {
                throw new CommonException("禁止自环: " + from);
            }
            String ek = from + "\0" + to + "\0" + label;
            if (edgeSeen.add(ek)) {
                uniqueEdges.add(e);
            }
        }

        List<IgEtlNode> oldNodes = nodeMapper.selectList(new QueryWrapper<IgEtlNode>().lambda()
                .eq(IgEtlNode::getDagId, dag.getId()));
        Map<String, IgEtlNode> oldByKey = oldNodes.stream()
                .collect(Collectors.toMap(IgEtlNode::getNodeKey, x -> x, (a, b) -> a));
        for (IgEtlNode old : oldNodes) {
            if (!incomingKeys.contains(old.getNodeKey())) {
                // 物理删：软删仍占 uq_ig_etl_node(dag_id,node_key)
                nodeMapper.physicalDeleteById(old.getId());
            }
        }
        for (Map<String, Object> n : nodeMaps) {
            String key = str(n.get("id"), str(n.get("nodeKey"), null));
            String type = str(n.get("type"), str(n.get("nodeType"), null));
            IgEtlNode entity = oldByKey.getOrDefault(key, new IgEtlNode());
            boolean insert = entity.getId() == null;
            if (insert) {
                // 清掉同 key 软删残留，避免唯一键冲突
                nodeMapper.physicalDeleteByDagAndKey(dag.getId(), key);
                entity.setId(IdUtil.getSnowflakeNextIdStr());
                entity.setDagId(dag.getId());
                entity.setNodeKey(key);
            }
            entity.setNodeType(type);
            entity.setName(str(n.get("name"), key));
            entity.setMeta(str(n.get("meta"), null));
            entity.setPosX(intVal(n.get("x"), intVal(n.get("posX"), 0)));
            entity.setPosY(intVal(n.get("y"), intVal(n.get("posY"), 0)));
            String confJson = sanitizeConfJson(n.get("conf"), n.get("confJson"));
            entity.setConfJson(confJson);
            try {
                Map<String, Object> resolved = engineResolver.resolve(type, confJson);
                entity.setResolvedEngine(String.valueOf(resolved.get("engine")));
            } catch (Exception ignored) {
                entity.setResolvedEngine(null);
            }
            if (insert) {
                nodeMapper.insert(entity);
            } else {
                nodeMapper.updateById(entity);
            }
        }

        // 全量替换边：必须物理删，软删会与 uq_ig_etl_edge 冲突
        edgeMapper.physicalDeleteByDagId(dag.getId());
        int sort = 0;
        for (Map<String, Object> e : uniqueEdges) {
            IgEtlEdge edge = new IgEtlEdge();
            edge.setId(IdUtil.getSnowflakeNextIdStr());
            edge.setDagId(dag.getId());
            edge.setFromNodeKey(str(e.get("from"), str(e.get("fromNodeKey"), null)));
            edge.setToNodeKey(str(e.get("to"), str(e.get("toNodeKey"), null)));
            edge.setLabel(StrUtil.blankToDefault(str(e.get("label"), null), ""));
            edge.setSortNo(++sort);
            edgeMapper.insert(edge);
        }

        if (!"prod".equals(dag.getStatus())) {
            dag.setStatus("draft");
        }
        dag.setRevision(dag.getRevision() == null ? 1 : dag.getRevision() + 1);
        dagMapper.updateById(dag);
        return graph(dag.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> updateNodeConfig(IgEtlNodeConfigParam param) {
        assertCanEditDag(requireDag(param.getDagId()));
        IgEtlNode node = nodeMapper.selectOne(new QueryWrapper<IgEtlNode>().lambda()
                .eq(IgEtlNode::getDagId, param.getDagId())
                .eq(IgEtlNode::getNodeKey, param.getNodeKey()));
        if (node == null) {
            throw new CommonException("节点不存在");
        }
        if (param.getName() != null) {
            node.setName(param.getName());
        }
        if (param.getMeta() != null) {
            node.setMeta(param.getMeta());
        }
        String confJson = sanitizeConfJson(param.getConf(), param.getConfJson());
        if (confJson != null) {
            node.setConfJson(confJson);
            try {
                Map<String, Object> resolved = engineResolver.resolve(node.getNodeType(), confJson);
                node.setResolvedEngine(String.valueOf(resolved.get("engine")));
            } catch (Exception ex) {
                throw new CommonException(ex.getMessage());
            }
        }
        nodeMapper.updateById(node);
        return nodeVo(node);
    }

    @Override
    public Map<String, Object> validate(IgEtlIdParam param) {
        IgEtlDag dag = requireDag(param.getId());
        List<IgEtlNode> nodes = nodeMapper.selectList(new QueryWrapper<IgEtlNode>().lambda()
                .eq(IgEtlNode::getDagId, dag.getId()));
        List<IgEtlEdge> edges = edgeMapper.selectList(new QueryWrapper<IgEtlEdge>().lambda()
                .eq(IgEtlEdge::getDagId, dag.getId()));
        List<Map<String, Object>> issues = new ArrayList<>();

        long sources = nodes.stream().filter(n -> IgEtlNodeTypes.SOURCE.contains(n.getNodeType())).count();
        long sinks = nodes.stream().filter(n -> IgEtlNodeTypes.SINK.contains(n.getNodeType())).count();
        if (sources < 1) {
            issues.add(issue("graph", null, "error", "至少需要 1 个源节点"));
        }
        if (sinks < 1) {
            issues.add(issue("graph", null, "error", "至少需要 1 个汇节点"));
        }

        Set<String> keys = nodes.stream().map(IgEtlNode::getNodeKey).collect(Collectors.toSet());
        for (IgEtlEdge e : edges) {
            if (!keys.contains(e.getFromNodeKey()) || !keys.contains(e.getToNodeKey())) {
                issues.add(issue("edge", e.getFromNodeKey() + "->" + e.getToNodeKey(), "error", "边端点不存在"));
            }
            if (StrUtil.equals(e.getFromNodeKey(), e.getToNodeKey())) {
                issues.add(issue("edge", e.getFromNodeKey(), "error", "禁止自环"));
            }
        }
        if (hasCycle(nodes, edges)) {
            issues.add(issue("graph", null, "error", "图中存在环"));
        }

        Set<String> connected = new HashSet<>();
        for (IgEtlEdge e : edges) {
            connected.add(e.getFromNodeKey());
            connected.add(e.getToNodeKey());
        }
        if (nodes.size() > 1) {
            for (IgEtlNode n : nodes) {
                if (!connected.contains(n.getNodeKey())) {
                    issues.add(issue("node", n.getNodeKey(), "warn", "孤立节点"));
                }
            }
        }

        for (IgEtlNode n : nodes) {
            issues.addAll(validateNodeConf(n));
        }

        boolean ok = issues.stream().noneMatch(i -> "error".equals(i.get("level")));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", ok);
        r.put("dagId", dag.getId());
        r.put("issueCount", issues.size());
        r.put("issues", issues);
        if (!ok) {
            r.put("message", formatValidateMessage(issues));
        }
        return r;
    }

    private void requireValidateOk(Map<String, Object> v, String action) {
        if (Boolean.TRUE.equals(v.get("ok"))) {
            return;
        }
        String detail = str(v.get("message"), null);
        if (StrUtil.isBlank(detail) && v.get("issues") instanceof List<?> list) {
            detail = formatValidateMessage(list);
        }
        if (StrUtil.isBlank(detail)) {
            detail = "存在校验错误";
        }
        throw new CommonException(action + "失败：" + detail);
    }

    @SuppressWarnings("unchecked")
    private static String formatValidateMessage(List<?> issues) {
        if (issues == null || issues.isEmpty()) {
            return "存在校验错误";
        }
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Object o : issues) {
            if (!(o instanceof Map<?, ?> m)) {
                continue;
            }
            String level = String.valueOf(m.get("level"));
            if (!"error".equals(level) && !"warn".equals(level)) {
                // 仍展示；优先 error
            }
            if (!"error".equals(level)) {
                continue;
            }
            if (n > 0) {
                sb.append("；");
            }
            Object ref = m.get("ref");
            Object message = m.get("message");
            if (ref != null && StrUtil.isNotBlank(String.valueOf(ref)) && !"null".equals(String.valueOf(ref))) {
                sb.append('[').append(ref).append("] ");
            }
            sb.append(message == null ? "未知问题" : message);
            n++;
            if (n >= 8) {
                sb.append("…");
                break;
            }
        }
        if (n == 0) {
            // 无 error 时（理论不应走到 requireValidateOk）回退全部
            for (Object o : issues) {
                if (!(o instanceof Map<?, ?> m)) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append("；");
                }
                Object ref = m.get("ref");
                Object message = m.get("message");
                if (ref != null && StrUtil.isNotBlank(String.valueOf(ref)) && !"null".equals(String.valueOf(ref))) {
                    sb.append('[').append(ref).append("] ");
                }
                sb.append(message == null ? "未知问题" : message);
            }
        }
        return sb.length() == 0 ? "存在校验错误" : sb.toString();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> trial(IgEtlTrialParam param) {
        assertCanEditDag(requireDag(param.getId()));
        IgEtlIdParam idParam = new IgEtlIdParam();
        idParam.setId(param.getId());
        Map<String, Object> v = validate(idParam);
        requireValidateOk(v, "试跑");
        IgEtlDag dag = requireDag(param.getId());
        String env = StrUtil.blankToDefault(param.getEnv(), "stg");
        List<IgEtlNode> nodes = nodeMapper.selectList(new QueryWrapper<IgEtlNode>().lambda()
                .eq(IgEtlNode::getDagId, dag.getId()));

        String runId = "trial-" + IdUtil.getSnowflakeNextIdStr();
        Date now = new Date();
        IgEtlRun run = new IgEtlRun();
        run.setId(IdUtil.getSnowflakeNextIdStr());
        run.setRunId(runId);
        run.setDagId(dag.getId());
        run.setWs(dag.getWs());
        run.setEnv(env);
        run.setTriggerType("manual");
        run.setStatus("submitted");
        run.setMessage("试跑提交中");
        run.setStartedAt(now);
        run.setCreateTime(now);

        List<Map<String, Object>> plan = new ArrayList<>();
        for (IgEtlNode n : nodes) {
            Map<String, Object> resolved = engineResolver.resolve(n.getNodeType(), n.getConfJson());
            String engine = String.valueOf(resolved.get("engine"));
            n.setResolvedEngine(engine);
            nodeMapper.updateById(n);

            IgEtlRunNode rn = new IgEtlRunNode();
            rn.setId(IdUtil.getSnowflakeNextIdStr());
            rn.setRunId(runId);
            rn.setNodeKey(n.getNodeKey());
            rn.setNodeType(n.getNodeType());
            rn.setStatus("pending");
            rn.setEngine(engine);
            rn.setMessage("rule=" + resolved.get("rule"));
            rn.setCreateTime(now);
            runNodeMapper.insert(rn);

            Map<String, Object> p = new LinkedHashMap<>();
            p.put("nodeKey", n.getNodeKey());
            p.put("nodeType", n.getNodeType());
            p.put("engine", engine);
            p.put("rule", resolved.get("rule"));
            p.put("submitShape", submitShape(engine));
            plan.add(p);
        }

        Map<String, Object> trialWf = DsWorkflowBuilder.build(
                dag, nodes,
                edgeMapper.selectList(new QueryWrapper<IgEtlEdge>().lambda()
                        .eq(IgEtlEdge::getDagId, dag.getId()).orderByAsc(IgEtlEdge::getSortNo)),
                plan,
                "WF_" + dag.getDagCode() + ".trial");
        trialWf.put("env", env);
        trialWf.put("runId", runId);
        trialWf.put("trial", true);
        Map<String, Object> vaultFx = vaultInjector.enrichWorkflow(trialWf, nodes);
        Map<String, Object> dsResp = dsClient.createOrUpdateWorkflow(trialWf);
        String wfCode = str(dsResp.get("workflowCode"), str(trialWf.get("workflowCode"), null));
        run.setDsRunId(wfCode);

        Map<String, Object> qualityFx = publishSideEffects.applyQuality(dag, nodes, runId);
        Map<String, Object> dsStart = null;
        Map<String, Object> localFx = null;

        if (Boolean.TRUE.equals(qualityFx.get("blocked"))) {
            run.setStatus("failed");
            run.setMessage("质量门禁阻断: " + str(qualityFx.get("qualityRunFail"), "0") + " 条规则失败");
            run.setFinishedAt(new Date());
            for (Object o : (List<?>) qualityFx.getOrDefault("qualityNodes", List.of())) {
                if (!(o instanceof Map<?, ?> m)) {
                    continue;
                }
                String nk = String.valueOf(m.get("nodeKey"));
                IgEtlRunNode rn = runNodeMapper.selectOne(new QueryWrapper<IgEtlRunNode>().lambda()
                        .eq(IgEtlRunNode::getRunId, runId).eq(IgEtlRunNode::getNodeKey, nk).last("LIMIT 1"));
                if (rn != null) {
                    rn.setStatus(Boolean.TRUE.equals(m.get("blocked")) ? "blocked" : "failed");
                    rn.setMessage("quality fail=" + m.get("fail") + " pass=" + m.get("pass"));
                    rn.setFinishedAt(new Date());
                    runNodeMapper.updateById(rn);
                }
            }
        } else {
            if (((Number) qualityFx.getOrDefault("qualityRunOk", 0)).intValue() > 0) {
                for (Object o : (List<?>) qualityFx.getOrDefault("qualityNodes", List.of())) {
                    if (!(o instanceof Map<?, ?> m) || Boolean.TRUE.equals(m.get("skipped"))) {
                        continue;
                    }
                    String nk = String.valueOf(m.get("nodeKey"));
                    IgEtlRunNode rn = runNodeMapper.selectOne(new QueryWrapper<IgEtlRunNode>().lambda()
                            .eq(IgEtlRunNode::getRunId, runId).eq(IgEtlRunNode::getNodeKey, nk).last("LIMIT 1"));
                    if (rn != null) {
                        rn.setStatus("success");
                        rn.setMessage("quality pass=" + m.get("pass"));
                        rn.setFinishedAt(new Date());
                        runNodeMapper.updateById(rn);
                    }
                }
            }

            // 投影成功后强制启动 DS 实例（最小真跑）
            Map<String, Object> startParams = new LinkedHashMap<>();
            startParams.put("run_id", runId);
            startParams.put("dag_code", dag.getDagCode());
            startParams.put("env", env);
            startParams.put("trial", "true");
            dsStart = dsClient.startProcessInstance(wfCode, startParams);
            String instanceId = str(dsStart.get("processInstanceId"), null);
            boolean createDegraded = Boolean.TRUE.equals(dsResp.get("degraded"));
            boolean startApiFail = Boolean.TRUE.equals(dsStart.get("degraded"));
            // 注意：DS 服务正常但响应未带实例 id 时，不得当作「DS 不可达」去跑本地 Trino
            boolean startDegraded = startApiFail;
            boolean missingInstanceId = StrUtil.isBlank(instanceId);
            boolean heavyEngine = plan.stream().anyMatch(p -> {
                String eng = String.valueOf(p.getOrDefault("engine", ""));
                return "flink".equalsIgnoreCase(eng) || "spark".equalsIgnoreCase(eng)
                        || "datax".equalsIgnoreCase(eng);
            });

            if (StrUtil.isNotBlank(instanceId)) {
                run.setDsRunId(instanceId);
            }

            if (!createDegraded && !startDegraded && !missingInstanceId) {
                run.setStatus("running");
                run.setMessage("试跑已提交 DS 实例 " + instanceId);
                markRunNodesRunning(runId);
            } else if (!createDegraded && !startDegraded && missingInstanceId) {
                // DS API 返回成功但未解析到实例 id：以 submitted 记，引导去 DS 查看
                run.setStatus("running");
                run.setMessage("试跑已调用 DS 启动（workflow=" + wfCode
                        + "），未解析到 processInstanceId；请到 DolphinScheduler 查看最新实例。"
                        + " DS resp: " + StrUtil.maxLength(str(dsStart.get("message"),
                        str(dsStart.get("resp"), "")), 180));
                markRunNodesRunning(runId);
            } else if (heavyEngine) {
                // Flink/Spark/DataX：禁止本地 Trino 假跑（易误报「本地 Trino 试跑失败」）
                run.setStatus("failed");
                String reason = createDegraded
                        ? "DS 工作流投影失败: " + str(dsResp.get("message"), str(dsResp.get("resp"), "degraded"))
                        : "DS 启动失败: " + str(dsStart.get("message"), str(dsStart.get("resp"), "degraded"));
                run.setMessage("试跑未提交到引擎：" + reason
                        + "。当前 DAG 为 Flink/Spark/DataX，不会回退本地 Trino；"
                        + "请核对 lh.ds.url / projectCode / Token，以及 DS 中是否已有对应流程定义。");
                run.setFinishedAt(new Date());
            } else {
                // 轻量 SQL 节点：DS 不可达时才本地 Trino 兜底
                localFx = localTrialExecutor.execute(runId, nodes, plan);
                applyLocalTrialResult(run, localFx, createDegraded, startDegraded, dsResp, dsStart);
            }
        }

        runMapper.insert(run);

        String olState = "failed".equals(run.getStatus()) ? "FAIL"
                : ("running".equals(run.getStatus()) ? "RUNNING" : "COMPLETE");
        Map<String, Object> olFx = openLineageProjector.project(dag, nodes, runId, olState);
        if (StrUtil.isNotBlank(str(olFx.get("olRunId"), null))) {
            run.setOlRunId(str(olFx.get("olRunId"), null));
            runMapper.updateById(run);
        }
        upsertWatermark("openlineage", "dag:" + dag.getId(),
                String.valueOf(olFx.getOrDefault("olOk", 0)));
        upsertWatermark("trial", "dag:" + dag.getId(), runId);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("runId", runId);
        r.put("dagId", dag.getId());
        r.put("env", env);
        r.put("status", run.getStatus());
        r.put("message", run.getMessage());
        r.put("dsRunId", run.getDsRunId());
        r.put("plan", plan);
        r.put("workflow", trialWf);
        r.put("quality", qualityFx);
        r.put("vault", vaultFx);
        r.put("ds", dsResp);
        if (dsStart != null) {
            r.put("dsStart", dsStart);
        }
        if (localFx != null) {
            r.put("localTrial", localFx);
        }
        r.put("openLineage", olFx);
        if ("failed".equals(run.getStatus())) {
            Map<String, Object> alert = runAlertBuilder.build(
                    dag, runId, run.getStatus(), run.getMessage(), "P1");
            r.put("alert", alert);
        }
        return r;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> backfill(IgEtlBackfillParam param) {
        IgEtlDag dag = requireDag(param.getId());
        assertCanEditDag(dag);
        if (!"prod".equals(dag.getStatus()) && !"paused".equals(dag.getStatus())) {
            throw new CommonException("仅已发布或暂停的 DAG 可补数（当前 status=" + dag.getStatus() + "）");
        }
        String markKey = param.getMarkKey().trim();
        String markValue = param.getMarkValue().trim();
        String env = StrUtil.blankToDefault(param.getEnv(), StrUtil.blankToDefault(dag.getEnv(), "prod"));

        // E7：命中已执行删除的表×分区 → 须回填合规请求号二次确认 + 告警载荷
        List<String> tableFqns = collectDagTableFqns(dag.getId());
        List<Map<String, Object>> delHits = govDelProcessingGate.assertBackfillAllowed(
                tableFqns, markKey, markValue, param.getConfirmReqNo());

        String runId = "bf-" + IdUtil.getSnowflakeNextIdStr();
        Date now = new Date();
        IgEtlRun run = new IgEtlRun();
        run.setId(IdUtil.getSnowflakeNextIdStr());
        run.setRunId(runId);
        run.setDagId(dag.getId());
        run.setWs(dag.getWs());
        run.setEnv(env);
        run.setTriggerType("backfill");
        run.setStatus("submitted");
        run.setMessage("补数 " + markKey + "=" + markValue);
        run.setStartedAt(now);
        run.setCreateTime(now);

        Map<String, Object> startParams = new LinkedHashMap<>();
        startParams.put("mark_key", markKey);
        startParams.put("mark_value", markValue);
        startParams.put("run_id", runId);
        startParams.put("dag_code", dag.getDagCode());
        if (!delHits.isEmpty()) {
            startParams.put("compliance_ack_req_no", StrUtil.trim(param.getConfirmReqNo()));
        }
        Map<String, Object> dsResp = dsClient.startProcessInstance(dag.getDsWorkflowCode(), startParams);
        String instanceId = str(dsResp.get("processInstanceId"), null);
        if (Boolean.TRUE.equals(dsResp.get("degraded")) || StrUtil.isBlank(instanceId)) {
            run.setMessage("补数已登记；DS 启动降级: " + str(dsResp.get("message"), "degraded")
                    + " · " + markKey + "=" + markValue);
            run.setDsRunId(str(dsResp.get("workflowCode"), dag.getDsWorkflowCode()));
        } else {
            run.setStatus("running");
            run.setMessage("补数已提交 DS 实例 " + instanceId + " · " + markKey + "=" + markValue);
            run.setDsRunId(instanceId);
        }
        if (!delHits.isEmpty()) {
            run.setMessage(run.getMessage() + " · 已确认合规门禁 " + StrUtil.trim(param.getConfirmReqNo()));
        }
        runMapper.insert(run);

        // 水位：source_system=backfill，mark_key=dag:{id}:{markKey}
        upsertWatermark("backfill", "dag:" + dag.getId() + ":" + markKey, markValue);
        upsertWatermark("backfill_last", "dag:" + dag.getId(), markKey + "=" + markValue);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("runId", runId);
        r.put("dagId", dag.getId());
        r.put("env", env);
        r.put("status", run.getStatus());
        r.put("trigger", "backfill");
        r.put("markKey", markKey);
        r.put("markValue", markValue);
        r.put("ds", dsResp);
        r.put("opsPath", "/ops?runId=" + runId + "&dagId=" + dag.getId());
        if (!delHits.isEmpty()) {
            r.put("complianceGate", Map.of(
                    "acknowledged", true,
                    "confirmReqNo", StrUtil.trim(param.getConfirmReqNo()),
                    "hits", delHits));
            Map<String, Object> alert = runAlertBuilder.build(
                    dag, runId, "acknowledged",
                    "补数命中已删分区并已二次确认 · " + markKey + "=" + markValue
                            + " · req=" + StrUtil.trim(param.getConfirmReqNo()),
                    "P0");
            alert.put("title", "ETL 补数门禁 · " + StrUtil.blankToDefault(dag.getDagCode(), dag.getId()));
            alert.put("kind", "compliance_backfill_gate");
            r.put("alert", alert);
        }
        return r;
    }

    /** 收集 DAG source / sink 表 FQN，供合规补数门禁匹配。 */
    private List<String> collectDagTableFqns(String dagId) {
        List<IgEtlNode> nodes = nodeMapper.selectList(new QueryWrapper<IgEtlNode>().lambda()
                .eq(IgEtlNode::getDagId, dagId));
        List<String> fqns = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (IgEtlNode n : nodes) {
            String type = n.getNodeType();
            JSONObject conf = StrUtil.isBlank(n.getConfJson())
                    ? JSONUtil.createObj()
                    : JSONUtil.parseObj(n.getConfJson());
            if ("sink_iceberg".equals(type) || "sink_ck".equals(type) || "sink_rdb".equals(type)) {
                IgEtlSinkTargetChecker.TargetRef ref = sinkTargetChecker.resolveSinkTarget(type, conf);
                if (ref != null && StrUtil.isNotBlank(ref.fqn()) && seen.add(ref.fqn())) {
                    fqns.add(ref.fqn());
                }
            } else if ("source".equals(type)) {
                String table = conf.getStr("table");
                if (StrUtil.isBlank(table)) {
                    table = conf.getStr("src");
                }
                JSONArray tables = conf.getJSONArray("tables");
                if (StrUtil.isBlank(table) && tables != null && !tables.isEmpty()) {
                    table = tables.getStr(0);
                }
                if (StrUtil.isBlank(table)) {
                    continue;
                }
                String db = StrUtil.blankToDefault(conf.getStr("database"), conf.getStr("schema"));
                String fqn = table.contains(".")
                        ? table.trim()
                        : (StrUtil.isNotBlank(db) ? db.trim() + "." + table.trim() : table.trim());
                if (seen.add(fqn)) {
                    fqns.add(fqn);
                }
            }
        }
        return fqns;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> deploy(IgEtlDeployParam param) {
        assertCanEditDag(requireDag(param.getId()));
        IgEtlIdParam idParam = new IgEtlIdParam();
        idParam.setId(param.getId());
        Map<String, Object> v = validate(idParam);
        requireValidateOk(v, "发布");
        IgEtlDag dag = requireDag(param.getId());
        List<IgEtlNode> nodes = nodeMapper.selectList(new QueryWrapper<IgEtlNode>().lambda()
                .eq(IgEtlNode::getDagId, dag.getId()));
        List<IgEtlEdge> edges = edgeMapper.selectList(new QueryWrapper<IgEtlEdge>().lambda()
                .eq(IgEtlEdge::getDagId, dag.getId()).orderByAsc(IgEtlEdge::getSortNo));

        Map<String, Object> contractFx = Map.of("checked", 0, "skipped", true);
        if (lhProperties.getEtl() != null && lhProperties.getEtl().isContractGateEnabled()) {
            contractFx = contractService.assertDeployAllowed(dag.getWs(), collectDagTableFqns(dag.getId()));
        }

        // 质量硬门禁：先于写 DS，避免远端工作流已创建后才失败
        Map<String, Object> qualityFxEarly = null;
        if (lhProperties.getEtl() != null && lhProperties.getEtl().isHardFailQualityOnDeploy()) {
            qualityFxEarly = publishSideEffects.applyQuality(dag, nodes, "deploy-precheck:" + dag.getDagCode());
            if (Boolean.TRUE.equals(qualityFxEarly.get("blocked"))) {
                throw new CommonException("质量门禁未通过，已阻止发布（lh.etl.hard-fail-quality-on-deploy=true）");
            }
        }

        List<Map<String, Object>> plan = new ArrayList<>();
        for (IgEtlNode n : nodes) {
            Map<String, Object> resolved = engineResolver.resolve(n.getNodeType(), n.getConfJson());
            String engine = String.valueOf(resolved.get("engine"));
            n.setResolvedEngine(engine);
            nodeMapper.updateById(n);
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("nodeKey", n.getNodeKey());
            p.put("nodeType", n.getNodeType());
            p.put("engine", engine);
            p.put("rule", resolved.get("rule"));
            p.put("submitShape", submitShape(engine));
            plan.add(p);
        }

        String preferredCode = StrUtil.blankToDefault(dag.getDsWorkflowCode(), "WF_" + dag.getDagCode());
        Map<String, Object> workflow = DsWorkflowBuilder.build(dag, nodes, edges, plan, preferredCode);
        Map<String, Object> vaultFx = vaultInjector.enrichWorkflow(workflow, nodes);
        Map<String, Object> sinkFx = sinkTargetChecker.applyOnDeploy(dag, nodes, workflow);
        Map<String, Object> dsResp = dsClient.createOrUpdateWorkflow(workflow);

        if (lhProperties.getEtl() != null
                && lhProperties.getEtl().isHardFailDsOnDeploy()
                && Boolean.TRUE.equals(dsResp.get("degraded"))) {
            runAlertBuilder.build(
                    dag, "deploy:" + dag.getDagCode(), "failed",
                    str(dsResp.get("message"), "DS degraded"), "P1");
            throw new CommonException("DS 投影降级，已阻止发布（lh.etl.hard-fail-ds-on-deploy=true）："
                    + str(dsResp.get("message"), str(dsResp.get("resp"), "degraded")));
        }

        String wfCode = str(dsResp.get("workflowCode"), preferredCode);
        String nextVer = bumpVer(dag.getVer());
        dag.setStatus("prod");
        dag.setEnv("prod");
        dag.setVer(nextVer);
        dag.setDsWorkflowCode(wfCode);
        if (StrUtil.isNotBlank(param.getGitRef())) {
            dag.setGitRef(param.getGitRef());
        }
        dag.setRevision(dag.getRevision() == null ? 1 : dag.getRevision() + 1);
        dagMapper.updateById(dag);
        upsertWatermark("deploy", "dag:" + dag.getId(), nextVer);

        Map<String, Object> sideEffects = publishSideEffects.apply(dag, nodes, edges);
        Map<String, Object> qualityFx = qualityFxEarly != null
                ? qualityFxEarly
                : publishSideEffects.applyQuality(dag, nodes, "deploy:" + dag.getDagCode() + ":" + nextVer);
        sideEffects.putAll(qualityFx);
        sideEffects.put("vault", vaultFx);
        sideEffects.put("sinkTarget", sinkFx);
        sideEffects.put("contract", contractFx);
        if (Boolean.TRUE.equals(qualityFx.get("blocked"))) {
            sideEffects.put("qualityGateBlocked", true);
        }
        upsertWatermark("lineage_parse", "dag:" + dag.getId(),
                String.valueOf(sideEffects.getOrDefault("lineageOk", 0)));
        upsertWatermark("dq_run", "dag:" + dag.getId(),
                String.valueOf(qualityFx.getOrDefault("qualityRunOk", 0)));
        upsertWatermark("vault_inject", "dag:" + dag.getId(),
                String.valueOf(vaultFx.getOrDefault("injected", 0)));
        upsertWatermark("sink_autocreate", "dag:" + dag.getId(),
                String.valueOf(sinkFx.getOrDefault("created", 0)));

        // C4：发布后投递 OL START（登记作业）+ DataX 解析补边 COMPLETE
        Map<String, Object> olFx = openLineageProjector.project(
                dag, nodes, "deploy:" + dag.getDagCode() + ":" + nextVer, "COMPLETE");
        sideEffects.put("openLineage", olFx);
        upsertWatermark("openlineage", "dag:" + dag.getId(),
                String.valueOf(olFx.getOrDefault("olOk", 0)));

        Map<String, Object> lineageSync = lineageSyncHelper.syncAfterDeploy(dag.getWs(), dag.getDagCode());
        sideEffects.put("lineageSync", lineageSync);

        // D1：发布后确保 DS ONLINE
        Map<String, Object> dsSchedule = dsClient.setScheduleState(wfCode, "ONLINE");

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("dagId", dag.getId());
        r.put("ver", nextVer);
        r.put("status", dag.getStatus());
        r.put("dsWorkflowCode", wfCode);
        r.put("plan", plan);
        r.put("workflow", workflow);
        r.put("sideEffects", sideEffects);
        r.put("dsSchedule", dsSchedule);
        r.put("validate", Map.of(
                "ok", true,
                "issueCount", v.get("issueCount"),
                "issues", v.get("issues")));
        r.put("ds", dsResp);
        r.put("degraded", Boolean.TRUE.equals(dsResp.get("degraded")));
        return r;
    }

    @Override
    public Page<Map<String, Object>> pageRuns(String dagId, String ws) {
        QueryWrapper<IgEtlRun> qw = new QueryWrapper<>();
        if (StrUtil.isNotBlank(dagId)) {
            qw.lambda().eq(IgEtlRun::getDagId, dagId);
        }
        if (StrUtil.isNotBlank(ws)) {
            qw.lambda().eq(IgEtlRun::getWs, ws);
        }
        qw.lambda().orderByDesc(IgEtlRun::getStartedAt).orderByDesc(IgEtlRun::getCreateTime);
        Page<IgEtlRun> page = runMapper.selectPage(CommonPageRequest.defaultPage(), qw);
        Page<Map<String, Object>> out = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        out.setRecords(page.getRecords().stream().map(this::runBrief).collect(Collectors.toList()));
        return out;
    }

    @Override
    public Map<String, Object> runDetail(String runId) {
        IgEtlRun run = runMapper.selectOne(new QueryWrapper<IgEtlRun>().lambda().eq(IgEtlRun::getRunId, runId));
        if (run == null) {
            throw new CommonException("运行不存在: " + runId);
        }
        Map<String, Object> dsSync = syncRunFromDs(run);
        List<IgEtlRunNode> nodes = runNodeMapper.selectList(new QueryWrapper<IgEtlRunNode>().lambda()
                .eq(IgEtlRunNode::getRunId, runId).orderByAsc(IgEtlRunNode::getNodeKey));
        Map<String, Object> m = runBrief(run);
        m.put("nodes", nodes.stream().map(this::runNodeVo).collect(Collectors.toList()));
        if (dsSync != null) {
            m.put("dsSync", dsSync);
        }
        if ("failed".equals(run.getStatus())) {
            IgEtlDag dag = dagMapper.selectById(run.getDagId());
            m.put("alert", runAlertBuilder.build(dag, run.getRunId(), run.getStatus(), run.getMessage(), "P1"));
        }
        return m;
    }

    @Override
    public Map<String, Object> runResultPreview(String runId, String nodeKey, Integer limit) {
        return resultPreviewHelper.preview(runId, nodeKey, limit);
    }

    @Override
    public Map<String, Object> runNodeLog(String runId, String nodeKey, Integer skipLineNum, Integer limit) {
        if (StrUtil.isBlank(runId) || StrUtil.isBlank(nodeKey)) {
            throw new CommonException("runId / nodeKey 不能为空");
        }
        IgEtlRun run = runMapper.selectOne(new QueryWrapper<IgEtlRun>().lambda().eq(IgEtlRun::getRunId, runId));
        if (run == null) {
            throw new CommonException("运行不存在: " + runId);
        }
        // 先同步一次 DS 任务态，便于拿到 taskInstanceId
        syncRunFromDs(run);
        IgEtlRunNode rn = runNodeMapper.selectOne(new QueryWrapper<IgEtlRunNode>().lambda()
                .eq(IgEtlRunNode::getRunId, runId)
                .eq(IgEtlRunNode::getNodeKey, nodeKey.trim())
                .last("LIMIT 1"));
        if (rn == null) {
            throw new CommonException("运行节点不存在: " + nodeKey);
        }
        int skip = skipLineNum == null ? 0 : Math.max(0, skipLineNum);
        int lim = limit == null || limit <= 0 ? 1000 : Math.min(limit, 5000);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", runId);
        out.put("nodeKey", rn.getNodeKey());
        out.put("nodeType", rn.getNodeType());
        out.put("status", rn.getStatus());
        out.put("logRef", rn.getLogRef());
        out.put("skipLineNum", skip);
        out.put("limit", lim);

        String taskId = resolveDsTaskInstanceId(run, rn);
        out.put("taskInstanceId", taskId);
        if (StrUtil.isBlank(taskId)) {
            out.put("ok", false);
            out.put("degraded", true);
            out.put("source", "portal");
            out.put("code", "no_ds_task_instance");
            String hint = StrUtil.isBlank(run.getDsRunId())
                    ? "本 run 无 dsRunId（本地试跑或未提交 DS）"
                    : (run.getDsRunId().startsWith("WF_") || !run.getDsRunId().trim().matches("\\d+")
                    ? "dsRunId=" + run.getDsRunId() + " 不是进程实例 id"
                    : "dsRunId=" + run.getDsRunId() + " 下未匹配到节点「"
                            + rn.getNodeKey() + "」的 DS 任务（常见于旧流程任务名只有中文展示名；请重新发布后再试跑）");
            out.put("content", "暂无 DS 任务实例，无法拉实时日志。" + hint);
            out.put("lineNum", skip);
            out.put("message", "暂无 DS 任务实例，无法拉实时日志");
            return out;
        }
        Map<String, Object> log = dsClient.queryTaskInstanceLog(taskId, skip, lim);
        out.put("source", "ds");
        out.put("ok", Boolean.TRUE.equals(log.get("ok")));
        out.put("degraded", Boolean.TRUE.equals(log.get("degraded")));
        out.put("content", str(log.get("content"), str(log.get("message"), "")));
        Object lineNum = log.get("lineNum");
        out.put("lineNum", lineNum != null ? lineNum : skip);
        if (log.get("message") != null && !Boolean.TRUE.equals(log.get("ok"))) {
            out.put("message", log.get("message"));
        }
        // 回写 logRef，便于下次直达
        String wantRef = "dsTask:" + taskId;
        if (!wantRef.equals(rn.getLogRef())) {
            rn.setLogRef(wantRef);
            runNodeMapper.updateById(rn);
            out.put("logRef", wantRef);
        }
        return out;
    }

    /** 从 logRef 或 DS 任务列表解析 taskInstanceId */
    @SuppressWarnings("unchecked")
    private String resolveDsTaskInstanceId(IgEtlRun run, IgEtlRunNode rn) {
        String fromRef = parseDsTaskId(rn.getLogRef());
        if (StrUtil.isNotBlank(fromRef)) {
            return fromRef;
        }
        if (run == null || StrUtil.isBlank(run.getDsRunId())) {
            return null;
        }
        String dsId = run.getDsRunId().trim();
        if (dsId.startsWith("WF_") || !dsId.matches("\\d+")) {
            return null;
        }
        Map<String, Object> taskSync = dsClient.listTaskInstances(dsId);
        if (!Boolean.TRUE.equals(taskSync.get("ok")) || !(taskSync.get("tasks") instanceof List<?> tasks)) {
            return null;
        }
        String displayName = lookupNodeDisplayName(run.getDagId(), rn.getNodeKey());
        Map<String, Object> matched = matchDsTask(tasks, rn, displayName);
        return matched == null ? null : str(matched.get("id"), null);
    }

    /** 门户节点展示名（DS 任务 name 常用这个，而不是 nodeKey） */
    private String lookupNodeDisplayName(String dagId, String nodeKey) {
        if (StrUtil.isBlank(dagId) || StrUtil.isBlank(nodeKey)) {
            return null;
        }
        IgEtlNode n = nodeMapper.selectOne(new QueryWrapper<IgEtlNode>().lambda()
                .eq(IgEtlNode::getDagId, dagId)
                .eq(IgEtlNode::getNodeKey, nodeKey.trim())
                .last("LIMIT 1"));
        return n == null ? null : n.getName();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> matchDsTask(List<?> tasks, IgEtlRunNode rn, String displayName) {
        if (tasks == null || rn == null) {
            return null;
        }
        String key = StrUtil.blankToDefault(rn.getNodeKey(), "").trim();
        String label = StrUtil.blankToDefault(displayName, "").trim();
        String labelDs = sanitizeDsTaskName(label);
        Map<String, Object> byKey = null;
        Map<String, Object> byLabel = null;
        for (Object o : tasks) {
            if (!(o instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> t = (Map<String, Object>) raw;
            String name = String.valueOf(t.get("name")).trim();
            if (StrUtil.isBlank(name)) {
                continue;
            }
            String nameDs = sanitizeDsTaskName(name);
            if (StrUtil.isNotBlank(key) && (nameDs.equals(key) || nameDs.startsWith(key + " ")
                    || nameDs.startsWith(key + "·") || nameDs.startsWith(key + " ·")
                    || nameDs.contains(key) || name.contains(key))) {
                byKey = t;
                break;
            }
            if (byLabel == null && StrUtil.isNotBlank(labelDs)
                    && (nameDs.equals(labelDs) || nameDs.contains(labelDs) || labelDs.contains(nameDs)
                    || name.equals(label) || name.contains(label) || label.contains(name))) {
                byLabel = t;
            }
        }
        if (byKey != null) {
            return byKey;
        }
        if (byLabel != null) {
            return byLabel;
        }
        // 单节点 DAG：仅 1 个 DS 任务时直接绑定
        if (tasks.size() == 1 && tasks.get(0) instanceof Map<?, ?> only) {
            return (Map<String, Object>) only;
        }
        return null;
    }

    /**
     * 与 {@code DsClient.sanitizeTaskName} 对齐：DS 建任务时会把 {@code /} 等替换成 {@code _}，
     * 门户展示名仍是「CDC / 库表」而 DS 任务名是「CDC _ 库表」，直接 equals 会匹配失败。
     */
    private static String sanitizeDsTaskName(String name) {
        String n = StrUtil.blankToDefault(name, "").trim();
        if (n.isEmpty()) {
            return n;
        }
        n = n.replaceAll("[\\\\/:*?\"<>|]", "_");
        return n.length() > 64 ? n.substring(0, 64) : n;
    }

    private static String parseDsTaskId(String logRef) {
        if (StrUtil.isBlank(logRef)) {
            return null;
        }
        String s = logRef.trim();
        if (s.startsWith("dsTask:")) {
            String id = s.substring("dsTask:".length()).trim();
            return StrUtil.isBlank(id) ? null : id;
        }
        if (s.matches("\\d+")) {
            return s;
        }
        return null;
    }

    @Override
    public Map<String, Object> resolveEngine(IgEtlEngineResolveParam param) {
        String confJson = sanitizeConfJson(param.getConf(), param.getConfJson());
        return engineResolver.resolve(param.getNodeType(), confJson);
    }

    @Override
    public Map<String, Object> nodeTypes() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("types", IgEtlNodeTypes.ALL);
        m.put("groups", IgEtlNodeTypes.GROUPS);
        m.put("meta", IgEtlNodeTypes.META);
        m.put("engines", engineResolver.policyMatrix());
        return m;
    }

    // ---------- helpers ----------

    private IgEtlDag requireDag(String id) {
        IgEtlDag dag = dagMapper.selectById(id);
        if (dag == null) {
            throw new CommonException("DAG 不存在: " + id);
        }
        return dag;
    }

    /** 拥有者或 MANAGE grant 方可编辑/删除/发布（超管不短路） */
    private void assertCanEditDag(IgEtlDag dag) {
        secAuthGrantService.assertCanEditEtl(dag);
    }

    private void assertCanDeleteDag(IgEtlDag dag) {
        secAuthGrantService.assertCanDeleteEtl(dag);
    }

    private Map<String, Object> dagBrief(IgEtlDag dag) {
        Map<String, Object> m = dagBriefRaw(dag);
        userNameResolver.fillDagBrief(m);
        return m;
    }

    private Map<String, Object> dagBriefRaw(IgEtlDag dag) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", dag.getId());
        m.put("ws", dag.getWs());
        m.put("dagCode", dag.getDagCode());
        m.put("name", dag.getName());
        m.put("desc", dag.getDescription());
        m.put("description", dag.getDescription());
        m.put("cron", dag.getCron());
        m.put("owner", dag.getOwner());
        m.put("createUser", dag.getCreateUser());
        m.put("status", dag.getStatus());
        m.put("ver", dag.getVer());
        m.put("env", dag.getEnv());
        m.put("engine", dag.getDefaultEngine());
        m.put("defaultEngine", dag.getDefaultEngine());
        m.put("sla", dag.getSla());
        m.put("dsWorkflowCode", dag.getDsWorkflowCode());
        m.put("gitRef", dag.getGitRef());
        m.put("revision", dag.getRevision());
        m.put("updateTime", dag.getUpdateTime());
        m.put("createTime", dag.getCreateTime());
        return m;
    }

    private Map<String, Object> nodeVo(IgEtlNode n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", n.getNodeKey());
        m.put("nodeKey", n.getNodeKey());
        m.put("type", n.getNodeType());
        m.put("nodeType", n.getNodeType());
        m.put("name", n.getName());
        m.put("meta", n.getMeta());
        m.put("x", n.getPosX());
        m.put("y", n.getPosY());
        m.put("conf", parseConf(n.getConfJson()));
        m.put("resolvedEngine", n.getResolvedEngine());
        return m;
    }

    private Map<String, Object> edgeVo(IgEtlEdge e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("from", e.getFromNodeKey());
        m.put("to", e.getToNodeKey());
        m.put("fromNodeKey", e.getFromNodeKey());
        m.put("toNodeKey", e.getToNodeKey());
        m.put("label", e.getLabel());
        m.put("sortNo", e.getSortNo());
        return m;
    }

    private Map<String, Object> runBrief(IgEtlRun run) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", run.getId());
        m.put("runId", run.getRunId());
        m.put("dagId", run.getDagId());
        m.put("ws", run.getWs());
        m.put("env", run.getEnv());
        m.put("trigger", run.getTriggerType());
        m.put("status", run.getStatus());
        m.put("dsRunId", run.getDsRunId());
        m.put("olRunId", run.getOlRunId());
        m.put("message", run.getMessage());
        m.put("startedAt", run.getStartedAt());
        m.put("finishedAt", run.getFinishedAt());
        String ops = "/ops?runId=" + StrUtil.blankToDefault(run.getRunId(), "");
        if (StrUtil.isNotBlank(run.getDagId())) {
            ops += "&dagId=" + run.getDagId();
        }
        m.put("opsPath", ops);
        return m;
    }

    private Map<String, Object> runNodeVo(IgEtlRunNode n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nodeKey", n.getNodeKey());
        m.put("nodeId", n.getNodeKey());
        m.put("nodeType", n.getNodeType());
        m.put("status", n.getStatus());
        m.put("engine", n.getEngine());
        m.put("logRef", n.getLogRef());
        m.put("dsTaskInstanceId", parseDsTaskId(n.getLogRef()));
        m.put("message", n.getMessage());
        m.put("startedAt", n.getStartedAt());
        m.put("finishedAt", n.getFinishedAt());
        return m;
    }

    /** 试跑本地兜底结果写回 run 头状态 */
    private void applyLocalTrialResult(
            IgEtlRun run,
            Map<String, Object> localFx,
            boolean createDegraded,
            boolean startDegraded,
            Map<String, Object> dsResp,
            Map<String, Object> dsStart) {
        String reason = createDegraded
                ? "DS 投影降级: " + str(dsResp.get("message"), "degraded")
                : "DS 启动降级: " + str(dsStart.get("message"), "no processInstanceId");
        if (localFx == null || Boolean.TRUE.equals(localFx.get("skipped"))) {
            run.setStatus("submitted");
            run.setMessage("试跑已登记；" + reason + "；本地兜底未执行: "
                    + str(localFx == null ? null : localFx.get("message"), "skipped"));
            return;
        }
        boolean success = Boolean.TRUE.equals(localFx.get("success"));
        boolean partial = Boolean.TRUE.equals(localFx.get("partial"));
        int skip = ((Number) localFx.getOrDefault("skipCount", 0)).intValue();
        int fail = ((Number) localFx.getOrDefault("failCount", 0)).intValue();
        int ok = ((Number) localFx.getOrDefault("okCount", 0)).intValue();
        if (success) {
            run.setStatus("success");
            run.setMessage("本地 Trino 试跑成功（DS 不可达）· ok=" + ok);
            run.setFinishedAt(new Date());
        } else if (partial) {
            run.setStatus("failed");
            run.setMessage("本地 Trino 试跑部分失败 · ok=" + ok + " fail=" + fail);
            run.setFinishedAt(new Date());
        } else if (fail > 0) {
            run.setStatus("failed");
            run.setMessage("本地 Trino 试跑失败 · fail=" + fail);
            run.setFinishedAt(new Date());
        } else if (skip > 0 && ok == 0) {
            // 典型：DAG 默认 Flink，DS 不可达且无可 SQL 本地节点
            run.setStatus("failed");
            run.setMessage("试跑未执行真实引擎：" + reason
                    + "。当前节点均为 Flink/Spark/DataX，无法用本地 Trino 兜底；"
                    + "请检查 DolphinScheduler 连通与 Flink 集群后重试");
            run.setFinishedAt(new Date());
        } else {
            run.setStatus("submitted");
            run.setMessage("试跑已登记；" + reason + "；无可本地执行节点");
        }
    }

    private void markRunNodesRunning(String runId) {
        List<IgEtlRunNode> nodes = runNodeMapper.selectList(new QueryWrapper<IgEtlRunNode>().lambda()
                .eq(IgEtlRunNode::getRunId, runId)
                .in(IgEtlRunNode::getStatus, List.of("pending", "submitted")));
        Date now = new Date();
        for (IgEtlRunNode rn : nodes) {
            if ("success".equals(rn.getStatus()) || "failed".equals(rn.getStatus())
                    || "blocked".equals(rn.getStatus())) {
                continue;
            }
            rn.setStatus("running");
            rn.setStartedAt(now);
            rn.setMessage(StrUtil.blankToDefault(rn.getMessage(), "DS instance started"));
            runNodeMapper.updateById(rn);
        }
    }

    /**
     * 若 dsRunId 像 DS 实例 id，则轮询实例 + 任务态并回写 ig_etl_run / run_node。
     * 终态 run 仍会回写缺失的 logRef（供节点日志），但不再改 run 头状态。
     */
    private Map<String, Object> syncRunFromDs(IgEtlRun run) {
        if (run == null || StrUtil.isBlank(run.getDsRunId())) {
            return null;
        }
        String status = StrUtil.blankToDefault(run.getStatus(), "");
        boolean terminal = "success".equals(status) || "failed".equals(status) || "blocked".equals(status);
        String dsId = run.getDsRunId().trim();
        if (dsId.startsWith("WF_") || !dsId.matches("\\d+")) {
            return null;
        }
        Map<String, Object> ds = dsClient.getProcessInstance(dsId);
        if (!Boolean.TRUE.equals(ds.get("ok")) || Boolean.TRUE.equals(ds.get("degraded"))) {
            return ds;
        }

        // 任务级回写（终态也补 logRef）
        Map<String, Object> taskSync = dsClient.listTaskInstances(dsId);
        int nodeSynced = 0;
        if (Boolean.TRUE.equals(taskSync.get("ok")) && taskSync.get("tasks") instanceof List<?> tasks) {
            nodeSynced = syncRunNodesFromDsTasks(run.getRunId(), run.getDagId(), tasks);
            ds.put("taskSync", Map.of(
                    "ok", true,
                    "taskCount", tasks.size(),
                    "nodeSynced", nodeSynced));
        } else {
            ds.put("taskSync", taskSync);
        }

        if (terminal) {
            ds.put("mappedStatus", status);
            ds.put("synced", nodeSynced > 0);
            ds.put("terminalLogRefOnly", true);
            return ds;
        }

        String mapped = mapDsStateToRunStatus(str(ds.get("state"), null));
        if (mapped == null) {
            return ds;
        }
        boolean changed = !mapped.equals(run.getStatus());
        if (changed) {
            run.setStatus(mapped);
            if ("success".equals(mapped) || "failed".equals(mapped)) {
                run.setFinishedAt(new Date());
                run.setMessage(StrUtil.blankToDefault(run.getMessage(), "")
                        + (StrUtil.isBlank(run.getMessage()) ? "" : " · ")
                        + "DS state=" + ds.get("state"));
                if (nodeSynced == 0) {
                    finishPendingRunNodes(run.getRunId(), mapped);
                }
                onRunTerminal(run, mapped);
            } else if ("running".equals(mapped) && !"running".equals(status)) {
                markRunNodesRunning(run.getRunId());
            }
            runMapper.updateById(run);
        }
        ds.put("mappedStatus", mapped);
        ds.put("synced", changed || nodeSynced > 0);
        return ds;
    }

    @SuppressWarnings("unchecked")
    private int syncRunNodesFromDsTasks(String runId, String dagId, List<?> tasks) {
        List<IgEtlRunNode> nodes = runNodeMapper.selectList(new QueryWrapper<IgEtlRunNode>().lambda()
                .eq(IgEtlRunNode::getRunId, runId));
        if (nodes.isEmpty() || tasks == null || tasks.isEmpty()) {
            return 0;
        }
        Map<String, String> namesByKey = new LinkedHashMap<>();
        if (StrUtil.isNotBlank(dagId)) {
            List<IgEtlNode> defs = nodeMapper.selectList(new QueryWrapper<IgEtlNode>().lambda()
                    .eq(IgEtlNode::getDagId, dagId));
            for (IgEtlNode d : defs) {
                if (d != null && StrUtil.isNotBlank(d.getNodeKey())) {
                    namesByKey.put(d.getNodeKey(), d.getName());
                }
            }
        }
        int updated = 0;
        Date now = new Date();
        for (IgEtlRunNode rn : nodes) {
            String displayName = namesByKey.get(rn.getNodeKey());
            Map<String, Object> matched = matchDsTask(tasks, rn, displayName);
            if (matched == null) {
                continue;
            }
            String taskId = str(matched.get("id"), null);
            boolean hasLogRef = StrUtil.isNotBlank(parseDsTaskId(rn.getLogRef()));
            boolean terminalNode = "success".equals(rn.getStatus()) || "failed".equals(rn.getStatus())
                    || "blocked".equals(rn.getStatus());
            // 终态节点若已有 dsTask logRef 则跳过；缺失时仍补写，否则查看日志永久 no_ds_task_instance
            if (terminalNode && hasLogRef) {
                continue;
            }
            String mapped = mapDsStateToRunStatus(str(matched.get("state"), null));
            if (!terminalNode) {
                if (mapped == null) {
                    continue;
                }
                rn.setStatus(mapped);
                if ("running".equals(mapped) && rn.getStartedAt() == null) {
                    rn.setStartedAt(now);
                }
                if ("success".equals(mapped) || "failed".equals(mapped)) {
                    rn.setFinishedAt(now);
                }
            }
            if (StrUtil.isNotBlank(taskId)) {
                rn.setLogRef("dsTask:" + taskId);
            } else if (!hasLogRef) {
                rn.setLogRef(str(matched.get("logPath"), rn.getLogRef()));
            }
            String path = str(matched.get("logPath"), null);
            if (!terminalNode || StrUtil.isBlank(rn.getMessage())) {
                rn.setMessage("DS task state=" + matched.get("state")
                        + (StrUtil.isNotBlank(path) ? " · logPath=" + path : ""));
            }
            runNodeMapper.updateById(rn);
            updated++;
        }
        return updated;
    }

    /** 终态：水位 + 可选 OL 补投 */
    private void onRunTerminal(IgEtlRun run, String status) {
        upsertWatermark("run", "dag:" + run.getDagId(), run.getRunId() + "=" + status);
        upsertWatermark("run_last", "dag:" + run.getDagId(), status);
        if ("success".equals(status) || "failed".equals(status)) {
            try {
                IgEtlDag dag = dagMapper.selectById(run.getDagId());
                if (dag != null) {
                    List<IgEtlNode> nodes = nodeMapper.selectList(new QueryWrapper<IgEtlNode>().lambda()
                            .eq(IgEtlNode::getDagId, dag.getId()));
                    Map<String, Object> olFx = openLineageProjector.project(
                            dag, nodes, run.getRunId(), "success".equals(status) ? "COMPLETE" : "FAIL");
                    if (StrUtil.isNotBlank(str(olFx.get("olRunId"), null))) {
                        run.setOlRunId(str(olFx.get("olRunId"), null));
                    }
                    upsertWatermark("openlineage", "dag:" + dag.getId(),
                            String.valueOf(olFx.getOrDefault("olOk", 0)));
                }
            } catch (Exception ignored) {
                // soft-fail
            }
            try {
                IgEtlDag dag = dagMapper.selectById(run.getDagId());
                String ws = dag == null ? "default" : StrUtil.blankToDefault(dag.getWs(), "default");
                String linkId = "failed".equals(status) ? "C" : "C";
                lhObsSpanService.recordComponentSpan(
                        ws,
                        linkId,
                        "etl",
                        "dag.run",
                        "failed".equals(status) ? "error" : "ok",
                        run.getRunId(),
                        run.getDagId(),
                        "failed".equals(status) ? StrUtil.blankToDefault(run.getMessage(), "etl run failed") : null,
                        "{\"dagId\":\"" + run.getDagId() + "\",\"status\":\"" + status + "\"}");
            } catch (Exception ignored) {
                // soft-fail span
            }
        }
    }

    /**
     * DS / Worker 回调回写（步骤 4）。
     * body: runId, status?, processInstanceId?, nodes?[{nodeKey,status,message,logRef}]
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> applyRunCallback(Map<String, Object> body) {
        if (body == null || StrUtil.isBlank(str(body.get("runId"), null))) {
            throw new CommonException("runId 必填");
        }
        String runId = str(body.get("runId"), null);
        IgEtlRun run = runMapper.selectOne(new QueryWrapper<IgEtlRun>().lambda().eq(IgEtlRun::getRunId, runId));
        if (run == null) {
            throw new CommonException("运行不存在: " + runId);
        }
        String instanceId = str(body.get("processInstanceId"), null);
        if (StrUtil.isNotBlank(instanceId)) {
            run.setDsRunId(instanceId);
        }
        int nodeOk = 0;
        Object nodesObj = body.get("nodes");
        if (nodesObj instanceof List<?> list) {
            Date now = new Date();
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> raw)) {
                    continue;
                }
                String nk = str(raw.get("nodeKey"), null);
                if (StrUtil.isBlank(nk)) {
                    continue;
                }
                IgEtlRunNode rn = runNodeMapper.selectOne(new QueryWrapper<IgEtlRunNode>().lambda()
                        .eq(IgEtlRunNode::getRunId, runId).eq(IgEtlRunNode::getNodeKey, nk).last("LIMIT 1"));
                if (rn == null) {
                    continue;
                }
                String st = str(raw.get("status"), rn.getStatus());
                rn.setStatus(st);
                if (raw.get("message") != null) {
                    rn.setMessage(StrUtil.maxLength(String.valueOf(raw.get("message")), 500));
                }
                if (raw.get("logRef") != null) {
                    rn.setLogRef(String.valueOf(raw.get("logRef")));
                }
                if ("running".equals(st) && rn.getStartedAt() == null) {
                    rn.setStartedAt(now);
                }
                if ("success".equals(st) || "failed".equals(st) || "blocked".equals(st)) {
                    rn.setFinishedAt(now);
                }
                runNodeMapper.updateById(rn);
                nodeOk++;
            }
        }
        String status = str(body.get("status"), null);
        if (StrUtil.isNotBlank(status)) {
            run.setStatus(status);
            if ("success".equals(status) || "failed".equals(status) || "blocked".equals(status)) {
                run.setFinishedAt(new Date());
                if (body.get("message") != null) {
                    run.setMessage(StrUtil.maxLength(String.valueOf(body.get("message")), 500));
                }
                onRunTerminal(run, status);
            }
        }
        runMapper.updateById(run);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("runId", runId);
        r.put("status", run.getStatus());
        r.put("dsRunId", run.getDsRunId());
        r.put("nodesUpdated", nodeOk);
        r.put("ok", true);
        return r;
    }

    private static String mapDsStateToRunStatus(String state) {
        if (StrUtil.isBlank(state)) {
            return null;
        }
        String s = state.trim().toUpperCase();
        return switch (s) {
            case "SUCCESS", "FINISHED", "COMPLETE", "COMPLETED" -> "success";
            case "FAILURE", "FAIL", "FAILED", "STOP", "STOPPED", "KILL", "KILLED" -> "failed";
            case "RUNNING_EXECUTION", "RUNNING", "SUBMITTED_SUCCESS", "ACCEPT", "DELAY_EXECUTION" -> "running";
            case "PAUSE", "PAUSED" -> "paused";
            default -> null;
        };
    }

    private void finishPendingRunNodes(String runId, String status) {
        List<IgEtlRunNode> nodes = runNodeMapper.selectList(new QueryWrapper<IgEtlRunNode>().lambda()
                .eq(IgEtlRunNode::getRunId, runId));
        Date now = new Date();
        for (IgEtlRunNode rn : nodes) {
            if ("success".equals(rn.getStatus()) || "failed".equals(rn.getStatus())
                    || "blocked".equals(rn.getStatus())) {
                continue;
            }
            rn.setStatus(status);
            rn.setFinishedAt(now);
            rn.setMessage(StrUtil.blankToDefault(rn.getMessage(), "synced from DS"));
            runNodeMapper.updateById(rn);
        }
    }

    private Object parseConf(String confJson) {
        if (StrUtil.isBlank(confJson)) {
            return new LinkedHashMap<>();
        }
        try {
            return JSONUtil.parse(confJson);
        } catch (Exception e) {
            return confJson;
        }
    }

    private String sanitizeConfJson(Object conf, Object confJson) {
        JSONObject obj = null;
        if (conf instanceof Map) {
            obj = JSONUtil.parseObj(JSONUtil.toJsonStr(conf));
        } else if (conf != null && !(conf instanceof String && StrUtil.isBlank((String) conf))) {
            obj = JSONUtil.parseObj(JSONUtil.toJsonStr(conf));
        } else if (confJson instanceof String && StrUtil.isNotBlank((String) confJson)) {
            obj = JSONUtil.parseObj((String) confJson);
        } else if (confJson != null) {
            obj = JSONUtil.parseObj(JSONUtil.toJsonStr(confJson));
        }
        if (obj == null) {
            return null;
        }
        for (String k : SECRET_KEYS) {
            if (obj.containsKey(k) && StrUtil.isNotBlank(obj.getStr(k))) {
                obj.set(k, "");
            }
        }
        return obj.toString();
    }

    private List<Map<String, Object>> validateNodeConf(IgEtlNode n) {
        List<Map<String, Object>> issues = new ArrayList<>();
        String type = n.getNodeType();
        JSONObject conf = StrUtil.isBlank(n.getConfJson()) ? JSONUtil.createObj() : JSONUtil.parseObj(n.getConfJson());

        if (StrUtil.isNotBlank(conf.getStr("engine_override")) && StrUtil.isBlank(conf.getStr("override_reason"))) {
            issues.add(issue("node", n.getNodeKey(), "error", "engine_override 须填写 override_reason"));
        }

        if ("source".equals(type)) {
            if (StrUtil.isBlank(conf.getStr("dsId")) && StrUtil.isBlank(conf.getStr("host"))) {
                issues.add(issue("node", n.getNodeKey(), "error", "source 须配置 dsId"));
            }
            String table = conf.getStr("table");
            if (StrUtil.isBlank(table)) {
                table = conf.getStr("src");
            }
            JSONArray tables = conf.getJSONArray("tables");
            if (StrUtil.isBlank(table) && tables != null && !tables.isEmpty()) {
                table = tables.getStr(0);
            }
            if (StrUtil.isBlank(table)) {
                issues.add(issue("node", n.getNodeKey(), "error", "source 须配置 table（单表）"));
            }
            if (tables != null && tables.size() > 1) {
                issues.add(issue("node", n.getNodeKey(), "error",
                        "source 仅支持单表，请拆分为多个 source 节点（当前 tables.length=" + tables.size() + "）"));
            }
        } else if ("source_api".equals(type)) {
            if (StrUtil.isBlank(conf.getStr("baseUrl")) && StrUtil.isBlank(conf.getStr("dsId"))) {
                issues.add(issue("node", n.getNodeKey(), "error", "source_api 须配置 baseUrl 或 dsId"));
            }
        } else if ("source_file".equals(type)) {
            if (StrUtil.isBlank(conf.getStr("basePath")) && StrUtil.isBlank(conf.getStr("bucket"))) {
                issues.add(issue("node", n.getNodeKey(), "error", "source_file 须配置 basePath/bucket"));
            }
        } else if ("transform".equals(type)) {
            String sql = conf.getStr("sql");
            if (StrUtil.isBlank(sql) && !"passthrough".equals(conf.getStr("template"))) {
                issues.add(issue("node", n.getNodeKey(), "error", "transform 须配置 sql"));
            }
        } else if ("mapping".equals(type)) {
            JSONArray mapList = conf.getJSONArray("mapList");
            if (mapList == null) {
                mapList = conf.getJSONArray("fieldMaps");
            }
            if (mapList == null || mapList.isEmpty()) {
                issues.add(issue("node", n.getNodeKey(), "warn", "mapping 未配置 mapList/fieldMaps"));
            }
        } else if ("quality".equals(type)) {
            JSONArray rules = conf.getJSONArray("rules");
            // rules 可能是字符串草稿
            boolean hasRulesJson = false;
            if (rules != null && !rules.isEmpty()) {
                hasRulesJson = true;
            } else if (StrUtil.isNotBlank(conf.getStr("rules")) && !"[]".equals(conf.getStr("rules").trim())) {
                hasRulesJson = true;
            }
            JSONArray ruleIds = conf.getJSONArray("ruleIds");
            if ((ruleIds == null || ruleIds.isEmpty()) && !hasRulesJson) {
                issues.add(issue("node", n.getNodeKey(), "error", "quality 须配置 ruleIds 或草稿 rules"));
            }
            if (ruleIds != null && !ruleIds.isEmpty()) {
                List<String> missing = publishSideEffects.missingRuleIds(conf);
                for (String mid : missing) {
                    issues.add(issue("node", n.getNodeKey(), "error", "quality 规则不存在: " + mid));
                }
            } else if (hasRulesJson) {
                issues.add(issue("node", n.getNodeKey(), "warn", "quality 仅有草稿 rules，发布/试跑不会写 gov_dq_rule_run；建议绑定 ruleIds"));
            }
        } else if ("sink_iceberg".equals(type)) {
            if (StrUtil.isBlank(conf.getStr("table")) && StrUtil.isBlank(conf.getStr("database"))) {
                issues.add(issue("node", n.getNodeKey(), "error", "sink_iceberg 须配置 table"));
            }
            issues.addAll(validateAutoCreate(n.getNodeKey(), conf, "sink_iceberg"));
            issues.addAll(validateSinkTarget(n));
        } else if ("sink_ck".equals(type)) {
            if (StrUtil.isBlank(conf.getStr("table"))) {
                issues.add(issue("node", n.getNodeKey(), "error", "sink_ck 须配置 table"));
            }
            issues.addAll(validateAutoCreate(n.getNodeKey(), conf, "sink_ck"));
            issues.addAll(validateSinkTarget(n));
        } else if ("sink_kafka".equals(type)) {
            if (StrUtil.isBlank(conf.getStr("topic"))) {
                issues.add(issue("node", n.getNodeKey(), "error", "sink_kafka 须配置 topic"));
            }
        } else if ("sink_object".equals(type)) {
            if (StrUtil.isBlank(conf.getStr("bucket"))) {
                issues.add(issue("node", n.getNodeKey(), "error", "sink_object 须配置 bucket"));
            }
        } else if ("sink_ftp".equals(type)) {
            if (StrUtil.isBlank(conf.getStr("host")) && StrUtil.isBlank(conf.getStr("dsId"))) {
                issues.add(issue("node", n.getNodeKey(), "error", "sink_ftp 须配置 host 或 dsId"));
            }
            issues.addAll(validateExportTicket(n.getNodeKey(), conf));
        } else if ("sink_rdb".equals(type)) {
            if (StrUtil.isBlank(conf.getStr("dsId")) && StrUtil.isBlank(conf.getStr("table"))) {
                issues.add(issue("node", n.getNodeKey(), "error", "sink_rdb 须配置 dsId/table"));
            }
            issues.addAll(validateExportTicket(n.getNodeKey(), conf));
            issues.addAll(validateAutoCreate(n.getNodeKey(), conf, "sink_rdb"));
            issues.addAll(validateSinkTarget(n));
        } else if ("sink_search".equals(type)) {
            if (StrUtil.isBlank(conf.getStr("index"))) {
                issues.add(issue("node", n.getNodeKey(), "error", "sink_search 须配置 index"));
            }
            issues.addAll(validateExportTicket(n.getNodeKey(), conf));
        } else if ("sink_bi".equals(type)) {
            if (StrUtil.isBlank(conf.getStr("targetSystem")) && StrUtil.isBlank(conf.getStr("dataset"))
                    && StrUtil.isBlank(conf.getStr("target"))) {
                issues.add(issue("node", n.getNodeKey(), "error", "sink_bi 须配置 targetSystem/dataset"));
            }
            issues.addAll(validateExportTicket(n.getNodeKey(), conf));
        } else if ("parallel".equals(type)) {
            Integer p = conf.getInt("parallelism");
            if (p != null && p < 2) {
                issues.add(issue("node", n.getNodeKey(), "warn", "parallelism 建议 ≥ 2"));
            }
        }
        return issues;
    }

    /** 出湖 sink：ticketNo 须为已审批的 lake_export 单 */
    private List<Map<String, Object>> validateExportTicket(String nodeKey, JSONObject conf) {
        List<Map<String, Object>> issues = new ArrayList<>();
        String ticketNo = StrUtil.trim(conf.getStr("ticketNo"));
        if (StrUtil.isBlank(ticketNo)) {
            issues.add(issue("node", nodeKey, "error",
                    "出湖须填写申请单号 ticketNo（申请中心出湖申请审批通过后回填 EXP-xxx）"));
            return issues;
        }
        try {
            applyTicketService.assertApprovedExportTicket(ticketNo);
        } catch (CommonException e) {
            issues.add(issue("node", nodeKey, "error", e.getMessage()));
        } catch (Exception e) {
            issues.add(issue("node", nodeKey, "error",
                    "出湖单号校验失败: " + StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName())));
        }
        return issues;
    }

    /** 受控自动建表：校验 autoCreate / schemaFrom */
    private List<Map<String, Object>> validateAutoCreate(String nodeKey, JSONObject conf, String sinkType) {
        List<Map<String, Object>> issues = new ArrayList<>();
        String mode = StrUtil.blankToDefault(conf.getStr("autoCreate"), "off");
        if (!List.of("off", "if_not_exists", "fail_if_missing").contains(mode)) {
            issues.add(issue("node", nodeKey, "error",
                    sinkType + " autoCreate 仅支持 off / if_not_exists / fail_if_missing"));
            return issues;
        }
        String schemaFrom = StrUtil.blankToDefault(conf.getStr("schemaFrom"), "upstream");
        if (!List.of("upstream", "mapping", "explicit").contains(schemaFrom)) {
            issues.add(issue("node", nodeKey, "error",
                    sinkType + " schemaFrom 仅支持 upstream / mapping / explicit"));
        }
        if ("if_not_exists".equals(mode)) {
            if ("mapping".equals(schemaFrom)) {
                JSONArray maps = conf.getJSONArray("fieldMaps");
                if (maps == null) {
                    maps = conf.getJSONArray("mapList");
                }
                if (maps == null || maps.isEmpty()) {
                    issues.add(issue("node", nodeKey, "warn",
                            sinkType + " autoCreate=if_not_exists 且 schemaFrom=mapping，但未配置 fieldMaps；将回退上游字段"));
                }
            }
            if ("sink_iceberg".equals(sinkType) && StrUtil.isBlank(conf.getStr("partition"))
                    && !"append".equals(conf.getStr("writeMode"))) {
                issues.add(issue("node", nodeKey, "warn",
                        "Iceberg 自动建表建议配置 partition（如 dt）"));
            }
            if ("sink_ck".equals(sinkType) && StrUtil.isBlank(conf.getStr("orderBy"))) {
                issues.add(issue("node", nodeKey, "warn",
                        "ClickHouse 自动建表建议配置 ORDER BY"));
            }
        }
        return issues;
    }

    private List<Map<String, Object>> validateSinkTarget(IgEtlNode n) {
        return sinkTargetChecker.validateSink(n);
    }

    private Map<String, Object> issue(String scope, String ref, String level, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scope", scope);
        m.put("ref", ref);
        m.put("level", level);
        m.put("message", message);
        return m;
    }

    private boolean hasCycle(List<IgEtlNode> nodes, List<IgEtlEdge> edges) {
        Map<String, List<String>> adj = new HashMap<>();
        for (IgEtlNode n : nodes) {
            adj.put(n.getNodeKey(), new ArrayList<>());
        }
        for (IgEtlEdge e : edges) {
            adj.computeIfAbsent(e.getFromNodeKey(), k -> new ArrayList<>()).add(e.getToNodeKey());
        }
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (String k : adj.keySet()) {
            if (dfsCycle(k, adj, visiting, visited)) {
                return true;
            }
        }
        return false;
    }

    private boolean dfsCycle(String cur, Map<String, List<String>> adj, Set<String> visiting, Set<String> visited) {
        if (visiting.contains(cur)) {
            return true;
        }
        if (visited.contains(cur)) {
            return false;
        }
        visiting.add(cur);
        for (String next : adj.getOrDefault(cur, List.of())) {
            if (dfsCycle(next, adj, visiting, visited)) {
                return true;
            }
        }
        visiting.remove(cur);
        visited.add(cur);
        return false;
    }

    private String truncateKey(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private void upsertWatermark(String sourceSystem, String markKey, String markValue) {
        CbEtlDeployWatermark row = watermarkMapper.selectOne(new QueryWrapper<CbEtlDeployWatermark>().lambda()
                .eq(CbEtlDeployWatermark::getSourceSystem, sourceSystem)
                .eq(CbEtlDeployWatermark::getMarkKey, markKey));
        if (row == null) {
            row = new CbEtlDeployWatermark();
            row.setId(IdUtil.getSnowflakeNextIdStr());
            row.setSourceSystem(sourceSystem);
            row.setMarkKey(markKey);
            row.setMarkValue(markValue);
            row.setUpdateTime(new Date());
            watermarkMapper.insert(row);
        } else {
            row.setMarkValue(markValue);
            row.setUpdateTime(new Date());
            watermarkMapper.updateById(row);
        }
    }

    private String bumpVer(String ver) {
        if (StrUtil.isBlank(ver)) {
            return "v0.1";
        }
        String v = ver.startsWith("v") || ver.startsWith("V") ? ver.substring(1) : ver;
        String[] parts = v.split("\\.");
        try {
            if (parts.length >= 2) {
                int minor = Integer.parseInt(parts[1]);
                return "v" + parts[0] + "." + (minor + 1);
            }
            int major = Integer.parseInt(parts[0]);
            return "v" + (major + 1);
        } catch (Exception e) {
            return ver + ".1";
        }
    }

    private String submitShape(String engine) {
        if ("flink".equalsIgnoreCase(engine)) {
            return "DS_FLINK_OR_SHELL";
        }
        if ("spark".equalsIgnoreCase(engine)) {
            return "DS_SPARK_OR_SHELL_SUBMIT";
        }
        if ("datax".equalsIgnoreCase(engine)) {
            return "DS_SHELL_DATAX";
        }
        if ("ds_sql".equalsIgnoreCase(engine)) {
            return "DS_SQL";
        }
        return "DS_SHELL";
    }

    private String str(Object o, String def) {
        if (o == null) {
            return def;
        }
        String s = String.valueOf(o);
        return StrUtil.isBlank(s) || "null".equals(s) ? def : s;
    }

    private int intVal(Object o, int def) {
        if (o == null) {
            return def;
        }
        if (o instanceof Number) {
            return ((Number) o).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (Exception e) {
            return def;
        }
    }
}
