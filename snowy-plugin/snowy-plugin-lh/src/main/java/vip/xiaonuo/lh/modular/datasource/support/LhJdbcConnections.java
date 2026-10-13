package vip.xiaonuo.lh.modular.datasource.support;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Properties;

/**
 * 按类型显式打开 JDBC，避免 {@link DriverManager} 遍历 SPI 时误触损坏的第三方驱动
 * （例如 ClickHouseDriver 类初始化失败导致 MySQL 测连变成 NoClassDefFoundError）。
 */
@Slf4j
public final class LhJdbcConnections {

    private LhJdbcConnections() {
    }

    public static String driverClass(String typeCode) {
        String t = StrUtil.blankToDefault(typeCode, "").toLowerCase(Locale.ROOT);
        return switch (t) {
            case "mysql", "mariadb", "doris", "starrocks" -> "com.mysql.cj.jdbc.Driver";
            case "postgresql", "pg" -> "org.postgresql.Driver";
            case "oracle" -> "oracle.jdbc.OracleDriver";
            case "sqlserver" -> "com.microsoft.sqlserver.jdbc.SQLServerDriver";
            case "clickhouse" -> "com.clickhouse.jdbc.ClickHouseDriver";
            case "trino" -> "io.trino.jdbc.TrinoDriver";
            case "hive" -> "org.apache.hive.jdbc.HiveDriver";
            default -> null;
        };
    }

    /** 尽力加载驱动；类缺失或初始化失败只打日志，不抛 Error */
    public static void ensureDriver(String typeCode) {
        String cls = driverClass(typeCode);
        if (cls == null) {
            return;
        }
        try {
            Class.forName(cls);
        } catch (ClassNotFoundException e) {
            log.warn("JDBC driver missing type={} class={}: {}", typeCode, cls, e.getMessage());
        } catch (Throwable t) {
            log.warn("JDBC driver init failed type={} class={}: {}", typeCode, cls, t.toString());
        }
    }

    /**
     * 用目标类型驱动直连；不经过 DriverManager 的全量 SPI 遍历。
     */
    public static Connection open(String typeCode, String url, String user, String password) throws SQLException {
        if (StrUtil.isBlank(url)) {
            throw new SQLException("JDBC URL 为空");
        }
        String cls = driverClass(typeCode);
        if (cls == null) {
            return DriverManager.getConnection(url, user, password);
        }
        try {
            Class<?> c = Class.forName(cls);
            Driver driver = (Driver) c.getDeclaredConstructor().newInstance();
            Properties props = new Properties();
            if (user != null) {
                props.setProperty("user", user);
            }
            if (password != null) {
                props.setProperty("password", password);
            }
            Connection conn = driver.connect(url, props);
            if (conn == null) {
                throw new SQLException("Driver " + cls + " rejected URL: " + url);
            }
            return conn;
        } catch (SQLException e) {
            throw e;
        } catch (Throwable t) {
            throw new SQLException("JDBC 驱动不可用 type=" + typeCode + " class=" + cls + ": " + t.getMessage(), t);
        }
    }
}
