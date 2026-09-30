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

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.modular.datasource.entity.LhDatasource;
import vip.xiaonuo.lh.modular.datasource.entity.LhDsTable;
import vip.xiaonuo.lh.modular.datasource.enums.LhDatasourceTypeEnum;
import vip.xiaonuo.lh.modular.datasource.param.LhDatasourceAddParam;
import vip.xiaonuo.lh.modular.datasource.result.LhDatasourceVo;
import vip.xiaonuo.lh.modular.datasource.result.LhDsTableVo;

import jakarta.annotation.Resource;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * 实体 ↔ 前端视图组装
 *
 * @author lakehouse
 * @date 2026/3/18
 */
@Component
public class LhDatasourceViewAssembler {

    private static final Map<String, String[]> TYPE_VISUAL = Map.ofEntries(
            // icon 字段留空：前端用官方 SVG（DsTypeIcon）；此处仅维护 bg / color
            Map.entry("mysql", new String[]{"", "#e6f7ff", "#08979c"}),
            Map.entry("postgresql", new String[]{"", "#e6f7ff", "#08979c"}),
            Map.entry("pg", new String[]{"", "#e6f7ff", "#08979c"}),
            Map.entry("oracle", new String[]{"", "#fff2e8", "#fa541c"}),
            Map.entry("sqlserver", new String[]{"", "#e6f7ff", "#096dd9"}),
            Map.entry("clickhouse", new String[]{"", "#fffbe6", "#d48806"}),
            Map.entry("doris", new String[]{"", "#fff7e6", "#d46b08"}),
            Map.entry("hive", new String[]{"", "#fff7e6", "#d48806"}),
            Map.entry("iceberg", new String[]{"", "#e6f4ff", "#1677ff"}),
            Map.entry("hbase", new String[]{"", "#f6ffed", "#389e0d"}),
            Map.entry("kafka", new String[]{"", "#f9f0ff", "#722ed1"}),
            Map.entry("rabbitmq", new String[]{"", "#f9f0ff", "#722ed1"}),
            Map.entry("pulsar", new String[]{"", "#f9f0ff", "#722ed1"}),
            Map.entry("mongodb", new String[]{"", "#f6ffed", "#52c41a"}),
            Map.entry("redis", new String[]{"", "#fff1f0", "#cf1322"}),
            Map.entry("elasticsearch", new String[]{"", "#fff1f0", "#f5222d"}),
            Map.entry("hdfs", new String[]{"", "#fafafa", "#595959"}),
            Map.entry("s3", new String[]{"", "#e6f4ff", "#1677ff"}),
            Map.entry("file", new String[]{"", "#fafafa", "#595959"}),
            Map.entry("ftp", new String[]{"", "#fafafa", "#595959"}),
            Map.entry("http_api", new String[]{"", "#f9f0ff", "#722ed1"}),
            Map.entry("trino", new String[]{"", "#e6f4ff", "#1677ff"}),
            Map.entry("tableau", new String[]{"", "#f6ffed", "#389e0d"}),
            Map.entry("superset", new String[]{"", "#e6fffb", "#13c2c2"}),
            Map.entry("airflow", new String[]{"", "#e6fffb", "#13c2c2"})
    );

    @Resource
    private LhDsFormSchemaService formSchemaService;

