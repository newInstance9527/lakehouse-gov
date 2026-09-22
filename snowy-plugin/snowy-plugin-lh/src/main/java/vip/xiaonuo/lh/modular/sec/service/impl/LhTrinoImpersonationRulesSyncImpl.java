package vip.xiaonuo.lh.modular.sec.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.core.vault.LhComponentCredentialResolver;
import vip.xiaonuo.lh.modular.sec.entity.LhTrinoPrincipal;
import vip.xiaonuo.lh.modular.sec.mapper.LhTrinoPrincipalMapper;
import vip.xiaonuo.lh.modular.sec.service.LhTrinoImpersonationRulesSync;
import vip.xiaonuo.lh.modular.sec.support.TrinoImpersonationRulesMerger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class LhTrinoImpersonationRulesSyncImpl implements LhTrinoImpersonationRulesSync {

    private static final Logger log = LoggerFactory.getLogger(LhTrinoImpersonationRulesSyncImpl.class);
    private static final String NOT_DELETE = "NOT_DELETE";
    /** 同步水位键：规则文件内容指纹 */
    public static final String MARK_KEY_CONTENT = "rules_content_sha256";

    @Resource
    private LhTrinoPrincipalMapper principalMapper;
    @Resource
    private LhProperties lhProperties;
    @Resource
    private LhComponentCredentialResolver credentialResolver;

    @Override
    public Map<String, Object> sync() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("duty", "trino-impersonation-rules-sync");
        out.put("markKey", MARK_KEY_CONTENT);
        String pathCfg = StrUtil.trim(lhProperties.getTrino().getImpersonationRulesPath());
        out.put("rulesPath", pathCfg);
        if (StrUtil.isBlank(pathCfg)) {
            out.put("status", "skipped");
            out.put("reason", "rules_path_empty");
            out.put("note", "配置 lh.trino.impersonation-rules-path 指向 Trino access-control/rules.json（可共享卷）后，主体变更会自动下发");
            return out;
        }

        List<LhTrinoPrincipal> rows = principalMapper.selectList(new QueryWrapper<LhTrinoPrincipal>().lambda()
                .eq(LhTrinoPrincipal::getDeleteFlag, NOT_DELETE)
                .eq(LhTrinoPrincipal::getStatus, "active")
                .eq(LhTrinoPrincipal::getKind, "human"));
        List<Map<String, Object>> impersonation = buildImpersonationRules(rows);
        List<String> users = new ArrayList<>();
        for (LhTrinoPrincipal row : rows) {
            if (row != null && StrUtil.isNotBlank(row.getTrinoUser())) {
                users.add(row.getTrinoUser().trim());
            }
        }
        out.put("principalCount", users.size());
        out.put("principals", users);

        Path target = Path.of(pathCfg).toAbsolutePath().normalize();
        try {
            Map<String, Object> existing;
            boolean bootstrapped = false;
            if (Files.isRegularFile(target)) {
                existing = TrinoImpersonationRulesMerger.parseRulesJson(Files.readString(target, StandardCharsets.UTF_8));
            } else {
                existing = TrinoImpersonationRulesMerger.bootstrapBase();
                bootstrapped = true;
            }
            Map<String, Object> merged = TrinoImpersonationRulesMerger.merge(existing, impersonation);
            String body = TrinoImpersonationRulesMerger.toPrettyJson(merged);
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = Path.of(target + ".tmp." + System.nanoTime());
            Files.writeString(tmp, body, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicFail) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            String markValue = TrinoImpersonationRulesMerger.contentMark(body);
            out.put("status", "synced");
            out.put("bootstrapped", bootstrapped);
            out.put("markValue", markValue);
            out.put("syncedAt", new Date());
            out.put("note", "已写入 impersonation；Trino file AC 若未开 refresh，需 reload/重启后生效（见 deploy/trino/README-impersonation.md）");
            log.info("Trino impersonation rules synced path={} principals={} mark={}", target, users.size(), markValue);
            return out;
        } catch (Exception e) {
            log.warn("Trino impersonation rules sync failed path={}: {}", target, e.toString());
            out.put("status", "degraded");
            out.put("reason", "write_failed");
            out.put("message", e.getMessage());
            return out;
        }
    }

    @Override
    public List<Map<String, Object>> buildImpersonationRules(List<LhTrinoPrincipal> rows) {
        List<String> users = new ArrayList<>();
        if (rows != null) {
            for (LhTrinoPrincipal row : rows) {
                if (row != null && StrUtil.isNotBlank(row.getTrinoUser())) {
                    users.add(row.getTrinoUser().trim());
                }
            }
        }
        return List.of(TrinoImpersonationRulesMerger.buildImpersonationRule(serviceUser(), users));
    }

    private String serviceUser() {
        try {
            String fromVault = credentialResolver.trino().get("username");
            if (StrUtil.isNotBlank(fromVault)) {
                return fromVault.trim();
            }
        } catch (Exception ignored) {
            // bootstrap
        }
        return StrUtil.blankToDefault(lhProperties.getTrino().getUser(), "admin").trim();
    }
}
