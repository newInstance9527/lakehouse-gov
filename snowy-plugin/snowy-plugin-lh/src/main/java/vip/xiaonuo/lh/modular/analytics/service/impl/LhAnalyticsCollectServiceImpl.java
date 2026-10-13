package vip.xiaonuo.lh.modular.analytics.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.modular.analytics.param.LhAnalyticsCollectParam;
import vip.xiaonuo.lh.modular.analytics.service.LhAnalyticsCollectService;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 门户自有埋点 BFF：校验 appKey → 展开单条事件 JSON → Kafka。
 * <p>失败 soft-fail，不拖垮门户 UX。</p>
 */
@Service
public class LhAnalyticsCollectServiceImpl implements LhAnalyticsCollectService {

    private static final Logger log = LoggerFactory.getLogger(LhAnalyticsCollectServiceImpl.class);

    @Resource
    private LhProperties lhProperties;

    private final AtomicReference<KafkaProducer<String, String>> producerRef = new AtomicReference<>();

    @Override
    public Map<String, Object> collect(LhAnalyticsCollectParam param) {
        LhProperties.Analytics cfg = lhProperties.getAnalytics();
        if (cfg == null) {
            cfg = new LhProperties.Analytics();
        }
        String expected = StrUtil.blankToDefault(cfg.getAppKey(), "lakehouse-portal").trim();
        String appKey = param == null ? "" : StrUtil.blankToDefault(param.getAppKey(), "").trim();
        if (!expected.equals(appKey)) {
            throw new CommonException("analytics appKey 不允许: {}（仅门户第一方）", appKey);
        }
        List<Map<String, Object>> events = param.getEvents();
        if (events == null || events.isEmpty()) {
            return result(true, false, 0, 0, "empty");
        }
        int max = Math.max(1, cfg.getMaxBatch());
        if (events.size() > max) {
            throw new CommonException("单批事件数超过上限 {}", max);
        }
        if (!cfg.isEnabled()) {
            return result(true, true, events.size(), 0, "disabled");
        }
        LhProperties.Analytics.Kafka kafka = cfg.getKafka() == null ? new LhProperties.Analytics.Kafka() : cfg.getKafka();
        if (StrUtil.isBlank(kafka.getBootstrap()) || StrUtil.isBlank(kafka.getTopic())) {
            log.warn("lh.analytics enabled but kafka.bootstrap/topic blank; skip {} events", events.size());
            return result(true, true, events.size(), 0, "no_broker");
        }

        KafkaProducer<String, String> producer;
        try {
            producer = producer(kafka);
        } catch (Exception e) {
            log.warn("analytics kafka producer init failed: {}", e.getMessage());
            return result(true, true, events.size(), 0, "producer_init_fail");
        }

        String topic = kafka.getTopic().trim();
        String ingestTime = Instant.now().toString();
        int accepted = 0;
        int failed = 0;
        for (Map<String, Object> raw : events) {
            if (raw == null || raw.isEmpty()) {
                failed++;
                continue;
            }
            try {
                Map<String, Object> envelope = new LinkedHashMap<>(raw);
                envelope.putIfAbsent("appKey", appKey);
                envelope.put("ingestTime", ingestTime);
                String eventId = str(envelope.get("eventId"));
                String key = StrUtil.blankToDefault(eventId, appKey);
                String value = JSONUtil.toJsonStr(envelope);
                producer.send(new ProducerRecord<>(topic, key, value), (meta, ex) -> {
                    if (ex != null) {
                        log.debug("analytics send fail topic={} err={}", topic, ex.getMessage());
                    }
                });
                accepted++;
            } catch (Exception e) {
                failed++;
                log.debug("analytics encode/send error: {}", e.getMessage());
            }
        }
        try {
            producer.flush();
        } catch (Exception e) {
            log.warn("analytics kafka flush: {}", e.getMessage());
        }
        return result(true, false, accepted, failed, null);
    }

    private KafkaProducer<String, String> producer(LhProperties.Analytics.Kafka kafka) {
        KafkaProducer<String, String> existing = producerRef.get();
        if (existing != null) {
            return existing;
        }
        synchronized (producerRef) {
            existing = producerRef.get();
            if (existing != null) {
                return existing;
            }
            Properties props = new Properties();
            props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrap().trim());
            props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
            props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
            props.put(ProducerConfig.ACKS_CONFIG, StrUtil.blankToDefault(kafka.getAcks(), "1"));
            props.put(ProducerConfig.CLIENT_ID_CONFIG, StrUtil.blankToDefault(kafka.getClientId(), "lh-portal-analytics"));
            props.put(ProducerConfig.LINGER_MS_CONFIG, "20");
            props.put(ProducerConfig.RETRIES_CONFIG, "2");
            if (StrUtil.isNotBlank(kafka.getSaslMechanism()) && StrUtil.isNotBlank(kafka.getSaslUsername())) {
                String protocol = StrUtil.blankToDefault(kafka.getSecurityProtocol(), "SASL_PLAINTEXT");
                props.put("security.protocol", protocol);
                props.put("sasl.mechanism", kafka.getSaslMechanism().trim());
                String jaas = String.format(
                        "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"%s\" password=\"%s\";",
                        escapeJaas(kafka.getSaslUsername()),
                        escapeJaas(StrUtil.nullToEmpty(kafka.getSaslPassword())));
                if (kafka.getSaslMechanism().toUpperCase().contains("SCRAM")) {
                    jaas = String.format(
                            "org.apache.kafka.common.security.scram.ScramLoginModule required username=\"%s\" password=\"%s\";",
                            escapeJaas(kafka.getSaslUsername()),
                            escapeJaas(StrUtil.nullToEmpty(kafka.getSaslPassword())));
                }
                props.put("sasl.jaas.config", jaas);
            }
            KafkaProducer<String, String> created = new KafkaProducer<>(props);
            producerRef.set(created);
            return created;
        }
    }

    private static String escapeJaas(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static Map<String, Object> result(boolean ok, boolean skipped, int accepted, int failed, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", ok);
        m.put("skipped", skipped);
        m.put("accepted", accepted);
        m.put("failed", failed);
        if (reason != null) {
            m.put("reason", reason);
        }
        return m;
    }

    @PreDestroy
    public void shutdown() {
        KafkaProducer<String, String> p = producerRef.getAndSet(null);
        if (p != null) {
            try {
                p.close();
            } catch (Exception ignored) {
                // ignore
            }
        }
    }
}
