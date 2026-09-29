package vip.xiaonuo.lh.modular.sec.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.lh.core.vault.LhVaultClient;
import vip.xiaonuo.lh.core.vault.LhVaultDynamicRotate;
import vip.xiaonuo.lh.core.vault.LhVaultPaths;
import vip.xiaonuo.lh.modular.catalog.entity.GovAsset;
import vip.xiaonuo.lh.modular.catalog.mapper.GovAssetMapper;
import vip.xiaonuo.lh.modular.datasource.entity.LhDatasource;
import vip.xiaonuo.lh.modular.datasource.entity.LhSecretStore;
import vip.xiaonuo.lh.modular.datasource.mapper.LhDatasourceMapper;
import vip.xiaonuo.lh.modular.datasource.mapper.LhSecretStoreMapper;
import vip.xiaonuo.lh.modular.datasource.param.LhDatasourceIdParam;
import vip.xiaonuo.lh.modular.datasource.service.LhDatasourceService;
import vip.xiaonuo.lh.modular.export.entity.GovExportAudit;
import vip.xiaonuo.lh.modular.export.mapper.GovExportAuditMapper;
import vip.xiaonuo.lh.modular.sec.entity.SecAuthGrant;
import vip.xiaonuo.lh.modular.sec.entity.SecJobSa;
import vip.xiaonuo.lh.modular.sec.entity.SecMaskPolicyProj;
import vip.xiaonuo.lh.modular.sec.mapper.SecAuthGrantMapper;
import vip.xiaonuo.lh.modular.sec.mapper.SecJobSaMapper;
import vip.xiaonuo.lh.modular.sec.mapper.SecMaskPolicyProjMapper;
import vip.xiaonuo.lh.modular.sec.param.SecJobSaRegisterParam;
import vip.xiaonuo.lh.modular.sec.service.SecBoardService;
import vip.xiaonuo.lh.modular.sec.support.SecVaultRotateAlertSupport;

