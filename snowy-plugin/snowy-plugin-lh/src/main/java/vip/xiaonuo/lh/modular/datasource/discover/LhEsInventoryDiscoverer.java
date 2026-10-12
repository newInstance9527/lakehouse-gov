/*
 * Copyright [2022] [https://www.xiaonuo.vip]
 *
 * Snowy采用APACHE LICENSE 2.0开源协议，您在使用过程中，需要注意以下几点：
 *
 * 1.请不要删除和修改根目录下的LICENSE文件。
 * 2.请不要删除和修改Snowy源码头部的版权声明。
 * 3.本项目代码可免费商业使用，商业使用请保留源码和相关描述文件的项目出处，作者声明等。
 * 4.分发源码时候，请注明软件出处 https://www.xiaonuo.vip
 * 5.不可二次分发开源参与同类竞品，如有想法可联系团队xiaonuobase@qq.com商议合作。
 * 6.若您的项目无法满足以上几点，需要更多功能代码，获取Snowy商业授权许可，请在官网购买授权，地址为 https://www.xiaonuo.vip
 */
package vip.xiaonuo.lh.modular.datasource.discover;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.core.vault.LhVaultClient;
import vip.xiaonuo.lh.modular.datasource.entity.LhDatasource;
import vip.xiaonuo.lh.modular.datasource.support.LhEsConnSupport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Elasticsearch 清单发现：多节点择优 GET /_cat/indices?format=json
 */
@Slf4j
@Component
@Order(10)
public class LhEsInventoryDiscoverer implements LhInventoryDiscoverer {

    @Resource
    private LhVaultClient vaultClient;
    @Resource
    private LhProperties lhProperties;

    @Override
    public boolean supports(String typeCode) {
        return "elasticsearch".equalsIgnoreCase(StrUtil.blankToDefault(typeCode, ""));
    }

    @Override
    public String objectKind(String typeCode) {
        return LhInventoryObjectKinds.INDEX;
    }

    @Override
    public LhInventoryDiscoverResult discover(LhDatasource ds) {
        Map<String, Object> secret = new LinkedHashMap<>(vaultClient.readOrEmpty(ds.getVaultPath()));
        if (StrUtil.isBlank(first(secret, "host", "endpoint", "nodes", "hosts"))
                && StrUtil.isNotBlank(ds.getEndpointHost())) {
            secret.putIfAbsent("host", ds.getEndpointHost());
        }
        if (StrUtil.isBlank(first(secret, "port")) && StrUtil.isNotBlank(ds.getEndpointPort())) {
            secret.putIfAbsent("port", ds.getEndpointPort());
        }
        if (StrUtil.isBlank(first(secret, "host", "nodes", "hosts"))) {
            throw new CommonException("Elasticsearch 清单同步需要 host 或 nodes（Vault / 端点）");
        }

        boolean skipSystem = skipSystemIndices(secret);
        boolean insecure = lhProperties.getHttp().isInsecureSsl();

        try {
            return LhEsConnSupport.withFirstHealthy(secret, insecure, base -> {
                HttpResponse resp = LhEsConnSupport.get(
                        base, "/_cat/indices?format=json&h=index,docs.count,store.size", secret, insecure);
                JSONArray arr = JSONUtil.parseArray(resp.body());
                List<LhRemoteInventoryItem> items = new ArrayList<>();
                for (int i = 0; i < arr.size(); i++) {
                    JSONObject row = arr.getJSONObject(i);
                    if (row == null) {
                        continue;
                    }
                    String index = StrUtil.trim(row.getStr("index"));
                    if (StrUtil.isBlank(index)) {
                        continue;
                    }
                    if (skipSystem && index.startsWith(".")) {
                        continue;
                    }
                    LhRemoteInventoryItem m = new LhRemoteInventoryItem();
                    m.name = index;
                    m.engine = "elasticsearch";
                    m.encoding = "UTF-8";
                    String docs = row.getStr("docs.count");
                    String store = row.getStr("store.size");
                    m.comment = "docs=" + StrUtil.blankToDefault(docs, "?")
                            + ", size=" + StrUtil.blankToDefault(store, "?")
                            + "; via=" + base;
                    if (StrUtil.isNotBlank(docs) && StrUtil.isNumeric(docs)) {
                        m.rowCount = Long.parseLong(docs);
                    }
                    items.add(m);
                }
                LhInventoryDiscoverResult r = LhInventoryDiscoverResult.remote(
                        "es_cat", LhInventoryObjectKinds.INDEX, items);
                List<String> bases = LhEsConnSupport.resolveBases(secret);
                if (bases.size() > 1) {
                    r.hint = "多节点共 " + bases.size() + " 个，本次使用 " + base;
                }
                return r;
            });
        } catch (CommonException e) {
            throw e;
        } catch (Exception e) {
            throw new CommonException("Elasticsearch 清单同步失败: {}", e.getMessage());
        }
    }

    private static boolean skipSystemIndices(Map<String, Object> secret) {
        String skip = first(secret, "skipSystemIndices", "skipSystem");
        if (StrUtil.isNotBlank(skip)) {
            String s = skip.toLowerCase(Locale.ROOT);
            return !("false".equals(s) || "0".equals(s) || "no".equals(s));
        }
        String include = first(secret, "includeSystem", "includeSystemIndices");
        if (StrUtil.isNotBlank(include)) {
            String s = include.toLowerCase(Locale.ROOT);
            return !("true".equals(s) || "1".equals(s) || "yes".equals(s) || s.contains("开启"));
        }
        return true;
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
