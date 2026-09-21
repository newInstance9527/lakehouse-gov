package vip.xiaonuo.lh.modular.query.support;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import vip.xiaonuo.auth.core.pojo.SaBaseLoginUser;
import vip.xiaonuo.auth.core.util.StpLoginUserUtil;
import vip.xiaonuo.lh.modular.catalog.entity.GovAsset;
import vip.xiaonuo.lh.modular.catalog.mapper.GovAssetMapper;
import vip.xiaonuo.lh.modular.schemasync.entity.CbGravAssetRef;

import java.util.Locale;
import java.util.Set;

/**
 * 湖 catalog 上的 Grav 表挂到门户资产，即席目录才能列出。
 * JDBC 登记名（ds_*）不在 lake-catalogs 里，不会建资产。
 */
@Component
public class LakeQueryAssetBinder {

    private static final String NOT_DELETE = "NOT_DELETE";
    private static final Set<String> LAYERS = Set.of("ods", "dwd", "dws", "ads", "dim");

    @Resource
    private GovAssetMapper assetMapper;
    @Resource
    private CpTrinoQueryCatalogService queryCatalogService;

    public void ensure(CbGravAssetRef ref) {
        if (ref == null || StrUtil.hasBlank(ref.getGravCatalog(), ref.getGravSchema(), ref.getGravTable())) {
            return;
        }
        if (!queryCatalogService.lakeCatalogs().stream().anyMatch(c -> c.equalsIgnoreCase(ref.getGravCatalog()))) {
            return;
        }
        String ws = StrUtil.blankToDefault(ref.getWs(), "default");
        GovAsset asset = assetMapper.selectOne(new QueryWrapper<GovAsset>().lambda()
                .eq(GovAsset::getGravAssetId, ref.getId())
                .eq(GovAsset::getDeleteFlag, NOT_DELETE)
                .last("LIMIT 1"));
        String code = assetCode(ref);
        if (asset == null) {
            GovAsset byCode = assetMapper.selectOne(new QueryWrapper<GovAsset>().lambda()
                    .eq(GovAsset::getWs, ws)
                    .eq(GovAsset::getAssetCode, code)
                    .eq(GovAsset::getDeleteFlag, NOT_DELETE)
                    .last("LIMIT 1"));
            if (byCode != null && (StrUtil.isBlank(byCode.getGravAssetId()) || ref.getId().equals(byCode.getGravAssetId()))) {
                asset = byCode;
            } else if (byCode != null) {
                code = scopedCode(ref);
            }
        }
        String userId = currentUserId();
        if (asset == null) {
            String[] ld = layerDomain(ref);
            asset = new GovAsset();
            asset.setId(IdUtil.getSnowflakeNextIdStr());
            asset.setRevision(1);
            asset.setStatus("active");
            asset.setWs(ws);
            asset.setAssetCode(code);
            asset.setName(ref.getGravTable().trim());
            asset.setAssetKind("table");
            asset.setLayer(ld[0]);
            asset.setDomainCode(ld[1]);
            asset.setSensitivity("internal");
            asset.setEngine("Iceberg");
            asset.setIsGold(0);
            asset.setGravAssetId(ref.getId());
            asset.setLastSyncStatus("ok");
            asset.setDeleteFlag(NOT_DELETE);
            if (StrUtil.isNotBlank(userId)) {
                asset.setCreateUser(userId);
                asset.setTechOwner(userId);
            }
            assetMapper.insert(asset);
            return;
        }
        boolean dirty = false;
        if (!ref.getId().equals(asset.getGravAssetId())) {
            asset.setGravAssetId(ref.getId());
            dirty = true;
        }
        if (StrUtil.isBlank(asset.getEngine())) {
            asset.setEngine("Iceberg");
            dirty = true;
        }
        if (StrUtil.isBlank(asset.getTechOwner()) && StrUtil.isNotBlank(userId)) {
            asset.setTechOwner(userId);
            dirty = true;
        }
        if (dirty) {
            asset.setRevision(asset.getRevision() == null ? 1 : asset.getRevision() + 1);
            assetMapper.updateById(asset);
        }
    }

    private static String assetCode(CbGravAssetRef ref) {
        String raw = ref.getGravTable().trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        if (raw.isEmpty() || !Character.isLetter(raw.charAt(0))) {
            raw = "t_" + raw;
        }
        if (raw.length() > 64) {
            raw = raw.substring(0, 64);
        }
        return raw;
    }

    private static String scopedCode(CbGravAssetRef ref) {
        String schema = ref.getGravSchema().trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        String code = schema + "_" + assetCode(ref);
        if (code.length() > 64) {
            code = code.substring(0, 64);
        }
        return code;
    }

    private static String[] layerDomain(CbGravAssetRef ref) {
        if (StrUtil.isNotBlank(ref.getLayer()) && StrUtil.isNotBlank(ref.getDomainCode())) {
            return new String[]{ref.getLayer().trim().toLowerCase(Locale.ROOT), ref.getDomainCode().trim()};
        }
        String schema = ref.getGravSchema().trim().toLowerCase(Locale.ROOT);
        for (String layer : LAYERS) {
            if (schema.equals(layer)) {
                return new String[]{layer, "default"};
            }
            String prefix = layer + "_";
            if (schema.startsWith(prefix) && schema.length() > prefix.length()) {
                return new String[]{layer, schema.substring(prefix.length())};
            }
        }
        String layer = StrUtil.blankToDefault(ref.getLayer(), "ods").trim().toLowerCase(Locale.ROOT);
        String domain = StrUtil.blankToDefault(ref.getDomainCode(), "default").trim();
        return new String[]{layer, domain};
    }

    private static String currentUserId() {
        try {
            SaBaseLoginUser user = StpLoginUserUtil.getLoginUser();
            return user == null ? null : user.getId();
        } catch (Exception e) {
            return null;
        }
    }
}
