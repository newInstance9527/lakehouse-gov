package vip.xiaonuo.lh.modular.workspace.service.impl;

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
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.lh.core.auth.LhLoginUsers;
import vip.xiaonuo.lh.modular.catalog.entity.GovAsset;
import vip.xiaonuo.lh.modular.catalog.mapper.GovAssetMapper;
import vip.xiaonuo.lh.modular.workspace.entity.GovWs;
import vip.xiaonuo.lh.modular.workspace.entity.GovWsMember;
import vip.xiaonuo.lh.modular.workspace.entity.GovWsQuota;
import vip.xiaonuo.lh.modular.workspace.entity.GovWsUserPref;
import vip.xiaonuo.lh.modular.workspace.mapper.GovWsMapper;
import vip.xiaonuo.lh.modular.workspace.mapper.GovWsMemberMapper;
import vip.xiaonuo.lh.modular.workspace.mapper.GovWsQuotaMapper;
import vip.xiaonuo.lh.modular.workspace.mapper.GovWsUserPrefMapper;
import vip.xiaonuo.lh.modular.workspace.param.GovWsCreateParam;
import vip.xiaonuo.lh.modular.workspace.param.GovWsCurrentParam;
import vip.xiaonuo.lh.modular.workspace.param.GovWsMemberItemParam;
import vip.xiaonuo.lh.modular.workspace.param.GovWsMembersReplaceParam;
import vip.xiaonuo.lh.modular.workspace.result.GovWsMemberVo;
import vip.xiaonuo.lh.modular.workspace.result.GovWsQuotaVo;
import vip.xiaonuo.lh.modular.workspace.result.GovWsVo;
import vip.xiaonuo.lh.modular.workspace.service.GovWsService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 工作空间：组织归属 / 成本 / 协作上下文（不建 Catalog、不写 Grav ACL）
 */
@Service
public class GovWsServiceImpl implements GovWsService {

    private static final String NOT_DELETE = "NOT_DELETE";
    private static final String STATUS_ACTIVE = "active";
    private static final String STATUS_ARCHIVED = "archived";
    private static final String SHARED_CATALOG = "lakehouse";
    private static final String WS_DEFAULT = "default";
    private static final Set<String> ROLES = Set.of(
            "Owner", "Developer", "Operator", "BusinessUser", "SecurityOfficer", "ServiceAccount");

    @Resource
    private GovWsMapper wsMapper;
    @Resource
    private GovWsMemberMapper memberMapper;
    @Resource
    private GovWsQuotaMapper quotaMapper;
    @Resource
    private GovWsUserPrefMapper prefMapper;
    @Resource
    private GovAssetMapper assetMapper;

    @Override
    public Map<String, Object> overview() {
        List<GovWs> all = listActiveEntities();
        String current = resolveCurrentWsCode();
        long memberTotal = memberMapper.selectCount(new QueryWrapper<GovWsMember>().lambda()
                .eq(GovWsMember::getDeleteFlag, NOT_DELETE)
                .eq(GovWsMember::getStatus, STATUS_ACTIVE));
        long warnQuota = quotaMapper.selectCount(new QueryWrapper<GovWsQuota>().lambda()
                .eq(GovWsQuota::getDeleteFlag, NOT_DELETE)
                .eq(GovWsQuota::getStatus, "warn"));
        long assetInCurrent = countAssets(current);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("spaceCount", all.size());
        out.put("domainHint", "归属团队 · 非隔离租户");
        out.put("currentWs", current);
        out.put("currentAssetCount", assetInCurrent);
        out.put("memberCount", memberTotal);
        out.put("quotaWarnCount", warnQuota);
        out.put("sharedCatalog", SHARED_CATALOG);
        return out;
    }

    @Override
    public List<GovWsVo> listSpaces() {
        String current = resolveCurrentWsCode();
        return listActiveEntities().stream().map(w -> toVo(w, current)).collect(Collectors.toList());
    }

