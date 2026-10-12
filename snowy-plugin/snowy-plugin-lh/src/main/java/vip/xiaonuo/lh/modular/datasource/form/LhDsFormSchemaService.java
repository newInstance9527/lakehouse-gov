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
package vip.xiaonuo.lh.modular.datasource.form;

import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.lh.modular.datasource.enums.LhDatasourceTypeEnum;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.Locale;
import java.util.Optional;

/**
 * 数据源按类型动态表单（对齐前端 {@code dsForm.js} / {@code DS_TYPE_FIELDS}）
 *
 * @author lakehouse
 * @date 2026/3/18
 */
@Component
public class LhDsFormSchemaService {

    private JSONObject root;
    private Map<String, String> typeCode = new LinkedHashMap<>();
    private Map<String, String> alias = new LinkedHashMap<>();
    private Map<String, String> codeToLabel = new LinkedHashMap<>();

    @PostConstruct
    public void init() {
        String json = ResourceUtil.readStr("lh/ds-type-fields.json", StandardCharsets.UTF_8);
        root = JSONUtil.parseObj(json);
        JSONObject codes = root.getJSONObject("typeCode");
        if (codes != null) {
            codes.forEach((k, v) -> {
                typeCode.put(k, String.valueOf(v));
                codeToLabel.put(String.valueOf(v), k);
            });
        }
        // 反向：也支持直接传 mysql
        typeCode.putIfAbsent("mysql", "mysql");
        typeCode.putIfAbsent("postgresql", "postgresql");
        typeCode.putIfAbsent("pg", "postgresql");
        JSONObject a = root.getJSONObject("alias");
        if (a != null) {
            a.forEach((k, v) -> alias.put(k, String.valueOf(v)));
        }
        // 枚举补齐：保证 kafka↔Kafka 等编码/展示名双向可解析
        for (LhDatasourceTypeEnum e : LhDatasourceTypeEnum.values()) {
            typeCode.putIfAbsent(e.getLabel(), e.getValue());
            codeToLabel.putIfAbsent(e.getValue(), e.getLabel());
        }
    }

