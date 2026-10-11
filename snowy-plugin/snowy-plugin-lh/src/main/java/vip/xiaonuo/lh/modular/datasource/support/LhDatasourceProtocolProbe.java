package vip.xiaonuo.lh.modular.datasource.support;

import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.ssh.JschUtil;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.Session;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListTopicsOptions;
import org.apache.pulsar.client.admin.PulsarAdmin;
import vip.xiaonuo.common.exception.CommonException;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * 非 JDBC 数据源协议级连通探测（Kafka / FTP·SFTP / Pulsar / HBase / Redis / ES / RMQ / S3 / Mongo / HDFS / Iceberg）。
 */
@Slf4j
public final class LhDatasourceProtocolProbe {

    private LhDatasourceProtocolProbe() {
    }

    public static void probeKafka(Map<String, Object> conn) throws Exception {
        String bootstrap = first(conn, "bootstrap", "bootstrapServers", "host");
        String port = first(conn, "port");
        if (StrUtil.isBlank(bootstrap)) {
            throw new CommonException("Kafka 需要 bootstrap/host");
        }
        if (StrUtil.isNotBlank(port) && !bootstrap.contains(":")) {
            bootstrap = bootstrap + ":" + port;
        }
        Properties props = new Properties();
        props.put("bootstrap.servers", bootstrap);
        props.put("request.timeout.ms", "12000");
        props.put("default.api.timeout.ms", "15000");
        props.put("client.id", "lh-ds-probe");
        applyKafkaSecurity(props, conn);
        try (AdminClient admin = AdminClient.create(props)) {
            admin.listTopics(new ListTopicsOptions().listInternal(false))
                    .names()
                    .get(15, TimeUnit.SECONDS);
        }
    }

    public static void probeFtpOrSftp(Map<String, Object> conn) throws Exception {
        String host = first(conn, "host", "endpoint");
        String port = first(conn, "port");
        String user = first(conn, "user", "username");
        String password = first(conn, "password");
        String protocol = first(conn, "protocol");
        String path = first(conn, "path", "remotePath");
        if (StrUtil.isBlank(host)) {
            throw new CommonException("FTP/SFTP 需要 host");
        }
        boolean sftp = protocol.toLowerCase(Locale.ROOT).contains("sftp")
                || "22".equals(port)
                || (!protocol.toLowerCase(Locale.ROOT).contains("ftp") && !"21".equals(port));
        if (sftp) {
            int p = parsePort(port, 22);
            String privateKey = first(conn, "privateKey", "pkey");
            Session session = null;
            ChannelSftp ch = null;
            try {
                if (StrUtil.isNotBlank(privateKey) && !privateKey.contains("建议")) {
                    session = JschUtil.createSession(host, p, StrUtil.blankToDefault(user, "root"),
                            privateKey.getBytes(StandardCharsets.UTF_8), null);
                } else {
                    session = JschUtil.createSession(host, p, StrUtil.blankToDefault(user, "root"),
                            StrUtil.nullToEmpty(password));
                }
                session.setTimeout(12000);
                session.connect(12000);
                ch = (ChannelSftp) session.openChannel("sftp");
                ch.connect(12000);
                ch.ls(StrUtil.blankToDefault(path, "."));
            } finally {
                if (ch != null) {
                    try {
                        ch.disconnect();
                    } catch (Exception ignored) {
                    }
                }
                if (session != null) {
                    try {
                        session.disconnect();
                    } catch (Exception ignored) {
                    }
                }
            }
            return;
        }
        int p = parsePort(port, 21);
        FTPClient ftp = new FTPClient();
        try {
            ftp.setConnectTimeout(12000);
            ftp.setDataTimeout(12000);
            ftp.connect(host, p);
            int reply = ftp.getReplyCode();
            if (!FTPReply.isPositiveCompletion(reply)) {
                throw new CommonException("FTP 连接被拒绝 reply={}", reply);
            }
            if (!ftp.login(StrUtil.blankToDefault(user, "anonymous"), StrUtil.nullToEmpty(password))) {
                throw new CommonException("FTP 登录失败");
            }
            ftp.enterLocalPassiveMode();
            ftp.setFileType(FTP.BINARY_FILE_TYPE);
            if (StrUtil.isNotBlank(path) && !ftp.changeWorkingDirectory(path)) {
                // 目录不存在仍算连通成功（账号可用）
                log.warn("FTP cwd {} 失败，但登录成功", path);
            }
            ftp.noop();
        } finally {
            if (ftp.isConnected()) {
                try {
                    ftp.logout();
                } catch (Exception ignored) {
                }
                try {
                    ftp.disconnect();
                } catch (Exception ignored) {
                }
            }
        }
    }

