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
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.lh.core.vault.LhVaultClient;
import vip.xiaonuo.lh.modular.datasource.entity.LhDatasource;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * RabbitMQ 清单发现：Management API GET /api/queues
 *
 * @author lakehouse
 * @date 2026/3/18
 */
@Slf4j
@Component
@Order(10)
public class LhRabbitMqInventoryDiscoverer implements LhInventoryDiscoverer {

    @Resource
    private LhVaultClient vaultClient;

    @Override
    public boolean supports(String typeCode) {
        return "rabbitmq".equalsIgnoreCase(StrUtil.blankToDefault(typeCode, ""));
    }

    @Override
    public String objectKind(String typeCode) {
        return LhInventoryObjectKinds.QUEUE;
    }

    @Override
    public LhInventoryDiscoverResult discover(LhDatasource ds) {
        Map<String, Object> secret = vaultClient.readOrEmpty(ds.getVaultPath());
        String host = first(secret, "host", "endpoint");
        if (StrUtil.isBlank(host) && StrUtil.isNotBlank(ds.getEndpointHost())) {
            host = ds.getEndpointHost();
        }
        if (StrUtil.isBlank(host)) {
            throw new CommonException("RabbitMQ 清单同步需要 host（Vault / 端点）");
        }
        host = host.replaceFirst("^https?://", "").replaceAll("/+$", "");
        if (host.contains(":") && !host.startsWith("[")) {
            host = host.substring(0, host.lastIndexOf(':'));
        }

        String mgmtPort = first(secret, "mgmtPort", "managementPort", "mgmt_port");
        if (StrUtil.isBlank(mgmtPort)) {
            mgmtPort = "15672";
        }
        String user = first(secret, "user", "username");
        String password = first(secret, "password");
        if (StrUtil.isBlank(user)) {
            throw new CommonException("RabbitMQ Management API 需要 user/password（Vault）");
        }
        String vhost = first(secret, "vhost", "virtualHost", "database");
        if (StrUtil.isBlank(vhost) && StrUtil.isNotBlank(ds.getDatabaseName())) {
            vhost = ds.getDatabaseName();
        }
        if (StrUtil.isBlank(vhost)) {
            vhost = "/";
        }

        boolean https = StrUtil.startWithIgnoreCase(first(secret, "mgmtScheme", "scheme"), "https")
                || "true".equalsIgnoreCase(first(secret, "mgmtSsl", "ssl"));
        String base = (https ? "https://" : "http://") + host + ":" + mgmtPort;
        String encodedVhost = URLEncoder.encode(vhost, StandardCharsets.UTF_8);
        String url = base + "/api/queues/" + encodedVhost;
        boolean scopedByVhost = true;

        try {
            HttpRequest req = HttpRequest.get(url).timeout(12000).basicAuth(user, StrUtil.nullToEmpty(password));
            HttpResponse resp = req.execute();
            // 部分环境无按 vhost 过滤权限时回退全量 /api/queues
            if (resp.getStatus() == 404 || resp.getStatus() == 401 || resp.getStatus() == 403) {
                log.warn("RabbitMQ /api/queues/{} -> {}, fallback /api/queues", vhost, resp.getStatus());
                url = base + "/api/queues";
                scopedByVhost = false;
                resp = HttpRequest.get(url).timeout(12000)
                        .basicAuth(user, StrUtil.nullToEmpty(password)).execute();
            }
            if (!resp.isOk()) {
                throw new CommonException("RabbitMQ Management {} HTTP {}：{}（检查 mgmtPort={} / 账号）",
                        url, resp.getStatus(), StrUtil.maxLength(resp.body(), 200), mgmtPort);
            }
            JSONArray arr = JSONUtil.parseArray(resp.body());
            List<LhRemoteInventoryItem> items = new ArrayList<>();
            for (int i = 0; i < arr.size(); i++) {
                JSONObject row = arr.getJSONObject(i);
                if (row == null) {
                    continue;
                }
                String qVhost = StrUtil.blankToDefault(row.getStr("vhost"), "/");
                // 全量接口时按登记 vhost 过滤；scoped 路径已限定
                if (!scopedByVhost && !"/".equals(vhost) && !vhost.equals(qVhost)) {
                    continue;
                }
                String name = StrUtil.trim(row.getStr("name"));
                if (StrUtil.isBlank(name)) {
                    continue;
                }
                LhRemoteInventoryItem m = new LhRemoteInventoryItem();
                m.name = name;
                m.engine = "rabbitmq";
                m.encoding = "UTF-8";
                Long messages = row.getLong("messages");
                m.rowCount = messages;
                m.comment = "vhost=" + qVhost + ", messages=" + (messages == null ? "?" : messages);
                items.add(m);
            }
            return LhInventoryDiscoverResult.remote("rabbitmq_mgmt", LhInventoryObjectKinds.QUEUE, items);
        } catch (CommonException e) {
            throw e;
        } catch (Exception e) {
            throw new CommonException("RabbitMQ 清单同步失败: {}", e.getMessage());
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