    /**
     * 完整表单 schema（供前端动态渲染）
     */
    public Map<String, Object> fullSchema() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("purposeOptions", root.get("purposeOptions"));
        m.put("types", listTypes());
        m.put("fields", root.get("fields"));
        m.put("alias", root.get("alias"));
        m.put("meta", root.get("meta"));
        m.put("typeCode", root.get("typeCode"));
        return m;
    }

    /**
     * 某类型的字段定义
     *
     * @param typeLabelOrCode 展示名或编码
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> fieldsOf(String typeLabelOrCode) {
        String label = resolveFormFieldsLabel(typeLabelOrCode);
        Object arr = fieldArrayOf(label);
        if (arr == null) {
            arr = fieldArrayOf(typeLabelOrCode);
        }
        if (arr == null) {
            return List.of();
        }
        return JSONUtil.toList(JSONUtil.parseArray(arr), Map.class).stream()
                .map(x -> (Map<String, Object>) x)
                .toList();
    }

    /** fields 以展示名（Kafka）为键；兼容编码 / 大小写 */
    private Object fieldArrayOf(String key) {
        if (StrUtil.isBlank(key) || root == null) {
            return null;
        }
        JSONObject fields = root.getJSONObject("fields");
        if (fields == null) {
            return null;
        }
        Object direct = fields.get(key);
        if (direct != null) {
            return direct;
        }
        for (String k : fields.keySet()) {
            if (k != null && k.equalsIgnoreCase(key)) {
                return fields.get(k);
            }
        }
        return null;
    }

    /**
     * 校验类型必填连接字段
     *
     * @param typeLabelOrCode 类型
     * @param conn            连接参数 Map
     */
    public void validateConn(String typeLabelOrCode, Map<String, Object> conn) {
        List<Map<String, Object>> fields = fieldsOf(typeLabelOrCode);
        if (fields.isEmpty()) {
            throw new CommonException("不支持的数据源类型或缺少表单定义: {}", typeLabelOrCode);
        }
        Map<String, Object> c = conn == null ? Map.of() : conn;
        for (Map<String, Object> f : fields) {
            boolean req = Boolean.TRUE.equals(f.get("req")) || Objects.equals(f.get("req"), 1)
                    || "true".equals(String.valueOf(f.get("req")));
            if (!req) {
                continue;
            }
            String name = String.valueOf(f.get("n"));
            Object val = c.get(name);
            // Kafka / Redis / ES：host 与 bootstrap、nodes 等互为兜底
            if ((val == null || StrUtil.isBlank(String.valueOf(val)))
                    && ("bootstrap".equals(name) || "bootstrapServers".equals(name) || "host".equals(name))) {
                val = firstPresent(c, "bootstrap", "bootstrapServers", "host",
                        "nodes", "hosts", "clusterNodes", "sentinels", "endpoints");
            }
            // Redis Cluster/Sentinel、ES 多节点：nodes 已含 host:port 时可不单独填 port
            if ((val == null || StrUtil.isBlank(String.valueOf(val))) && "port".equals(name)) {
                Object nodes = firstPresent(c, "nodes", "hosts", "clusterNodes", "sentinels", "host", "bootstrap");
                if (nodes != null && String.valueOf(nodes).contains(":")) {
                    val = "from-nodes";
                }
            }
            if (val == null || StrUtil.isBlank(String.valueOf(val))) {
                throw new CommonException("请填写：{}", f.get("l"));
            }
        }
    }

    private static Object firstPresent(Map<String, Object> c, String... keys) {
        for (String k : keys) {
            Object v = c.get(k);
            if (v != null && StrUtil.isNotBlank(String.valueOf(v))) {
                return v;
            }
        }
        return null;
    }

    /**
     * 展示名 / 别名 → 内部 type 编码
     * <p>优先使用 typeCode 显式映射（如 Trino→trino），避免被表单 alias（Trino→Hive 字段）改写成 hive。</p>
     */
    public String resolveTypeCode(String typeLabelOrCode) {
        if (StrUtil.isBlank(typeLabelOrCode)) {
            throw new CommonException("数据源类型不能为空");
        }
        String raw = typeLabelOrCode.trim();
        // 1) 已是内部编码（file 历史码归一为 ftp）
        if (typeCode.containsValue(raw) || codeToLabel.containsKey(raw)
                || LhDatasourceTypeEnum.of(raw).isPresent()) {
            String code = raw.toLowerCase(Locale.ROOT);
            if ("pg".equals(code)) {
                return "postgresql";
            }
            if ("file".equals(code)) {
                return "ftp";
            }
            return code;
        }
        if ("pg".equalsIgnoreCase(raw)) {
            return "postgresql";
        }
        // 2) 展示名在 typeCode 中有独立编码（即便表单字段 alias 到其它类型）
        //    例：StarRocks→starrocks、Tableau→tableau，禁止被 alias 改成 doris/http_api
        if (typeCode.containsKey(raw)) {
            return normalizePersistedCode(typeCode.get(raw));
        }
        for (Map.Entry<String, String> e : typeCode.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(raw)) {
                return normalizePersistedCode(e.getValue());
            }
        }
        // 3) 纯别名类型（如 MariaDB→MySQL 表单）落到目标编码
        if (alias.containsKey(raw)) {
            String formLabel = alias.get(raw);
            if (typeCode.containsKey(formLabel)) {
                return normalizePersistedCode(typeCode.get(formLabel));
            }
        }
        String identity = resolveTypeLabel(raw);
        String code = typeCode.get(identity);
        if (StrUtil.isNotBlank(code)) {
            return normalizePersistedCode(code);
        }
        String fieldsLabel = resolveFormFieldsLabel(raw);
        code = typeCode.get(fieldsLabel);
        if (StrUtil.isNotBlank(code) && !hasOwnTypeCode(identity) && !hasOwnTypeCode(raw)) {
            // 仅当身份本身无独立 typeCode 时（MariaDB），才用表单借用目标码
            return normalizePersistedCode(code);
        }
        // 4) 兜底：枚举 label / value
        Optional<LhDatasourceTypeEnum> byEnum = LhDatasourceTypeEnum.of(raw);
        if (byEnum.isPresent()) {
            return normalizePersistedCode(byEnum.get().getValue());
        }
        for (LhDatasourceTypeEnum e : LhDatasourceTypeEnum.values()) {
            if (e.getLabel().equalsIgnoreCase(raw) || e.getLabel().equalsIgnoreCase(identity)) {
                return normalizePersistedCode(e.getValue());
            }
        }
        throw new CommonException("不支持的数据源类型: {}", typeLabelOrCode);
    }

    private static String normalizePersistedCode(String code) {
        String c = StrUtil.blankToDefault(code, "").toLowerCase(Locale.ROOT);
        if ("pg".equals(c)) {
            return "postgresql";
        }
        if ("file".equals(c)) {
            return "ftp";
        }
        return c;
    }

    /** 展示名是否在 typeCode 映射中有独立编码（与表单 alias 目标区分） */
    private boolean hasOwnTypeCode(String labelOrCode) {
        if (StrUtil.isBlank(labelOrCode)) {
            return false;
        }
        if (typeCode.containsKey(labelOrCode)) {
            return true;
        }
        for (String k : typeCode.keySet()) {
            if (k != null && k.equalsIgnoreCase(labelOrCode)) {
                return true;
            }
        }
        return codeToLabel.containsKey(labelOrCode.toLowerCase(Locale.ROOT));
    }

    /**
     * 门户 / VO 用的类型身份展示名（禁止把 StarRocks/Tableau 等折叠成 Doris/HTTP API）。
     */
    public String resolveTypeLabel(String typeLabelOrCode) {
        if (StrUtil.isBlank(typeLabelOrCode)) {
            return typeLabelOrCode;
        }
        String raw = typeLabelOrCode.trim();
        if ("pg".equalsIgnoreCase(raw)) {
            return "PostgreSQL";
        }
        if ("file".equalsIgnoreCase(raw)) {
            return "FTP/SFTP";
        }
        // 编码 → 独立展示名（tableau → Tableau）
        if (codeToLabel.containsKey(raw)) {
            return codeToLabel.get(raw);
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        if (codeToLabel.containsKey(lower)) {
            return codeToLabel.get(lower);
        }
        Optional<LhDatasourceTypeEnum> byCode = LhDatasourceTypeEnum.of(raw);
        if (byCode.isPresent()) {
            return byCode.get().getLabel();
        }
        // 已是展示名 / 兼容大小写
        if (typeCode.containsKey(raw)) {
            return raw;
        }
        for (String k : typeCode.keySet()) {
            if (k != null && k.equalsIgnoreCase(raw)) {
                return k;
            }
        }
        for (LhDatasourceTypeEnum e : LhDatasourceTypeEnum.values()) {
            if (e.getLabel().equalsIgnoreCase(raw)) {
                return e.getLabel();
            }
        }
        if (fieldArrayOf(raw) != null) {
            JSONObject fields = root.getJSONObject("fields");
            for (String k : fields.keySet()) {
                if (k != null && k.equalsIgnoreCase(raw)) {
                    return k;
                }
            }
        }
        return raw;
    }

    /**
     * 表单字段 schema 键（可走 alias：Tableau→HTTP API、StarRocks→Doris）。
     */
    public String resolveFormFieldsLabel(String typeLabelOrCode) {
        if (StrUtil.isBlank(typeLabelOrCode)) {
            return typeLabelOrCode;
        }
        String raw = typeLabelOrCode.trim();
        String identity = resolveTypeLabel(raw);
        if (fieldArrayOf(identity) != null) {
            return identity;
        }
        if (alias.containsKey(identity)) {
            return alias.get(identity);
        }
        if (alias.containsKey(raw)) {
            return alias.get(raw);
        }
        if (fieldArrayOf(raw) != null) {
            JSONObject fields = root.getJSONObject("fields");
            for (String k : fields.keySet()) {
                if (k != null && k.equalsIgnoreCase(raw)) {
                    return k;
                }
            }
            return raw;
        }
        return identity;
    }

    public String defaultPort(String typeLabelOrCode) {
        String label = resolveFormFieldsLabel(typeLabelOrCode);
        JSONObject meta = root.getJSONObject("meta");
        if (meta != null) {
            Object byLabel = meta.getByPath(label + ".port");
            if (byLabel != null) {
                return String.valueOf(byLabel);
            }
            for (String k : meta.keySet()) {
                if (k != null && k.equalsIgnoreCase(label)) {
                    Object p = meta.getByPath(k + ".port");
                    if (p != null) {
                        return String.valueOf(p);
                    }
                }
            }
        }
        return "";
    }

    private List<Map<String, Object>> listTypes() {
        List<Map<String, Object>> list = new ArrayList<>();
        LinkedHashSet<String> labels = new LinkedHashSet<>();
        JSONObject fields = root.getJSONObject("fields");
        if (fields != null) {
            labels.addAll(fields.keySet());
        }
        // 独立 typeCode 的展示名（StarRocks/Tableau/…），即使 fields 借用别名也要出现在注册下拉
        for (String label : typeCode.keySet()) {
            if (StrUtil.isBlank(label)) {
                continue;
            }
            // 跳过纯编码键（mysql 等）
            if (label.equals(label.toLowerCase(Locale.ROOT)) && codeToLabel.containsKey(label)) {
                continue;
            }
            labels.add(label);
        }
        for (LhDatasourceTypeEnum e : LhDatasourceTypeEnum.values()) {
            if ("pg".equals(e.getValue()) || "file".equals(e.getValue())) {
                continue; // 归一到 postgresql / ftp，不单独暴露
            }
            labels.add(e.getLabel());
        }
        for (String label : labels) {
            String code = typeCode.containsKey(label)
                    ? typeCode.get(label)
                    : resolveTypeCode(label);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("label", label);
            m.put("value", label);
            m.put("code", code);
            m.put("port", defaultPort(label));
            list.add(m);
        }
        return list;
    }
}
