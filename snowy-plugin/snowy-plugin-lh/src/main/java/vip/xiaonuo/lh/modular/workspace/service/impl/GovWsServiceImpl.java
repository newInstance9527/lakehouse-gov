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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vip.xiaonuo.auth.core.pojo.SaBaseLoginUser;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.lh.core.auth.LhLoginUsers;
import vip.xiaonuo.lh.core.auth.LhOwnerGuard;
import vip.xiaonuo.lh.modular.aimodel.entity.GovAiUsageDaily;
import vip.xiaonuo.lh.modular.aimodel.mapper.GovAiUsageDailyMapper;
import vip.xiaonuo.lh.modular.catalog.entity.GovAsset;
import vip.xiaonuo.lh.modular.catalog.mapper.GovAssetMapper;
import vip.xiaonuo.lh.modular.compute.support.CpGiteaClient;
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
import vip.xiaonuo.lh.modular.workspace.param.GovWsQuotaUpdateParam;
import vip.xiaonuo.lh.modular.workspace.param.GovWsTagAddParam;
import vip.xiaonuo.lh.modular.workspace.param.GovWsTagsReplaceParam;
import vip.xiaonuo.lh.modular.workspace.result.GovWsMemberVo;
import vip.xiaonuo.lh.modular.workspace.result.GovWsQuotaVo;
import vip.xiaonuo.lh.modular.workspace.result.GovWsVo;
import vip.xiaonuo.lh.modular.workspace.service.GovWsService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Calendar;
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
    @Resource
    private GovAiUsageDailyMapper aiUsageMapper;
    @Resource
    private CpGiteaClient giteaClient;

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
        row.setWsKind("team");
        row.setName(param.getName().trim());
        row.setIcon(StrUtil.blankToDefault(param.getIcon(), "🗂️"));
        row.setDomainCode(StrUtil.blankToDefault(param.getDomainCode(), "自定义"));
        row.setCostCenter(StrUtil.blankToDefault(param.getCostCenter(), "CC-" + code.toUpperCase(Locale.ROOT)));
        row.setTrinoRg(StrUtil.blankToDefault(param.getTrinoRg(), "rg_" + code.replace("ws_", "")));
        row.setPreferredSchemas(StrUtil.blankToDefault(param.getPreferredSchemas(), "—"));
        row.setOwners(StrUtil.blankToDefault(user.getName(), user.getAccount()));
        row.setDetail(StrUtil.blankToDefault(param.getDetail(),
                "已创建团队空间；列表默认跟随本空间。读数请走申请中心。"));
        row.setTagsJson("[{\"text\":\"新建\",\"cls\":\"tag-blue\"},{\"text\":\"空间优先\",\"cls\":\"tag-gray\"}]");
        if (giteaClient != null && giteaClient.enabled()) {
            String ensureErr = giteaClient.ensureRepo(code, row.getId());
            if (StrUtil.isBlank(ensureErr)) {
                row.setGitRemoteUrl(giteaClient.remoteUrlFor(code, row.getId()));
            }
            // Gitea 不可达时仍可建空间；git_remote_url 留空，Owner 稍后「同步 Gitea」
        }
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
        return softDelete(wsCode, "默认空间不可归档");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GovWsVo delete(String wsCode) {
        return softDelete(wsCode, "默认空间不可删除");
    }

    /**
     * 软删：status=archived；列表只展示 active。
     * 成员/配额行保留（历史与 showback）；用户偏好回落 default。
     */
    private GovWsVo softDelete(String wsCode, String defaultBlockedMsg) {
        GovWs row = requireWs(wsCode);
        assertWsOwner(row);
        if (WS_DEFAULT.equals(row.getWsCode())) {
            throw new CommonException(defaultBlockedMsg);
        }
        if (STATUS_ARCHIVED.equals(row.getStatus())) {
            throw new CommonException("空间已归档/删除: " + row.getWsCode());
        }
        long activeCount = wsMapper.selectCount(new QueryWrapper<GovWs>().lambda()
                .eq(GovWs::getDeleteFlag, NOT_DELETE)
                .eq(GovWs::getStatus, STATUS_ACTIVE));
        if (activeCount <= 1) {
            throw new CommonException("至少保留一个活跃工作空间，不可删除最后一个");
        }
        String code = row.getWsCode();
        row.setStatus(STATUS_ARCHIVED);
        row.setRevision(row.getRevision() == null ? 2 : row.getRevision() + 1);
        Date now = new Date();
        row.setUpdateTime(now);
        row.setUpdateUser(LhLoginUsers.requireUserId());
        wsMapper.updateById(row);

        // 偏好指向已删空间 → 回落平台 default
        List<GovWsUserPref> prefs = prefMapper.selectList(new QueryWrapper<GovWsUserPref>().lambda()
                .eq(GovWsUserPref::getCurrentWsCode, code));
        for (GovWsUserPref pref : prefs) {
            pref.setCurrentWsCode(WS_DEFAULT);
            pref.setUpdateTime(now);
            prefMapper.updateById(pref);
        }
        return toVo(row, resolveCurrentWsCode());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GovWsVo syncGitRemote(String wsCode) {
        GovWs row = requireWs(wsCode);
        assertWsOwner(row);
        if (giteaClient == null || !giteaClient.enabled()) {
            throw new CommonException("未启用 lh.compute.gitea，无法同步远程");
        }
        String stored = StrUtil.trim(row.getGitRemoteUrl());
        // 自定义公网 remote：保留，不覆盖为平台 Gitea（畸形 ws-- 等仍改写）
        if (giteaClient.isCustomPublicRemote(stored)
                && !CpGiteaClient.isMalformedWsRepoRemote(stored)) {
            return toVo(row, resolveCurrentWsCode());
        }
        String ensureErr = giteaClient.ensureRepo(row.getWsCode(), row.getId());
        if (StrUtil.isNotBlank(ensureErr)) {
            throw new CommonException(ensureErr);
        }
        String url = giteaClient.remoteUrlFor(row.getWsCode(), row.getId());
        if (StrUtil.isBlank(url)) {
            throw new CommonException("无法拼装 Gitea remote URL（检查 lh.compute.gitea.base-url/token）");
        }
        row.setGitRemoteUrl(url);
        row.setRevision(row.getRevision() == null ? 2 : row.getRevision() + 1);
        row.setUpdateTime(new Date());
        row.setUpdateUser(LhLoginUsers.requireUserId());
        wsMapper.updateById(row);
        return toVo(row, resolveCurrentWsCode());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GovWsVo addTag(String wsCode, GovWsTagAddParam param) {
        GovWs row = requireWs(wsCode);
        assertWsOwner(row);
        if (param == null || StrUtil.isBlank(param.getText())) {
            throw new CommonException("标签文案不能为空");
        }
        String text = param.getText().trim();
        if (text.length() > 32) {
            throw new CommonException("标签文案最多 32 字");
        }
        String cls = normalizeTagCls(param.getCls());
        List<Map<String, String>> tags = new ArrayList<>(parseTags(row.getTagsJson()));
        for (Map<String, String> t : tags) {
            if (text.equalsIgnoreCase(StrUtil.blankToDefault(t.get("text"), ""))) {
                throw new CommonException("标签已存在: " + text);
            }
        }
        if (tags.size() >= 12) {
            throw new CommonException("每个空间最多 12 个标签");
        }
        Map<String, String> item = new LinkedHashMap<>();
        item.put("text", text);
        item.put("cls", cls);
        tags.add(item);
        persistTags(row, tags);
        return toVo(requireWs(wsCode), resolveCurrentWsCode());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GovWsVo removeTag(String wsCode, String text) {
        GovWs row = requireWs(wsCode);
        assertWsOwner(row);
        String target = StrUtil.trim(text);
        if (StrUtil.isBlank(target)) {
            throw new CommonException("请指定要移除的标签文案");
        }
        List<Map<String, String>> tags = new ArrayList<>(parseTags(row.getTagsJson()));
        int before = tags.size();
        tags.removeIf(t -> target.equalsIgnoreCase(StrUtil.blankToDefault(t.get("text"), "")));
        if (tags.size() == before) {
            throw new CommonException("标签不存在: " + target);
        }
        persistTags(row, tags);
        return toVo(requireWs(wsCode), resolveCurrentWsCode());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GovWsVo replaceTags(String wsCode, GovWsTagsReplaceParam param) {
        GovWs row = requireWs(wsCode);
        assertWsOwner(row);
        List<Map<String, String>> tags = new ArrayList<>();
        if (param != null && param.getTags() != null) {
            Set<String> seen = new java.util.LinkedHashSet<>();
            for (GovWsTagAddParam p : param.getTags()) {
                if (p == null || StrUtil.isBlank(p.getText())) {
                    continue;
                }
                String text = p.getText().trim();
                if (text.length() > 32) {
                    throw new CommonException("标签文案最多 32 字: " + text);
                }
                String key = text.toLowerCase(Locale.ROOT);
                if (!seen.add(key)) {
                    continue;
                }
                Map<String, String> item = new LinkedHashMap<>();
                item.put("text", text);
                item.put("cls", normalizeTagCls(p.getCls()));
                tags.add(item);
                if (tags.size() > 12) {
                    throw new CommonException("每个空间最多 12 个标签");
                }
            }
        }
        persistTags(row, tags);
        return toVo(requireWs(wsCode), resolveCurrentWsCode());
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
        GovWs ws = requireWs(wsCode);
        assertWsOwner(ws);
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
        assertWsOwner(requireWs(wsCode));
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
        assertWsOwner(requireWs(wsCode));
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
        Set<String> activeCodes = listActiveEntities().stream()
                .map(GovWs::getWsCode)
                .collect(Collectors.toSet());
        return quotaMapper.selectList(new QueryWrapper<GovWsQuota>().lambda()
                        .eq(GovWsQuota::getDeleteFlag, NOT_DELETE)
                        .orderByAsc(GovWsQuota::getWsCode))
                .stream()
                .filter(q -> activeCodes.contains(q.getWsCode()))
                .map(this::toQuotaVo)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GovWsQuotaVo updateQuota(String wsCode, GovWsQuotaUpdateParam param) {
        GovWs ws = requireWs(wsCode);
        assertWsOwner(ws);
        if (param == null) {
            throw new CommonException("配额更新参数不能为空");
        }
        GovWsQuota q = quotaMapper.selectOne(new QueryWrapper<GovWsQuota>().lambda()
                .eq(GovWsQuota::getDeleteFlag, NOT_DELETE)
                .eq(GovWsQuota::getWsCode, wsCode)
                .last("LIMIT 1"));
        if (q == null) {
            throw new CommonException("配额未配置: " + wsCode);
        }
        boolean touched = false;
        if (param.getStorageQuotaTb() != null) {
            if (param.getStorageQuotaTb().compareTo(BigDecimal.ZERO) < 0) {
                throw new CommonException("storageQuotaTb 不能为负");
            }
            q.setStorageQuotaTb(param.getStorageQuotaTb());
            touched = true;
        }
        if (param.getCuQuota() != null) {
            if (param.getCuQuota() < 0) {
                throw new CommonException("cuQuota 不能为负");
            }
            q.setCuQuota(param.getCuQuota());
            touched = true;
        }
        if (param.getTrinoQuota() != null) {
            if (param.getTrinoQuota() < 0) {
                throw new CommonException("trinoQuota 不能为负");
            }
            q.setTrinoQuota(param.getTrinoQuota());
            touched = true;
        }
        if (param.getApiQpsQuota() != null) {
            if (param.getApiQpsQuota() < 0) {
                throw new CommonException("apiQpsQuota 不能为负");
            }
            q.setApiQpsQuota(param.getApiQpsQuota());
            touched = true;
        }
        // AI：null = 不改；0 = 不限（写 NULL）；>0 = 上限
        if (param.getAiTokenQuota() != null) {
            if (param.getAiTokenQuota() < 0) {
                throw new CommonException("aiTokenQuota 不能为负");
            }
            q.setAiTokenQuota(param.getAiTokenQuota() == 0L ? null : param.getAiTokenQuota());
            touched = true;
        }
        if (param.getAiCostQuota() != null) {
            if (param.getAiCostQuota().compareTo(BigDecimal.ZERO) < 0) {
                throw new CommonException("aiCostQuota 不能为负");
            }
            q.setAiCostQuota(param.getAiCostQuota().compareTo(BigDecimal.ZERO) == 0
                    ? null
                    : param.getAiCostQuota());
            touched = true;
        }
        if (!touched) {
            throw new CommonException("未提供可更新的配额字段");
        }
        q.setUpdateTime(new Date());
        try {
            q.setUpdateUser(LhLoginUsers.requireUserId());
        } catch (Exception ignored) {
            // soft
        }
        quotaMapper.updateById(q);
        return toQuotaVo(q);
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
            boolean member = isWsCollaborator(w);
            out.put("member", member);
            out.put("myRole", myRole(w.getWsCode()));
        } else {
            out.put("member", true);
            out.put("myRole", null);
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
        boolean member = isWsCollaborator(w);
        boolean force = Boolean.TRUE.equals(param.getConfirmNonMember());
        // default 对全员开放；其它空间非成员须确认（登记默认归属可能误绑）
        if (!member && !WS_DEFAULT.equals(code) && !force) {
            throw new CommonException(
                    "NON_MEMBER_CONFIRM: 你不是空间「" + code + "」成员，切换后新建资产将默认归属该空间。确认请传 confirmNonMember=true");
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
        // 勿在写事务内调用 getCurrent()：其 myRole/resolve 路径会吞掉 SQL 异常，
        // 导致事务已被标 rollback-only 却正常返回 → UnexpectedRollbackException。
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("wsCode", code);
        out.put("sharedCatalog", SHARED_CATALOG);
        out.put("name", w.getName());
        out.put("domainCode", w.getDomainCode());
        out.put("member", member);
        out.put("myRole", myRole(code));
        out.put("nonMemberConfirmed", !member && force);
        return out;
    }

    @Override
    public List<Map<String, Object>> listQuotaAlerts() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (GovWsQuotaVo vo : listQuotas()) {
            int storagePct = vo.getStoragePct() == null ? 0 : vo.getStoragePct();
            int cuPct = vo.getCuPct() == null ? 0 : vo.getCuPct();
            int aiTokenPct = vo.getAiTokenPct() == null ? 0 : vo.getAiTokenPct();
            int aiCostPct = vo.getAiCostPct() == null ? 0 : vo.getAiCostPct();
            int maxPct = Math.max(Math.max(storagePct, cuPct), Math.max(aiTokenPct, aiCostPct));
            if (maxPct < 60) {
                continue;
            }
            String level = maxPct >= 80 ? "alert" : "warn";
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("wsCode", vo.getWsCode());
            row.put("level", level);
            row.put("maxPct", maxPct);
            row.put("storagePct", storagePct);
            row.put("cuPct", cuPct);
            row.put("aiTokenPct", aiTokenPct);
            row.put("aiCostPct", aiCostPct);
            row.put("status", vo.getStatus());
            row.put("storageLabel", vo.getStorageLabel());
            row.put("cuLabel", vo.getCuLabel());
            row.put("hint", maxPct >= 80
                    ? "配额告警：建议限流或调高上限"
                    : "配额提示：接近上限");
            GovWs w = findByCode(vo.getWsCode());
            if (w != null) {
                row.put("name", w.getName());
            }
            out.add(row);
        }
        out.sort((a, b) -> Integer.compare(
                ((Number) b.getOrDefault("maxPct", 0)).intValue(),
                ((Number) a.getOrDefault("maxPct", 0)).intValue()));
        return out;
    }

    /** 超管 / 成员 / 创建人 / 平台 default（全员开放）视为协作方 */
    private boolean isWsCollaborator(GovWs w) {
        if (w == null) {
            return false;
        }
        // 平台默认空间：全员可切、可协作上下文；不要求写入 gov_ws_member
        if (WS_DEFAULT.equals(w.getWsCode())) {
            return true;
        }
        if (LhLoginUsers.isSuperAdmin()) {
            return true;
        }
        if (StrUtil.isNotBlank(myRole(w.getWsCode()))) {
            return true;
        }
        try {
            SaBaseLoginUser user = LhLoginUsers.requireUser();
            return LhOwnerGuard.isOwner(user, w.getCreateUser());
        } catch (Exception e) {
            return false;
        }
    }

    // ---------- helpers ----------

    /** 空间成员管理：仅空间 Owner（gov_ws_member.role=Owner）或超管 */
    private void assertWsOwner(GovWs ws) {
        if (ws == null) {
            throw new CommonException("工作空间不存在");
        }
        if (LhLoginUsers.isSuperAdmin()) {
            return;
        }
        SaBaseLoginUser user = LhLoginUsers.requireUser();
        String role = myRole(ws.getWsCode());
        if ("Owner".equalsIgnoreCase(StrUtil.blankToDefault(role, ""))) {
            return;
        }
        // 兼容：创建人视为 Owner（成员表尚未写入时）
        if (LhOwnerGuard.isOwner(user, ws.getCreateUser())) {
            return;
        }
        throw new CommonException("仅空间 Owner 可管理成员/标签/删除：" + LhOwnerGuard.MSG_NEED_APPLY);
    }

    private static final Set<String> TAG_CLS_ALLOWED = Set.of(
            "tag-blue", "tag-gray", "tag-green", "tag-orange", "tag-red", "tag-purple");

    private String normalizeTagCls(String cls) {
        String c = StrUtil.blankToDefault(StrUtil.trim(cls), "tag-blue");
        if (!TAG_CLS_ALLOWED.contains(c)) {
            throw new CommonException("不支持的标签样式: " + c);
        }
        return c;
    }

    /** 持久化 tags_json（列长 512）；空列表写 null */
    private void persistTags(GovWs row, List<Map<String, String>> tags) {
        String json;
        if (tags == null || tags.isEmpty()) {
            json = null;
        } else {
            json = JSONUtil.toJsonStr(tags);
            if (json.length() > 512) {
                throw new CommonException("标签合计过长（上限 512 字符），请删减后再试");
            }
        }
        row.setTagsJson(json);
        row.setRevision(row.getRevision() == null ? 2 : row.getRevision() + 1);
        row.setUpdateTime(new Date());
        row.setUpdateUser(LhLoginUsers.requireUserId());
        wsMapper.updateById(row);
    }

    private List<GovWs> listActiveEntities() {
        return wsMapper.selectList(new QueryWrapper<GovWs>().lambda()
                .eq(GovWs::getDeleteFlag, NOT_DELETE)
                .eq(GovWs::getStatus, STATUS_ACTIVE)
                .ne(GovWs::getWsCode, "enterprise")
                .ne(GovWs::getWsKind, "enterprise")
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
        } catch (Exception e) {
            // 写事务内不可吞掉：DataAccessException 已被标 rollback-only
            rethrowIfInTransaction(e);
            // 未登录时概览/列表仍可用默认
        }
        // 无用户偏好时软上下文为平台 default（不造演示域空间）
        return WS_DEFAULT;
    }

    private long countAssets(String wsCode) {
        try {
            return assetMapper.selectCount(new QueryWrapper<GovAsset>().lambda()
                    .eq(GovAsset::getDeleteFlag, NOT_DELETE)
                    .eq(GovAsset::getWs, wsCode));
        } catch (Exception e) {
            rethrowIfInTransaction(e);
            return 0L;
        }
    }

    /** 写事务中吞掉 RuntimeException 会触发 UnexpectedRollbackException */
    private static void rethrowIfInTransaction(Exception e) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            return;
        }
        if (e instanceof RuntimeException re) {
            throw re;
        }
        throw new CommonException(StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()));
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
            if (!list.isEmpty()) {
                return list.get(0).getRoleCode();
            }
            // 平台 default：无成员行时仍视为全员可用（顶栏勿标「非成员」）
            if (WS_DEFAULT.equals(StrUtil.trim(wsCode))) {
                return "Member";
            }
            return null;
        } catch (Exception e) {
            rethrowIfInTransaction(e);
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
            c = c.replaceAll("_+", "_").replaceAll("^_|_$", "");
            if (c.startsWith("ws_")) {
                c = c.substring(3).replaceAll("^_|_$", "");
            }
            if (WS_DEFAULT.equals(c)) {
                return WS_DEFAULT;
            }
            if (StrUtil.isBlank(c)) {
                throw new CommonException("空间编码无效：需包含字母或数字（纯中文/符号不可用）");
            }
            return "ws_" + c;
        }
        String base = StrUtil.blankToDefault(name, "space")
                .trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
        if (StrUtil.isBlank(base)) {
            // 纯中文名等消毒后为空 → 雪花 id，避免落库 ws__ → Gitea ws--
            base = IdUtil.getSnowflakeNextIdStr();
        }
        return "ws_" + base;
    }

    private GovWsVo toVo(GovWs w, String current) {
        GovWsVo vo = new GovWsVo();
        vo.setId(w.getId());
        vo.setWsCode(w.getWsCode());
        vo.setWsKind(StrUtil.blankToDefault(w.getWsKind(), "team"));
        vo.setName(w.getName());
        vo.setIcon(w.getIcon());
        vo.setDomainCode(w.getDomainCode());
        vo.setCostCenter(w.getCostCenter());
        vo.setTrinoRg(w.getTrinoRg());
        vo.setPreferredSchemas(w.getPreferredSchemas());
        vo.setTechNs(w.getTechNs());
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
        String display = CpGiteaClient.redactRemoteUrl(w.getGitRemoteUrl());
        vo.setGitRemoteUrlDisplay(display);
        // 兼容旧 FE：同脱敏值；永不下发带 token 的 raw
        vo.setGitRemoteUrl(display);
        boolean custom = giteaClient != null && giteaClient.isCustomPublicRemote(w.getGitRemoteUrl());
        vo.setGitRemoteCustom(custom);

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

        long aiTokenUsed = 0L;
        BigDecimal aiCostUsed = BigDecimal.ZERO;
        try {
            Date day = truncateDay(new Date());
            List<GovAiUsageDaily> rows = aiUsageMapper.selectList(new QueryWrapper<GovAiUsageDaily>().lambda()
                    .eq(GovAiUsageDaily::getDay, day)
                    .eq(GovAiUsageDaily::getWs, q.getWsCode()));
            for (GovAiUsageDaily row : rows) {
                aiTokenUsed += (row.getPromptTokens() == null ? 0L : row.getPromptTokens())
                        + (row.getCompletionTokens() == null ? 0L : row.getCompletionTokens());
                if (row.getCostAmount() != null) {
                    aiCostUsed = aiCostUsed.add(row.getCostAmount());
                }
            }
        } catch (Exception ignored) {
            // soft：用量表未就绪时不阻断配额读
        }
        Long aiTokenQuota = q.getAiTokenQuota();
        BigDecimal aiCostQuota = q.getAiCostQuota();
        vo.setAiTokenQuota(aiTokenQuota);
        vo.setAiTokenUsed(aiTokenUsed);
        vo.setAiCostQuota(aiCostQuota);
        vo.setAiCostUsed(aiCostUsed);
        boolean limitTok = aiTokenQuota != null && aiTokenQuota > 0;
        boolean limitCost = aiCostQuota != null && aiCostQuota.compareTo(BigDecimal.ZERO) > 0;
        vo.setAiTokenPct(limitTok ? pctLong(aiTokenUsed, aiTokenQuota) : 0);
        vo.setAiCostPct(limitCost ? pct(aiCostUsed, aiCostQuota) : 0);
        vo.setAiTokenLabel(limitTok
                ? (aiTokenUsed + "/" + aiTokenQuota)
                : (aiTokenUsed + "/不限"));
        vo.setAiCostLabel(limitCost
                ? (aiCostUsed.stripTrailingZeros().toPlainString() + "/"
                    + aiCostQuota.stripTrailingZeros().toPlainString())
                : (aiCostUsed.stripTrailingZeros().toPlainString() + "/不限"));
        vo.setAiTokenRemaining(limitTok ? Math.max(0L, aiTokenQuota - aiTokenUsed) : null);
        vo.setAiCostRemaining(limitCost
                ? aiCostQuota.subtract(aiCostUsed).max(BigDecimal.ZERO)
                : null);
        vo.setAiLimited(limitTok || limitCost);

        if (vo.getStoragePct() >= 80 || vo.getCuPct() >= 80
                || vo.getAiTokenPct() >= 80 || vo.getAiCostPct() >= 80) {
            vo.setStatus("warn");
        }
        return vo;
    }

    private static Date truncateDay(Date d) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(d);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTime();
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

    private int pctLong(long used, long quota) {
        if (quota <= 0) {
            return 0;
        }
        return (int) Math.round(used * 100.0 / quota);
    }
}
