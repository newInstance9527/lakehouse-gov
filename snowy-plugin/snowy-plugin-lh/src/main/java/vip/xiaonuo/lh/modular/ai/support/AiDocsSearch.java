package vip.xiaonuo.lh.modular.ai.support;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 站内文档只读检索：扫仓库 {@code doc/*.md}（多候选根路径），无文件时回退内置索引。
 */
@Slf4j
@Component
public class AiDocsSearch {

    private static final List<Map<String, String>> BUILTIN = List.of(
            entry("AI助手", "doc/AI助手.md", "/aiassistant", "只读探索智能体、工具白名单、问数 ACL、SSE 对话"),
            entry("工作空间", "doc/工作空间.md", "/workspace", "空间优先、currentWs 软偏好、成员与配额"),
            entry("资产目录", "doc/资产目录.md", "/catalog", "分层资产登记、预览、看见≠能查、Grav/OM"),
            entry("申请中心", "doc/申请中心.md", "/apply", "表读申请、审批链、Grav 投影、授权状态"),
            entry("数据源管理", "doc/数据源管理.md", "/datasource", "异构源登记、连通、表清单、用途"),
            entry("数据质量", "doc/数据质量.md", "/quality", "质量规则、门禁、失败诊断"),
            entry("字段血缘", "doc/字段血缘.md", "/lineage", "上下游影响、OM soft-fail"),
            entry("指标中心", "doc/指标中心.md", "/metrics", "指标口径 SoT、metric_code 编译"),
            entry("ETL编排", "doc/ETL编排.md", "/integration", "DAG、试跑、发布、运行记录"),
            entry("即席查询", "doc/即席查询.md", "/query", "Trino 查询、二次确认、脱敏"),
            entry("数据服务", "doc/数据服务.md", "/dataservice", "API 构建与发布、订阅"),
            entry("命名与工程约束", "doc/命名与工程约束.md", "/aiassistant", "禁止 cursor 命名、watermark 同步进度")
    );

    public Map<String, Object> search(String query, int topK) {
        String q = StrUtil.blankToDefault(query, "").trim().toLowerCase(Locale.ROOT);
        int n = Math.max(1, Math.min(topK, 12));
        List<Map<String, Object>> hits = new ArrayList<>();
        if (StrUtil.isNotBlank(q)) {
            Path docRoot = resolveDocRoot();
            if (docRoot != null) {
                hits.addAll(scanFs(docRoot, q, n));
            }
            if (hits.size() < n) {
                for (Map<String, String> b : BUILTIN) {
                    if (hits.size() >= n) {
                        break;
                    }
                    String blob = (b.get("title") + " " + b.get("path") + " " + b.get("summary")).toLowerCase(Locale.ROOT);
                    if (!blob.contains(q)) {
                        continue;
                    }
                    boolean dup = hits.stream().anyMatch(h -> StrUtil.equals(String.valueOf(h.get("path")), b.get("path")));
                    if (dup) {
                        continue;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("title", b.get("title"));
                    row.put("path", b.get("path"));
                    row.put("href", b.get("href"));
                    row.put("snippet", b.get("summary"));
                    row.put("source", "builtin");
                    hits.add(row);
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("q", query);
        out.put("count", hits.size());
        out.put("items", hits);
        out.put("note", "站内文档检索；非外网。无命中时可到知识库 kb_search");
        return out;
    }

    private List<Map<String, Object>> scanFs(Path docRoot, String q, int n) {
        List<Map<String, Object>> hits = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(docRoot, 2)) {
            stream.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md"))
                    .forEach(p -> {
                        if (hits.size() >= n) {
                            return;
                        }
                        try {
                            String name = p.getFileName().toString();
                            String rel = docRoot.relativize(p).toString().replace('\\', '/');
                            String pathLabel = "doc/" + rel;
                            String text = Files.readString(p, StandardCharsets.UTF_8);
                            String head = StrUtil.maxLength(text.replaceAll("\\s+", " ").trim(), 800);
                            String blob = (name + " " + head).toLowerCase(Locale.ROOT);
                            if (!blob.contains(q)) {
                                return;
                            }
                            int idx = blob.indexOf(q);
                            String snippet = idx >= 0
                                    ? StrUtil.maxLength(head.substring(Math.max(0, idx - 40)), 180)
                                    : StrUtil.maxLength(head, 160);
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("title", name.replace(".md", ""));
                            row.put("path", pathLabel);
                            row.put("href", guessHref(name));
                            row.put("snippet", snippet);
                            row.put("source", "fs");
                            hits.add(row);
                        } catch (Exception e) {
                            log.debug("docs scan skip {}: {}", p, e.toString());
                        }
                    });
        } catch (Exception e) {
            log.debug("docs walk soft-fail: {}", e.toString());
        }
        return hits;
    }

    private static Path resolveDocRoot() {
        String[] candidates = {
                "doc",
                "../doc",
                "../../doc",
                "lakehouse-design/doc",
                System.getProperty("user.dir", "") + "/doc",
                System.getProperty("user.dir", "") + "/../doc"
        };
        for (String c : candidates) {
            if (StrUtil.isBlank(c)) {
                continue;
            }
            Path p = Path.of(c).toAbsolutePath().normalize();
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        return null;
    }

    private static String guessHref(String fileName) {
        String n = StrUtil.blankToDefault(fileName, "").toLowerCase(Locale.ROOT);
        if (n.contains("ai助手") || n.contains("ai")) return "/aiassistant";
        if (n.contains("工作空间")) return "/workspace";
        if (n.contains("资产")) return "/catalog";
        if (n.contains("申请")) return "/apply";
        if (n.contains("数据源")) return "/datasource";
        if (n.contains("质量")) return "/quality";
        if (n.contains("血缘")) return "/lineage";
        if (n.contains("指标")) return "/metrics";
        if (n.contains("etl") || n.contains("编排")) return "/integration";
        if (n.contains("即席") || n.contains("查询")) return "/query";
        if (n.contains("数据服务") || n.contains("api")) return "/dataservice";
        return "/knowledge";
    }

    private static Map<String, String> entry(String title, String path, String href, String summary) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("title", title);
        m.put("path", path);
        m.put("href", href);
        m.put("summary", summary);
        return m;
    }
}
