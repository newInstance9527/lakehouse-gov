package vip.xiaonuo.lh.modular.lifecycle.support;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.modular.catalog.entity.GovAsset;
import vip.xiaonuo.lh.modular.catalog.entity.GovAssetSourceLink;
import vip.xiaonuo.lh.modular.catalog.mapper.GovAssetMapper;
import vip.xiaonuo.lh.modular.catalog.mapper.GovAssetSourceLinkMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 从资产目录取 owner / layer，避免采集作业按表名前缀猜分层。
 */
@Component
public class GovLcStorageAssetEnricher {

    private static final Logger log = LoggerFactory.getLogger(GovLcStorageAssetEnricher.class);
    private static final String NOT_DELETE = "NOT_DELETE";

    @Resource
    private GovAssetMapper govAssetMapper;
    @Resource
    private GovAssetSourceLinkMapper sourceLinkMapper;

    public record AssetMeta(String owner, String layer, String assetCode, String ws) {
    }

    /** 按 fqtn / objectName 模糊匹配资产；失败返回 null。 */
    public AssetMeta resolve(String fqtn) {
        if (StrUtil.isBlank(fqtn)) {
            return null;
        }
        String key = fqtn.trim();
        String shortName = shortName(key);
        try {
            List<GovAssetSourceLink> links = sourceLinkMapper.selectList(new QueryWrapper<GovAssetSourceLink>().lambda()
                    .eq(GovAssetSourceLink::getDeleteFlag, NOT_DELETE)
                    .and(w -> w.eq(GovAssetSourceLink::getObjectName, key)
                            .or().eq(GovAssetSourceLink::getObjectName, shortName)
                            .or().like(GovAssetSourceLink::getObjectName, shortName))
                    .last("LIMIT 8"));
            for (GovAssetSourceLink link : links) {
                if (link == null || StrUtil.isBlank(link.getAssetId())) {
                    continue;
                }
                GovAsset asset = govAssetMapper.selectById(link.getAssetId());
                if (asset == null || !NOT_DELETE.equals(asset.getDeleteFlag())) {
                    continue;
                }
                String owner = firstNonBlank(asset.getTechOwner(), asset.getBizOwner());
                String layer = StrUtil.isBlank(asset.getLayer()) ? null
                        : asset.getLayer().trim().toUpperCase(Locale.ROOT);
                return new AssetMeta(owner, layer, asset.getAssetCode(), asset.getWs());
            }
            // 回退：om_fqn / name
            List<GovAsset> assets = govAssetMapper.selectList(new QueryWrapper<GovAsset>().lambda()
                    .eq(GovAsset::getDeleteFlag, NOT_DELETE)
                    .and(w -> w.eq(GovAsset::getOmFqn, key)
                            .or().like(GovAsset::getOmFqn, shortName)
                            .or().eq(GovAsset::getName, shortName))
                    .last("LIMIT 3"));
            for (GovAsset asset : assets) {
                String owner = firstNonBlank(asset.getTechOwner(), asset.getBizOwner());
                String layer = StrUtil.isBlank(asset.getLayer()) ? null
                        : asset.getLayer().trim().toUpperCase(Locale.ROOT);
                return new AssetMeta(owner, layer, asset.getAssetCode(), asset.getWs());
            }
        } catch (Exception e) {
            log.debug("asset enrich soft-fail {}: {}", key, e.getMessage());
        }
        return null;
    }

    public Map<String, AssetMeta> resolveBatch(Iterable<String> fqtns) {
        Map<String, AssetMeta> out = new HashMap<>();
        if (fqtns == null) {
            return out;
        }
        for (String f : fqtns) {
            if (StrUtil.isBlank(f) || out.containsKey(f)) {
                continue;
            }
            AssetMeta m = resolve(f);
            if (m != null) {
                out.put(f.trim(), m);
            }
        }
        return out;
    }

    private static String shortName(String fqn) {
        int i = fqn.lastIndexOf('.');
        return i >= 0 && i < fqn.length() - 1 ? fqn.substring(i + 1) : fqn;
    }

    private static String firstNonBlank(String a, String b) {
        if (StrUtil.isNotBlank(a)) {
            return a.trim();
        }
        if (StrUtil.isNotBlank(b)) {
            return b.trim();
        }
        return null;
    }
}
