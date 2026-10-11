package vip.xiaonuo.lh.modular.datasource.discover;

import cn.hutool.core.util.StrUtil;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
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
 * MongoDB：listCollectionNames（限流）；登记集合清单合并。
 */
@Slf4j
@Component
@Order(10)
public class LhMongoInventoryDiscoverer implements LhInventoryDiscoverer {

    private static final int MAX_COLLECTIONS = 300;

    @Resource
    private LhVaultClient vaultClient;

    @Override
    public boolean supports(String typeCode) {
        return "mongodb".equalsIgnoreCase(StrUtil.blankToDefault(typeCode, ""));
    }

    @Override
    public String objectKind(String typeCode) {
        return LhInventoryObjectKinds.COLLECTION;
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
        if (StrUtil.isBlank(str(secret.get("database"))) && StrUtil.isNotBlank(ds.getDatabaseName())) {
            secret.put("database", ds.getDatabaseName());
        }
        String dbName = StrUtil.blankToDefault(first(secret, "database", "db"), "admin");
        String uri = LhDatasourceProtocolProbe.buildMongoUri(secret);
        try (MongoClient client = MongoClients.create(uri)) {
            MongoDatabase db = client.getDatabase(dbName);
            List<LhRemoteInventoryItem> items = new ArrayList<>();
            for (String name : db.listCollectionNames()) {
                if (StrUtil.isBlank(name) || name.startsWith("system.")) {
                    continue;
                }
                LhRemoteInventoryItem m = new LhRemoteInventoryItem();
                m.name = dbName + "." + name;
                m.engine = "mongodb";
                m.encoding = "UTF-8";
                m.comment = "mongo_list";
                items.add(m);
                if (items.size() >= MAX_COLLECTIONS) {
                    break;
                }
            }
            // 表单登记集合合并
            String registered = StrUtil.blankToDefault(ds.getSchemaSummary(), first(secret, "schema"));
            if (StrUtil.isNotBlank(registered)) {
                for (String part : registered.split("[,，;；\\n\\r]+")) {
                    String n = part.trim();
                    if (n.isEmpty()) {
                        continue;
                    }
                    String full = n.contains(".") ? n : dbName + "." + n;
                    if (items.stream().anyMatch(i -> full.equals(i.name))) {
                        continue;
                    }
                    LhRemoteInventoryItem m = new LhRemoteInventoryItem();
                    m.name = full;
                    m.engine = "mongodb";
                    m.comment = "registered";
                    items.add(m);
                }
            }
            return LhInventoryDiscoverResult.remote("mongo_list", LhInventoryObjectKinds.COLLECTION, items);
        } catch (CommonException e) {
            throw e;
        } catch (Exception e) {
            throw new CommonException("MongoDB 清单同步失败: {}", e.getMessage());
        }
    }

    private static String first(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            Object v = m.get(k);
            if (v != null && StrUtil.isNotBlank(String.valueOf(v))) {
                return String.valueOf(v).trim();
            }
        }
        return "";
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }
}