    public static void probePulsar(Map<String, Object> conn) throws Exception {
        String adminUrl = resolvePulsarAdminHttp(conn);
        try (PulsarAdmin admin = PulsarAdmin.builder().serviceHttpUrl(adminUrl).build()) {
            String tenant = StrUtil.blankToDefault(first(conn, "tenant"), "public");
            admin.namespaces().getNamespaces(tenant);
        }
    }

    /** HBase：探测 ZK 可达；若配置 restUrl 则再打 Stargate /version */
    public static void probeHbase(Map<String, Object> conn) throws Exception {
        String zk = first(conn, "zkQuorum", "host", "zookeeper");
        String port = first(conn, "port");
        if (StrUtil.isBlank(zk)) {
            throw new CommonException("HBase 需要 zkQuorum/host");
        }
        String[] hosts = zk.split(",");
        Exception last = null;
        int ok = 0;
        for (String raw : hosts) {
            String h = raw.trim();
            String p = port;
            if (h.contains(":")) {
                String[] hp = h.split(":", 2);
                h = hp[0];
                p = hp[1];
            }
            try {
                probeTcp(h, StrUtil.blankToDefault(p, "2181"), "HBase ZK");
                ok++;
            } catch (Exception e) {
                last = e;
            }
        }
        if (ok == 0) {
            throw last != null ? last : new CommonException("HBase ZK 不可达");
        }
        String rest = first(conn, "restUrl", "restEndpoint", "stargate");
        if (StrUtil.isNotBlank(rest)) {
            String url = rest.startsWith("http") ? rest : "http://" + rest;
            if (!url.endsWith("/")) {
                url = url + "/";
            }
            cn.hutool.http.HttpRequest.get(url + "version").timeout(8000).execute();
        }
    }

    public static String resolvePulsarAdminHttp(Map<String, Object> conn) {
        String http = first(conn, "adminUrl", "webServiceUrl", "httpUrl");
        if (StrUtil.isNotBlank(http)) {
            return http.startsWith("http") ? http : "http://" + http;
        }
        String service = first(conn, "serviceUrl", "broker");
        String host = first(conn, "host");
        String adminPort = StrUtil.blankToDefault(first(conn, "adminPort", "httpPort"), "8080");
        if (StrUtil.isNotBlank(service)) {
            String s = service.replace("pulsar+ssl://", "").replace("pulsar://", "");
            if (s.contains(":")) {
                host = s.split(":", 2)[0];
            } else if (StrUtil.isBlank(host)) {
                host = s;
            }
        }
        if (StrUtil.isBlank(host)) {
            throw new CommonException("Pulsar 需要 serviceUrl/host 或 adminUrl");
        }
        return "http://" + host + ":" + adminPort;
    }