    public LhDatasourceVo toVo(LhDatasource e) {
        if (e == null) {
            return null;
        }
        LhDatasourceVo vo = new LhDatasourceVo();
        vo.setId(e.getId());
        String typeCode = e.getType();
        String label = formSchemaService.resolveTypeLabel(typeCode);
        // 展示名优先用表单 label；枚举兜底
        if (StrUtil.isBlank(label) || label.equals(typeCode)) {
            label = LhDatasourceTypeEnum.of(typeCode).map(LhDatasourceTypeEnum::getLabel).orElse(typeCode);
        }
        vo.setType(label);
        vo.setTypeCode(typeCode);
        vo.setCategory(e.getCategory());
        String[] vis = TYPE_VISUAL.getOrDefault(typeCode, new String[]{"", "#e8f0ff", "#1e6fff"});
        vo.setIcon(vis[0]);
        vo.setBg(vis[1]);
        vo.setColor(vis[2]);
        vo.setName(e.getName());
        vo.setDsCode(e.getDsCode());
        vo.setHost(e.getEndpointHost());
        vo.setPort(e.getEndpointPort());
        vo.setDatabase(e.getDatabaseName());
        vo.setSchema(e.getSchemaSummary());
        vo.setLag(e.getLagDesc());
        vo.setAccess(e.getAccessMode());
        vo.setStatus(e.getStatus());
        vo.setHealth(e.getHealthScore());
        vo.setAsset(e.getAssetName());
        vo.setOwner(e.getOwner());
        vo.setCreateUser(e.getCreateUser());
        vo.setVer(e.getVer());
        vo.setDesc(e.getRemark());
        vo.setRevision(e.getRevision());
        vo.setPurposes(e.getPurposes());
        vo.setPurpose(purposeLabel(e.getPurposes()));
        if (e.getCreateTime() != null) {
            vo.setCreated(new SimpleDateFormat("yyyy-MM-dd").format(e.getCreateTime()));
        }
        Map<String, Object> conn = parseConn(e.getConnMasked());
        vo.setConn(conn);
        vo.setUser(str(conn.get("user"), conn.get("username"), ""));
        vo.setPassword(StrUtil.isNotBlank(str(conn.get("password"), null, "")) ? "******" : "");
        // extra 仅来自 conn；禁止回落到 accessMode（否则 JDBC 高级参数被填成「接入方式」）
        String extra = str(conn.get("extra"), null, "");
        if (StrUtil.isNotBlank(extra) && extra.equals(StrUtil.blankToDefault(e.getAccessMode(), ""))) {
            // 历史脏数据：曾把接入方式误写入 extra
            extra = "";
        }
        vo.setExtra(extra);
        // 用 conn 补齐 host/port/database；HTTP API 优先从 baseURL 解析
        if (StrUtil.isBlank(vo.getHost()) || StrUtil.isBlank(vo.getPort())) {
            LhDatasourceConnNormalizer.HttpEndpoint http = LhDatasourceConnNormalizer.parseHttpEndpoint(
                    str(conn.get("baseURL"), conn.get("httpUrl"), ""));
            if (http != null) {
                if (StrUtil.isBlank(vo.getHost())) {
                    vo.setHost(http.host);
                }
                if (StrUtil.isBlank(vo.getPort())) {
                    vo.setPort(http.port);
                }
            }
        }
        if (StrUtil.isBlank(vo.getHost())) {
            vo.setHost(str(conn.get("host"), conn.get("bootstrap"), conn.get("endpoint")));
        }
        if (StrUtil.isBlank(vo.getPort())) {
            vo.setPort(str(conn.get("port"), null, ""));
        }
        if (StrUtil.isBlank(vo.getDatabase())) {
            vo.setDatabase(str(conn.get("database"), conn.get("sid"), conn.get("namespace")));
        }
        if (StrUtil.isBlank(vo.getAccess())) {
            vo.setAccess(str(conn.get("access"), conn.get("pollCycle"), ""));
        }
        if (StrUtil.isBlank(vo.getSchema())) {
            vo.setSchema(str(conn.get("schema"), conn.get("topics"), conn.get("queues")));
        }
        return vo;
    }

