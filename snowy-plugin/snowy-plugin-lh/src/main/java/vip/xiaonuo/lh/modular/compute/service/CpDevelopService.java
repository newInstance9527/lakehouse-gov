package vip.xiaonuo.lh.modular.compute.service;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.xiaonuo.auth.core.pojo.SaBaseLoginUser;
import vip.xiaonuo.auth.core.util.StpLoginUserUtil;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.core.engine.DsClient;
import vip.xiaonuo.lh.modular.compute.entity.CpRelease;
import vip.xiaonuo.lh.modular.compute.entity.CpScriptIndex;
import vip.xiaonuo.lh.modular.compute.entity.CpScriptRun;
import vip.xiaonuo.lh.modular.compute.entity.CpUdf;
import vip.xiaonuo.lh.modular.compute.mapper.CpReleaseMapper;
import vip.xiaonuo.lh.modular.compute.mapper.CpScriptIndexMapper;
import vip.xiaonuo.lh.modular.compute.mapper.CpScriptRunMapper;
import vip.xiaonuo.lh.modular.compute.mapper.CpUdfMapper;
import vip.xiaonuo.lh.modular.compute.param.CpReleaseCreateParam;
import vip.xiaonuo.lh.modular.compute.param.CpScriptCreateParam;
import vip.xiaonuo.lh.modular.compute.param.CpScriptRunParam;
import vip.xiaonuo.lh.modular.compute.param.CpScriptSaveParam;
import vip.xiaonuo.lh.modular.compute.support.CpScriptGitStore;
import vip.xiaonuo.lh.modular.compute.support.CpScriptLint;
import vip.xiaonuo.lh.modular.query.param.CpQueryExecParam;
import vip.xiaonuo.lh.modular.query.service.CpQueryService;
import vip.xiaonuo.lh.modular.workspace.entity.GovWs;
import vip.xiaonuo.lh.modular.workspace.mapper.GovWsMapper;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 数据开发脚本：Git 存正文，库只存索引、试跑和发布单。
 */
@Service
public class CpDevelopService {

    private static final int TRIAL_MAX_ROWS = 200;
    private static final int TRIAL_CELL_CHARS = 400;
    private static final int TRIAL_LOG_CHARS = 8000;
    private static final Pattern READ_HEAD = Pattern.compile("^(select|with)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern WRITE_SQL = Pattern.compile(
            "\\b(insert|update|delete|merge|drop|truncate|alter|create)\\b", Pattern.CASE_INSENSITIVE);

    @Resource
    private CpScriptIndexMapper scriptMapper;
    @Resource
    private CpScriptRunMapper runMapper;
    @Resource
    private CpReleaseMapper releaseMapper;
    @Resource
    private CpUdfMapper udfMapper;
    @Resource
    private GovWsMapper govWsMapper;
    @Resource
    private CpScriptGitStore gitStore;
    @Resource
    private DsClient dsClient;
    @Resource
    private LhProperties lhProperties;
    @Resource
    private CpQueryService cpQueryService;

