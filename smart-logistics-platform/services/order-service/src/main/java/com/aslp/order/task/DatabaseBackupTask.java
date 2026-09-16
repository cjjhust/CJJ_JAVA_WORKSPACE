package com.aslp.order.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.File;

/**
 * M4 — 数据库定时备份（PostgreSQL {@code pg_dump}）。
 *
 * <p>历史 Bug：原实现硬编码 {@code -h localhost} 与 MySQL 时期的 {@code mysqldump} 描述，
 * 容器内无法备份；现已改为可配置（{@code aslp.backup.*}）并支持开关。
 */
@Component
public class DatabaseBackupTask {

    private static final Logger log = LoggerFactory.getLogger(DatabaseBackupTask.class);

    private final String host;
    private final String port;
    private final String database;
    private final String username;
    private final String outputDir;
    private final boolean enabled;

    public DatabaseBackupTask(
            @Value("${aslp.backup.host:localhost}") String host,
            @Value("${aslp.backup.port:5432}") String port,
            @Value("${aslp.backup.database:aslp}") String database,
            @Value("${aslp.backup.username:aslp}") String username,
            @Value("${aslp.backup.output-dir:/tmp}") String outputDir,
            @Value("${aslp.backup.enabled:false}") boolean enabled) {
        this.host = host;
        this.port = port;
        this.database = database;
        this.username = username;
        this.outputDir = outputDir;
        this.enabled = enabled;
    }

    /** 每天凌晨 2 点执行。 */
    @Scheduled(cron = "0 0 2 * * ?")
    public void backupPostgres() {
        if (!enabled) {
            log.info("[数据库备份] 已跳过（aslp.backup.enabled=false）");
            return;
        }
        String target = outputDir + File.separator + "aslp_backup_" + System.currentTimeMillis() + ".sql";
        String[] cmd = {
                "pg_dump", "-h", host, "-p", port, "-U", username, "-d", database, "-f", target
        };
        try {
            Process process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                log.info("[数据库备份] pg_dump 成功 → {}", target);
            } else {
                log.warn("[数据库备份] pg_dump 失败，退出码：{}", exitCode);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[数据库备份] 被中断", e);
        } catch (Exception e) {
            // pg_dump 不存在（如本地未安装客户端）不应影响主服务运行
            log.warn("[数据库备份] 无法执行 pg_dump：{}", e.getMessage());
        }
    }
}