    public LhDsTableVo toTableVo(LhDsTable t) {
        LhDsTableVo vo = new LhDsTableVo();
        vo.setId(t.getId());
        vo.setDsId(t.getDsId());
        vo.setName(t.getTableName());
        vo.setCnName(t.getCnName());
        vo.setComment(t.getCommentTxt());
        vo.setEncoding(t.getEncoding());
        vo.setEngine(t.getEngine());
        vo.setRowCount(t.getRowCount());
        vo.setStatus(t.getStatus());
        if (t.getSyncedAt() != null) {
            vo.setSyncedAt(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(t.getSyncedAt()));
        }
        return vo;
    }

    /**
     * 把前端扁平字段并入 conn，供归一化使用
     */
    public void enrichParamFromFe(LhDatasourceAddParam p) {
        Map<String, Object> conn = p.getConn() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(p.getConn());
        put(conn, "host", p.getHost());
        put(conn, "port", p.getPort());
        put(conn, "database", p.getDatabase());
        put(conn, "user", p.getUser());
        put(conn, "password", p.getPassword());
        put(conn, "extra", p.getExtra());
        put(conn, "schema", p.getSchema());
        put(conn, "access", p.getAccess());
        put(conn, "bootstrap", first(p.getBootstrap(), p.getBootstrapServers()));
        put(conn, "bootstrapServers", first(p.getBootstrapServers(), p.getBootstrap()));
        put(conn, "topics", p.getTopics());
        put(conn, "queues", p.getQueues());
        put(conn, "endpoint", p.getEndpoint());
        put(conn, "nameNode", p.getNameNode());
        put(conn, "serviceUrl", p.getServiceUrl());
        put(conn, "zkQuorum", p.getZkQuorum());
        put(conn, "baseURL", p.getBaseURL());
        put(conn, "httpUrl", p.getBaseURL());
        put(conn, "bucket", p.getBucket());
        put(conn, "accessKey", p.getAccessKey());
        put(conn, "secretKey", p.getSecretKey());
        put(conn, "path", p.getPath());
        put(conn, "token", p.getToken());
        put(conn, "sid", p.getSid());
        put(conn, "namespace", p.getNamespace());
        put(conn, "vhost", p.getVhost());
        put(conn, "tenant", p.getTenant());
        put(conn, "db", p.getDb());
        put(conn, "warehouse", p.getWarehouse());
        put(conn, "feNodes", p.getFeNodes());
        put(conn, "pollCycle", p.getPollCycle());
        put(conn, "jdbcUrl", p.getJdbcUrl());
        // lag 仅作展示文案，勿回写进 access（避免与接入方式/JDBC 参数串味）
        p.setConn(conn);
    }

    private static String purposeLabel(String purposesJson) {
        if (StrUtil.isBlank(purposesJson)) {
            return "数据入湖";
        }
        if (purposesJson.contains("meta_collect")) {
            return "仅元数据采集(OM)";
        }
        if (purposesJson.contains("export")) {
            return "出湖目标";
        }
        return "数据入湖";
    }

    private static Map<String, Object> parseConn(String json) {
        if (StrUtil.isBlank(json)) {
            return new LinkedHashMap<>();
        }
        try {
            JSONObject o = JSONUtil.parseObj(json);
            Map<String, Object> m = new LinkedHashMap<>();
            o.forEach(m::put);
            return m;
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private static void put(Map<String, Object> m, String k, Object v) {
        if (v == null) {
            return;
        }
        if (v instanceof String s && StrUtil.isBlank(s)) {
            return;
        }
        m.putIfAbsent(k, v);
    }

    private static String first(String a, String b) {
        return StrUtil.isNotBlank(a) ? a : b;
    }

    private static String str(Object a, Object b, Object def) {
        if (a != null && StrUtil.isNotBlank(String.valueOf(a))) {
            return String.valueOf(a);
        }
        if (b != null && StrUtil.isNotBlank(String.valueOf(b))) {
            return String.valueOf(b);
        }
        if (def != null && StrUtil.isNotBlank(String.valueOf(def))) {
            return String.valueOf(def);
        }
        return "";
    }

    private static String str(Object a, Object b) {
        return str(a, b, null);
    }
}
