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

import java.nio.charset.StandardCharsets;
import java.util.*;

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
    public List<Map<String, Object>> fieldsOf(String typeLabelOrCode) {
        String label = resolveTypeLabel(typeLabelOrCode);
        Object arr = root.getByPath("fields." + label);
        if (arr == null) {
            return List.of();
        }
        return JSONUtil.toList(JSONUtil.parseArray(arr), Map.class).stream()
                .map(x -> (Map<String, Object>) x)
                .toList();
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
            if (val == null || StrUtil.isBlank(String.valueOf(val))) {
                throw new CommonException("请填写：{}", f.get("l"));
            }
        }
    }

    /**
     * 展示名 / 别名 → 内部 type 编码
     */
    public String resolveTypeCode(String typeLabelOrCode) {
        if (StrUtil.isBlank(typeLabelOrCode)) {
            throw new CommonException("数据源类型不能为空");
        }
        String label = resolveTypeLabel(typeLabelOrCode);
        String code = typeCode.get(label);
        if (StrUtil.isBlank(code)) {
            // 已是编码
            if (typeCode.containsValue(typeLabelOrCode) || typeCode.containsKey(typeLabelOrCode)) {
                return typeCode.getOrDefault(typeLabelOrCode, typeLabelOrCode);
            }
            throw new CommonException("不支持的数据源类型: {}", typeLabelOrCode);
        }
        return code;
    }

    /**
     * 解析为表单 schema 用的类型展示名
     */
    public String resolveTypeLabel(String typeLabelOrCode) {
        if (StrUtil.isBlank(typeLabelOrCode)) {
            return typeLabelOrCode;
        }
        if (root.getByPath("fields." + typeLabelOrCode) != null) {
            return typeLabelOrCode;
        }
        if (alias.containsKey(typeLabelOrCode)) {
            return alias.get(typeLabelOrCode);
        }
        if (codeToLabel.containsKey(typeLabelOrCode)) {
            String label = codeToLabel.get(typeLabelOrCode);
            // Trino 等编码映射到展示名后，再走 alias（Trino→Hive 表单）
            if (alias.containsKey(label)) {
                return alias.get(label);
            }
            return label;
        }
        if ("pg".equalsIgnoreCase(typeLabelOrCode)) {
            return "PostgreSQL";
        }
        return typeLabelOrCode;
    }

    public String defaultPort(String typeLabelOrCode) {
        String label = resolveTypeLabel(typeLabelOrCode);
        Object p = root.getByPath("meta." + label + ".port");
        return p == null ? "" : String.valueOf(p);
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
