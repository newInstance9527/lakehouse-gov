package vip.xiaonuo.lh.modular.datasource.discover;

import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.lh.core.vault.LhVaultClient;
import vip.xiaonuo.lh.modular.datasource.entity.LhDatasource;
import vip.xiaonuo.lh.modular.datasource.support.LhDatasourceProtocolProbe;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * FTP/SFTP：LIST 远程目录（限流）；登记 path 清单优先回填时可与 manual 并存。
 */
@Slf4j
@Component
@Order(10)
public class LhFtpInventoryDiscoverer implements LhInventoryDiscoverer {

    @Resource
    private LhVaultClient vaultClient;

    @Override
    public boolean supports(String typeCode) {
        String t = StrUtil.blankToDefault(typeCode, "").toLowerCase(java.util.Locale.ROOT);
        // file 为历史编码，与 FTP/SFTP 同协议
        return "ftp".equals(t) || "file".equals(t);
    }

    @Override
    public String objectKind(String typeCode) {
        return LhInventoryObjectKinds.PATH;
    }

    @Override
    public LhInventoryDiscoverResult discover(LhDatasource ds) {
        Map<String, Object> secret = vaultClient.readOrEmpty(ds.getVaultPath());
        if (StrUtil.isBlank(str(secret.get("host"))) && StrUtil.isNotBlank(ds.getEndpointHost())) {
            secret.put("host", ds.getEndpointHost());
        }
        if (StrUtil.isBlank(str(secret.get("port"))) && StrUtil.isNotBlank(ds.getEndpointPort())) {
            secret.put("port", ds.getEndpointPort());
        }
        try {
            List<String> names = LhDatasourceProtocolProbe.listFtpNames(secret, 200);
            List<LhRemoteInventoryItem> items = new ArrayList<>();
            for (String n : names) {
                LhRemoteInventoryItem m = new LhRemoteInventoryItem();
                m.name = n;
                m.engine = StrUtil.blankToDefault(ds.getType(), "ftp");
                m.encoding = "UTF-8";
                m.comment = "ftp_list";
                items.add(m);
            }
            if (items.isEmpty() && StrUtil.isNotBlank(ds.getSchemaSummary())) {
                return LhInventoryDiscoverResult.manual(
                        LhInventoryObjectKinds.PATH,
                        "远程目录为空，已回落手工路径清单",
                        parseSummary(ds.getSchemaSummary()));
            }
            return LhInventoryDiscoverResult.remote("ftp_list", LhInventoryObjectKinds.PATH, items);
        } catch (CommonException e) {
            throw e;
        } catch (Exception e) {
            throw new CommonException("FTP/SFTP 清单同步失败: {}", e.getMessage());
        }
    }

    private static List<LhRemoteInventoryItem> parseSummary(String summary) {
        List<LhRemoteInventoryItem> items = new ArrayList<>();
        if (StrUtil.isBlank(summary)) {
            return items;
        }
        for (String part : summary.split("[,，;；\\n\\r]+")) {
            String n = part.trim();
            if (n.isEmpty()) {
                continue;
            }
            LhRemoteInventoryItem m = new LhRemoteInventoryItem();
            m.name = n;
            m.engine = "ftp";
            m.comment = "manual:path";
            items.add(m);
        }
        return items;
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }
}
