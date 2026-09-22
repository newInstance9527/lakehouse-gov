package vip.xiaonuo.lh.modular.sec.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import vip.xiaonuo.auth.core.pojo.SaBaseLoginUser;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.core.auth.LhLoginUsers;
import vip.xiaonuo.lh.core.vault.LhComponentCredentialResolver;
import vip.xiaonuo.lh.modular.sec.entity.LhTrinoPrincipal;
import vip.xiaonuo.lh.modular.sec.mapper.LhTrinoPrincipalMapper;
import vip.xiaonuo.lh.modular.sec.param.LhTrinoPrincipalBindParam;
import vip.xiaonuo.lh.modular.sec.service.LhTrinoImpersonationRulesSync;
import vip.xiaonuo.lh.modular.sec.service.LhTrinoPrincipalService;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class LhTrinoPrincipalServiceImpl implements LhTrinoPrincipalService {

    private static final Logger log = LoggerFactory.getLogger(LhTrinoPrincipalServiceImpl.class);
    private static final String NOT_DELETE = "NOT_DELETE";
    private static final Pattern TRINO_USER = Pattern.compile("^[A-Za-z][A-Za-z0-9._\\-]{0,63}$");

    @Resource
    private LhTrinoPrincipalMapper principalMapper;
    @Resource
    private LhProperties lhProperties;
    @Resource
    private LhComponentCredentialResolver credentialResolver;
    @Resource
    private LhTrinoImpersonationRulesSync impersonationRulesSync;

    @Override
    public LhTrinoPrincipal requireCurrent() {
        SaBaseLoginUser user = LhLoginUsers.requireUser();
        LhTrinoPrincipal row = findActive(user.getId());
        if (row == null) {
            throw new CommonException(
                    "门户账号 " + StrUtil.blankToDefault(user.getAccount(), user.getId())
                            + " 未映射 Gravitino 主体，拒绝以服务账号执行。"
                            + "请超管 POST /lh/sec/principals/bind-me {\"trinoUser\":\"你的Trino人类主体\"}；"
                            + "禁止填服务账号 admin。表权限不在门户");
        }
        assertNotServiceAccount(row.getTrinoUser());
        return row;
    }

    @Override
    public LhTrinoPrincipal requireByPortalUserId(String portalUserId) {
        LhTrinoPrincipal row = findActive(portalUserId);
        if (row == null) {
            throw new CommonException("申请人未映射 Gravitino 主体，不能授予表权限: " + portalUserId);
        }
        assertNotServiceAccount(row.getTrinoUser());
        return row;
    }

    @Override
    public LhTrinoPrincipal findActive(String portalUserId) {
        if (StrUtil.isBlank(portalUserId)) {
            return null;
        }
        return principalMapper.selectOne(new QueryWrapper<LhTrinoPrincipal>().lambda()
                .eq(LhTrinoPrincipal::getDeleteFlag, NOT_DELETE)
                .eq(LhTrinoPrincipal::getStatus, "active")
                .eq(LhTrinoPrincipal::getKind, "human")
                .eq(LhTrinoPrincipal::getPortalUserId, portalUserId.trim())
                .last("LIMIT 1"));
    }

    @Override
    public LhTrinoPrincipal bind(LhTrinoPrincipalBindParam param) {
        if (!LhLoginUsers.isSuperAdmin()) {
            throw new CommonException("仅超管可绑定 Gravitino 主体（不授予表权限）");
        }
        String trinoUser = param.getTrinoUser().trim();
        if (!TRINO_USER.matcher(trinoUser).matches()) {
            throw new CommonException("Trino 主体只允许字母开头的标识符");
        }
        assertNotServiceAccount(trinoUser);
        String portalUserId = param.getPortalUserId().trim();
        LhTrinoPrincipal existing = principalMapper.selectOne(new QueryWrapper<LhTrinoPrincipal>().lambda()
                .eq(LhTrinoPrincipal::getPortalUserId, portalUserId)
                .last("LIMIT 1"));
        LhTrinoPrincipal nameTaken = principalMapper.selectOne(new QueryWrapper<LhTrinoPrincipal>().lambda()
                .eq(LhTrinoPrincipal::getTrinoUser, trinoUser)
                .eq(LhTrinoPrincipal::getDeleteFlag, NOT_DELETE)
                .last("LIMIT 1"));
        if (nameTaken != null && (existing == null || !nameTaken.getId().equals(existing.getId()))) {
            throw new CommonException("该 Gravitino 主体已绑定其他门户用户");
        }
        Date now = new Date();
        if (existing == null) {
            existing = new LhTrinoPrincipal();
            existing.setId(IdUtil.getSnowflakeNextIdStr());
            existing.setPortalUserId(portalUserId);
            existing.setCreateTime(now);
            existing.setCreateUser(LhLoginUsers.requireUserId());
            existing.setDeleteFlag(NOT_DELETE);
            fill(existing, param, trinoUser, now);
            principalMapper.insert(existing);
        } else {
            fill(existing, param, trinoUser, now);
            principalMapper.updateById(existing);
        }
        try {
            Map<String, Object> sync = impersonationRulesSync.sync();
            Object st = sync == null ? null : sync.get("status");
            if (st != null && !"synced".equals(String.valueOf(st)) && !"skipped".equals(String.valueOf(st))) {
                log.warn("principal bind ok but impersonation rules sync status={}", st);
            }
        } catch (Exception e) {
            log.warn("principal bind ok but impersonation rules sync failed: {}", e.toString());
        }
        return existing;
    }

    @Override
    public List<LhTrinoPrincipal> listActive() {
        if (!LhLoginUsers.isSuperAdmin()) {
            throw new CommonException("仅超管可查看主体映射");
        }
        return principalMapper.selectList(new QueryWrapper<LhTrinoPrincipal>().lambda()
                .eq(LhTrinoPrincipal::getDeleteFlag, NOT_DELETE)
                .eq(LhTrinoPrincipal::getStatus, "active")
                .orderByAsc(LhTrinoPrincipal::getPortalAccount));
    }

    @Override
    public Map<String, Object> impersonationFragment() {
        if (!LhLoginUsers.isSuperAdmin()) {
            throw new CommonException("仅超管可导出代执行规则");
        }
        List<LhTrinoPrincipal> rows = principalMapper.selectList(new QueryWrapper<LhTrinoPrincipal>().lambda()
                .eq(LhTrinoPrincipal::getDeleteFlag, NOT_DELETE)
                .eq(LhTrinoPrincipal::getStatus, "active")
                .eq(LhTrinoPrincipal::getKind, "human"));
        List<Map<String, Object>> rules = impersonationRulesSync.buildImpersonationRules(rows);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("duty", "trino-impersonation-only");
        out.put("note", "合并进 Trino rules.json 的 impersonation；字段须 original_user/new_user（snake_case）；主体变更后 POST /lh/sec/principals/impersonation-sync 或配置 rules-path 自动下发");
        out.put("impersonation", rules);
        out.put("configHint", "lh.trino.impersonation-rules-path + access-control.name=file；见 deploy/trino/README-impersonation.md");
        String path = StrUtil.trim(lhProperties.getTrino().getImpersonationRulesPath());
        out.put("rulesPathConfigured", StrUtil.isNotBlank(path));
        out.put("rulesPath", path);
        return out;
    }

    @Override
    public Map<String, Object> syncImpersonationRules() {
        if (!LhLoginUsers.isSuperAdmin()) {
            throw new CommonException("仅超管可下发代执行规则");
        }
        return impersonationRulesSync.sync();
    }

    private void fill(LhTrinoPrincipal row, LhTrinoPrincipalBindParam param, String trinoUser, Date now) {
        row.setPortalAccount(param.getPortalAccount().trim());
        row.setTrinoUser(trinoUser);
        row.setKind("human");
        row.setStatus("active");
        row.setRemark(param.getRemark());
        row.setDeleteFlag(NOT_DELETE);
        row.setUpdateTime(now);
        row.setUpdateUser(LhLoginUsers.requireUserId());
    }

    private void assertNotServiceAccount(String trinoUser) {
        String service = serviceUser();
        if (StrUtil.isNotBlank(service) && service.equalsIgnoreCase(trinoUser)) {
            throw new CommonException("禁止把 Trino 服务账号 " + service + " 映射为终端用户");
        }
    }

    private String serviceUser() {
        try {
            String fromVault = credentialResolver.trino().get("username");
            if (StrUtil.isNotBlank(fromVault)) {
                return fromVault.trim();
            }
        } catch (Exception ignored) {
            // bootstrap 用户名
        }
        return StrUtil.blankToDefault(lhProperties.getTrino().getUser(), "admin").trim();
    }
}
