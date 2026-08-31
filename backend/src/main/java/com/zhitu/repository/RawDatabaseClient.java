package com.zhitu.repository;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 第二数据源客户端：专门访问已有的 career_data_governance MySQL。
 *
 * V8 稳定性优化：
 * 1. HikariCP 连接池由 4 个连接扩展为可配置的 8 个；
 * 2. 保留最少空闲连接，避免页面轮询与后台治理抢不到连接；
 * 3. 延长 connectionTimeout / maxLifetime，并启用 keepalive；
 * 4. 为 MySQL 批量写入开启 prepared statement 缓存和 rewriteBatchedStatements；
 * 5. 提供独立事务模板，使一个治理批次的岗位、技能、问题写入复用同一数据库连接；
 * 6. MySQL 暂时不可用时仍不阻断 Spring Boot 主业务库启动。
 *
 * 注意：本类不是 Spring 主 datasource，系统 H2 业务库仍保持原样。
 */
@Component
public class RawDatabaseClient {

    private static final Logger log = LoggerFactory.getLogger(RawDatabaseClient.class);

    private final HikariDataSource dataSource;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;
    private final String rawTable;

    public RawDatabaseClient(
            @Value("${app.raw-database.url}") String url,
            @Value("${app.raw-database.username}") String username,
            @Value("${app.raw-database.password}") String password,
            @Value("${app.raw-database.table:dataset_job_raw}") String rawTable,
            @Value("${app.raw-database.pool.maximum-pool-size:8}") int configuredMaxPoolSize,
            @Value("${app.raw-database.pool.minimum-idle:2}") int configuredMinimumIdle,
            @Value("${app.raw-database.pool.connection-timeout-ms:30000}") long configuredConnectionTimeout,
            @Value("${app.raw-database.pool.validation-timeout-ms:5000}") long configuredValidationTimeout,
            @Value("${app.raw-database.pool.idle-timeout-ms:600000}") long configuredIdleTimeout,
            @Value("${app.raw-database.pool.max-lifetime-ms:1500000}") long configuredMaxLifetime,
            @Value("${app.raw-database.pool.keepalive-time-ms:120000}") long configuredKeepaliveTime,
            @Value("${app.raw-database.pool.query-timeout-seconds:300}") int queryTimeoutSeconds,
            @Value("${app.raw-database.pool.transaction-timeout-seconds:300}") int transactionTimeoutSeconds
    ) {
        this.rawTable = validateIdentifier(rawTable);

        int maxPoolSize = Math.max(4, Math.min(configuredMaxPoolSize, 32));
        int minimumIdle = Math.max(1, Math.min(configuredMinimumIdle, maxPoolSize));
        long connectionTimeout = Math.max(10_000L, configuredConnectionTimeout);
        long validationTimeout = Math.max(
                1_000L,
                Math.min(configuredValidationTimeout, connectionTimeout - 1_000L)
        );
        long maxLifetime = Math.max(600_000L, configuredMaxLifetime);
        long keepaliveTime = Math.max(
                30_000L,
                Math.min(configuredKeepaliveTime, maxLifetime - 30_000L)
        );
        long idleTimeout = Math.max(60_000L, configuredIdleTimeout);

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setDriverClassName("com.mysql.cj.jdbc.Driver");
        config.setPoolName("zhitu-raw-history-pool");

        // ===== 连接池稳定性 =====
        config.setMaximumPoolSize(maxPoolSize);
        config.setMinimumIdle(minimumIdle);
        config.setConnectionTimeout(connectionTimeout);
        config.setValidationTimeout(validationTimeout);
        config.setIdleTimeout(idleTimeout);
        config.setMaxLifetime(maxLifetime);
        config.setKeepaliveTime(keepaliveTime);

        // MySQL 没启动时不阻断 Spring Boot 主业务系统启动。
        config.setInitializationFailTimeout(-1);

        // ===== MySQL / JDBC 批处理性能 =====
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.addDataSourceProperty("useServerPrepStmts", "true");
        config.addDataSourceProperty("rewriteBatchedStatements", "true");
        config.addDataSourceProperty("cacheResultSetMetadata", "true");
        config.addDataSourceProperty("cacheServerConfiguration", "true");
        config.addDataSourceProperty("elideSetAutoCommits", "true");
        config.addDataSourceProperty("maintainTimeStats", "false");
        config.addDataSourceProperty("tcpKeepAlive", "true");

        this.dataSource = new HikariDataSource(config);
        this.jdbc = new JdbcTemplate(dataSource);
        this.jdbc.setQueryTimeout(Math.max(30, queryTimeoutSeconds));

        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setTimeout(Math.max(30, transactionTimeoutSeconds));

        log.info(
                "百万岗位 MySQL 连接池已创建：pool={}, maxPoolSize={}, minimumIdle={}, connectionTimeout={}ms, keepalive={}ms",
                config.getPoolName(),
                maxPoolSize,
                minimumIdle,
                connectionTimeout,
                keepaliveTime
        );
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    /**
     * 在同一个 MySQL 事务中执行一组 JdbcTemplate 操作。
     * 治理批次用它把岗位、技能、质量问题三组 batchUpdate 放在同一连接中，
     * 既减少连接池反复借还，也避免出现“岗位已写入、技能尚未写完”的半批次状态。
     */
    public <T> T inTransaction(Supplier<T> action) {
        return transactionTemplate.execute(status -> action.get());
    }

    public void inTransaction(Runnable action) {
        transactionTemplate.executeWithoutResult(status -> action.run());
    }

    public String rawTable() {
        return rawTable;
    }

    public String quotedRawTable() {
        return "`" + rawTable + "`";
    }

    /**
     * 轻量探活。这里只做 SELECT 1，不再把任何业务统计当作连接健康检查。
     */
    public boolean ping() {
        try {
            Integer value = jdbc.queryForObject("SELECT 1", Integer.class);
            return value != null && value == 1;
        } catch (Exception e) {
            log.warn("百万岗位 MySQL 探活失败：{}", rootMessage(e));
            return false;
        }
    }

    /**
     * 用于排查“数据源不可用”到底是数据库真的断开，还是连接池暂时繁忙。
     */
    public Map<String, Object> poolStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("poolName", dataSource.getPoolName());
        result.put("closed", dataSource.isClosed());
        try {
            if (dataSource.getHikariPoolMXBean() != null) {
                result.put("active", dataSource.getHikariPoolMXBean().getActiveConnections());
                result.put("idle", dataSource.getHikariPoolMXBean().getIdleConnections());
                result.put("total", dataSource.getHikariPoolMXBean().getTotalConnections());
                result.put("waiting", dataSource.getHikariPoolMXBean().getThreadsAwaitingConnection());
            }
        } catch (Exception e) {
            result.put("metricsError", rootMessage(e));
        }
        return result;
    }