    public Map<String, Object> tree(String ws) {
        String workspace = ws(ws);
        List<CpScriptIndex> rows = scriptMapper.selectList(new QueryWrapper<CpScriptIndex>().lambda()
                .eq(CpScriptIndex::getWs, workspace)
                .eq(CpScriptIndex::getDeleteFlag, "NOT_DELETE")
                .orderByAsc(CpScriptIndex::getPath));
        List<Map<String, Object>> nodes = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (CpScriptIndex row : rows) {
            String folder = StrUtil.blankToDefault(row.getFolder(), "default");
            String folderId = "folder:" + folder;
            if (seen.add(folderId)) {
                Map<String, Object> dir = new LinkedHashMap<>();
                dir.put("id", folderId);
                dir.put("type", "folder");
                dir.put("name", folder);
                dir.put("open", true);
                nodes.add(dir);
            }
            nodes.add(fileNode(row, folderId));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ws", workspace);
        out.put("nodes", nodes);
        return out;
    }

    public Map<String, Object> content(String id) {
        CpScriptIndex row = requireScript(id);
        Map<String, Object> out = fileNode(row, "folder:" + StrUtil.blankToDefault(row.getFolder(), "default"));
        out.put("sql", gitStore.read(row.getWs(), row.getPath()));
        out.put("lint", readLint(row.getLintJson()));
        return out;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> create(CpScriptCreateParam param) {
        UserSnap user = currentUser();
        String workspace = ws(param.getWs());
        String engine;
        String env;
        String path;
        try {
            engine = CpScriptLint.normalizeEngine(param.getEngine());
            env = CpScriptLint.normalizeEnv(param.getEnv());
            path = CpScriptLint.normalizePath(param.getFolder(), param.getName(), null);
        } catch (IllegalArgumentException e) {
            throw new CommonException(e.getMessage());
        }
        if (findByPath(workspace, path) != null) {
            throw new CommonException("脚本已存在: {}", path);
        }
        String sql = StrUtil.blankToDefault(param.getSql(), "-- " + CpScriptLint.nameOf(path) + "\nSELECT 1;\n");
        String sha = gitStore.commit(workspace, path, sql, "create " + path, user.name, user.email);
        Date now = new Date();
        CpScriptIndex row = new CpScriptIndex();
        row.setId(IdUtil.getSnowflakeNextIdStr());
        row.setWs(workspace);
        row.setPath(path);
        row.setName(CpScriptLint.nameOf(path));
        row.setFolder(CpScriptLint.folderOf(path));
        row.setEngine(engine);
        row.setEnv(env);
        row.setStatus(CpScriptLint.hasError(CpScriptLint.check(sql)) ? "FAILED" : "DRAFT");
        row.setGitSha(sha);
        row.setAuthorName(user.name);
        row.setLintJson(JSONUtil.toJsonStr(CpScriptLint.check(sql)));
        row.setDeleteFlag("NOT_DELETE");
        row.setCreateTime(now);
        row.setCreateUser(user.id);
        row.setUpdateTime(now);
        row.setUpdateUser(user.id);
        scriptMapper.insert(row);
        Map<String, Object> out = content(row.getId());
        out.put("remoteWarning", pushRemote(workspace));
        return out;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> commit(CpScriptSaveParam param) {
        UserSnap user = currentUser();
        CpScriptIndex row = requireScript(param.getId());
        String engine;
        String env;
        try {
            engine = CpScriptLint.normalizeEngine(StrUtil.blankToDefault(param.getEngine(), row.getEngine()));
            env = CpScriptLint.normalizeEnv(StrUtil.blankToDefault(param.getEnv(), row.getEnv()));
        } catch (IllegalArgumentException e) {
            throw new CommonException(e.getMessage());
        }
        String sql = param.getSql() == null ? gitStore.read(row.getWs(), row.getPath()) : param.getSql();
        List<Map<String, String>> lint = CpScriptLint.check(sql);
        String sha = gitStore.commit(row.getWs(), row.getPath(), sql, "save " + row.getPath(), user.name, user.email);
        row.setEngine(engine);
        row.setEnv(env);
        row.setGitSha(sha);
        row.setAuthorName(user.name);
        row.setLintJson(JSONUtil.toJsonStr(lint));
        if (!"IN_REVIEW".equals(row.getStatus()) && !"PUBLISHED".equals(row.getStatus())) {
            row.setStatus(CpScriptLint.hasError(lint) ? "FAILED" : "DRAFT");
        }
        row.setUpdateTime(new Date());
        row.setUpdateUser(user.id);
        scriptMapper.updateById(row);
        Map<String, Object> out = content(row.getId());
        out.put("remoteWarning", pushRemote(row.getWs()));
        return out;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> runStg(CpScriptRunParam param) {
        UserSnap user = currentUser();
        CpScriptIndex row = requireScript(param.getId());
        if (param.getSql() != null || StrUtil.isNotBlank(param.getEngine()) || StrUtil.isNotBlank(param.getEnv())) {
            CpScriptSaveParam save = new CpScriptSaveParam();
            save.setId(row.getId());
            save.setSql(param.getSql());
            save.setEngine(param.getEngine());
            save.setEnv(param.getEnv());
            commit(save);
            row = requireScript(row.getId());
        }
        List<Map<String, String>> lint = CpScriptLint.check(gitStore.read(row.getWs(), row.getPath()));
        if (CpScriptLint.hasError(lint)) {
            throw new CommonException("静态检查未通过，不能试跑");
        }
        String sql = gitStore.read(row.getWs(), row.getPath());
        String engine;
        String env;
        try {
            engine = CpScriptLint.normalizeEngine(row.getEngine());
            env = CpScriptLint.normalizeEnv(row.getEnv());
        } catch (IllegalArgumentException e) {
            throw new CommonException(e.getMessage());
        }
        Date now = new Date();
        CpScriptRun run = new CpScriptRun();
        run.setId(IdUtil.getSnowflakeNextIdStr());
        run.setRunId("run-" + IdUtil.getSnowflakeNextIdStr());
        run.setScriptId(row.getId());
        run.setWs(row.getWs());
        run.setEngine(engine);
        run.setEnv(env);
        run.setGitSha(row.getGitSha());
        run.setDeleteFlag("NOT_DELETE");
        run.setCreateTime(now);
        run.setCreateUser(user.id);
        run.setUpdateTime(now);
        run.setUpdateUser(user.id);

        if ("trino".equals(engine)) {
            fillTrinoRun(run, sql, row.getWs());
        } else {
            fillDsRun(run, row, sql, true);
        }
        runMapper.insert(run);
        if ("ok".equals(run.getStatus()) || "running".equals(run.getStatus())) {
            row.setStatus("READY");
            row.setUpdateTime(new Date());
            scriptMapper.updateById(row);
        }
        return runView(run, true);
    }

    public List<Map<String, Object>> runs(String scriptId) {
        requireScript(scriptId);
        List<CpScriptRun> rows = runMapper.selectList(new QueryWrapper<CpScriptRun>().lambda()
                .eq(CpScriptRun::getScriptId, scriptId)
                .eq(CpScriptRun::getDeleteFlag, "NOT_DELETE")
                .orderByDesc(CpScriptRun::getCreateTime)
                .last("LIMIT 20"));
        List<Map<String, Object>> out = new ArrayList<>();
        for (CpScriptRun row : rows) {
            out.add(runView(row, false));
        }
        return out;
    }

    public Map<String, Object> runDetail(String runId) {
        CpScriptRun run = requireRun(runId);
        if (refreshDsIfNeeded(run)) {
            run.setUpdateTime(new Date());
            runMapper.updateById(run);
        }
        return runView(run, true);
    }

    public List<Map<String, Object>> kpis(String ws) {
        String workspace = ws(ws);
        long scripts = scriptMapper.selectCount(new QueryWrapper<CpScriptIndex>().lambda()
                .eq(CpScriptIndex::getWs, workspace)
                .eq(CpScriptIndex::getDeleteFlag, "NOT_DELETE"));
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.DAY_OF_WEEK, cal.getFirstDayOfWeek());
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        long weekRuns = runMapper.selectCount(new QueryWrapper<CpScriptRun>().lambda()
                .eq(CpScriptRun::getWs, workspace)
                .ge(CpScriptRun::getCreateTime, cal.getTime()));
        long review = releaseMapper.selectCount(new QueryWrapper<CpRelease>().lambda()
                .eq(CpRelease::getWs, workspace)
                .eq(CpRelease::getStatus, "IN_REVIEW")
                .eq(CpRelease::getDeleteFlag, "NOT_DELETE"));
        long udfs = udfMapper.selectCount(new QueryWrapper<CpUdf>().lambda()
                .eq(CpUdf::getStatus, "ENABLE")
                .eq(CpUdf::getDeleteFlag, "NOT_DELETE"));
        return List.of(
                kpi("工作空间脚本数", scripts, "个", "Git 索引"),
                kpi("本周试跑", weekRuns, "次", "含校验"),
                kpi("待审发布单", review, "单", "门禁中"),
                kpi("登记 UDF", udfs, "个", "按引擎过滤")
        );
    }

    public List<Map<String, Object>> udfs(String engine) {
        String key = StrUtil.blankToDefault(engine, "").trim().toLowerCase(Locale.ROOT);
        List<CpUdf> rows = udfMapper.selectList(new QueryWrapper<CpUdf>().lambda()
                .eq(CpUdf::getStatus, "ENABLE")
                .eq(CpUdf::getDeleteFlag, "NOT_DELETE")
                .orderByAsc(CpUdf::getName));
        List<Map<String, Object>> out = new ArrayList<>();
        for (CpUdf row : rows) {
            if (StrUtil.isNotBlank(key) && !containsEngine(row.getEngines(), key)) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", row.getName());
            m.put("desc", row.getDescription());
            m.put("engine", row.getEngineLabel());
            m.put("engines", row.getEngines());
            m.put("ver", row.getVer());
            m.put("uses", row.getUsesText());
            m.put("snippet", row.getSnippet());
            out.add(m);
        }
        return out;
    }

    public List<Map<String, Object>> releases(String ws) {
        String workspace = ws(ws);
        List<CpRelease> rows = releaseMapper.selectList(new QueryWrapper<CpRelease>().lambda()
                .eq(CpRelease::getWs, workspace)
                .eq(CpRelease::getDeleteFlag, "NOT_DELETE")
                .orderByDesc(CpRelease::getCreateTime)
                .last("LIMIT 50"));
        List<Map<String, Object>> out = new ArrayList<>();
        for (CpRelease row : rows) {
            out.add(releaseView(row));
        }
        return out;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> createRelease(CpReleaseCreateParam param) {
        UserSnap user = currentUser();
        CpScriptIndex script = requireScript(param.getScriptId());
        String sql = gitStore.read(script.getWs(), script.getPath());
        List<Map<String, String>> lint = CpScriptLint.check(sql);
        String engine;
        String relEnv;
        try {
            engine = CpScriptLint.normalizeEngine(StrUtil.blankToDefault(param.getEngine(), script.getEngine()));
            relEnv = CpScriptLint.releaseEnv(StrUtil.blankToDefault(param.getEnv(), script.getEnv()));
        } catch (IllegalArgumentException e) {
            throw new CommonException(e.getMessage());
        }
        boolean trialOk = hasSuccessfulRun(script.getId());
        List<Map<String, Object>> gates = buildGates(script, lint, trialOk);
        Date now = new Date();
        CpRelease row = new CpRelease();
        row.setId(IdUtil.getSnowflakeNextIdStr());
        row.setWs(script.getWs());
        row.setScriptId(script.getId());
        row.setPkg(pkgName(script));
        row.setScriptName(script.getName());
        row.setScriptPath(script.getPath());
        row.setEngine(engine);
        row.setEnv(relEnv);
        row.setGitSha(script.getGitSha());
        row.setStatus("IN_REVIEW");
        row.setResultLabel(blocking(gates) ? "未通过" : "门禁中");
        if (blocking(gates)) {
            row.setStatus("REJECTED");
        }
        row.setGatesJson(JSONUtil.toJsonStr(gates));
        row.setDeleteFlag("NOT_DELETE");
        row.setCreateTime(now);
        row.setCreateUser(user.id);
        row.setUpdateTime(now);
        row.setUpdateUser(user.id);
        releaseMapper.insert(row);
        script.setStatus(blocking(gates) ? "FAILED" : "IN_REVIEW");
        script.setEngine(engine);
        script.setUpdateTime(now);
        scriptMapper.updateById(script);
        return releaseView(row);
    }

    public Map<String, Object> gates(String id) {
        return releaseView(requireRelease(id));
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> publish(String id) {
        UserSnap user = currentUser();
        CpRelease row = requireRelease(id);
        List<Map<String, Object>> gates = readGates(row.getGatesJson());
        if (blocking(gates)) {
            throw new CommonException("门禁未通过，不能标为已发布");
        }
        if ("PUBLISHED".equals(row.getStatus())) {
            return releaseView(row);
        }
        CpScriptIndex script = requireScript(row.getScriptId());
        String sql = gitStore.read(script.getWs(), script.getPath());
        String tag = gitStore.tag(script.getWs(), "rel-" + row.getId(), "publish " + row.getPkg());
        if (!"trino".equalsIgnoreCase(script.getEngine())) {
            CpScriptRun projection = new CpScriptRun();
            projection.setEngine(script.getEngine());
            projection.setEnv(script.getEnv());
            fillDsRun(projection, script, sql, false);
            if ("failed".equals(projection.getStatus())) {
                throw new CommonException("投影调度失败: {}", projection.getMessage());
            }
            row.setDsWorkflowCode(projection.getDsWorkflowCode());
        }
        for (Map<String, Object> gate : gates) {
            if ("生产发布".equals(String.valueOf(gate.get("name")))) {
                gate.put("status", "pass");
                gate.put("detail", "已打 tag " + tag + " 并投影调度");
            }
        }
        row.setGitTag(tag);
        row.setGitSha(script.getGitSha());
        row.setStatus("PUBLISHED");
        row.setResultLabel("成功");
        row.setGatesJson(JSONUtil.toJsonStr(gates));
        row.setUpdateTime(new Date());
        row.setUpdateUser(user.id);
        releaseMapper.updateById(row);
        script.setStatus("PUBLISHED");
        script.setUpdateTime(new Date());
        scriptMapper.updateById(script);
        return releaseView(row);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> rollback(String id) {
        UserSnap user = currentUser();
        CpRelease row = requireRelease(id);
        if (StrUtil.isBlank(row.getGitTag()) && !"PUBLISHED".equals(row.getStatus())
                && !"ROLLED_BACK".equals(row.getStatus())) {
            throw new CommonException("尚未发布，没有可回滚的 Git tag");
        }
        CpScriptIndex script = requireScript(row.getScriptId());
        String previous = gitStore.previousTag(script.getWs(), row.getGitTag());
        if (StrUtil.isBlank(previous) || previous.equals(row.getGitTag())) {
            throw new CommonException("没有更早的 Git tag，无法回滚");
        }
        String sql = gitStore.readAt(script.getWs(), script.getPath(), previous);
        CpScriptRun projection = new CpScriptRun();
        projection.setEngine(script.getEngine());
        if (!"trino".equalsIgnoreCase(script.getEngine())) {
            fillDsRun(projection, script, sql, false);
            if ("failed".equals(projection.getStatus())) {
                throw new CommonException("按 tag {} 重新投影失败: {}", previous, projection.getMessage());
            }
            row.setDsWorkflowCode(projection.getDsWorkflowCode());
        }
        row.setStatus("ROLLED_BACK");
        row.setResultLabel("回滚");
        row.setRolledToTag(previous);
        row.setUpdateTime(new Date());
        row.setUpdateUser(user.id);
        releaseMapper.updateById(row);
        script.setStatus("READY");
        script.setGitSha(previous);
        script.setUpdateTime(new Date());
        scriptMapper.updateById(script);
        Map<String, Object> out = releaseView(row);
        out.put("rolledSqlSha", previous);
        return out;
    }

    private void fillTrinoRun(CpScriptRun run, String sql, String ws) {
        CpQueryExecParam q = new CpQueryExecParam();
        q.setSql(sql);
        q.setWs(ws);
        q.setMaxRows(TRIAL_MAX_ROWS);
        Map<String, Object> resp;
        try {
            resp = cpQueryService.exec(q);
        } catch (CommonException e) {
            run.setStatus("failed");
            run.setMessage("Trino 校验失败: " + StrUtil.maxLength(StrUtil.blankToDefault(e.getMessage(), "执行失败"), 500));
            writeResult(run, "trino", List.of(), List.of(), false, null, null);
            return;
        }
        String status = String.valueOf(resp.getOrDefault("status", "failed"));
        boolean ok = "ok".equalsIgnoreCase(status);
        run.setStatus(ok ? "ok" : "failed");
        Object err = resp.get("errorMsg");
        if (err == null) {
            err = resp.get("message");
        }
        if (err == null) {
            err = resp.get("statusLabel");
        }
        run.setMessage(ok
                ? "Trino 试跑完成 · query " + resp.get("queryId")
                : "Trino 校验失败: " + StrUtil.maxLength(String.valueOf(err), 500));
        run.setLogUri(resp.get("queryId") == null ? null : "trino:" + resp.get("queryId"));
        Sample sample = sampleOf(resp);
        if (sample.rowCount != null) {
            run.setRowCount(sample.rowCount);
        }
        if (resp.get("durMs") instanceof Number n) {
            run.setDurMs(n.longValue());
        }
        writeResult(run, "trino", sample.columns, sample.rows, sample.truncated, null, null);
    }

    private void fillDsRun(CpScriptRun run, CpScriptIndex script, String sql, boolean trial) {
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("nodeKey", "script");
        task.put("name", script.getName());
        task.put("engine", script.getEngine());
        task.put("taskType", "flink".equalsIgnoreCase(script.getEngine()) ? "FLINK" : "SPARK");
        task.put("failRetryTimes", 0);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("sql", sql);
        params.put("lhSql", sql);
        params.put("rawScript", sql);
        if ("spark".equalsIgnoreCase(script.getEngine()) && lhProperties.getSpark() != null) {
            params.put("master", lhProperties.getSpark().getMaster());
        }
        task.put("taskParams", params);

        String code = "lh_script_" + script.getId();
        Map<String, Object> wf = new LinkedHashMap<>();
        wf.put("workflowCode", code);
        wf.put("name", code);
        wf.put("description", "lakehouse script " + script.getPath());
        wf.put("trial", trial);
        wf.put("tasks", List.of(task));
        wf.put("taskCount", 1);
        wf.put("edgeCount", 0);
        Map<String, Object> created = dsClient.createOrUpdateWorkflow(wf);
        String wfCode = str(created.get("workflowCode"));
        run.setDsWorkflowCode(wfCode);
        if (Boolean.TRUE.equals(created.get("degraded")) && !Boolean.TRUE.equals(created.get("ok"))) {
            run.setStatus("failed");
            run.setMessage("调度投影失败: " + str(created.get("message")));
            if (trial) {
                attachReadPreview(run, sql, script.getWs(), "");
            }
            return;
        }
        if (!trial) {
            if (Boolean.TRUE.equals(created.get("degraded"))) {
                run.setStatus("failed");
                run.setMessage(str(created.get("message")));
            } else {
                run.setStatus("ok");
                run.setMessage("已投影调度流程 " + wfCode);
            }
            return;
        }
        Map<String, Object> start = dsClient.startProcessInstance(wfCode, Map.of(
                "script_id", script.getId(),
                "env", script.getEnv(),
                "trial", "true"));
        String instanceId = str(start.get("processInstanceId"));
        boolean degraded = Boolean.TRUE.equals(created.get("degraded")) || Boolean.TRUE.equals(start.get("degraded"));
        if (degraded) {
            run.setStatus("failed");
            run.setMessage("试跑未提交到引擎: " + StrUtil.maxLength(
                    str(start.get("message")) + " " + str(created.get("message")), 500));
        } else {
            run.setDsInstanceId(instanceId);
            run.setStatus(StrUtil.isBlank(instanceId) ? "submitted" : "running");
            run.setMessage(StrUtil.isBlank(instanceId)
                    ? "已调用调度启动，未解析到实例 id，请到 DolphinScheduler 查看"
                    : "试跑已提交 DS 实例 " + instanceId);
            if (StrUtil.isNotBlank(instanceId)) {
                run.setLogUri("ds:" + instanceId);
            }
        }
        String log = StrUtil.isBlank(instanceId) ? "" : pullDsLog(instanceId);
        attachReadPreview(run, sql, script.getWs(), log);
    }

    private void attachReadPreview(CpScriptRun run, String sql, String ws, String log) {
        Sample sample = Sample.empty();
        String previewNote = null;
        if (isReadQuery(sql)) {
            Preview preview = trinoPreview(sql, ws);
            sample = preview.sample;
            previewNote = preview.note;
            if (sample.rowCount != null) {
                run.setRowCount(sample.rowCount);
            }
        }
        String source = sample.columns.isEmpty() ? "ds-log" : "trino-check";
        writeResult(run, source, sample.columns, sample.rows, sample.truncated, previewNote, log);
    }

    private Preview trinoPreview(String sql, String ws) {
        CpQueryExecParam q = new CpQueryExecParam();
        q.setSql(sql);
        q.setWs(ws);
        q.setMaxRows(TRIAL_MAX_ROWS);
        try {
            Map<String, Object> resp = cpQueryService.exec(q);
            String status = String.valueOf(resp.getOrDefault("status", "failed"));
            if (!"ok".equalsIgnoreCase(status)) {
                Object err = resp.get("message");
                if (err == null) {
                    err = resp.get("statusLabel");
                }
                return new Preview(Sample.empty(), "Trino 抽样未通过: " + StrUtil.maxLength(String.valueOf(err), 300));
            }
            return new Preview(sampleOf(resp), "结果表是 Trino 抽样校验，不是 Spark/Flink 引擎输出");
        } catch (CommonException e) {
            return new Preview(Sample.empty(), "Trino 抽样未通过: " + StrUtil.maxLength(
                    StrUtil.blankToDefault(e.getMessage(), "执行失败"), 300));
        }
    }

    private Sample sampleOf(Map<String, Object> resp) {
        List<String> columns = new ArrayList<>();
        Object cols = resp.get("columns");
        if (cols instanceof List<?> list) {
            for (Object col : list) {
                if (col != null) {
                    columns.add(String.valueOf(col));
                }
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        boolean truncated = Boolean.TRUE.equals(resp.get("truncated"));
        Object rawRows = resp.get("rows");
        if (rawRows instanceof List<?> list) {
            if (list.size() > TRIAL_MAX_ROWS) {
                truncated = true;
            }
            int n = 0;
            for (Object item : list) {
                if (n >= TRIAL_MAX_ROWS) {
                    break;
                }
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> cell = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        cell.put(String.valueOf(entry.getKey()), clipCell(entry.getValue()));
                    }
                    rows.add(cell);
                }
                n++;
            }
        }
        Integer rowCount = resp.get("rowCount") instanceof Number num ? num.intValue() : rows.size();
        return new Sample(columns, rows, truncated, rowCount);
    }

    private void writeResult(CpScriptRun run, String source, List<String> columns, List<Map<String, Object>> rows,
                             boolean truncated, String previewNote, String log) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", source);
        body.put("columns", columns == null ? List.of() : columns);
        body.put("rows", rows == null ? List.of() : rows);
        body.put("truncated", truncated);
        if (StrUtil.isNotBlank(previewNote)) {
            body.put("previewNote", previewNote);
        }
        if (StrUtil.isNotBlank(log)) {
            body.put("log", StrUtil.maxLength(log, TRIAL_LOG_CHARS));
        }
        String json = JSONUtil.toJsonStr(body);
        if (json.length() > 500_000 && rows != null && rows.size() > 20) {
            body.put("rows", rows.subList(0, 20));
            body.put("truncated", true);
            json = JSONUtil.toJsonStr(body);
        }
        run.setResultJson(json);
    }

    private String pullDsLog(String processInstanceId) {
        Map<String, Object> listed = dsClient.listTaskInstances(processInstanceId);
        Object tasks = listed.get("tasks");
        if (!(tasks instanceof List<?> list) || list.isEmpty()) {
            return "";
        }
        Object first = list.get(0);
        if (!(first instanceof Map<?, ?> task)) {
            return "";
        }
        String taskId = str(task.get("id"));
        if (StrUtil.isBlank(taskId)) {
            return "";
        }
        Map<String, Object> log = dsClient.queryTaskInstanceLog(taskId, 0, 400);
        String text = str(log.get("content"));
        if (StrUtil.isBlank(text)) {
            text = str(log.get("message"));
        }
        return StrUtil.maxLength(text, TRIAL_LOG_CHARS);
    }

    private boolean refreshDsIfNeeded(CpScriptRun run) {
        if (run == null || StrUtil.isBlank(run.getDsInstanceId())) {
            return false;
        }
        if (!"running".equals(run.getStatus()) && !"submitted".equals(run.getStatus())) {
            return false;
        }
        boolean changed = false;
        Map<String, Object> inst = dsClient.getProcessInstance(run.getDsInstanceId());
        String state = str(inst.get("state")).toUpperCase(Locale.ROOT);
        if (state.contains("SUCCESS")) {
            run.setStatus("ok");
            run.setMessage("调度实例成功 " + run.getDsInstanceId());
            changed = true;
        } else if (state.contains("FAIL") || state.contains("STOP") || state.contains("KILL")) {
            run.setStatus("failed");
            run.setMessage("调度实例失败 " + state + " · " + run.getDsInstanceId());
            changed = true;
        }
        String log = pullDsLog(run.getDsInstanceId());
        if (StrUtil.isNotBlank(log)) {
            JSONObject sample = readResult(run.getResultJson());
            if (!log.equals(sample.getStr("log"))) {
                sample.set("log", log);
                run.setResultJson(sample.toString());
                changed = true;
            }
        }
        return changed;
    }

    private CpScriptRun requireRun(String runId) {
        if (StrUtil.isBlank(runId)) {
            throw new CommonException("缺少试跑号");
        }
        CpScriptRun row = runMapper.selectOne(new QueryWrapper<CpScriptRun>().lambda()
                .eq(CpScriptRun::getRunId, runId)
                .eq(CpScriptRun::getDeleteFlag, "NOT_DELETE")
                .last("LIMIT 1"));
        if (row == null) {
            throw new CommonException("试跑不存在");
        }
        return row;
    }

    private static boolean isReadQuery(String sql) {
        String text = String.valueOf(sql == null ? "" : sql)
                .replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)--[^\\n]*", " ")
                .trim();
        if (!READ_HEAD.matcher(text).find()) {
            return false;
        }
        return !WRITE_SQL.matcher(text).find();
    }

    private static Object clipCell(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        if (text.length() <= TRIAL_CELL_CHARS) {
            return value;
        }
        return text.substring(0, TRIAL_CELL_CHARS) + "…";
    }

    private static JSONObject readResult(String json) {
        if (StrUtil.isBlank(json) || !JSONUtil.isTypeJSONObject(json)) {
            return new JSONObject();
        }
        return JSONUtil.parseObj(json);
    }

    private static List<Object> jsonList(Object raw) {
        if (raw instanceof JSONArray arr) {
            return new ArrayList<>(arr);
        }
        if (raw instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        return List.of();
    }

    private static List<Object> jsonRows(Object raw) {
        return jsonList(raw);
    }

    private static final class Sample {
        private final List<String> columns;
        private final List<Map<String, Object>> rows;
        private final boolean truncated;
        private final Integer rowCount;

        private Sample(List<String> columns, List<Map<String, Object>> rows, boolean truncated, Integer rowCount) {
            this.columns = columns;
            this.rows = rows;
            this.truncated = truncated;
            this.rowCount = rowCount;
        }

        private static Sample empty() {
            return new Sample(List.of(), List.of(), false, null);
        }
    }

    private static final class Preview {
        private final Sample sample;
        private final String note;

        private Preview(Sample sample, String note) {
            this.sample = sample;
            this.note = note;
        }
    }

    private List<Map<String, Object>> buildGates(CpScriptIndex script, List<Map<String, String>> lint, boolean trialOk) {
        boolean lintFail = CpScriptLint.hasError(lint);
        String lintDetail = lint.isEmpty() ? "无检查项" : lint.get(0).get("label");
        List<Map<String, Object>> gates = new ArrayList<>();
        gates.add(gate(1, "静态检查", lintDetail, lintFail ? "fail" : "pass"));
        gates.add(gate(2, "血缘解析入库", "未接线", "skip"));
        gates.add(gate(3, "质量规则绑定", "未接线", "skip"));
        gates.add(gate(4, "stg 试跑", trialOk ? "已有成功或运行中的试跑" : "尚无成功试跑", trialOk ? "pass" : "fail"));
        gates.add(gate(5, "变更影响", "未接线，不阻断", "skip"));
        gates.add(gate(6, "生产发布", "等待门禁通过后发布 " + script.getName(), "wait"));
        return gates;
    }

    private static Map<String, Object> gate(int step, String name, String detail, String status) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("step", step);
        m.put("name", name);
        m.put("detail", detail);
        m.put("status", status);
        return m;
    }

    private static boolean blocking(List<Map<String, Object>> gates) {
        for (Map<String, Object> gate : gates) {
            if ("fail".equals(String.valueOf(gate.get("status")))) {
                return true;
            }
        }
        return false;
    }

    private boolean hasSuccessfulRun(String scriptId) {
        Long n = runMapper.selectCount(new QueryWrapper<CpScriptRun>().lambda()
                .eq(CpScriptRun::getScriptId, scriptId)
                .in(CpScriptRun::getStatus, List.of("ok", "running")));
        return n != null && n > 0;
    }

    private String pushRemote(String ws) {
        GovWs space = govWsMapper.selectOne(new QueryWrapper<GovWs>().lambda()
                .eq(GovWs::getWsCode, ws)
                .last("LIMIT 1"));
        if (space == null) {
            space = govWsMapper.selectOne(new QueryWrapper<GovWs>().lambda()
                    .eq(GovWs::getWs, ws)
                    .last("LIMIT 1"));
        }
        if (space == null || StrUtil.isBlank(space.getGitRemoteUrl())) {
            return null;
        }
        return gitStore.bindRemote(ws, space.getGitRemoteUrl());
    }

    private CpScriptIndex requireScript(String id) {
        if (StrUtil.isBlank(id)) {
            throw new CommonException("缺少脚本 id");
        }
        CpScriptIndex row = scriptMapper.selectById(id);
        if (row == null || "DELETE".equals(row.getDeleteFlag())) {
            throw new CommonException("脚本不存在");
        }
        return row;
    }

    private CpRelease requireRelease(String id) {
        CpRelease row = releaseMapper.selectById(id);
        if (row == null || "DELETE".equals(row.getDeleteFlag())) {
            throw new CommonException("发布单不存在");
        }
        return row;
    }

    private CpScriptIndex findByPath(String ws, String path) {
        return scriptMapper.selectOne(new QueryWrapper<CpScriptIndex>().lambda()
                .eq(CpScriptIndex::getWs, ws)
                .eq(CpScriptIndex::getPath, path)
                .last("LIMIT 1"));
    }

    private Map<String, Object> fileNode(CpScriptIndex row, String folderId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", row.getId());
        m.put("type", "file");
        m.put("folder", folderId);
        m.put("name", row.getName());
        m.put("path", row.getPath());
        m.put("lang", "SQL");
        m.put("status", row.getStatus());
        m.put("engine", row.getEngine());
        m.put("env", row.getEnv());
        m.put("version", shortSha(row.getGitSha()));
        m.put("author", row.getAuthorName());
        m.put("editedAt", row.getUpdateTime() == null ? "" : String.valueOf(row.getUpdateTime()));
        m.put("links", row.getPath());
        m.put("lint", readLint(row.getLintJson()));
        return m;
    }

    private Map<String, Object> runView(CpScriptRun row) {
        return runView(row, false);
    }

    private Map<String, Object> runView(CpScriptRun row, boolean detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", row.getId());
        m.put("runId", row.getRunId());
        m.put("scriptId", row.getScriptId());
        m.put("engine", row.getEngine());
        m.put("env", row.getEnv());
        m.put("status", row.getStatus());
        m.put("message", row.getMessage());
        m.put("logUri", row.getLogUri());
        m.put("gitSha", shortSha(row.getGitSha()));
        m.put("rowCount", row.getRowCount());
        m.put("durMs", row.getDurMs());
        m.put("time", row.getCreateTime());
        JSONObject sample = readResult(row.getResultJson());
        boolean hasRows = sample.getJSONArray("columns") != null && !sample.getJSONArray("columns").isEmpty();
        boolean hasLog = StrUtil.isNotBlank(sample.getStr("log"));
        m.put("hasResult", hasRows || hasLog);
        m.put("resultSource", sample.getStr("source"));
        if (!detail) {
            return m;
        }
        m.put("columns", jsonList(sample.get("columns")));
        m.put("rows", jsonRows(sample.get("rows")));
        m.put("log", StrUtil.blankToDefault(sample.getStr("log"), ""));
        m.put("truncated", Boolean.TRUE.equals(sample.getBool("truncated")));
        m.put("previewNote", sample.getStr("previewNote"));
        return m;
    }

    private Map<String, Object> releaseView(CpRelease row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", row.getId());
        m.put("pkg", row.getPkg());
        m.put("tag", StrUtil.blankToDefault(row.getRolledToTag(), StrUtil.blankToDefault(row.getGitTag(), "—")));
        m.put("gitTag", row.getGitTag());
        m.put("rolledToTag", row.getRolledToTag());
        m.put("env", row.getEnv());
        m.put("result", row.getResultLabel());
        m.put("status", row.getStatus());
        m.put("script", row.getScriptName());
        m.put("scriptId", row.getScriptId());
        m.put("engine", row.getEngine());
        m.put("time", row.getCreateTime());
        m.put("gates", readGates(row.getGatesJson()));
        m.put("dsWorkflowCode", row.getDsWorkflowCode());
        return m;
    }

    private List<Map<String, Object>> readLint(String json) {
        if (StrUtil.isBlank(json)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        JSONArray arr = JSONUtil.parseArray(json);
        for (Object o : arr) {
            JSONObject jo = JSONUtil.parseObj(o);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("label", jo.getStr("label"));
            m.put("tone", jo.getStr("tone"));
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> readGates(String json) {
        if (StrUtil.isBlank(json)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        JSONArray arr = JSONUtil.parseArray(json);
        for (Object o : arr) {
            JSONObject jo = JSONUtil.parseObj(o);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("step", jo.getInt("step"));
            m.put("name", jo.getStr("name"));
            m.put("detail", jo.getStr("detail"));
            m.put("status", jo.getStr("status"));
            out.add(m);
        }
        return out;
    }

    private static Map<String, Object> kpi(String label, long value, String unit, String delta) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("value", String.valueOf(value));
        m.put("unit", unit);
        m.put("delta", delta);
        m.put("tone", "");
        return m;
    }

    private static String pkgName(CpScriptIndex script) {
        String base = script.getName() == null ? "script" : script.getName().replaceAll("(?i)\\.sql$", "");
        return "rel-" + base;
    }

    private static String shortSha(String sha) {
        if (StrUtil.isBlank(sha)) {
            return "";
        }
        return sha.length() <= 8 ? sha : sha.substring(0, 8);
    }

    private static boolean containsEngine(String engines, String key) {
        if (StrUtil.isBlank(engines)) {
            return true;
        }
        for (String part : engines.split(",")) {
            if (key.equalsIgnoreCase(part.trim())) {
                return true;
            }
        }
        return false;
    }

    private static String ws(String ws) {
        return StrUtil.blankToDefault(ws, "default");
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private UserSnap currentUser() {
        UserSnap u = new UserSnap();
        try {
            SaBaseLoginUser login = StpLoginUserUtil.getLoginUser();
            if (login != null) {
                u.id = login.getId();
                u.name = StrUtil.blankToDefault(login.getName(),
                        StrUtil.blankToDefault(login.getNickname(), login.getAccount()));
                u.email = StrUtil.blankToDefault(login.getAccount(), "lakehouse") + "@local";
                return u;
            }
        } catch (Exception ignored) {
        }
        u.id = "0";
        u.name = "lakehouse";
        u.email = "lakehouse@local";
        return u;
    }

    private static final class UserSnap {
        private String id;
        private String name;
        private String email;
    }
}
