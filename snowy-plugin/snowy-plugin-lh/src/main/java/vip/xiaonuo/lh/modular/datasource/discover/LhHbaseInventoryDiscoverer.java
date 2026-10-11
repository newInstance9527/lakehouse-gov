package vip.xiaonuo.lh.modular.datasource.discover;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
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
 * HBase：优先 Stargate REST 列表示；否则 ZK 探测成功后回落手工 namespace/表清单。
 */
@Slf4j
@Component
@Order(10)
public class LhHbaseInventoryDiscoverer implements LhInventoryDiscoverer {

    @Resource
    private LhVaultClient vaultClient;

    @Override
    public boolean supports(String typeCode) {
        return "hbase".equalsIgnoreCase(StrUtil.blankToDefault(typeCode, ""));
    }

    @Override
    public String objectKind(String typeCode) {
        return LhInventoryObjectKinds.TABLE;
    }

    @Override
    public LhInventoryDiscoverResult discover(LhDatasource ds) {
        Map<String, Object> secret = vaultClient.readOrEmpty(ds.getVaultPath());
        try {
            LhDatasourceProtocolProbe.probeHbase(secret);
        } catch (CommonException e) {
            throw e;
        } catch (Exception e) {
            throw new CommonException("HBase ZK 探测失败: {}", e.getMessage());
        }

        String rest = first(secret, "restUrl", "restEndpoint", "stargate");
        String ns = StrUtil.blankToDefault(first(secret, "namespace"), "default");
        if (StrUtil.isNotBlank(rest)) {
            try {
                List<LhRemoteInventoryItem> items = listViaRest(rest, ns);
                if (!items.isEmpty()) {
                    return LhInventoryDiscoverResult.remote("hbase_rest", LhInventoryObjectKinds.TABLE, items);
                }
            } catch (Exception e) {
                log.warn("HBase REST list soft-fail: {}", e.getMessage());
            }
        }

        // 手工清单 / schemaSummary
        List<LhRemoteInventoryItem> manual = new ArrayList<>();
        String summary = StrUtil.blankToDefault(ds.getSchemaSummary(), first(secret, "schema"));
        if (StrUtil.isNotBlank(summary)) {
            for (String part : summary.split("[,，;；\\n\\r]+")) {
                String n = part.trim();
                if (n.isEmpty()) {
                    continue;
                }
                LhRemoteInventoryItem m = new LhRemoteInventoryItem();
                m.name = n.contains(":") ? n : ns + ":" + n;
                m.engine = "hbase";
                m.comment = "manual+zk_ok";
                manual.add(m);
            }
        }
        return LhInventoryDiscoverResult.manual(
                LhInventoryObjectKinds.TABLE,
                "HBase ZK 可达；未配置 restUrl 时请维护表清单（或填写 Stargate restUrl 自动列举）",
                manual);
    }

    private static List<LhRemoteInventoryItem> listViaRest(String rest, String namespace) throws Exception {
        String base = rest.startsWith("http") ? rest : "http://" + rest;
        if (!base.endsWith("/")) {
            base = base + "/";
        }
        String url = base + namespace + "/";
        HttpResponse resp = HttpRequest.get(url)
                .header("Accept", "application/json")
                .timeout(12000)
                .execute();
        if (!resp.isOk()) {
            throw new CommonException("HBase REST {} HTTP {}", url, resp.getStatus());
        }
        List<LhRemoteInventoryItem> items = new ArrayList<>();
        String body = resp.body();
        if (StrUtil.isBlank(body)) {
            return items;
        }
        // Stargate 可能返回 JSON 数组或 {"table":[...]}
        if (body.trim().startsWith("[")) {
            JSONArray arr = JSONUtil.parseArray(body);
            for (int i = 0; i < arr.size(); i++) {
                Object o = arr.get(i);
                String name = o instanceof JSONObject jo
                        ? StrUtil.blankToDefault(jo.getStr("name"), jo.getStr("tableName"))
                        : String.valueOf(o);
                addTable(items, namespace, name);
            }
        } else {
            JSONObject obj = JSONUtil.parseObj(body);
            Object table = obj.get("table");
            if (table instanceof JSONArray arr) {
                for (int i = 0; i < arr.size(); i++) {
                    Object o = arr.get(i);
                    String name = o instanceof JSONObject jo
                            ? StrUtil.blankToDefault(jo.getStr("name"), jo.getStr("tableName"))
                            : String.valueOf(o);
                    addTable(items, namespace, name);
                }
            }
        }
        return items;
    }

    private static void addTable(List<LhRemoteInventoryItem> items, String ns, String name) {
        if (StrUtil.isBlank(name)) {
            return;
        }
        String n = name.contains(":") ? name : ns + ":" + name;
        LhRemoteInventoryItem m = new LhRemoteInventoryItem();
        m.name = n;
        m.engine = "hbase";
        m.comment = "hbase_rest";
        items.add(m);
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
}
