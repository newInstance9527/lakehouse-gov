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
        String label = resolveTypeLabel(typeLabelOrCode);
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
            // Kafka 等：bootstrap / bootstrapServers / host 任一即可
            if ((val == null || StrUtil.isBlank(String.valueOf(val)))
                    && ("bootstrap".equals(name) || "bootstrapServers".equals(name) || "host".equals(name))) {
                val = firstPresent(c, "bootstrap", "bootstrapServers", "host");
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
        // 1) 已是内部编码
        if (typeCode.containsValue(raw) || codeToLabel.containsKey(raw)) {
            return "pg".equalsIgnoreCase(raw) ? "postgresql" : raw.toLowerCase(Locale.ROOT);
        }
        if ("pg".equalsIgnoreCase(raw)) {
            return "postgresql";
        }
        // 2) 展示名在 typeCode 中有独立编码（即便表单字段 alias 到其它类型）
        if (typeCode.containsKey(raw)) {
            return typeCode.get(raw);
        }
        for (Map.Entry<String, String> e : typeCode.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(raw)) {
                return e.getValue();
            }
        }
        // 3) 纯别名类型（如 MariaDB→MySQL 表单）落到目标编码
        if (alias.containsKey(raw)) {
            String formLabel = alias.get(raw);
            if (typeCode.containsKey(formLabel)) {
                return typeCode.get(formLabel);
            }
        }
        String label = resolveTypeLabel(raw);
        String code = typeCode.get(label);
        if (StrUtil.isNotBlank(code)) {
            return code;
        }
        // 4) 兜底：枚举 label / value（避免 json 缺 typeCode 时 Kafka 等展示名解析失败）
        Optional<LhDatasourceTypeEnum> byEnum = LhDatasourceTypeEnum.of(raw);
        if (byEnum.isPresent()) {
            String v = byEnum.get().getValue();
            return "pg".equalsIgnoreCase(v) ? "postgresql" : v;
        }
        for (LhDatasourceTypeEnum e : LhDatasourceTypeEnum.values()) {
            if (e.getLabel().equalsIgnoreCase(raw) || e.getLabel().equalsIgnoreCase(label)) {
                String v = e.getValue();
                return "pg".equalsIgnoreCase(v) ? "postgresql" : v;
            }
        }
        throw new CommonException("不支持的数据源类型: {}", typeLabelOrCode);
    }

    /**
     * 解析为表单 schema 用的类型展示名（字段定义；可走 alias）
     */
    public String resolveTypeLabel(String typeLabelOrCode) {
        if (StrUtil.isBlank(typeLabelOrCode)) {
            return typeLabelOrCode;
        }
        String raw = typeLabelOrCode.trim();
        if (fieldArrayOf(raw) != null) {
            // 返回 fields 中的真实键名
            JSONObject fields = root.getJSONObject("fields");
            for (String k : fields.keySet()) {
                if (k.equalsIgnoreCase(raw)) {
                    return k;
                }
            }
            return raw;
        }
        if (alias.containsKey(raw)) {
            return alias.get(raw);
        }
        // 编码 → 展示名（kafka → Kafka）
        if (codeToLabel.containsKey(raw)) {
            String label = codeToLabel.get(raw);
            if (alias.containsKey(label) && fieldArrayOf(label) == null) {
                return alias.get(label);
            }
            if (fieldArrayOf(label) != null) {
                return label;
            }
            if (alias.containsKey(label)) {
                return alias.get(label);
            }
            return label;
        }
        // 枚举兜底
        Optional<LhDatasourceTypeEnum> byCode = LhDatasourceTypeEnum.of(raw);
        if (byCode.isPresent()) {
            String label = byCode.get().getLabel();
            if (alias.containsKey(label) && fieldArrayOf(label) == null) {
                return alias.get(label);
            }
            return label;
        }
        for (LhDatasourceTypeEnum e : LhDatasourceTypeEnum.values()) {
            if (e.getLabel().equalsIgnoreCase(raw)) {
                return e.getLabel();
            }
        }
        if ("pg".equalsIgnoreCase(raw)) {
            return "PostgreSQL";
        }
        return raw;
    }

    public String defaultPort(String typeLabelOrCode) {
        String label = resolveTypeLabel(typeLabelOrCode);
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
        JSONObject fields = root.getJSONObject("fields");
        if (fields == null) {
            return list;
        }
        for (String label : fields.keySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("label", label);
            m.put("value", label);
            m.put("code", typeCode.getOrDefault(label, label));
            m.put("port", defaultPort(label));
            list.add(m);
        }
        return list;
    }
}
