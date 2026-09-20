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
package vip.xiaonuo.lh.modular.catalog.preview;

import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.core.vault.LhVaultClient;
import vip.xiaonuo.lh.modular.datasource.entity.LhDatasource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/**
 * Kafka Topic：短时消费最近消息样例（非 Trino）。
 */
@Slf4j
@Component
@Order(20)
public class KafkaPreviewAdapter implements GovAssetPreviewAdapter {

    @Resource
    private LhVaultClient vaultClient;

    @Override
    public int order() {
        return 20;
    }

    @Override
    public boolean supports(GovAssetPreviewContext ctx) {
        return "kafka".equalsIgnoreCase(PreviewAdapterSupport.dsType(ctx.getPrimaryDs()))
                && StrUtil.isNotBlank(ctx.getObjectName());
    }

    @Override
    public Map<String, Object> preview(GovAssetPreviewContext ctx) {
        LhDatasource ds = ctx.getPrimaryDs();
        String topic = PreviewAdapterSupport.shortName(ctx.getObjectName());
        int limit = ctx.getLimit();
        Map<String, Object> r = ctx.newResult();
        r.put("qualifiedName", topic);
        try {
            Map<String, Object> secret = vaultClient.readOrEmpty(ds.getVaultPath());
            String bootstrap = PreviewAdapterSupport.firstNonBlank(
                    PreviewAdapterSupport.str(secret.get("bootstrap")),
                    PreviewAdapterSupport.str(secret.get("bootstrapServers")),
                    PreviewAdapterSupport.str(secret.get("host")),
                    ds.getEndpointHost());
            String port = PreviewAdapterSupport.firstNonBlank(
                    PreviewAdapterSupport.str(secret.get("port")), ds.getEndpointPort());
            if (StrUtil.isBlank(bootstrap)) {
                return PreviewAdapterSupport.emptyFail(r, "kafka", "Kafka 无 bootstrap，无法预览");
            }
            if (StrUtil.isNotBlank(port) && !bootstrap.contains(":")) {
                bootstrap = bootstrap + ":" + port;
            }
            Properties props = new Properties();
            props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
            props.put(ConsumerConfig.GROUP_ID_CONFIG, "lh-catalog-preview-" + UUID.randomUUID());
            props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
            props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
            props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
            props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, String.valueOf(Math.min(limit, 50)));
            props.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, "8000");
            props.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "10000");

            List<String> columns = List.of("partition", "offset", "timestamp", "key", "value");
            List<Map<String, Object>> rows = new ArrayList<>();
            try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
                consumer.subscribe(List.of(topic));
                long deadline = System.currentTimeMillis() + 5000L;
                while (rows.size() < limit && System.currentTimeMillis() < deadline) {
                    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(800));
                    if (records.isEmpty()) {
                        continue;
                    }
                    for (ConsumerRecord<String, String> rec : records) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("partition", rec.partition());
                        row.put("offset", rec.offset());
                        row.put("timestamp", rec.timestamp());
                        row.put("key", rec.key());
                        String val = rec.value();
                        if (val != null && val.length() > 2000) {
                            val = val.substring(0, 2000) + "…";
                        }
                        row.put("value", val);
                        rows.add(row);
                        if (rows.size() >= limit) {
                            break;
                        }
                    }
                }
            }
            r.put("ok", true);
            r.put("source", "kafka");
            r.put("columns", columns);
            r.put("rows", rows);
            r.put("rowCount", rows.size());
            r.put("message", rows.isEmpty()
                    ? "已连接 Topic，暂无可用消息样例（空主题或超时）"
                    : "Kafka 短时消费样例（探查，非分析路径）");
            return r;
        } catch (Exception e) {
            log.warn("Kafka preview fail topic={}: {}", topic, e.getMessage());
            Map<String, Object> fail = PreviewAdapterSupport.emptyFail(r, "kafka",
                    "Kafka 预览失败: " + StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()));
            fail.put("degraded", true);
            return fail;
        }
    }
}
