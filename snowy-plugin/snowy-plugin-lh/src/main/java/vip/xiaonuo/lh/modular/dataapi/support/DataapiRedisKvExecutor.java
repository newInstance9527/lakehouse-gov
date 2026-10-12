package vip.xiaonuo.lh.modular.dataapi.support;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.lh.core.vault.LhVaultClient;
import vip.xiaonuo.lh.modular.datasource.entity.LhDatasource;
import vip.xiaonuo.lh.modular.datasource.mapper.LhDatasourceMapper;
import vip.xiaonuo.lh.modular.datasource.support.LhRedisConnSupport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Redis KV 读 API：门户直连（不经 SQLREST）。
 * <p>正文 JSON：{@code op}=get|mget|hgetall|scan}；{@code key}/{@code keys}/{@code pattern}；占位 {@code #{name}}。
 */
@Component
public class DataapiRedisKvExecutor {

    private static final Pattern PLACEHOLDER = Pattern.compile("#\\{([a-zA-Z_][\\w.]*)}");
    private static final int SCAN_MAX = 200;

    @Resource
    private LhDatasourceMapper datasourceMapper;
    @Resource
    private LhVaultClient vaultClient;

    public Map<String, Object> execute(String portalDsId, String script, List<Map<String, Object>> portalParams) {
        if (StrUtil.isBlank(portalDsId)) {
            throw new CommonException("REDIS_KV 需要 portalDsId");
        }
        if (StrUtil.isBlank(script)) {
            throw new CommonException("REDIS_KV 需要 JSON 正文");
        }
        LhDatasource ds = datasourceMapper.selectById(portalDsId);
        if (ds == null || !"NOT_DELETE".equals(ds.getDeleteFlag())) {
            throw new CommonException("数据源不存在: {}", portalDsId);
        }
        if (!"redis".equalsIgnoreCase(StrUtil.blankToDefault(ds.getType(), ""))) {
            throw new CommonException("REDIS_KV 仅支持 Redis 数据源，当前 type={}", ds.getType());
        }

        Map<String, Object> secret = new LinkedHashMap<>(vaultClient.readOrEmpty(ds.getVaultPath()));
        if (StrUtil.isBlank(str(secret.get("host"))) && StrUtil.isNotBlank(ds.getEndpointHost())) {
            secret.putIfAbsent("host", ds.getEndpointHost());
        }
        if (StrUtil.isBlank(str(secret.get("port"))) && StrUtil.isNotBlank(ds.getEndpointPort())) {
            secret.putIfAbsent("port", ds.getEndpointPort());
        }

        Map<String, Object> values = paramValueMap(portalParams);
        String rendered = renderPlaceholders(script.trim(), values);
        ParsedKv parsed = parse(rendered);

        return LhRedisConnSupport.withClient(secret, jedis -> {
            jedis.ping();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("engine", "REDIS_KV");
            out.put("op", parsed.op());
            out.put("mode", String.valueOf(LhRedisConnSupport.resolveMode(secret)));
            List<Map<String, Object>> sample = new ArrayList<>();
            List<Map<String, Object>> types = new ArrayList<>();
            switch (parsed.op()) {
                case "get" -> {
                    String v = jedis.get(parsed.key());
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("key", parsed.key());
                    row.put("value", v);
                    sample.add(row);
                }
                case "mget" -> {
                    List<String> keys = parsed.keys();
                    List<String> vals = jedis.mget(keys.toArray(new String[0]));
                    for (int i = 0; i < keys.size(); i++) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("key", keys.get(i));
                        row.put("value", vals != null && i < vals.size() ? vals.get(i) : null);
                        sample.add(row);
                    }
                }
                case "hgetall" -> {
                    Map<String, String> hash = jedis.hgetAll(parsed.key());
                    if (hash != null) {
                        for (Map.Entry<String, String> e : hash.entrySet()) {
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("key", parsed.key());
                            row.put("field", e.getKey());
                            row.put("value", e.getValue());
                            sample.add(row);
                        }
                    }
                }
                case "scan" -> {
                    ScanParams params = new ScanParams().match(parsed.pattern()).count(Math.min(50, parsed.count()));
                    String mark = ScanParams.SCAN_POINTER_START;
                    int rounds = 0;
                    do {
                        ScanResult<String> res = jedis.scan(mark, params);
                        if (res.getResult() != null) {
                            for (String k : res.getResult()) {
                                if (StrUtil.isBlank(k)) {
                                    continue;
                                }
                                Map<String, Object> row = new LinkedHashMap<>();
                                row.put("key", k);
                                sample.add(row);
                                if (sample.size() >= Math.min(SCAN_MAX, parsed.count())) {
                                    break;
                                }
                            }
                        }
                        mark = res.getCursor();
                        rounds++;
                    } while (!"0".equals(mark) && rounds < 40 && sample.size() < Math.min(SCAN_MAX, parsed.count()));
                }
                default -> throw new CommonException("不支持的 Redis op: {}", parsed.op());
            }
            if (!sample.isEmpty()) {
                for (String col : sample.get(0).keySet()) {
                    Map<String, Object> t = new LinkedHashMap<>();
                    t.put("name", col);
                    t.put("type", "STRING");
                    types.add(t);
                }
            }
            out.put("sample", sample);
            out.put("types", types);
            out.put("sqlPreview", parsed.op() + " " + StrUtil.blankToDefault(parsed.key(), parsed.pattern()));
            return out;
        });
    }

    public static List<String> extractParamNames(String text) {
        LinkedHashMap<String, Boolean> names = new LinkedHashMap<>();
        if (StrUtil.isBlank(text)) {
            return List.of();
        }
        Matcher m = PLACEHOLDER.matcher(text);
        while (m.find()) {
            names.put(m.group(1), Boolean.TRUE);
        }
        return new ArrayList<>(names.keySet());
    }

    public static void validateStructure(String script) {
        parse(script == null ? "" : script.trim());
    }

    private static ParsedKv parse(String text) {
        if (StrUtil.isBlank(text) || !JSONUtil.isTypeJSON(text)) {
            throw new CommonException("REDIS_KV 正文须为 JSON（含 op / key|keys|pattern）");
        }
        JSONObject root = JSONUtil.parseObj(text);
        String op = StrUtil.blankToDefault(root.getStr("op"), root.getStr("command")).trim().toLowerCase(Locale.ROOT);
        if (StrUtil.isBlank(op)) {
            throw new CommonException("REDIS_KV 须指定 op：get / mget / hgetall / scan");
        }
        return switch (op) {
            case "get", "hgetall" -> {
                String key = root.getStr("key");
                if (StrUtil.isBlank(key)) {
                    throw new CommonException("op={} 需要 key", op);
                }
                yield new ParsedKv(op, key.trim(), List.of(), "", 50);
            }
            case "mget" -> {
                List<String> keys = new ArrayList<>();
                Object raw = root.get("keys");
                if (raw instanceof JSONArray arr) {
                    for (int i = 0; i < arr.size(); i++) {
                        if (StrUtil.isNotBlank(arr.getStr(i))) {
                            keys.add(arr.getStr(i).trim());
                        }
                    }
                } else if (StrUtil.isNotBlank(root.getStr("key"))) {
                    for (String p : root.getStr("key").split("[,;\\s]+")) {
                        if (StrUtil.isNotBlank(p)) {
                            keys.add(p.trim());
                        }
                    }
                }
                if (keys.isEmpty()) {
                    throw new CommonException("op=mget 需要 keys 数组或 key 列表");
                }
                yield new ParsedKv(op, "", keys, "", 50);
            }
            case "scan", "keys" -> {
                String pattern = StrUtil.blankToDefault(root.getStr("pattern"), root.getStr("match"));
                if (StrUtil.isBlank(pattern)) {
                    pattern = "*";
                }
                int count = root.getInt("count", 50);
                if (count < 1) {
                    count = 50;
                }
                if (count > SCAN_MAX) {
                    count = SCAN_MAX;
                }
                yield new ParsedKv("scan", "", List.of(), pattern.trim(), count);
            }
            default -> throw new CommonException("不支持的 Redis op: {}（允许 get/mget/hgetall/scan）", op);
        };
    }

    private static String renderPlaceholders(String script, Map<String, Object> values) {
        Matcher m = PLACEHOLDER.matcher(script);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String name = m.group(1);
            if (!values.containsKey(name)) {
                throw new CommonException("缺少入参：{}", name);
            }
            Object v = values.get(name);
            String repl = v == null ? "" : String.valueOf(v);
            // JSON 字符串内替换：做最小转义
            repl = repl.replace("\\", "\\\\").replace("\"", "\\\"");
            m.appendReplacement(sb, Matcher.quoteReplacement(repl));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static Map<String, Object> paramValueMap(List<Map<String, Object>> portalParams) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (portalParams == null) {
            return values;
        }
        for (Map<String, Object> p : portalParams) {
            if (p == null) {
                continue;
            }
            String name = str(p.get("name"));
            if (StrUtil.isBlank(name)) {
                continue;
            }
            Object val = p.containsKey("value") ? p.get("value") : p.get("example");
            values.put(name, val);
        }
        return values;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private record ParsedKv(String op, String key, List<String> keys, String pattern, int count) {
    }
}
