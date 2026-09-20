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
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeTopicsResult;
import org.apache.kafka.clients.admin.ListTopicsOptions;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.KafkaFuture;
import org.springframework.stereotype.Component;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.lh.core.vault.LhVaultClient;
import vip.xiaonuo.lh.modular.datasource.entity.LhDatasource;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Kafka 清单发现：AdminClient listTopics（含分区数）
 *
 * @author lakehouse
 * @date 2026/3/18
 */
@Slf4j
@Component
@org.springframework.core.annotation.Order(10)
public class LhKafkaInventoryDiscoverer implements LhInventoryDiscoverer {

    @Resource
    private LhVaultClient vaultClient;

    @Override
    public boolean supports(String typeCode) {
        return "kafka".equalsIgnoreCase(StrUtil.blankToDefault(typeCode, ""));
    }

    @Override
    public String objectKind(String typeCode) {
        return LhInventoryObjectKinds.TOPIC;
    }

    @Override
    public LhInventoryDiscoverResult discover(LhDatasource ds) {
        Map<String, Object> secret = vaultClient.readOrEmpty(ds.getVaultPath());
        String bootstrap = first(secret, "bootstrap", "bootstrapServers", "host");
        String port = first(secret, "port");
        if (StrUtil.isBlank(bootstrap) && StrUtil.isNotBlank(ds.getEndpointHost())) {
            bootstrap = ds.getEndpointHost();
            if (StrUtil.isBlank(port)) {
                port = ds.getEndpointPort();
            }
        }
        if (StrUtil.isBlank(bootstrap)) {
            throw new CommonException("Kafka 清单同步需要 bootstrap/host");
        }
        // 登记填什么用什么：仅补全「host + 独立 port」形态，不改写地址
        if (StrUtil.isNotBlank(port) && !bootstrap.contains(":")) {
            bootstrap = bootstrap + ":" + port;
        }

        Properties props = new Properties();
        props.put("bootstrap.servers", bootstrap);
        props.put("request.timeout.ms", "15000");
        props.put("default.api.timeout.ms", "20000");
        props.put("client.id", "lh-ds-inventory-" + StrUtil.blankToDefault(ds.getId(), "x"));

        try (AdminClient admin = AdminClient.create(props)) {
            Set<String> names = admin.listTopics(new ListTopicsOptions().listInternal(false))
                    .names()
                    .get(20, TimeUnit.SECONDS);
            List<String> sorted = names.stream().sorted().collect(Collectors.toList());
            Map<String, TopicDescription> descMap = Map.of();
            if (!sorted.isEmpty()) {
                try {
                    DescribeTopicsResult desc = admin.describeTopics(sorted);
                    Map<String, KafkaFuture<TopicDescription>> futures = desc.topicNameValues();
                    Map<String, TopicDescription> collected = new LinkedHashMap<>();
                    for (Map.Entry<String, KafkaFuture<TopicDescription>> e : futures.entrySet()) {
                        try {
                            collected.put(e.getKey(), e.getValue().get(10, TimeUnit.SECONDS));
                        } catch (Exception ignored) {
                            // 单 topic 描述失败仍保留名称
                        }
                    }
                    descMap = collected;
                } catch (Exception e) {
                    log.warn("Kafka describeTopics soft-fail: {}", e.getMessage());
                }
            }
            List<LhRemoteInventoryItem> items = new ArrayList<>();
            for (String topic : sorted) {
                if (StrUtil.isBlank(topic) || topic.startsWith("__")) {
                    continue;
                }
                LhRemoteInventoryItem m = new LhRemoteInventoryItem();
                m.name = topic;
                m.engine = "kafka";
                m.encoding = "UTF-8";
                TopicDescription td = descMap.get(topic);
                if (td != null && td.partitions() != null) {
                    int parts = td.partitions().size();
                    m.comment = "partitions=" + parts;
                    m.rowCount = (long) parts;
                } else {
                    m.comment = "Kafka topic";
                }
                items.add(m);
            }
            return LhInventoryDiscoverResult.remote("kafka_admin", LhInventoryObjectKinds.TOPIC, items);
        } catch (CommonException e) {
            throw e;
        } catch (Exception e) {
            String msg = StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName());
            throw new CommonException("Kafka 清单同步失败（bootstrap={}）: {}", bootstrap, msg);
        }
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
