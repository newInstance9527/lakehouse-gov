package vip.xiaonuo.lh.modular.ai.support;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.xiaonuo.auth.core.pojo.SaBaseLoginUser;
import vip.xiaonuo.lh.core.auth.LhLoginUsers;
import vip.xiaonuo.lh.modular.catalog.entity.GovAsset;
import vip.xiaonuo.lh.modular.catalog.mapper.GovAssetMapper;
import vip.xiaonuo.lh.modular.sec.service.GravTableAccessService;
import vip.xiaonuo.lh.modular.sec.service.SecAuthGrantService;
import vip.xiaonuo.lh.modular.sec.service.impl.SecAuthGrantServiceImpl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * AI「可查」与目录预览 / {@code /lh/sec/grants/check} 对齐：
 * <ul>
 *   <li>门户：owner 或 Grav 投影成功的 SELECT（{@link SecAuthGrantService#hasTableReadGrant}）</li>
 *   <li>引擎：当前主体 Trino 实测可 SELECT（{@link GravTableAccessService#canCurrentSelect}，与 FE check 同口径）</li>
 * </ul>
 */
@Slf4j
@Component
public class AiAssetReadAccess {

    private static final String NOT_DELETE = "NOT_DELETE";
    private static final int SCAN_CAP = 200;
    /** 对尚未被门户判定命中的当前空间资产，最多做几次 Grav 实测，避免 N 次 Trino */
    private static final int GRAV_PROBE_CAP = 24;

    @Resource
    private GovAssetMapper assetMapper;
    @Resource
    private SecAuthGrantService secAuthGrantService;
    @Resource
    private GravTableAccessService gravTableAccessService;

    /** 单表是否可读（AI schema / 搜索过滤 / 列表）。 */
    public boolean canRead(String assetId) {
        if (StrUtil.isBlank(assetId)) {
            return false;
        }
        try {
            if (secAuthGrantService.hasTableReadGrant(assetId)) {
                return true;
            }
        } catch (Exception e) {
            log.debug("hasTableReadGrant soft-fail asset={}: {}", assetId, e.toString());
        }
        try {
            return gravTableAccessService.canCurrentSelect(assetId);
        } catch (Exception e) {
            log.debug("canCurrentSelect soft-fail asset={}: {}", assetId, e.toString());
            return false;
        }
    }

    /**
     * 可查资产列表：当前空间门户可读优先，再补跨空间投影，再对缺口做有限 Grav 实测。
     * currentWs 仅软排序，不硬过滤。
     */
    public List<Map<String, Object>> listMyAssets(String preferWs, int topN) {
        int n = Math.max(1, Math.min(topN, 50));
        LinkedHashMap<String, Map<String, Object>> byId = new LinkedHashMap<>();
        List<Map<String, Object>> preferred = new ArrayList<>();
        List<Map<String, Object>> others = new ArrayList<>();
        try {
            SaBaseLoginUser user = LhLoginUsers.requireUser();
            // 1) 当前空间：按 hasTableReadGrant（含 owner）扫目录，避免 identity SQL IN 漏匹配
            if (StrUtil.isNotBlank(preferWs)) {
                List<GovAsset> wsRows = assetMapper.selectList(new QueryWrapper<GovAsset>().lambda()
                        .eq(GovAsset::getDeleteFlag, NOT_DELETE)
                        .eq(GovAsset::getWs, preferWs)
                        .orderByDesc(GovAsset::getUpdateTime)
                        .last("LIMIT " + SCAN_CAP));
                for (GovAsset a : wsRows) {
                    putIfReadablePortal(byId, a, user);
                }
                // 2) 门户未命中但 Grav 实测可 SELECT（与 FE grants/check、湖表预览同路径）
                int probed = 0;
                for (GovAsset a : wsRows) {
                    if (a == null || StrUtil.isBlank(a.getId()) || byId.containsKey(a.getId())) {
                        continue;
                    }
                    if (probed >= GRAV_PROBE_CAP) {
                        break;
                    }
                    probed++;
                    try {
                        if (gravTableAccessService.canCurrentSelect(a.getId())) {
                            byId.put(a.getId(), assetRow(a, "grav_live"));
                        }
                    } catch (Exception e) {
                        log.debug("grav probe soft-fail asset={}: {}", a.getId(), e.toString());
                    }
                }
            }
            // 3) 跨空间：门户已投影 SELECT
            List<String> grantIds = secAuthGrantService.listGrantedSelectAssetIds(user.getId());
            if (grantIds != null && !grantIds.isEmpty()) {
                List<String> need = grantIds.stream()
                        .filter(StrUtil::isNotBlank)
                        .filter(id -> !byId.containsKey(id))
                        .collect(Collectors.toList());
                if (!need.isEmpty()) {
                    List<GovAsset> granted = assetMapper.selectList(new QueryWrapper<GovAsset>().lambda()
                            .eq(GovAsset::getDeleteFlag, NOT_DELETE)
                            .in(GovAsset::getId, need)
                            .orderByDesc(GovAsset::getUpdateTime)
                            .last("LIMIT " + SCAN_CAP));
                    for (GovAsset a : granted) {
                        if (a == null || StrUtil.isBlank(a.getId()) || byId.containsKey(a.getId())) {
                            continue;
                        }
                        byId.put(a.getId(), assetRow(a, "granted"));
                    }
                }
            }
            // 4) 其它空间 owner：用原始 identity（非 lower）粗查，再 isAssetOwner 精筛
            List<String> rawIds = Stream.of(user.getId(), user.getAccount(), user.getName(), user.getNickname())
                    .filter(StrUtil::isNotBlank)
                    .map(String::trim)
                    .distinct()
                    .collect(Collectors.toList());
            if (!rawIds.isEmpty()) {
                List<GovAsset> ownedCand = assetMapper.selectList(new QueryWrapper<GovAsset>().lambda()
                        .eq(GovAsset::getDeleteFlag, NOT_DELETE)
                        .and(w -> w.in(GovAsset::getCreateUser, rawIds)
                                .or().in(GovAsset::getTechOwner, rawIds)
                                .or().in(GovAsset::getBizOwner, rawIds))
                        .orderByDesc(GovAsset::getUpdateTime)
                        .last("LIMIT " + SCAN_CAP));
                for (GovAsset a : ownedCand) {
                    if (a == null || StrUtil.isBlank(a.getId()) || byId.containsKey(a.getId())) {
                        continue;
                    }
                    if (SecAuthGrantServiceImpl.isAssetOwner(a, user)) {
                        byId.put(a.getId(), assetRow(a, "owned"));
                    }
                }
            }
            for (Map<String, Object> row : byId.values()) {
                if (StrUtil.isNotBlank(preferWs) && preferWs.equals(String.valueOf(row.get("ws")))) {
                    preferred.add(row);
                } else {
                    others.add(row);
                }
            }
        } catch (Exception e) {
            log.warn("listMyAssets failed: {}", e.toString());
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        out.addAll(preferred);
        out.addAll(others);
        if (out.size() > n) {
            return out.subList(0, n);
        }
        return out;
    }

    private void putIfReadablePortal(LinkedHashMap<String, Map<String, Object>> byId,
                                     GovAsset a, SaBaseLoginUser user) {
        if (a == null || StrUtil.isBlank(a.getId()) || byId.containsKey(a.getId())) {
            return;
        }
        // 创建人/Owner 优先（与目录预览一致）；勿仅依赖 hasTableReadGrant 间接路径
        if (SecAuthGrantServiceImpl.isAssetOwner(a, user)) {
            byId.put(a.getId(), assetRow(a, "owned"));
            return;
        }
        boolean portalOk;
        try {
            portalOk = secAuthGrantService.hasTableReadGrant(a.getId());
        } catch (Exception e) {
            portalOk = false;
        }
        if (!portalOk) {
            return;
        }
        byId.put(a.getId(), assetRow(a, "granted"));
    }

    private static Map<String, Object> assetRow(GovAsset a, String access) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("assetId", a.getId());
        m.put("assetCode", a.getAssetCode());
        m.put("name", StrUtil.blankToDefault(a.getCnName(), a.getName()));
        m.put("ws", a.getWs());
        m.put("layer", a.getLayer());
        m.put("access", access);
        return m;
    }
}