    @Override
    public GovWsVo detail(String wsCode) {
        return toVo(requireWs(wsCode), resolveCurrentWsCode());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GovWsVo create(GovWsCreateParam param) {
        SaBaseLoginUser user = LhLoginUsers.requireUser();
        String code = normalizeWsCode(param.getWsCode(), param.getName());
        if (findByCode(code) != null) {
            throw new CommonException("空间编码已存在: " + code);
        }
        Date now = new Date();
        GovWs row = new GovWs();
        row.setId(IdUtil.getSnowflakeNextIdStr());
        row.setRevision(1);
        row.setStatus(STATUS_ACTIVE);
        row.setWs(code);
        row.setRemark(param.getRemark());
        row.setWsCode(code);
        row.setName(param.getName().trim());
        row.setIcon(StrUtil.blankToDefault(param.getIcon(), "🗂️"));
        row.setDomainCode(StrUtil.blankToDefault(param.getDomainCode(), "自定义"));
        row.setCostCenter(StrUtil.blankToDefault(param.getCostCenter(), "CC-" + code.toUpperCase(Locale.ROOT)));
        row.setTrinoRg(StrUtil.blankToDefault(param.getTrinoRg(), "rg_" + code.replace("ws_", "")));
        row.setPreferredSchemas(StrUtil.blankToDefault(param.getPreferredSchemas(), "—"));
        row.setOwners(StrUtil.blankToDefault(user.getName(), user.getAccount()));
        row.setDetail(StrUtil.blankToDefault(param.getDetail(),
                "已创建归属空间，不新建 Grav Catalog。读数请走申请中心。"));
        row.setTagsJson("[{\"text\":\"新建\",\"cls\":\"tag-blue\"},{\"text\":\"共享 Catalog\",\"cls\":\"tag-gray\"}]");
        row.setDeleteFlag(NOT_DELETE);
        row.setCreateTime(now);
        row.setCreateUser(user.getId());
        row.setUpdateTime(now);
        row.setUpdateUser(user.getId());
        wsMapper.insert(row);

        GovWsQuota quota = new GovWsQuota();
        quota.setId(IdUtil.getSnowflakeNextIdStr());
        quota.setRevision(1);
        quota.setStatus("ok");
        quota.setWs(code);
        quota.setWsCode(code);
        quota.setStorageQuotaTb(param.getStorageQuotaTb() == null ? BigDecimal.valueOf(4) : param.getStorageQuotaTb());
        quota.setStorageUsedTb(BigDecimal.ZERO);
        quota.setCuQuota(param.getCuQuota() == null ? 800 : param.getCuQuota());
        quota.setCuUsed(0);
        quota.setTrinoQuota(8);
        quota.setTrinoUsed(0);
        quota.setApiQpsQuota(800);
        quota.setApiQpsUsed(0);
        quota.setDeleteFlag(NOT_DELETE);
        quota.setCreateTime(now);
        quota.setCreateUser(user.getId());
        quotaMapper.insert(quota);

        // 创建人默认 Owner
        GovWsMemberItemParam self = new GovWsMemberItemParam();
        self.setSubjectType("user");
        self.setSubjectId(user.getId());
        self.setDisplayName(StrUtil.blankToDefault(user.getName(), user.getAccount()));
        self.setRoleCode("Owner");
        self.setScopeNote("认责 · 默认审批人");
        insertMember(code, self, user.getId(), now);

        if (param.getMembers() != null) {
            for (GovWsMemberItemParam m : param.getMembers()) {
                if (m == null || StrUtil.isBlank(m.getSubjectId())) {
                    continue;
                }
                if (user.getId().equals(m.getSubjectId())) {
                    continue;
                }
                insertMember(code, m, user.getId(), now);
            }
        }
        return toVo(requireWs(code), resolveCurrentWsCode());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GovWsVo archive(String wsCode) {
        GovWs row = requireWs(wsCode);
        if (WS_DEFAULT.equals(row.getWsCode())) {
            throw new CommonException("默认空间不可归档");
        }
        row.setStatus(STATUS_ARCHIVED);
        row.setRevision(row.getRevision() == null ? 2 : row.getRevision() + 1);
        row.setUpdateTime(new Date());
        row.setUpdateUser(LhLoginUsers.requireUserId());
        wsMapper.updateById(row);
        return toVo(row, resolveCurrentWsCode());
    }

    @Override
    public List<GovWsMemberVo> listMembers(String wsCode) {
        requireWs(wsCode);
        return memberMapper.selectList(new QueryWrapper<GovWsMember>().lambda()
                        .eq(GovWsMember::getDeleteFlag, NOT_DELETE)
                        .eq(GovWsMember::getWsCode, wsCode)
                        .orderByAsc(GovWsMember::getRoleCode)
                        .orderByAsc(GovWsMember::getDisplayName))
                .stream().map(this::toMemberVo).collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<GovWsMemberVo> replaceMembers(String wsCode, GovWsMembersReplaceParam param) {
        requireWs(wsCode);
        String userId = LhLoginUsers.requireUserId();
        Date now = new Date();
        memberMapper.physicalDeleteByWs(wsCode);
        if (param != null && param.getMembers() != null) {
            for (GovWsMemberItemParam item : param.getMembers()) {
                if (item == null || StrUtil.isBlank(item.getSubjectId())) {
                    continue;
                }
                insertMember(wsCode, item, userId, now);
            }
        }
        refreshOwners(wsCode);
        return listMembers(wsCode);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GovWsMemberVo addMember(String wsCode, GovWsMemberItemParam param) {
        requireWs(wsCode);
        if (param == null || StrUtil.isBlank(param.getSubjectId())) {
            throw new CommonException("subjectId 不能为空");
        }
        GovWsMember exist = memberMapper.selectOne(new QueryWrapper<GovWsMember>().lambda()
                .eq(GovWsMember::getDeleteFlag, NOT_DELETE)
                .eq(GovWsMember::getWsCode, wsCode)
                .eq(GovWsMember::getSubjectType, StrUtil.blankToDefault(param.getSubjectType(), "user"))
                .eq(GovWsMember::getSubjectId, param.getSubjectId().trim())
                .last("LIMIT 1"));
        if (exist != null) {
            throw new CommonException("成员已存在");
        }
        GovWsMember m = insertMember(wsCode, param, LhLoginUsers.requireUserId(), new Date());
        refreshOwners(wsCode);
        return toMemberVo(m);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeMember(String wsCode, String memberId) {
        requireWs(wsCode);
        GovWsMember m = memberMapper.selectById(memberId);
        if (m == null || !wsCode.equals(m.getWsCode())) {
            throw new CommonException("成员不存在");
        }
        memberMapper.physicalDeleteById(memberId);
        refreshOwners(wsCode);
    }

    @Override
    public GovWsQuotaVo quota(String wsCode) {
        requireWs(wsCode);
        GovWsQuota q = quotaMapper.selectOne(new QueryWrapper<GovWsQuota>().lambda()
                .eq(GovWsQuota::getDeleteFlag, NOT_DELETE)
                .eq(GovWsQuota::getWsCode, wsCode)
                .last("LIMIT 1"));
        if (q == null) {
            throw new CommonException("配额未配置: " + wsCode);
        }
        return toQuotaVo(q);
    }

    @Override
    public List<GovWsQuotaVo> listQuotas() {
        return quotaMapper.selectList(new QueryWrapper<GovWsQuota>().lambda()
                        .eq(GovWsQuota::getDeleteFlag, NOT_DELETE)
                        .orderByAsc(GovWsQuota::getWsCode))
                .stream().map(this::toQuotaVo).collect(Collectors.toList());
    }

    @Override
    public Map<String, Object> getCurrent() {
        String code = resolveCurrentWsCode();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("wsCode", code);
        out.put("sharedCatalog", SHARED_CATALOG);
        GovWs w = findByCode(code);
        if (w != null) {
            out.put("name", w.getName());
            out.put("domainCode", w.getDomainCode());
        }
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> setCurrent(GovWsCurrentParam param) {
        String code = StrUtil.trim(param.getWsCode());
        GovWs w = requireWs(code);
        if (!STATUS_ACTIVE.equals(w.getStatus())) {
            throw new CommonException("已归档空间不可设为当前上下文");
        }
        String userId = LhLoginUsers.requireUserId();
        Date now = new Date();
        GovWsUserPref pref = prefMapper.selectOne(new QueryWrapper<GovWsUserPref>().lambda()
                .eq(GovWsUserPref::getUserId, userId)
                .last("LIMIT 1"));
        if (pref == null) {
            pref = new GovWsUserPref();
            pref.setId(IdUtil.getSnowflakeNextIdStr());
            pref.setUserId(userId);
            pref.setCurrentWsCode(code);
            pref.setCreateTime(now);
            pref.setUpdateTime(now);
            prefMapper.insert(pref);
        } else {
            pref.setCurrentWsCode(code);
            pref.setUpdateTime(now);
            prefMapper.updateById(pref);
        }
        return getCurrent();
    }

    // ---------- helpers ----------

    private List<GovWs> listActiveEntities() {
        return wsMapper.selectList(new QueryWrapper<GovWs>().lambda()
                .eq(GovWs::getDeleteFlag, NOT_DELETE)
                .eq(GovWs::getStatus, STATUS_ACTIVE)
                .orderByAsc(GovWs::getDomainCode)
                .orderByAsc(GovWs::getWsCode));
    }

    private GovWs requireWs(String wsCode) {
        GovWs w = findByCode(wsCode);
        if (w == null) {
            throw new CommonException("工作空间不存在: " + wsCode);
        }
        return w;
    }

    private GovWs findByCode(String wsCode) {
        if (StrUtil.isBlank(wsCode)) {
            return null;
        }
        return wsMapper.selectOne(new QueryWrapper<GovWs>().lambda()
                .eq(GovWs::getDeleteFlag, NOT_DELETE)
                .eq(GovWs::getWsCode, wsCode.trim())
                .last("LIMIT 1"));
    }

    private String resolveCurrentWsCode() {
        try {
            String userId = LhLoginUsers.requireUserId();
            GovWsUserPref pref = prefMapper.selectOne(new QueryWrapper<GovWsUserPref>().lambda()
                    .eq(GovWsUserPref::getUserId, userId)
                    .last("LIMIT 1"));
            if (pref != null && StrUtil.isNotBlank(pref.getCurrentWsCode())) {
                GovWs w = findByCode(pref.getCurrentWsCode());
                if (w != null && STATUS_ACTIVE.equals(w.getStatus())) {
                    return w.getWsCode();
                }
            }
        } catch (Exception ignored) {
            // 未登录时概览/列表仍可用默认
        }
        // 演示默认：优先交易域，否则 default
        if (findByCode("ws_trade") != null) {
            return "ws_trade";
        }
        return WS_DEFAULT;
    }

    private long countAssets(String wsCode) {
        try {
            return assetMapper.selectCount(new QueryWrapper<GovAsset>().lambda()
                    .eq(GovAsset::getDeleteFlag, NOT_DELETE)
                    .eq(GovAsset::getWs, wsCode));
        } catch (Exception e) {
            return 0L;
        }
    }

    private long countMembers(String wsCode) {
        return memberMapper.selectCount(new QueryWrapper<GovWsMember>().lambda()
                .eq(GovWsMember::getDeleteFlag, NOT_DELETE)
                .eq(GovWsMember::getWsCode, wsCode)
                .eq(GovWsMember::getStatus, STATUS_ACTIVE));
    }

    private String myRole(String wsCode) {
        try {
            SaBaseLoginUser u = LhLoginUsers.requireUser();
            List<GovWsMember> list = memberMapper.selectList(new QueryWrapper<GovWsMember>().lambda()
                    .eq(GovWsMember::getDeleteFlag, NOT_DELETE)
                    .eq(GovWsMember::getWsCode, wsCode)
                    .eq(GovWsMember::getSubjectType, "user")
                    .and(w -> w.eq(GovWsMember::getSubjectId, u.getId())
                            .or().eq(GovWsMember::getSubjectId, u.getAccount())));
            return list.isEmpty() ? null : list.get(0).getRoleCode();
        } catch (Exception e) {
            return null;
        }
    }

    private GovWsMember insertMember(String wsCode, GovWsMemberItemParam param, String opUser, Date now) {
        String role = normalizeRole(param.getRoleCode());
        String subjectType = StrUtil.blankToDefault(param.getSubjectType(), "user").trim().toLowerCase(Locale.ROOT);
        GovWsMember m = new GovWsMember();
        m.setId(IdUtil.getSnowflakeNextIdStr());
        m.setRevision(1);
        m.setStatus(STATUS_ACTIVE);
        m.setWs(wsCode);
        m.setWsCode(wsCode);
        m.setSubjectType(subjectType);
        m.setSubjectId(param.getSubjectId().trim());
        m.setDisplayName(StrUtil.blankToDefault(param.getDisplayName(), param.getSubjectId().trim()));
        m.setRoleCode(role);
        m.setScopeNote(StrUtil.blankToDefault(param.getScopeNote(), defaultScope(role)));
        m.setLastLogin(now);
        m.setDeleteFlag(NOT_DELETE);
        m.setCreateTime(now);
        m.setCreateUser(opUser);
        m.setUpdateTime(now);
        m.setUpdateUser(opUser);
        memberMapper.insert(m);
        return m;
    }

    private void refreshOwners(String wsCode) {
        List<GovWsMember> owners = memberMapper.selectList(new QueryWrapper<GovWsMember>().lambda()
                .eq(GovWsMember::getDeleteFlag, NOT_DELETE)
                .eq(GovWsMember::getWsCode, wsCode)
                .eq(GovWsMember::getRoleCode, "Owner")
                .eq(GovWsMember::getStatus, STATUS_ACTIVE));
        String names = owners.stream()
                .map(m -> StrUtil.blankToDefault(m.getDisplayName(), m.getSubjectId()))
                .collect(Collectors.joining(" · "));
        GovWs w = requireWs(wsCode);
        w.setOwners(names);
        w.setUpdateTime(new Date());
        wsMapper.updateById(w);
    }

    private String normalizeRole(String role) {
        String r = StrUtil.blankToDefault(role, "BusinessUser").trim();
        if ("Analyst".equalsIgnoreCase(r)) {
            r = "BusinessUser";
        }
        if ("Auditor".equalsIgnoreCase(r)) {
            r = "SecurityOfficer";
        }
        for (String known : ROLES) {
            if (known.equalsIgnoreCase(r)) {
                return known;
            }
        }
        throw new CommonException("非法角色: " + role);
    }

    private String defaultScope(String role) {
        return switch (role) {
            case "Owner" -> "认责 · 默认审批人";
            case "Developer" -> "可登记/开发 · 读数需申请";
            case "Operator" -> "运维协作 · 引擎权限另申请";
            case "BusinessUser" -> "消费方 · 读数需申请";
            case "SecurityOfficer" -> "高敏感审批参与方";
            case "ServiceAccount" -> "作业身份 · ACL 在 Grav";
            default -> "门户协作角色 · 非引擎 ACL";
        };
    }

    private String normalizeWsCode(String wsCode, String name) {
        if (StrUtil.isNotBlank(wsCode)) {
            String c = wsCode.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
            if (!c.startsWith("ws_") && !WS_DEFAULT.equals(c)) {
                c = "ws_" + c;
            }
            return c;
        }
        String base = StrUtil.blankToDefault(name, "space")
                .trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_");
        if (StrUtil.isBlank(base)) {
            base = IdUtil.getSnowflakeNextIdStr();
        }
        return "ws_" + base;
    }

    private GovWsVo toVo(GovWs w, String current) {
        GovWsVo vo = new GovWsVo();
        vo.setId(w.getId());
        vo.setWsCode(w.getWsCode());
        vo.setName(w.getName());
        vo.setIcon(w.getIcon());
        vo.setDomainCode(w.getDomainCode());
        vo.setCostCenter(w.getCostCenter());
        vo.setTrinoRg(w.getTrinoRg());
        vo.setPreferredSchemas(w.getPreferredSchemas());
        vo.setOwners(w.getOwners());
        vo.setDetail(w.getDetail());
        vo.setStatus(w.getStatus());
        vo.setRemark(w.getRemark());
        vo.setCreateTime(w.getCreateTime());
        vo.setTags(parseTags(w.getTagsJson()));
        vo.setCurrent(w.getWsCode().equals(current));
        vo.setMemberCount(countMembers(w.getWsCode()));
        vo.setAssetCount(countAssets(w.getWsCode()));
        vo.setMyRole(myRole(w.getWsCode()));
        vo.setSharedCatalog(SHARED_CATALOG);

        GovWsQuota q = quotaMapper.selectOne(new QueryWrapper<GovWsQuota>().lambda()
                .eq(GovWsQuota::getDeleteFlag, NOT_DELETE)
                .eq(GovWsQuota::getWsCode, w.getWsCode())
                .last("LIMIT 1"));
        if (q != null) {
            vo.setStorageUsedTb(q.getStorageUsedTb());
            vo.setStorageQuotaTb(q.getStorageQuotaTb());
            vo.setCuUsed(q.getCuUsed());
            vo.setCuQuota(q.getCuQuota());
            vo.setQuotaStatus(q.getStatus());
        }
        return vo;
    }

    private List<Map<String, String>> parseTags(String json) {
        List<Map<String, String>> out = new ArrayList<>();
        if (StrUtil.isBlank(json)) {
            return out;
        }
        try {
            JSONArray arr = JSONUtil.parseArray(json);
            for (int i = 0; i < arr.size(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Map<String, String> m = new LinkedHashMap<>();
                m.put("text", o.getStr("text"));
                m.put("cls", o.getStr("cls", "tag-gray"));
                out.add(m);
            }
        } catch (Exception ignored) {
            // ignore bad json
        }
        return out;
    }

    private GovWsMemberVo toMemberVo(GovWsMember m) {
        GovWsMemberVo vo = new GovWsMemberVo();
        vo.setId(m.getId());
        vo.setWsCode(m.getWsCode());
        vo.setSubjectType(m.getSubjectType());
        vo.setSubjectId(m.getSubjectId());
        vo.setDisplayName(m.getDisplayName());
        vo.setRoleCode(m.getRoleCode());
        vo.setScopeNote(m.getScopeNote());
        vo.setLastLogin(m.getLastLogin());
        vo.setStatus(m.getStatus());
        return vo;
    }

    private GovWsQuotaVo toQuotaVo(GovWsQuota q) {
        GovWsQuotaVo vo = new GovWsQuotaVo();
        vo.setWsCode(q.getWsCode());
        vo.setStatus(StrUtil.blankToDefault(q.getStatus(), "ok"));
        vo.setStorageQuotaTb(nz(q.getStorageQuotaTb()));
        vo.setStorageUsedTb(nz(q.getStorageUsedTb()));
        vo.setCuQuota(q.getCuQuota() == null ? 0 : q.getCuQuota());
        vo.setCuUsed(q.getCuUsed() == null ? 0 : q.getCuUsed());
        vo.setTrinoQuota(q.getTrinoQuota() == null ? 0 : q.getTrinoQuota());
        vo.setTrinoUsed(q.getTrinoUsed() == null ? 0 : q.getTrinoUsed());
        vo.setApiQpsQuota(q.getApiQpsQuota() == null ? 0 : q.getApiQpsQuota());
        vo.setApiQpsUsed(q.getApiQpsUsed() == null ? 0 : q.getApiQpsUsed());
        vo.setStoragePct(pct(vo.getStorageUsedTb(), vo.getStorageQuotaTb()));
        vo.setCuPct(pctInt(vo.getCuUsed(), vo.getCuQuota()));
        vo.setStorageLabel(vo.getStorageUsedTb().stripTrailingZeros().toPlainString()
                + "/" + vo.getStorageQuotaTb().stripTrailingZeros().toPlainString() + " TB");
        vo.setCuLabel(vo.getCuUsed() + "/" + vo.getCuQuota());
        vo.setTrinoLabel(vo.getTrinoUsed() + "/" + vo.getTrinoQuota());
        vo.setApiLabel(vo.getApiQpsUsed() + "/" + vo.getApiQpsQuota());
        if (vo.getStoragePct() >= 80 || vo.getCuPct() >= 80) {
            vo.setStatus("warn");
        }
        return vo;
    }

    private BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private int pct(BigDecimal used, BigDecimal quota) {
        if (quota == null || quota.compareTo(BigDecimal.ZERO) <= 0) {
            return 0;
        }
        return used.multiply(BigDecimal.valueOf(100))
                .divide(quota, 0, RoundingMode.HALF_UP)
                .intValue();
    }

    private int pctInt(int used, int quota) {
        if (quota <= 0) {
            return 0;
        }
        return (int) Math.round(used * 100.0 / quota);
    }
}