import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class SecBoardServiceImpl implements SecBoardService {

    private static final String NOT_DELETE = "NOT_DELETE";
    /** 健康台账最多返回条数，避免一次解密过多 */
    private static final int VAULT_HEALTH_LIMIT = 100;
    /** §35.2：剩余 &lt; 7 天标黄 */
    private static final int WARN_REMAINING_DAYS = 7;
    private static final Pattern SA_NAME = Pattern.compile("^job\\.[a-zA-Z0-9_.-]+\\.[a-zA-Z0-9_.-]+$");

    @Resource
    private SecAuthGrantMapper grantMapper;
    @Resource
    private SecMaskPolicyProjMapper maskMapper;
    @Resource
    private GovAssetMapper assetMapper;
    @Resource
    private GovExportAuditMapper exportAuditMapper;
    @Resource
    private LhSecretStoreMapper secretStoreMapper;
    @Resource
    private LhDatasourceMapper datasourceMapper;
    @Resource
    private LhDatasourceService datasourceService;
    @Resource
    private LhVaultClient vaultClient;
    @Resource
    private SecJobSaMapper jobSaMapper;
    @Resource
    private LhVaultDynamicRotate vaultDynamicRotate;
    @Resource
    private SecVaultRotateAlertSupport vaultRotateAlertSupport;

    @Override
    public Map<String, Object> overview(String ws) {
        String workspace = vip.xiaonuo.lh.core.ws.LhWsFilters.listWs(ws);
        Date dayAgo = hoursAgo(24);

        long activeGrants = grantMapper.selectCount(new QueryWrapper<SecAuthGrant>().lambda()
                .eq(SecAuthGrant::getDeleteFlag, NOT_DELETE)
                .eq(SecAuthGrant::getStatus, "active")
                .eq(StrUtil.isNotBlank(workspace), SecAuthGrant::getWs, workspace));

        long sensitiveCols = maskMapper.selectCount(new QueryWrapper<SecMaskPolicyProj>().lambda()
                .eq(SecMaskPolicyProj::getDeleteFlag, NOT_DELETE)
                .eq(StrUtil.isNotBlank(workspace), SecMaskPolicyProj::getWs, workspace));

        long sensitiveAssets = assetMapper.selectCount(new QueryWrapper<GovAsset>().lambda()
                .eq(GovAsset::getDeleteFlag, NOT_DELETE)
                .eq(StrUtil.isNotBlank(workspace), GovAsset::getWs, workspace)
                .isNotNull(GovAsset::getSensitivity)
                .ne(GovAsset::getSensitivity, "")
                .ne(GovAsset::getSensitivity, "公开")
                .ne(GovAsset::getSensitivity, "PUBLIC"));

        long audit24h = exportAuditMapper.selectCount(new QueryWrapper<GovExportAudit>().lambda()
                .eq(GovExportAudit::getDeleteFlag, NOT_DELETE)
                .eq(StrUtil.isNotBlank(workspace), GovExportAudit::getWs, workspace)
                .ge(GovExportAudit::getEventTime, dayAgo));

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ws", workspace);
        r.put("userCount", null);
        r.put("activeGrants", activeGrants);
        r.put("sensitiveColumns", sensitiveCols);
        r.put("sensitiveAssets", sensitiveAssets);
        r.put("auditLast24h", audit24h);
        r.put("source", "portal");
        return r;
    }

    @Override
    public Map<String, Object> pageGrants(String ws, String q, long current, long size) {
        String workspace = vip.xiaonuo.lh.core.ws.LhWsFilters.listWs(ws);
        long cur = Math.max(1, current);
        long sz = Math.min(100, Math.max(1, size));
        QueryWrapper<SecAuthGrant> qw = new QueryWrapper<>();
        qw.lambda()
                .eq(SecAuthGrant::getDeleteFlag, NOT_DELETE)
                .eq(StrUtil.isNotBlank(workspace), SecAuthGrant::getWs, workspace)
                .orderByDesc(SecAuthGrant::getCreateTime);
        if (StrUtil.isNotBlank(q)) {
            String kw = q.trim();
            qw.lambda().and(w -> w.like(SecAuthGrant::getSubjectId, kw)
                    .or().like(SecAuthGrant::getResourceId, kw)
                    .or().like(SecAuthGrant::getAssetId, kw)
                    .or().like(SecAuthGrant::getPrivilege, kw)
                    .or().like(SecAuthGrant::getTicketId, kw));
        }
        Page<SecAuthGrant> page = grantMapper.selectPage(new Page<>(cur, sz), qw);
        List<Map<String, Object>> records = new ArrayList<>();
        for (SecAuthGrant g : page.getRecords()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", g.getId());
            row.put("ws", g.getWs());
            row.put("status", g.getStatus());
            row.put("subjectType", g.getSubjectType());
            row.put("subjectId", g.getSubjectId());
            row.put("resourceType", g.getResourceType());
            row.put("resourceId", firstNonBlank(g.getResourceId(), g.getAssetId()));
            row.put("assetId", g.getAssetId());
            row.put("privilege", g.getPrivilege());
            row.put("columnMask", g.getColumnMask());
            row.put("rowFilter", g.getRowFilter());
            row.put("ticketId", g.getTicketId());
            row.put("gravProjected", g.getGravProjected());
            row.put("expiresAt", g.getExpiresAt());
            row.put("createTime", g.getCreateTime());
            records.add(row);
        }
        return pageMap(records, page.getTotal(), cur, sz);
    }

    @Override
    public Map<String, Object> pageMasks(String ws, String q, long current, long size) {
        String workspace = vip.xiaonuo.lh.core.ws.LhWsFilters.listWs(ws);
        long cur = Math.max(1, current);
        long sz = Math.min(100, Math.max(1, size));
        QueryWrapper<SecMaskPolicyProj> qw = new QueryWrapper<>();
        qw.lambda()
                .eq(SecMaskPolicyProj::getDeleteFlag, NOT_DELETE)
                .eq(StrUtil.isNotBlank(workspace), SecMaskPolicyProj::getWs, workspace)
                .orderByDesc(SecMaskPolicyProj::getUpdateTime);
        if (StrUtil.isNotBlank(q)) {
            String kw = q.trim();
            qw.lambda().and(w -> w.like(SecMaskPolicyProj::getGravAssetId, kw)
                    .or().like(SecMaskPolicyProj::getColumnName, kw)
                    .or().like(SecMaskPolicyProj::getMaskAlgo, kw)
                    .or().like(SecMaskPolicyProj::getSensitivity, kw));
        }
        Page<SecMaskPolicyProj> page = maskMapper.selectPage(new Page<>(cur, sz), qw);
        List<Map<String, Object>> records = new ArrayList<>();
        for (SecMaskPolicyProj m : page.getRecords()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", m.getId());
            row.put("ws", m.getWs());
            row.put("status", m.getStatus());
            row.put("gravAssetId", m.getGravAssetId());
            row.put("columnName", m.getColumnName());
            row.put("sensitivity", m.getSensitivity());
            row.put("maskAlgo", m.getMaskAlgo());
            row.put("omTagFqn", m.getOmTagFqn());
            row.put("gravPolicyId", m.getGravPolicyId());
            row.put("updateTime", m.getUpdateTime());
            records.add(row);
        }
        return pageMap(records, page.getTotal(), cur, sz);
    }

    @Override
    public Map<String, Object> classification(String ws) {
        String workspace = vip.xiaonuo.lh.core.ws.LhWsFilters.listWs(ws);
        List<GovAsset> assets = assetMapper.selectList(new QueryWrapper<GovAsset>().lambda()
                .eq(GovAsset::getDeleteFlag, NOT_DELETE)
                .eq(StrUtil.isNotBlank(workspace), GovAsset::getWs, workspace)
                .select(GovAsset::getSensitivity));
        Map<String, Long> buckets = new LinkedHashMap<>();
        long total = 0;
        for (GovAsset a : assets) {
            String s = StrUtil.blankToDefault(a.getSensitivity(), "未分级").trim();
            if (s.isEmpty()) {
                s = "未分级";
            }
            buckets.merge(s, 1L, Long::sum);
            total++;
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map.Entry<String, Long> e : buckets.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sensitivity", e.getKey());
            item.put("count", e.getValue());
            items.add(item);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ws", workspace);
        r.put("total", total);
        r.put("items", items);
        r.put("source", "gov_asset");
        return r;
    }

    @Override
    public Map<String, Object> pageAudit(String ws, String q, long current, long size) {
        String workspace = vip.xiaonuo.lh.core.ws.LhWsFilters.listWs(ws);
        long cur = Math.max(1, current);
        long sz = Math.min(100, Math.max(1, size));
        QueryWrapper<GovExportAudit> qw = new QueryWrapper<>();
        qw.lambda()
                .eq(GovExportAudit::getDeleteFlag, NOT_DELETE)
                .eq(StrUtil.isNotBlank(workspace), GovExportAudit::getWs, workspace)
                .orderByDesc(GovExportAudit::getEventTime);
        if (StrUtil.isNotBlank(q)) {
            String kw = q.trim();
            qw.lambda().and(w -> w.like(GovExportAudit::getTicketNo, kw)
                    .or().like(GovExportAudit::getSrcTable, kw)
                    .or().like(GovExportAudit::getTarget, kw)
                    .or().like(GovExportAudit::getEventType, kw)
                    .or().like(GovExportAudit::getApprover, kw));
        }
        Page<GovExportAudit> page = exportAuditMapper.selectPage(new Page<>(cur, sz), qw);
        List<Map<String, Object>> records = new ArrayList<>();
        for (GovExportAudit a : page.getRecords()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", a.getId());
            row.put("time", a.getEventTime());
            row.put("eventType", a.getEventType());
            row.put("ticketNo", a.getTicketNo());
            row.put("src", a.getSrcTable());
            row.put("target", a.getTarget());
            row.put("approver", a.getApprover());
            row.put("purpose", a.getPurpose());
            row.put("channel", "export");
            row.put("risk", riskOf(a.getEventType()));
            records.add(row);
        }
        Map<String, Object> r = pageMap(records, page.getTotal(), cur, sz);
        r.put("source", records.isEmpty() ? "empty" : "gov_export_audit");
        r.put("hint", records.isEmpty()
                ? "暂无高风险审计片段（出湖审计空；即席审计待扩展）"
                : "当前拼接出湖审计；即席查询审计可后续并入");
        return r;
    }

    @Override
    public Map<String, Object> listSa(String ws) {
        String workspace = vip.xiaonuo.lh.core.ws.LhWsFilters.listWs(ws);
        List<SecJobSa> rows = jobSaMapper.selectList(new QueryWrapper<SecJobSa>().lambda()
                .eq(SecJobSa::getDeleteFlag, NOT_DELETE)
                .eq(StrUtil.isNotBlank(workspace), SecJobSa::getWs, workspace)
                .orderByDesc(SecJobSa::getUpdateTime)
                .orderByDesc(SecJobSa::getCreateTime));
        List<Map<String, Object>> items = new ArrayList<>();
        for (SecJobSa row : rows) {
            items.add(toSaItem(row));
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("source", "sec_job_sa");
        r.put("items", items);
        r.put("total", items.size());
        r.put("hint", items.isEmpty()
                ? "暂无作业 SA；点「注册 SA」创建（凭证进 Vault，本表无明文）"
                : "§35.1：一 SA 一作业域；凭证仅注入运行时");
        return r;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> registerSa(SecJobSaRegisterParam param) {
        if (param == null || StrUtil.isBlank(param.getSaName())) {
            throw new CommonException("saName 不能为空");
        }
        String saName = param.getSaName().trim();
        if (!SA_NAME.matcher(saName).matches()) {
            throw new CommonException("saName 须形如 job.{domain}.{action}，如 job.trade.ods_writer");
        }
        String workspace = StrUtil.blankToDefault(
                vip.xiaonuo.lh.core.ws.LhWsFilters.listWs(param.getWs()), "default");
        Long dup = jobSaMapper.selectCount(new QueryWrapper<SecJobSa>().lambda()
                .eq(SecJobSa::getWs, workspace)
                .eq(SecJobSa::getSaName, saName)
                .eq(SecJobSa::getDeleteFlag, NOT_DELETE));
        if (dup != null && dup > 0) {
            throw new CommonException("同空间已存在 SA: {}", saName);
        }
        String domain = StrUtil.blankToDefault(param.getDomain(), guessDomain(saName));
        String vaultPath = LhVaultPaths.jobSa(workspace, saName);
        vaultDynamicRotate.seedNew(vaultPath);

        Date now = new Date();
        SecJobSa row = new SecJobSa();
        row.setId(IdUtil.getSnowflakeNextIdStr());
        row.setRevision(1);
        row.setWs(workspace);
        row.setSaName(saName);
        row.setDomain(domain);
        row.setJobBind(StrUtil.trim(param.getJobBind()));
        row.setPrivilegeScope(StrUtil.trim(param.getPrivilegeScope()));
        row.setVaultPath(vaultPath);
        row.setStatus("active");
        row.setExpireAt(parseExpire(param.getExpireAt()));
        row.setDeleteFlag(NOT_DELETE);
        row.setCreateTime(now);
        row.setUpdateTime(now);
        jobSaMapper.insert(row);
        return toSaItem(row);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void retireSa(String id) {
        if (StrUtil.isBlank(id)) {
            throw new CommonException("id 不能为空");
        }
        SecJobSa row = jobSaMapper.selectById(id);
        if (row == null || !NOT_DELETE.equals(row.getDeleteFlag())) {
            throw new CommonException("作业 SA 不存在");
        }
        Date now = new Date();
        row.setStatus("retired");
        row.setDeleteFlag("DELETED");
        row.setRevision(row.getRevision() == null ? 1 : row.getRevision() + 1);
        row.setUpdateTime(now);
        jobSaMapper.updateById(row);
        if (StrUtil.isNotBlank(row.getVaultPath()) && vaultClient.exists(row.getVaultPath())) {
            try {
                Map<String, Object> secret = new LinkedHashMap<>(vaultClient.read(row.getVaultPath()));
                secret.put("retiredAt", now.toString());
                vaultClient.write(row.getVaultPath(), secret);
            } catch (Exception ignored) {
                // soft
            }
        }
    }

    @Override
    public Map<String, Object> vaultHealth() {
        Page<LhSecretStore> page = secretStoreMapper.selectPage(
                new Page<>(1, VAULT_HEALTH_LIMIT),
                new QueryWrapper<LhSecretStore>().lambda()
                        .eq(LhSecretStore::getDeleteFlag, NOT_DELETE)
                        .orderByDesc(LhSecretStore::getUpdateTime)
                        .orderByDesc(LhSecretStore::getCreateTime));
        Map<String, LhDatasource> dsByPath = loadDatasourceByVaultPath();
        List<Map<String, Object>> items = new ArrayList<>();
        int warn = 0;
        int expired = 0;
        int missing = 0;
        for (LhSecretStore row : page.getRecords()) {
            Map<String, Object> item = toVaultHealthItem(row, dsByPath.get(row.getVaultPath()));
            String health = String.valueOf(item.get("health"));
            if ("warn".equals(health)) {
                warn++;
            } else if ("expired".equals(health)) {
                expired++;
            } else if ("missing".equals(health)) {
                missing++;
            }
            items.add(item);
        }
        items.sort(Comparator
                .comparingInt((Map<String, Object> m) -> healthRank(String.valueOf(m.get("health"))))
                .thenComparing(m -> {
                    Object rem = m.get("remainingDays");
                    return rem instanceof Number ? ((Number) rem).intValue() : Integer.MAX_VALUE;
                }));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("source", "ig_secret_store");
        r.put("items", items);
        r.put("total", page.getTotal());
        r.put("ok", expired == 0);
        r.put("warnCount", warn);
        r.put("expiredCount", expired);
        r.put("missingCount", missing);
        r.put("hint", items.isEmpty()
                ? "暂无 Vault 台账（ig_secret_store 空）"
                : "§35.2：剩余<7 天 warn、过期 expired；立即轮换=本地动态密（previous* 宽限期）");
        return r;
    }

    @Override
    public Map<String, Object> rotateVault(String vaultPath) {
        String path = StrUtil.trim(vaultPath);
        String kind = "platform";
        try {
            if (StrUtil.isBlank(path)) {
                throw new CommonException("vaultPath 不能为空");
            }
            if (path.startsWith(LhVaultPaths.AI_MODEL_PREFIX)) {
                throw new CommonException("AI 模型 Key 须在「AI 模型管理」提交新 Key 轮换，安全中心不代写明文");
            }
            if (path.startsWith("lh/compliance/subject/")) {
                throw new CommonException("合规主体明文不可在此轮换");
            }
            if (!vaultClient.exists(path)) {
                throw new CommonException("凭证不存在或为 PLACEHOLDER: {}", path);
            }

            LhDatasource ds = datasourceMapper.selectOne(new QueryWrapper<LhDatasource>().lambda()
                    .eq(LhDatasource::getVaultPath, path)
                    .eq(LhDatasource::getDeleteFlag, NOT_DELETE)
                    .last("LIMIT 1"));
            Map<String, Object> secret;
            String mode;
            if (ds != null) {
                kind = "datasource";
                LhDatasourceIdParam idParam = new LhDatasourceIdParam();
                idParam.setId(ds.getId());
                datasourceService.rotateCred(idParam);
                secret = vaultClient.read(path);
                mode = "dynamic_datasource";
            } else {
                if (path.startsWith("lh/job-sa/")) {
                    kind = "job_sa";
                }
                secret = vaultDynamicRotate.rotateExisting(path);
                mode = "dynamic";
            }

            Map<String, Object> alert = vaultRotateAlertSupport.onSuccess(path, kind);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("vaultPath", path);
            r.put("mode", mode);
            r.put("generation", LhVaultDynamicRotate.generationOf(secret));
            r.put("dsId", ds != null ? ds.getId() : null);
            r.put("ok", true);
            r.put("hint", "已生成新密（generation=" + LhVaultDynamicRotate.generationOf(secret)
                    + "），旧密在 previous*；消费侧重读 Vault"
                    + ("dynamic_datasource".equals(mode) ? "；已置 binding stale" : ""));
            r.put("alert", alert);
            return r;
        } catch (Exception e) {
            Map<String, Object> alert = vaultRotateAlertSupport.onFailure(path, kind, e.getMessage());
            String ticketNo = String.valueOf(alert.getOrDefault("ticketNo", "—"));
            if (e instanceof CommonException) {
                throw new CommonException(e.getMessage() + "（已登记工单 " + ticketNo + "）");
            }
            throw new CommonException("Vault 轮换失败: {}（工单={}）", e.getMessage(), ticketNo);
        }
    }

    @Override
    public Map<String, Object> routeWhitelist() {
        List<Map<String, Object>> allow = List.of(
                pathNode("人 / BI", "OIDC → 门户"),
                pathNode("Trino", "脱敏 + 配额"),
                pathNode("Iceberg 读", "按授权列"));
        List<Map<String, Object>> forbid = List.of(
                pathNode("人", "直连源库/CK/MinIO"),
                pathNode("阻断", "安全组 + 防火墙"),
                pathNode("审计", "记录尝试"));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("allow", allow);
        r.put("forbid", forbid);
        r.put("source", "policy");
        return r;
    }

    private Map<String, LhDatasource> loadDatasourceByVaultPath() {
        List<LhDatasource> list = datasourceMapper.selectList(new QueryWrapper<LhDatasource>().lambda()
                .eq(LhDatasource::getDeleteFlag, NOT_DELETE)
                .isNotNull(LhDatasource::getVaultPath)
                .ne(LhDatasource::getVaultPath, "")
                .select(LhDatasource::getId, LhDatasource::getName, LhDatasource::getType,
                        LhDatasource::getVaultPath, LhDatasource::getDsCode));
        Map<String, LhDatasource> map = new HashMap<>();
        for (LhDatasource ds : list) {
            if (StrUtil.isNotBlank(ds.getVaultPath())) {
                map.putIfAbsent(ds.getVaultPath().trim(), ds);
            }
        }
        return map;
    }

    private Map<String, Object> toVaultHealthItem(LhSecretStore row, LhDatasource ds) {
        String path = row.getVaultPath();
        String kind = kindOf(path);
        int rotateDays = rotateDaysOf(path, ds != null ? ds.getType() : null);
        boolean placeholder = row.getSecretCipher() == null
                || "PLACEHOLDER".equals(row.getSecretCipher())
                || StrUtil.isBlank(row.getSecretCipher());

        Date lastRotated = row.getUpdateTime() != null ? row.getUpdateTime() : row.getCreateTime();
        if (!placeholder) {
            Map<String, Object> secret = vaultClient.readOrEmpty(path);
            Date stamped = parseFlexibleDate(secret.get("rotatedAt"));
            if (stamped != null) {
                lastRotated = stamped;
            }
        }

        String health;
        Integer remainingDays = null;
        if (placeholder) {
            health = "missing";
        } else if (lastRotated == null) {
            health = "warn";
            remainingDays = 0;
        } else {
            long ageDays = ChronoUnit.DAYS.between(lastRotated.toInstant(), Instant.now());
            remainingDays = rotateDays - (int) ageDays;
            if (remainingDays < 0) {
                health = "expired";
            } else if (remainingDays < WARN_REMAINING_DAYS) {
                health = "warn";
            } else {
                health = "ok";
            }
        }

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("vaultPath", path);
        item.put("kind", kind);
        item.put("rotateDays", rotateDays);
        item.put("lastRotatedAt", lastRotated);
        item.put("remainingDays", remainingDays);
        item.put("health", health);
        item.put("rotatable", !placeholder && !path.startsWith(LhVaultPaths.AI_MODEL_PREFIX)
                && !path.startsWith("lh/compliance/subject/"));
        item.put("dsId", ds != null ? ds.getId() : null);
        item.put("bindLabel", ds != null
                ? firstNonBlank(ds.getName(), ds.getDsCode(), ds.getId())
                : labelOfKind(kind, path));
        return item;
    }

    private static Map<String, Object> toSaItem(SecJobSa row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", row.getId());
        m.put("ws", row.getWs());
        m.put("saName", row.getSaName());
        m.put("domain", row.getDomain());
        m.put("jobBind", row.getJobBind());
        m.put("privilegeScope", row.getPrivilegeScope());
        m.put("vaultPath", row.getVaultPath());
        m.put("status", row.getStatus());
        m.put("expireAt", row.getExpireAt());
        m.put("createTime", row.getCreateTime());
        m.put("updateTime", row.getUpdateTime());
        return m;
    }

    private static String guessDomain(String saName) {
        String[] parts = StrUtil.blankToDefault(saName, "").split("\\.");
        return parts.length >= 2 ? parts[1] : "";
    }

    private static Date parseExpire(String raw) {
        if (StrUtil.isBlank(raw)) {
            return null;
        }
        String s = raw.trim();
        try {
            if (s.length() <= 10) {
                return new SimpleDateFormat("yyyy-MM-dd").parse(s);
            }
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(s);
        } catch (Exception e) {
            try {
                return Date.from(Instant.parse(s));
            } catch (Exception ignored) {
                throw new CommonException("expireAt 格式无效: {}", raw);
            }
        }
    }

    private static String kindOf(String path) {
        if (StrUtil.isBlank(path)) {
            return "unknown";
        }
        if (path.startsWith("datasource/")) {
            return "datasource";
        }
        if (path.startsWith("lh/job-sa/")) {
            return "job_sa";
        }
        if (path.startsWith(LhVaultPaths.AI_MODEL_PREFIX)) {
            return "ai";
        }
        if (path.startsWith("lh/compliance/") || path.startsWith("platform/compliance/")) {
            return "compliance";
        }
        if (path.startsWith("platform/")) {
            return "platform";
        }
        return "other";
    }

    /** §35.2 轮换周期（天） */
    private static int rotateDaysOf(String path, String dsType) {
        String p = StrUtil.blankToDefault(path, "").toLowerCase(Locale.ROOT);
        String t = StrUtil.blankToDefault(dsType, "").toLowerCase(Locale.ROOT);
        if (p.contains("minio") || p.contains("/s3") || t.contains("s3") || t.contains("minio")
                || t.contains("kafka") || p.contains("kafka")) {
            return 90;
        }
        if (p.startsWith(LhVaultPaths.AI_MODEL_PREFIX.toLowerCase(Locale.ROOT))) {
            return 90;
        }
        if (p.contains("dataapi/keys")) {
            return 30;
        }
        return 30;
    }

    private static String labelOfKind(String kind, String path) {
        return switch (kind) {
            case "platform" -> "平台组件 · " + StrUtil.blankToDefault(path, "");
            case "job_sa" -> "作业 SA · " + StrUtil.blankToDefault(path, "");
            case "ai" -> "AI 模型 Key";
            case "compliance" -> "合规密钥";
            case "datasource" -> "数据源凭证";
            default -> path;
        };
    }

    private static int healthRank(String health) {
        return switch (StrUtil.blankToDefault(health, "")) {
            case "expired" -> 0;
            case "missing" -> 1;
            case "warn" -> 2;
            default -> 3;
        };
    }

    private static Date parseFlexibleDate(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Date d) {
            return d;
        }
        String s = String.valueOf(raw).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return Date.from(Instant.parse(s));
        } catch (Exception ignored) {
            // Date#toString() 等非 ISO：忽略，回退 update_time
        }
        try {
            @SuppressWarnings("deprecation")
            long millis = Date.parse(s);
            return new Date(millis);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Map<String, Object> pathNode(String title, String sub) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("title", title);
        n.put("sub", sub);
        return n;
    }

    private static String riskOf(String eventType) {
        String t = StrUtil.blankToDefault(eventType, "").toLowerCase(Locale.ROOT);
        if (t.contains("expire") || t.contains("purge") || t.contains("reject")) {
            return "high";
        }
        if (t.contains("approved") || t.contains("job")) {
            return "medium";
        }
        return "low";
    }

    private static Date hoursAgo(int hours) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.HOUR_OF_DAY, -hours);
        return c.getTime();
    }

    private static Map<String, Object> pageMap(List<Map<String, Object>> records, long total, long current, long size) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("records", records);
        r.put("total", total);
        r.put("current", current);
        r.put("size", size);
        return r;
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