    public static void probeTcp(String host, String port, String label) throws Exception {
        if (StrUtil.isBlank(host)) {
            throw new CommonException(label + " host 不能为空");
        }
        String h = host.trim();
        int p = 9092;
        if (h.contains(":") && !h.startsWith("[")) {
            int idx = h.lastIndexOf(':');
            String maybe = h.substring(idx + 1);
            if (maybe.chars().allMatch(Character::isDigit)) {
                p = Integer.parseInt(maybe);
                h = h.substring(0, idx);
            }
        }
        if (StrUtil.isNotBlank(port) && port.chars().allMatch(Character::isDigit)) {
            p = Integer.parseInt(port.trim());
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(h, p), 5000);
        }
    }

    private static void applyKafkaSecurity(Properties props, Map<String, Object> conn) {
        String security = first(conn, "security", "securityProtocol", "security.protocol");
        if (StrUtil.isBlank(security) || "PLAINTEXT".equalsIgnoreCase(security)) {
            return;
        }
        props.put("security.protocol", security.trim());
        if (!security.toUpperCase(Locale.ROOT).contains("SASL")) {
            return;
        }
        String mech = StrUtil.blankToDefault(first(conn, "saslMech", "sasl.mechanism"), "PLAIN");
        props.put("sasl.mechanism", mech);
        String user = first(conn, "user", "username");
        String password = first(conn, "password");
        if (StrUtil.isBlank(user)) {
            return;
        }
        String module = mech.toUpperCase(Locale.ROOT).startsWith("SCRAM")
                ? "org.apache.kafka.common.security.scram.ScramLoginModule"
                : "org.apache.kafka.common.security.plain.PlainLoginModule";
        props.put("sasl.jaas.config", module + " required username=\""
                + user.replace("\"", "\\\"") + "\" password=\""
                + StrUtil.nullToEmpty(password).replace("\"", "\\\"") + "\";");
    }

    private static int parsePort(String port, int def) {
        if (StrUtil.isBlank(port)) {
            return def;
        }
        try {
            return Integer.parseInt(port.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String first(Map<String, Object> m, String... keys) {
        if (m == null) {
            return "";
        }
        for (String k : keys) {
            Object v = m.get(k);
            if (v != null && StrUtil.isNotBlank(String.valueOf(v))) {
                return String.valueOf(v).trim();
            }
        }
        return "";
    }

    /** 供清单发现复用：列出 FTP/SFTP 当前目录名 */
    public static List<String> listFtpNames(Map<String, Object> conn, int limit) throws Exception {
        Map<String, Object> c = conn == null ? Map.of() : conn;
        String host = first(c, "host", "endpoint");
        String port = first(c, "port");
        String user = first(c, "user", "username");
        String password = first(c, "password");
        String protocol = first(c, "protocol");
        String path = first(c, "path", "remotePath");
        boolean sftp = protocol.toLowerCase(Locale.ROOT).contains("sftp")
                || "22".equals(port);
        java.util.ArrayList<String> names = new java.util.ArrayList<>();
        if (sftp) {
            int p = parsePort(port, 22);
            String privateKey = first(c, "privateKey", "pkey");
            Session session;
            if (StrUtil.isNotBlank(privateKey) && !privateKey.contains("建议")) {
                session = JschUtil.createSession(host, p, StrUtil.blankToDefault(user, "root"),
                        privateKey.getBytes(StandardCharsets.UTF_8), null);
            } else {
                session = JschUtil.createSession(host, p, StrUtil.blankToDefault(user, "root"),
                        StrUtil.nullToEmpty(password));
            }
            session.setTimeout(12000);
            session.connect(12000);
            ChannelSftp ch = (ChannelSftp) session.openChannel("sftp");
            ch.connect(12000);
            try {
                @SuppressWarnings("unchecked")
                java.util.Vector<ChannelSftp.LsEntry> entries =
                        ch.ls(StrUtil.blankToDefault(path, "."));
                for (ChannelSftp.LsEntry e : entries) {
                    String n = e.getFilename();
                    if (".".equals(n) || "..".equals(n)) {
                        continue;
                    }
                    names.add(n);
                    if (names.size() >= limit) {
                        break;
                    }
                }
            } finally {
                ch.disconnect();
                session.disconnect();
            }
            return names;
        }
        int p = parsePort(port, 21);
        FTPClient ftp = new FTPClient();
        ftp.setConnectTimeout(12000);
        ftp.connect(host, p);
        if (!ftp.login(StrUtil.blankToDefault(user, "anonymous"), StrUtil.nullToEmpty(password))) {
            throw new CommonException("FTP 登录失败");
        }
        ftp.enterLocalPassiveMode();
        if (StrUtil.isNotBlank(path)) {
            ftp.changeWorkingDirectory(path);
        }
        try {
            for (org.apache.commons.net.ftp.FTPFile f : ftp.listFiles()) {
                if (f == null || StrUtil.isBlank(f.getName()) || ".".equals(f.getName()) || "..".equals(f.getName())) {
                    continue;
                }
                names.add(f.getName());
                if (names.size() >= limit) {
                    break;
                }
            }
        } finally {
            ftp.logout();
            ftp.disconnect();
        }
        return names;
    }

    public static void probeRedis(Map<String, Object> conn) throws Exception {
        String host = first(conn, "host", "endpoint");
        String port = StrUtil.blankToDefault(first(conn, "port"), "6379");
        if (StrUtil.isBlank(host)) {
            throw new CommonException("Redis 需要 host");
        }
        String mode = first(conn, "mode", "deployMode");
        if (StrUtil.isNotBlank(mode)) {
            String m = mode.toLowerCase(Locale.ROOT);
            if (m.contains("cluster") || m.contains("哨兵") || m.contains("sentinel") || m.contains("集群")) {
                throw new CommonException("Redis 测连当前仅支持单机模式（mode={}）", mode);
            }
        }
        int db = 0;
        String dbStr = first(conn, "db", "database");
        if (StrUtil.isNotBlank(dbStr) && dbStr.chars().allMatch(Character::isDigit)) {
            db = Integer.parseInt(dbStr.trim());
        }
        String password = first(conn, "password");
        String user = first(conn, "user", "username");
        redis.clients.jedis.DefaultJedisClientConfig.Builder cfg =
                redis.clients.jedis.DefaultJedisClientConfig.builder()
                        .connectionTimeoutMillis(3000)
                        .socketTimeoutMillis(3000)
                        .database(db);
        if (StrUtil.isNotBlank(password)) {
            if (StrUtil.isNotBlank(user)) {
                cfg.user(user).password(password);
            } else {
                cfg.password(password);
            }
        }
        int portNum = parsePort(port, 6379);
        try (redis.clients.jedis.Jedis jedis = new redis.clients.jedis.Jedis(
                new redis.clients.jedis.HostAndPort(host.trim(), portNum), cfg.build())) {
            String pong = jedis.ping();
            if (!"PONG".equalsIgnoreCase(StrUtil.blankToDefault(pong, ""))) {
                throw new CommonException("Redis PING 异常: {}", pong);
            }
        }
    }

    public static void probeElasticsearch(Map<String, Object> conn) throws Exception {
        String host = first(conn, "host", "endpoint");
        String port = StrUtil.blankToDefault(first(conn, "port"), "9200");
        if (StrUtil.isBlank(host)) {
            throw new CommonException("Elasticsearch 需要 host");
        }
        boolean https = truthySsl(first(conn, "ssl", "secure", "https"))
                || StrUtil.startWithIgnoreCase(host, "https://");
        host = host.replaceFirst("^https?://", "").replaceAll("/+$", "");
        if (host.contains(":") && !host.startsWith("[")) {
            int colon = host.lastIndexOf(':');
            if (colon > 0 && StrUtil.isBlank(first(conn, "port"))) {
                port = host.substring(colon + 1);
                host = host.substring(0, colon);
            }
        }
        String base = (https ? "https://" : "http://") + host + ":" + port;
        cn.hutool.http.HttpRequest req = cn.hutool.http.HttpRequest.get(base + "/")
                .timeout(10000);
        String apiKey = first(conn, "apiKey", "apiKeyId", "api_key");
        String user = first(conn, "user", "username");
        String password = first(conn, "password");
        if (StrUtil.isNotBlank(apiKey) && !apiKey.contains("建议")) {
            String token = apiKey.contains(":")
                    ? java.util.Base64.getEncoder().encodeToString(apiKey.getBytes(StandardCharsets.UTF_8))
                    : apiKey.trim();
            req.header("Authorization", "ApiKey " + token);
        } else if (StrUtil.isNotBlank(user)) {
            req.basicAuth(user, StrUtil.nullToEmpty(password));
        }
        cn.hutool.http.HttpResponse resp = req.execute();
        if (!resp.isOk()) {
            throw new CommonException("Elasticsearch HTTP {}：{}",
                    resp.getStatus(), StrUtil.maxLength(resp.body(), 160));
        }
    }

    public static void probeRabbitMq(Map<String, Object> conn) throws Exception {
        String host = first(conn, "host", "endpoint");
        if (StrUtil.isBlank(host)) {
            throw new CommonException("RabbitMQ 需要 host");
        }
        host = host.replaceFirst("^https?://", "").replaceAll("/+$", "");
        if (host.contains(":") && !host.startsWith("[")) {
            host = host.substring(0, host.lastIndexOf(':'));
        }
        String mgmtPort = StrUtil.blankToDefault(first(conn, "mgmtPort", "managementPort", "mgmt_port"), "15672");
        String user = first(conn, "user", "username");
        String password = first(conn, "password");
        if (StrUtil.isBlank(user)) {
            throw new CommonException("RabbitMQ Management 需要 user/password");
        }
        boolean https = StrUtil.startWithIgnoreCase(first(conn, "mgmtScheme", "scheme"), "https")
                || truthySsl(first(conn, "mgmtSsl", "ssl"));
        String url = (https ? "https://" : "http://") + host + ":" + mgmtPort + "/api/overview";
        cn.hutool.http.HttpResponse resp = cn.hutool.http.HttpRequest.get(url)
                .timeout(10000)
                .basicAuth(user, StrUtil.nullToEmpty(password))
                .execute();
        if (!resp.isOk()) {
            throw new CommonException("RabbitMQ Management {} HTTP {}", url, resp.getStatus());
        }
    }

    public static void probeS3(Map<String, Object> conn) throws Exception {
        String endpoint = first(conn, "endpoint", "host", "url");
        String port = first(conn, "port");
        String ak = first(conn, "accessKey", "access-key", "accessKeyId", "username", "user");
        String sk = first(conn, "secretKey", "secret-key", "secretAccessKey", "password");
        if (StrUtil.isBlank(endpoint)) {
            throw new CommonException("S3/MinIO 需要 endpoint");
        }
        if (StrUtil.isBlank(ak) || StrUtil.isBlank(sk) || ak.contains("建议") || sk.contains("建议")
                || ak.startsWith("REPLACE_") || sk.startsWith("REPLACE_")) {
            throw new CommonException("S3/MinIO 需要有效 accessKey/secretKey");
        }
        boolean secure = truthySsl(first(conn, "secure", "ssl", "https"))
                || StrUtil.startWithIgnoreCase(endpoint, "https://");
        String ep = endpoint.trim();
        if (ep.startsWith("https://")) {
            secure = true;
            ep = ep.substring("https://".length());
        } else if (ep.startsWith("http://")) {
            secure = false;
            ep = ep.substring("http://".length());
        }
        ep = ep.replaceAll("/+$", "");
        if (StrUtil.isNotBlank(port) && !ep.matches(".*:\\d+$") && !ep.contains(":")) {
            ep = ep + ":" + port;
        }
        String region = StrUtil.blankToDefault(first(conn, "region"), "us-east-1");
        io.minio.MinioClient client = io.minio.MinioClient.builder()
                .endpoint((secure ? "https://" : "http://") + ep)
                .credentials(ak, sk)
                .region(region)
                .build();
        client.listBuckets();
    }

    public static void probeMongo(Map<String, Object> conn) throws Exception {
        String uri = buildMongoUri(conn);
        try (com.mongodb.client.MongoClient client = com.mongodb.client.MongoClients.create(uri)) {
            String db = StrUtil.blankToDefault(first(conn, "database", "db"), "admin");
            client.getDatabase(db).runCommand(new org.bson.Document("ping", 1));
        }
    }

    /** 拼装 mongodb:// URI（供测连与清单复用） */
    public static String buildMongoUri(Map<String, Object> conn) {
        String uri = first(conn, "uri", "mongoUri", "connectionString");
        if (StrUtil.isNotBlank(uri)) {
            return uri;
        }
        String host = first(conn, "host", "endpoint");
        String port = StrUtil.blankToDefault(first(conn, "port"), "27017");
        if (StrUtil.isBlank(host)) {
            throw new CommonException("MongoDB 需要 host 或 uri");
        }
        String user = first(conn, "user", "username");
        String password = first(conn, "password");
        String db = first(conn, "database", "db");
        String authSource = first(conn, "authSource");
        String replicaSet = first(conn, "replicaSet");
        StringBuilder clean = new StringBuilder("mongodb://");
        if (StrUtil.isNotBlank(user)) {
            clean.append(urlEncode(user)).append(":").append(urlEncode(password)).append("@");
        }
        clean.append(host.trim()).append(":").append(port);
        if (StrUtil.isNotBlank(db)) {
            clean.append("/").append(db.trim());
        } else {
            clean.append("/");
        }
        java.util.ArrayList<String> q = new java.util.ArrayList<>();
        if (StrUtil.isNotBlank(authSource)) {
            q.add("authSource=" + urlEncode(authSource));
        }
        if (StrUtil.isNotBlank(replicaSet)) {
            q.add("replicaSet=" + urlEncode(replicaSet));
        }
        q.add("serverSelectionTimeoutMS=8000");
        q.add("connectTimeoutMS=8000");
        clean.append("?").append(String.join("&", q));
        return clean.toString();
    }

    /**
     * HDFS：优先 WebHDFS LISTSTATUS；失败则 TCP 探测 NameNode（RPC/HTTP 端口）。
     */
    public static void probeHdfs(Map<String, Object> conn) throws Exception {
        String nn = first(conn, "nameNode", "host", "endpoint");
        String port = first(conn, "port");
        String user = StrUtil.blankToDefault(first(conn, "user", "username"), "hdfs");
        String path = StrUtil.blankToDefault(first(conn, "path", "remotePath"), "/");
        if (StrUtil.isBlank(nn)) {
            throw new CommonException("HDFS 需要 nameNode/host");
        }
        nn = nn.replaceFirst("^hdfs://", "").replaceFirst("^https?://", "").replaceAll("/+$", "");
        String rpcHost = nn;
        String rpcPort = port;
        if (rpcHost.contains(":") && !rpcHost.startsWith("[")) {
            String[] hp = rpcHost.split(":", 2);
            rpcHost = hp[0];
            if (StrUtil.isBlank(rpcPort)) {
                rpcPort = hp[1];
            }
        }
        Exception webFail = null;
        try {
            listWebHdfs(rpcHost, resolveWebHdfsPort(rpcPort), user, path, 1);
            return;
        } catch (Exception e) {
            webFail = e;
            log.warn("HDFS WebHDFS soft-fail, fallback TCP: {}", e.getMessage());
        }
        try {
            probeTcp(rpcHost, StrUtil.blankToDefault(rpcPort, "8020"), "HDFS NameNode");
        } catch (Exception tcpEx) {
            throw webFail != null ? webFail : tcpEx;
        }
    }

    public static java.util.List<String> listWebHdfs(String host, String httpPort, String user,
                                                     String path, int limit) throws Exception {
        String p = StrUtil.blankToDefault(path, "/");
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        String url = "http://" + host + ":" + StrUtil.blankToDefault(httpPort, "9870")
                + "/webhdfs/v1" + p + "?op=LISTSTATUS&user.name="
                + urlEncode(StrUtil.blankToDefault(user, "hdfs"));
        cn.hutool.http.HttpResponse resp = cn.hutool.http.HttpRequest.get(url).timeout(12000).execute();
        if (!resp.isOk()) {
            throw new CommonException("WebHDFS LISTSTATUS HTTP {}：{}",
                    resp.getStatus(), StrUtil.maxLength(resp.body(), 160));
        }
        cn.hutool.json.JSONObject root = cn.hutool.json.JSONUtil.parseObj(resp.body());
        cn.hutool.json.JSONObject statuses = root.getJSONObject("FileStatuses");
        java.util.ArrayList<String> names = new java.util.ArrayList<>();
        if (statuses == null) {
            return names;
        }
        cn.hutool.json.JSONArray arr = statuses.getJSONArray("FileStatus");
        if (arr == null) {
            return names;
        }
        for (int i = 0; i < arr.size() && names.size() < limit; i++) {
            cn.hutool.json.JSONObject row = arr.getJSONObject(i);
            if (row == null) {
                continue;
            }
            String name = row.getStr("pathSuffix");
            if (StrUtil.isBlank(name)) {
                name = row.getStr("path");
            }
            if (StrUtil.isNotBlank(name)) {
                names.add(name);
            }
        }
        return names;
    }

    public static String resolveWebHdfsPort(String port) {
        if (StrUtil.isBlank(port)) {
            return "9870";
        }
        // RPC 8020/9000 → NN HTTP 9870；已是 9870/50070 则沿用
        if ("8020".equals(port) || "9000".equals(port)) {
            return "9870";
        }
        return port;
    }

    /**
     * Iceberg：校验 warehouse；REST Catalog 走 HTTP；Hive Metastore 等走 TCP。
     */
    public static void probeIceberg(Map<String, Object> conn) throws Exception {
        String warehouse = first(conn, "warehouse");
        if (StrUtil.isBlank(warehouse)) {
            throw new CommonException("Iceberg 需要 warehouse");
        }
        String host = first(conn, "host", "catalogUri", "uri", "endpoint");
        String port = first(conn, "port");
        String catalogType = first(conn, "catalogType", "type");
        if (StrUtil.isBlank(host)) {
            // 仅 warehouse（如 s3a://）时做格式校验即可
            if (!warehouse.contains("://") && !warehouse.startsWith("/")) {
                throw new CommonException("Iceberg warehouse 格式无效: {}", warehouse);
            }
            return;
        }
        boolean rest = StrUtil.startWithIgnoreCase(host, "http")
                || catalogType.toLowerCase(Locale.ROOT).contains("rest")
                || catalogType.toLowerCase(Locale.ROOT).contains("nessie");
        if (rest) {
            String url = host;
            if (!url.startsWith("http")) {
                url = "http://" + host + (StrUtil.isNotBlank(port) ? ":" + port : "");
            }
            cn.hutool.http.HttpResponse resp = cn.hutool.http.HttpRequest.get(url)
                    .timeout(10000).execute();
            // 401/404 也说明服务可达
            if (resp.getStatus() >= 500) {
                throw new CommonException("Iceberg Catalog HTTP {}", resp.getStatus());
            }
            return;
        }
        probeTcp(host.replaceFirst("^https?://", "").split("/")[0],
                StrUtil.blankToDefault(port, "9083"), "Iceberg Catalog");
    }

    public static Map<String, Object> asMutable(Map<String, Object> conn) {
        return conn == null ? new LinkedHashMap<>() : new LinkedHashMap<>(conn);
    }

    private static boolean truthySsl(String ssl) {
        if (StrUtil.isBlank(ssl)) {
            return false;
        }
        String s = ssl.toLowerCase(Locale.ROOT);
        return s.contains("开启") || s.contains("启用") || "true".equals(s) || "yes".equals(s)
                || "1".equals(s) || "https".equals(s) || "ssl".equals(s);
    }

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(StrUtil.nullToEmpty(s), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return StrUtil.nullToEmpty(s);
        }
    }
}
