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
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.modular.datasource.entity.LhDatasource;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 手工摘要清单：无专用 Discoverer 的类型从 schemaSummary 回填
 * <p>ES / Redis / RabbitMQ / MinIO 由 Order(10) 真实发现器优先；本类 Order(100) 作其余类型兜底。</p>
 * <p>从 schemaSummary 解析对象名写入既有 LhDsTable（ig_ds_table）；path=manual，fromRemote=false。</p>
 *
 * @author lakehouse
 * @date 2026/3/18
 */
@Component
@Order(100)
public class LhManualSummaryInventoryDiscoverer implements LhInventoryDiscoverer {

    /** 明确走手工摘要的类型（无专用 Discoverer 或自动扫描不适宜） */
    private static final Set<String> MANUAL_TYPES = Set.of(
            "mongodb",
            "hdfs",
            "file",
            "ftp",
            "http_api",
            "pulsar",
            "hbase",
            "iceberg",
            "tableau",
            "superset",
            "airflow"
    );

    @Override
    public boolean supports(String typeCode) {
        return MANUAL_TYPES.contains(normalize(typeCode));
    }

    @Override
    public String objectKind(String typeCode) {
        return LhInventoryObjectKinds.ofType(typeCode);
    }

    @Override
    public LhInventoryDiscoverResult discover(LhDatasource ds) {
        String type = normalize(ds.getType());
        String kind = objectKind(type);
        String kindLabel = LhInventoryObjectKinds.labelOf(kind);
        List<LhRemoteInventoryItem> items = parseSummary(ds.getSchemaSummary()).stream()
                .map(n -> {
                    LhRemoteInventoryItem m = LhRemoteInventoryItem.of(n);
                    m.comment = "manual:" + kind;
                    m.engine = type;
                    return m;
                })
                .collect(Collectors.toList());
        return LhInventoryDiscoverResult.manual(kind, hintOf(type, kindLabel), items);
    }

    public static String hintOf(String type, String kindLabel) {
        return switch (type) {
            case "elasticsearch" ->
                    "Elasticsearch 请先保存数据源后同步（path=es_cat，/_cat/indices）；"
                            + "未登记时可在表单维护 Index 清单作兜底。";
            case "redis" ->
                    "Redis 请先保存后同步（登记前缀→redis_prefix；否则 SCAN 限流 redis_scan，禁止 KEYS *）；"
                            + "失败时请检查 host/port/password 或维护 Key 前缀清单。";
            case "rabbitmq" ->
                    "RabbitMQ 请先保存后同步（path=rabbitmq_mgmt，Management /api/queues，默认端口 15672）。";
            case "s3", "minio" ->
                    "S3/MinIO 请先保存后同步（path=minio_s3，ListBuckets；已填 bucket 时可列顶层前缀）。";
            case "mongodb" ->
                    "MongoDB 暂不自动拉取集合清单，请维护「集合清单」后同步回填。";
            case "hdfs", "file", "ftp" ->
                    "文件/路径类源请维护「路径清单」后同步回填；不做全量目录扫描。";
            case "http_api" ->
                    "HTTP API 请维护「接口清单」后同步回填。";
            case "pulsar" ->
                    "Pulsar 暂不自动拉取 Topic，请维护 Topic 清单后同步回填。";
            default ->
                    "当前类型不支持自动发现" + kindLabel + "，请维护注册表单清单摘要后同步回填。";
        };
    }

    private static List<String> parseSummary(String summary) {
        if (StrUtil.isBlank(summary)) {
            return Collections.emptyList();
        }
        return Arrays.stream(summary.split("[,;/\\n]+"))
                .map(String::trim)
                .filter(StrUtil::isNotBlank)
                .distinct()
                .collect(Collectors.toList());
    }

    private static String normalize(String typeCode) {
        return StrUtil.blankToDefault(typeCode, "").toLowerCase(Locale.ROOT);
    }
}