    public List<Map<String, Object>> list(String sql, Object... args) {
        return jdbc.queryForList(sql, args);
    }

    public Map<String, Object> one(String sql, Object... args) {
        List<Map<String, Object>> rows = list(sql, args);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("查询结果为空");
        }
        return rows.get(0);
    }

    public int update(String sql, Object... args) {
        return jdbc.update(sql, args);
    }

    public long scalarLong(String sql, Object... args) {
        Number value = jdbc.queryForObject(sql, Number.class, args);
        return value == null ? 0L : value.longValue();
    }

    public double scalarDouble(String sql, Object... args) {
        Number value = jdbc.queryForObject(sql, Number.class, args);
        return value == null ? 0D : value.doubleValue();
    }

    public boolean tableExists(String tableName) {
        String safe = validateIdentifier(tableName);
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables " +
                        "WHERE table_schema = DATABASE() AND table_name = ?",
                Long.class,
                safe
        );
        return count != null && count > 0;
    }

    public boolean columnExists(String tableName, String columnName) {
        String safeTable = validateIdentifier(tableName);
        String safeColumn = validateIdentifier(columnName);
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns " +
                        "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                Long.class,
                safeTable,
                safeColumn
        );
        return count != null && count > 0;
    }

    public boolean indexOnColumnExists(String tableName, String columnName) {
        String safeTable = validateIdentifier(tableName);
        String safeColumn = validateIdentifier(columnName);
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics " +
                        "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                Long.class,
                safeTable,
                safeColumn
        );
        return count != null && count > 0;
    }

    private static String validateIdentifier(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("非法数据库标识符：" + value);
        }
        return value;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        return message == null || message.isBlank()
                ? cursor.getClass().getSimpleName()
                : message;
    }

    @PreDestroy
    public void close() {
        if (!dataSource.isClosed()) {
            dataSource.close();
        }
    }
}
