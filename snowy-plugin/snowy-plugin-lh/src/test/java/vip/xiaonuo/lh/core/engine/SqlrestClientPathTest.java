package vip.xiaonuo.lh.core.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SqlrestClientPathTest {

    @Test
    void toSqlrestPath_strips_api_prefix() {
        assertEquals("devLog", SqlrestClient.toSqlrestPath("/api/devLog"));
        assertEquals("devLog", SqlrestClient.toSqlrestPath("api/devLog"));
        assertEquals("a/b", SqlrestClient.toSqlrestPath("/api/a/b"));
        assertEquals("x", SqlrestClient.toSqlrestPath("/api/api/x"));
        assertEquals("plain", SqlrestClient.toSqlrestPath("/plain"));
        assertEquals("", SqlrestClient.toSqlrestPath("/api/"));
    }

    @Test
    void toGatewayRequestUrl_builds_sqlrest_gateway_url() {
        assertEquals(
                "http://127.0.0.1:18091/api/devLog",
                SqlrestClient.toGatewayRequestUrl("http://127.0.0.1:18091", "/api/devLog"));
        assertEquals(
                "http://127.0.0.1:18091/api/devLog",
                SqlrestClient.toGatewayRequestUrl("http://127.0.0.1:18091/", "api/devLog"));
        // gateway-url 误配带 /api 时不双写
        assertEquals(
                "http://127.0.0.1:18091/api/devLog",
                SqlrestClient.toGatewayRequestUrl("http://127.0.0.1:18091/api", "/api/devLog"));
    }

    @Test
    void normalizeSqlContext_strips_trailing_semicolon_and_limit() {
        String raw = "SELECT *\nFROM \"dev_log\"\nWHERE name like concat('%',\n#{name},\n'%')\nLIMIT 100;";
        String got = SqlrestClient.normalizeSqlContext(raw);
        assertEquals(
                "SELECT *\nFROM \"dev_log\"\nWHERE name like concat('%',\n#{name},\n'%')",
                got);
        assertEquals("SELECT 1 AS ok", SqlrestClient.normalizeSqlContext("SELECT 1 AS ok;"));
        // 子查询内部 LIMIT 保留
        assertEquals(
                "SELECT * FROM (SELECT id FROM t LIMIT 10) x",
                SqlrestClient.normalizeSqlContext("SELECT * FROM (SELECT id FROM t LIMIT 10) x;"));
    }

    @Test
    void toSqlrestSql_mustache_and_normalize() {
        assertEquals(
                "SELECT * FROM t WHERE id = #{id}",
                SqlrestClient.toSqlrestSql("SELECT * FROM t WHERE id = {{id}} LIMIT 50;"));
    }
}
