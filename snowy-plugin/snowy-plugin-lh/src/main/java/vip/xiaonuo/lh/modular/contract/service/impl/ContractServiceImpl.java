package vip.xiaonuo.lh.modular.contract.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.lh.core.auth.LhLoginUsers;
import vip.xiaonuo.lh.core.idempotency.LhIdempotencyGuard;
import vip.xiaonuo.lh.modular.contract.entity.GovContractCdcCfg;
import vip.xiaonuo.lh.modular.contract.entity.GovContractChange;
import vip.xiaonuo.lh.modular.contract.entity.GovContractSchema;
import vip.xiaonuo.lh.modular.contract.entity.GovContractSchemaVer;
import vip.xiaonuo.lh.modular.contract.mapper.GovContractCdcCfgMapper;
import vip.xiaonuo.lh.modular.contract.mapper.GovContractChangeMapper;
import vip.xiaonuo.lh.modular.contract.mapper.GovContractSchemaMapper;
import vip.xiaonuo.lh.modular.contract.mapper.GovContractSchemaVerMapper;
import vip.xiaonuo.lh.modular.contract.service.ContractService;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class ContractServiceImpl implements ContractService {

    private static final String NOT_DELETE = "NOT_DELETE";
    private static final Set<String> CHANGE_STATUSES = Set.of(
            "draft", "checking", "review", "approved", "rejected", "executed", "blocked");

    @Resource
    private GovContractSchemaMapper schemaMapper;
    @Resource
    private GovContractSchemaVerMapper verMapper;
    @Resource
    private GovContractChangeMapper changeMapper;
    @Resource
    private GovContractCdcCfgMapper cdcMapper;
    @Resource
    private LhIdempotencyGuard idempotencyGuard;

    @Override
    public Map<String, Object> overview(String ws) {
        String workspace = vip.xiaonuo.lh.core.ws.LhWsFilters.listWs(ws);
        long schemas = schemaMapper.selectCount(new QueryWrapper<GovContractSchema>().lambda()
                .eq(GovContractSchema::getDeleteFlag, NOT_DELETE)
                .eq(StrUtil.isNotBlank(workspace), GovContractSchema::getWs, workspace));
        long compatOk = schemaMapper.selectCount(new QueryWrapper<GovContractSchema>().lambda()
                .eq(GovContractSchema::getDeleteFlag, NOT_DELETE)
                .eq(StrUtil.isNotBlank(workspace), GovContractSchema::getWs, workspace)
                .eq(GovContractSchema::getStatus, "ok"));
        long breaking = changeMapper.selectCount(new QueryWrapper<GovContractChange>().lambda()
                .eq(GovContractChange::getDeleteFlag, NOT_DELETE)
                .eq(StrUtil.isNotBlank(workspace), GovContractChange::getWs, workspace)
                .and(w -> w.in(GovContractChange::getCompatResult, "fail", "BREAKING")
                        .or().eq(GovContractChange::getStatus, "blocked")));
        long blocked = changeMapper.selectCount(new QueryWrapper<GovContractChange>().lambda()
                .eq(GovContractChange::getDeleteFlag, NOT_DELETE)
                .eq(StrUtil.isNotBlank(workspace), GovContractChange::getWs, workspace)
                .eq(GovContractChange::getStatus, "blocked"));
        long cdc = cdcMapper.selectCount(new QueryWrapper<GovContractCdcCfg>().lambda()
                .eq(GovContractCdcCfg::getDeleteFlag, NOT_DELETE)
                .eq(StrUtil.isNotBlank(workspace), GovContractCdcCfg::getWs, workspace));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ws", workspace);
        r.put("schemaCount", schemas);
        r.put("compatOk", compatOk);
        r.put("breaking", breaking);
        r.put("blocked", blocked);
        r.put("cdcConfigCount", cdc);
        r.put("source", "portal");
        return r;
    }

    @Override
    public Map<String, Object> listSchemas(String ws, String q) {
        String workspace = vip.xiaonuo.lh.core.ws.LhWsFilters.listWs(ws);
        QueryWrapper<GovContractSchema> qw = new QueryWrapper<>();
        qw.lambda()
                .eq(GovContractSchema::getDeleteFlag, NOT_DELETE)
                .eq(StrUtil.isNotBlank(workspace), GovContractSchema::getWs, workspace)
                .orderByDesc(GovContractSchema::getUpdateTime);
        if (StrUtil.isNotBlank(q)) {
            qw.lambda().like(GovContractSchema::getName, q.trim());
        }
        List<Map<String, Object>> records = new ArrayList<>();
        for (GovContractSchema s : schemaMapper.selectList(qw)) {
            records.add(toSchemaRow(s));
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("records", records);
        r.put("total", records.size());
        return r;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @SuppressWarnings("unchecked")
    public Map<String, Object> registerSchema(Map<String, Object> body) {
        String idemKey = body == null ? null : str(body.get("idempotencyKey"));
        return idempotencyGuard.run(
                LhIdempotencyGuard.SCOPE_CONTRACT_SCHEMA,
                idemKey,
                LhIdempotencyGuard.hashPayload(body),
                () -> registerSchemaOnce(body),
                "gov_contract_schema",
                m -> m == null ? null : str(m.get("id")),
                (Class<Map<String, Object>>) (Class<?>) Map.class);
    }

    private Map<String, Object> registerSchemaOnce(Map<String, Object> body) {
        String workspace = StrUtil.blankToDefault(str(body.get("ws")), "default");
        String name = StrUtil.trim(str(body.get("name")));
        if (StrUtil.isBlank(name)) {
            name = StrUtil.trim(str(body.get("topic")));
        }
        if (StrUtil.isBlank(name)) {
            throw new CommonException("须填写 Topic/表名");
        }
        String fields = firstNonBlank(str(body.get("fieldsJson")), str(body.get("fields")));
        if (StrUtil.isBlank(fields)) {
            throw new CommonException("须填写字段定义");
        }
        GovContractSchema existed = schemaMapper.selectOne(new QueryWrapper<GovContractSchema>().lambda()
                .eq(GovContractSchema::getDeleteFlag, NOT_DELETE)
                .eq(StrUtil.isNotBlank(workspace), GovContractSchema::getWs, workspace)
                .eq(GovContractSchema::getName, name)
                .last("LIMIT 1"));
        if (existed != null) {
            throw new CommonException("Schema 已存在：" + name);
        }
        Date now = new Date();
        String userId = safeUserId();
        String compat = StrUtil.blankToDefault(str(body.get("compat")), "BACKWARD").toUpperCase(Locale.ROOT);
        if ("NONE".equals(compat)) {
            compat = "BREAKING";
        }
        GovContractSchema row = new GovContractSchema();
        row.setId(IdUtil.getSnowflakeNextIdStr());
        row.setRevision(1);
        row.setStatus("ok");
        row.setWs(workspace);
        row.setName(name);
        row.setKind(StrUtil.blankToDefault(str(body.get("kind")), name.contains(".") ? "topic" : "table"));
        row.setSchemaType(StrUtil.blankToDefault(firstNonBlank(str(body.get("schemaType")), str(body.get("type"))), "AVRO"));
        row.setCompat(compat);
        row.setFieldsJson(fields);
        row.setFieldCount(countFields(fields));
        row.setCurrentVersion("v1");
        row.setLastChange("初始注册");
        row.setDeleteFlag(NOT_DELETE);
        row.setCreateTime(now);
        row.setCreateUser(userId);
        row.setUpdateTime(now);
        row.setUpdateUser(userId);
        schemaMapper.insert(row);

        GovContractSchemaVer ver = new GovContractSchemaVer();
        ver.setId(IdUtil.getSnowflakeNextIdStr());
        ver.setSchemaId(row.getId());
        ver.setWs(workspace);
        ver.setVersion("v1");
        ver.setCompat(compat);
        ver.setFieldsJson(fields);
        ver.setDiffSummary("初始版本");
        ver.setDeleteFlag(NOT_DELETE);
        ver.setCreateTime(now);
        ver.setCreateUser(userId);
        verMapper.insert(ver);

        return toSchemaRow(row);
    }

    @Override
    public List<Map<String, Object>> schemaVersions(String name, String ws) {
        String workspace = vip.xiaonuo.lh.core.ws.LhWsFilters.listWs(ws);
        GovContractSchema schema = findSchema(workspace, name);
        if (schema == null) {
            return List.of();
        }
        List<GovContractSchemaVer> vers = verMapper.selectList(new QueryWrapper<GovContractSchemaVer>().lambda()
                .eq(GovContractSchemaVer::getDeleteFlag, NOT_DELETE)
                .eq(GovContractSchemaVer::getSchemaId, schema.getId())
                .orderByDesc(GovContractSchemaVer::getCreateTime));
        List<Map<String, Object>> out = new ArrayList<>();
        for (GovContractSchemaVer v : vers) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", v.getId());
            m.put("version", v.getVersion());
            m.put("compat", v.getCompat());
            m.put("diffSummary", v.getDiffSummary());
            m.put("createTime", v.getCreateTime());
            out.add(m);
        }
        return out;
    }

    @Override
    public Map<String, Object> listChanges(String ws, String status) {
        String workspace = vip.xiaonuo.lh.core.ws.LhWsFilters.listWs(ws);
        QueryWrapper<GovContractChange> qw = new QueryWrapper<>();
        qw.lambda()
                .eq(GovContractChange::getDeleteFlag, NOT_DELETE)
                .eq(StrUtil.isNotBlank(workspace), GovContractChange::getWs, workspace)
                .eq(StrUtil.isNotBlank(status), GovContractChange::getStatus, status)
                .orderByDesc(GovContractChange::getCreateTime);
        List<Map<String, Object>> records = new ArrayList<>();
        for (GovContractChange c : changeMapper.selectList(qw)) {
            records.add(toChangeRow(c));
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("records", records);
        r.put("total", records.size());
        return r;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @SuppressWarnings("unchecked")
    public Map<String, Object> createChange(Map<String, Object> body) {
        String idemKey = body == null ? null : str(body.get("idempotencyKey"));
        return idempotencyGuard.run(
                LhIdempotencyGuard.SCOPE_CONTRACT_CHANGE,
                idemKey,
                LhIdempotencyGuard.hashPayload(body),
                () -> createChangeOnce(body),
                "gov_contract_change",
                m -> m == null ? null : str(m.get("id")),
                (Class<Map<String, Object>>) (Class<?>) Map.class);
    }

    private Map<String, Object> createChangeOnce(Map<String, Object> body) {
        String workspace = StrUtil.blankToDefault(str(body.get("ws")), "default");
        String schemaName = StrUtil.trim(firstNonBlank(str(body.get("schemaName")), str(body.get("name")), str(body.get("topic"))));
        if (StrUtil.isBlank(schemaName)) {
            throw new CommonException("须指定 schemaName");
        }
        Date now = new Date();
        String userId = safeUserId();
        GovContractSchema schema = findSchema(workspace, schemaName);
        GovContractChange row = new GovContractChange();
        row.setId(IdUtil.getSnowflakeNextIdStr());
        row.setRevision(1);
        row.setStatus("draft");
        row.setWs(workspace);
        row.setSchemaId(schema == null ? null : schema.getId());
        row.setSchemaName(schemaName);
        row.setTitle(StrUtil.blankToDefault(str(body.get("title")), "变更 · " + schemaName));
        row.setChangeSummary(firstNonBlank(str(body.get("changeSummary")), str(body.get("summary")), str(body.get("fields"))));
        row.setFieldsJson(firstNonBlank(str(body.get("fieldsJson")), str(body.get("fields"))));
        row.setCompatResult(null);
        row.setDeleteFlag(NOT_DELETE);
        row.setCreateTime(now);
        row.setCreateUser(userId);
        row.setUpdateTime(now);
        row.setUpdateUser(userId);
        changeMapper.insert(row);
        return toChangeRow(row);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> changeAction(String id, String action, Map<String, Object> body) {
        GovContractChange row = changeMapper.selectById(id);
        if (row == null || !NOT_DELETE.equals(row.getDeleteFlag())) {
            throw new CommonException("变更单不存在");
        }
        String act = StrUtil.blankToDefault(action, "").trim().toLowerCase(Locale.ROOT);
        String next = switch (act) {
            case "check", "checking" -> "checking";
            case "submit", "review" -> "review";
            case "approve", "approved" -> "approved";
            case "reject", "rejected" -> "rejected";
            case "execute", "executed" -> "executed";
            case "block", "blocked" -> "blocked";
            case "draft" -> "draft";
            default -> throw new CommonException("不支持的 action：" + action);
        };
        if (!CHANGE_STATUSES.contains(next)) {
            throw new CommonException("非法状态");
        }
        if ("checking".equals(next) || "review".equals(next)) {
            Map<String, Object> checkBody = body == null ? new LinkedHashMap<>() : new LinkedHashMap<>(body);
            checkBody.putIfAbsent("schemaName", row.getSchemaName());
            checkBody.putIfAbsent("fields", row.getFieldsJson());
            checkBody.putIfAbsent("ws", row.getWs());
            Map<String, Object> check = check(checkBody);
            row.setCompatResult(str(check.get("result")));
            row.setImpactJson(JSONUtil.toJsonStr(check));
            if ("fail".equals(row.getCompatResult())) {
                next = "blocked";
            }
        }
        if ("executed".equals(next) && StrUtil.isNotBlank(row.getSchemaId())) {
            applyChangeToSchema(row);
        }
        Date now = new Date();
        row.setStatus(next);
        if (body != null && StrUtil.isNotBlank(str(body.get("remark")))) {
            row.setRemark(str(body.get("remark")));
        }
        row.setUpdateTime(now);
        row.setUpdateUser(safeUserId());
        changeMapper.updateById(row);
        return toChangeRow(row);
    }

    @Override
    public Map<String, Object> check(Map<String, Object> body) {
        String workspace = StrUtil.blankToDefault(str(body.get("ws")), "default");
        String schemaName = StrUtil.trim(firstNonBlank(str(body.get("schemaName")), str(body.get("name")), str(body.get("topic"))));
        String newFields = firstNonBlank(str(body.get("fieldsJson")), str(body.get("fields")));
        GovContractSchema schema = StrUtil.isBlank(schemaName) ? null : findSchema(workspace, schemaName);
        String oldFields = schema == null ? "" : StrUtil.blankToDefault(schema.getFieldsJson(), "");
        String result = "ok";
        String summary = "无基线，视为兼容通过";
        List<String> diffs = new ArrayList<>();
        if (schema != null) {
            Set<String> oldNames = fieldNames(oldFields);
            Set<String> newNames = fieldNames(StrUtil.blankToDefault(newFields, oldFields));
            for (String n : oldNames) {
                if (!newNames.contains(n)) {
                    diffs.add("删除字段 " + n);
                    result = "fail";
                }
            }
            for (String n : newNames) {
                if (!oldNames.contains(n)) {
                    diffs.add("新增字段 " + n);
                    if (!"fail".equals(result)) {
                        result = "ok";
                    }
                }
            }
            if (diffs.isEmpty()) {
                summary = "字段集合无变化";
            } else if ("fail".equals(result)) {
                summary = "检测到删列/破坏性变更：" + String.join("；", diffs);
            } else {
                summary = "兼容变更：" + String.join("；", diffs);
                result = "warn".equals(result) ? result : "ok";
            }
        } else if (StrUtil.isNotBlank(newFields)) {
            summary = "新 Schema 字段 " + countFields(newFields) + " 个";
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("result", result);
        r.put("summary", summary);
        r.put("diffs", diffs);
        r.put("schemaName", schemaName);
        r.put("impact", List.of());
        r.put("hint", "P0 字段级 stub；可后续接 /lh/lineage/impact");
        return r;
    }

    @Override
    public Map<String, Object> getCdcConfig(String topic, String ws) {
        String workspace = vip.xiaonuo.lh.core.ws.LhWsFilters.listWs(ws);
        if (StrUtil.isBlank(topic)) {
            throw new CommonException("须指定 topic");
        }
        GovContractCdcCfg row = cdcMapper.selectOne(new QueryWrapper<GovContractCdcCfg>().lambda()
                .eq(GovContractCdcCfg::getDeleteFlag, NOT_DELETE)
                .eq(StrUtil.isNotBlank(workspace), GovContractCdcCfg::getWs, workspace)
                .eq(GovContractCdcCfg::getTopic, topic)
                .last("LIMIT 1"));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("topic", topic);
        r.put("ws", workspace);
        if (row == null) {
            r.put("config", defaultCdcConfig());
            r.put("source", "default");
            return r;
        }
        try {
            r.put("config", JSONUtil.parseObj(StrUtil.blankToDefault(row.getConfigJson(), "{}")));
        } catch (Exception e) {
            r.put("config", defaultCdcConfig());
        }
        r.put("id", row.getId());
        r.put("source", "gov_contract_cdc_cfg");
        return r;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> putCdcConfig(String topic, String ws, Map<String, Object> body) {
        String workspace = vip.xiaonuo.lh.core.ws.LhWsFilters.listWs(ws);
        if (StrUtil.isBlank(topic)) {
            throw new CommonException("须指定 topic");
        }
        Object cfg = body == null ? null : body.get("config");
        if (cfg == null && body != null) {
            cfg = body;
        }
        String json = cfg == null ? JSONUtil.toJsonStr(defaultCdcConfig()) : JSONUtil.toJsonStr(cfg);
        Date now = new Date();
        String userId = safeUserId();
        GovContractCdcCfg row = cdcMapper.selectOne(new QueryWrapper<GovContractCdcCfg>().lambda()
                .eq(GovContractCdcCfg::getDeleteFlag, NOT_DELETE)
                .eq(StrUtil.isNotBlank(workspace), GovContractCdcCfg::getWs, workspace)
                .eq(GovContractCdcCfg::getTopic, topic)
                .last("LIMIT 1"));
        if (row == null) {
            row = new GovContractCdcCfg();
            row.setId(IdUtil.getSnowflakeNextIdStr());
            row.setWs(workspace);
            row.setTopic(topic);
            row.setConfigJson(json);
            row.setDeleteFlag(NOT_DELETE);
            row.setCreateTime(now);
            row.setCreateUser(userId);
            row.setUpdateTime(now);
            row.setUpdateUser(userId);
            cdcMapper.insert(row);
        } else {
            row.setConfigJson(json);
            row.setUpdateTime(now);
            row.setUpdateUser(userId);
            cdcMapper.updateById(row);
        }
        return getCdcConfig(topic, workspace);
    }

    private void applyChangeToSchema(GovContractChange change) {
        GovContractSchema schema = schemaMapper.selectById(change.getSchemaId());
        if (schema == null) {
            return;
        }
        Date now = new Date();
        String userId = safeUserId();
        String nextVer = bumpVersion(schema.getCurrentVersion());
        if (StrUtil.isNotBlank(change.getFieldsJson())) {
            schema.setFieldsJson(change.getFieldsJson());
            schema.setFieldCount(countFields(change.getFieldsJson()));
        }
        schema.setCurrentVersion(nextVer);
        schema.setLastChange(StrUtil.blankToDefault(change.getChangeSummary(), "变更单 " + change.getId()));
        schema.setStatus("fail".equals(change.getCompatResult()) ? "fail" : "ok");
        schema.setUpdateTime(now);
        schema.setUpdateUser(userId);
        schemaMapper.updateById(schema);

        GovContractSchemaVer ver = new GovContractSchemaVer();
        ver.setId(IdUtil.getSnowflakeNextIdStr());
        ver.setSchemaId(schema.getId());
        ver.setWs(schema.getWs());
        ver.setVersion(nextVer);
        ver.setCompat(schema.getCompat());
        ver.setFieldsJson(schema.getFieldsJson());
        ver.setDiffSummary(change.getChangeSummary());
        ver.setDeleteFlag(NOT_DELETE);
        ver.setCreateTime(now);
        ver.setCreateUser(userId);
        verMapper.insert(ver);
    }

    private GovContractSchema findSchema(String ws, String name) {
        return schemaMapper.selectOne(new QueryWrapper<GovContractSchema>().lambda()
                .eq(GovContractSchema::getDeleteFlag, NOT_DELETE)
                .eq(GovContractSchema::getWs, ws)
                .eq(GovContractSchema::getName, name)
                .last("LIMIT 1"));
    }

    private static Map<String, Object> toSchemaRow(GovContractSchema s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("name", s.getName());
        m.put("type", StrUtil.blankToDefault(s.getSchemaType(), "AVRO"));
        m.put("kind", s.getKind());
        m.put("version", s.getCurrentVersion());
        m.put("compat", s.getCompat());
        m.put("fields", s.getFieldCount() == null ? 0 : s.getFieldCount());
        m.put("change", s.getLastChange());
        m.put("status", StrUtil.blankToDefault(s.getStatus(), "ok"));
        m.put("ws", s.getWs());
        m.put("updateTime", s.getUpdateTime());
        return m;
    }

    private static Map<String, Object> toChangeRow(GovContractChange c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("status", c.getStatus());
        m.put("schemaName", c.getSchemaName());
        m.put("schemaId", c.getSchemaId());
        m.put("title", c.getTitle());
        m.put("changeSummary", c.getChangeSummary());
        m.put("compatResult", c.getCompatResult());
        m.put("remark", c.getRemark());
        m.put("createTime", c.getCreateTime());
        m.put("updateTime", c.getUpdateTime());
        return m;
    }

    private static Map<String, Object> defaultCdcConfig() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("bootstrap", "snapshot_then_cdc");
        c.put("pkRequired", true);
        c.put("deleteSemantics", "equality_delete");
        c.put("lateData", "side_output");
        c.put("timezone", "UTC");
        c.put("ddlGate", "contract_first");
        return c;
    }

    private static int countFields(String fields) {
        return fieldNames(fields).size();
    }

    private static java.util.HashSet<String> fieldNames(String fields) {
        java.util.HashSet<String> set = new java.util.HashSet<>();
        if (StrUtil.isBlank(fields)) {
            return set;
        }
        String text = fields.trim();
        if (text.startsWith("[")) {
            try {
                for (Object o : JSONUtil.parseArray(text)) {
                    if (o instanceof Map<?, ?> map) {
                        Object n = map.get("name");
                        if (n == null) {
                            n = map.get("field");
                        }
                        if (n != null && StrUtil.isNotBlank(String.valueOf(n))) {
                            set.add(String.valueOf(n).trim());
                        }
                    }
                }
                return set;
            } catch (Exception ignored) {
                /* fall through */
            }
        }
        for (String part : text.split("[,;\\n]+")) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            String name = p.split("\\s+")[0].trim();
            if (!name.isEmpty()) {
                set.add(name);
            }
        }
        return set;
    }

    private static String bumpVersion(String cur) {
        String c = StrUtil.blankToDefault(cur, "v1");
        if (c.matches("v\\d+")) {
            return "v" + (Integer.parseInt(c.substring(1)) + 1);
        }
        return c + ".1";
    }

    private static String safeUserId() {
        try {
            return LhLoginUsers.requireUserId();
        } catch (Exception e) {
            return "system";
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String firstNonBlank(String... vals) {
        if (vals == null) {
            return null;
        }
        for (String v : vals) {
            if (StrUtil.isNotBlank(v)) {
                return v.trim();
            }
        }
        return null;
    }
}
