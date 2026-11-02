package com.aslp.order.task;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;

/**
 * M4 — 数据库定时备份（PostgreSQL {@code pg_dump}）。
 *
 * <p>历史 Bug：原实现硬编码 {@code -h localhost} 与 MySQL 时期的 {@code mysqldump} 描述，
 * 容器内无法备份；现已改为可配置（{@code aslp.backup.*}）并支持开关。
 *
 * <p><b>P2-4：从"假备份"改成"能用的备份"</b>，原实现有四个让备份形同虚设的缺陷：
 * <ol>
 *   <li><b>无密码</b>：pg_dump 走 TCP 连 {@code -h} 主机时必然要求认证，
 *       不带 {@code PGPASSWORD}（或 .pgpass）直接挂在 "password authentication failed"；</li>
 *   <li><b>{@code waitFor()} 无超时</b>：pg_dump 挂住（大库 + 网络抖动）会把
 *       调度线程（默认单线程的 {@code TaskScheduler}）占死，拖垮同池的其他定时任务；</li>
 *   <li><b>备份文件落在 {@code /tmp}</b>：容器重建即丢，"备份"只活在一个临时层里；
 *       现在默认目录可配置，compose 里挂到命名卷 {@code /var/backups/aslp}；</li>
 *   <li><b>失败只打 warn</b>：备份连续失败一周没人知道。现在有
 *       {@code aslp_backup_runs_total{outcome}} 计数（success/failed/timeout）
 *       与 {@code aslp_backup_last_success_timestamp_seconds} Gauge（可配 Grafana 告警
 *       "上次成功备份超过 26h 前"）。</li>
 * </ol>
 *
 * <p>轮转策略：只保留最近 {@code aslp.backup.retention} 份（默认 7 份 ≈ 一周），
 * 按文件修改时间删旧 —— 备份比数据更怕"存了个寂寞"，无限增长会先撑爆卷。
 */
@Component
public class DatabaseBackupTask {

    private static final Logger log = LoggerFactory.getLogger(DatabaseBackupTask.class);

    /** P2-4：备份结果计数（outcome = success / failed / timeout）。 */
    public static final String METRIC_BACKUP_RUNS = "aslp.backup.runs";

    /** P2-4：最近一次成功备份的时间戳（秒，可配告警阈值）。 */
    public static final String GAUGE_LAST_SUCCESS = "aslp.backup.last-success-timestamp-seconds";

    private final String host;
    private final String port;
    private final String database;
    private final String username;
    private final String password;
    private final String outputDir;
    private final int timeoutSeconds;
    private final int retention;
    private final boolean enabled;
    private final MeterRegistry meterRegistry;

    /** 最近一次成功备份的 epoch 秒（无成功记录为 0）。 */
    private volatile long lastSuccessEpochSeconds;

    public DatabaseBackupTask(
            @Value("${aslp.backup.host:localhost}") String host,
            @Value("${aslp.backup.port:5432}") String port,
            @Value("${aslp.backup.database:aslp}") String database,
            @Value("${aslp.backup.username:aslp}") String username,
            @Value("${aslp.backup.password:}") String password,
            @Value("${aslp.backup.output-dir:/tmp}") String outputDir,
            @Value("${aslp.backup.timeout-seconds:300}") int timeoutSeconds,
            @Value("${aslp.backup.retention:7}") int retention,
            @Value("${aslp.backup.enabled:false}") boolean enabled,
            MeterRegistry meterRegistry) {
        this.host = host;
        this.port = port;
        this.database = database;
        this.username = username;
        this.password = password;
        this.outputDir = outputDir;
        this.timeoutSeconds = timeoutSeconds;
        this.retention = retention;
        this.enabled = enabled;
        this.meterRegistry = meterRegistry;
        Gauge.builder(GAUGE_LAST_SUCCESS, () -> lastSuccessEpochSeconds).register(meterRegistry);
    }

    /** 每天凌晨 2 点执行。 */
    @Scheduled(cron = "0 0 2 * * ?")
    public void backupPostgres() {
        if (!enabled) {
            log.info("[数据库备份] 已跳过（aslp.backup.enabled=false）");
            return;
        }

        File target;
        try {
            Path dir = Path.of(outputDir);
            Files.createDirectories(dir);
            target = dir.resolve("aslp_backup_" + System.currentTimeMillis() + ".sql").toFile();
        } catch (IOException e) {
            // 目录不可写时连备份目标都建不出来 —— 直接记 failed，不抛（备份失败不能炸主服务）
            log.error("[数据库备份] 备份目录不可写：{}（{}）", outputDir, e.getMessage());
            meterRegistry.counter(METRIC_BACKUP_RUNS, "outcome", "failed").increment();
            return;
        }

        String[] cmd = {
                "pg_dump", "-h", host, "-p", port, "-U", username, "-d", database, "-f", target.getAbsolutePath()
        };
        ProcessBuilder builder = new ProcessBuilder(cmd).redirectErrorStream(true);
        if (password != null && !password.isEmpty()) {
            // 走环境变量而不是命令行参数（-W/psql 交互在容器里没有 tty；
            // 命令行上的密码还会出现在 /proc/*/cmdline 与容器日志里）
            builder.environment().put("PGPASSWORD", password);
        }

        try {
            Process process = builder.start();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                deleteQuietly(target);
                log.error("[数据库备份] pg_dump 超过 {}s 未结束，已强杀", timeoutSeconds);
                meterRegistry.counter(METRIC_BACKUP_RUNS, "outcome", "timeout").increment();
                return;
            }
            int exitCode = process.exitValue();
            if (exitCode == 0 && target.length() > 0) {
                lastSuccessEpochSeconds = System.currentTimeMillis() / 1000;
                log.info("[数据库备份] pg_dump 成功 → {}（{} KB）", target, target.length() / 1024);
                meterRegistry.counter(METRIC_BACKUP_RUNS, "outcome", "success").increment();
                rotateOldBackups();
            } else {
                // 空文件 / 非零退出都算失败：pg_dump 失败时可能留下部分文件，不能留下误导性的"成功产物"
                deleteQuietly(target);
                log.error("[数据库备份] pg_dump 失败，退出码：{}（host={}:{}, db={}）", exitCode, host, port, database);
                meterRegistry.counter(METRIC_BACKUP_RUNS, "outcome", "failed").increment();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            deleteQuietly(target);
            log.warn("[数据库备份] 被中断", e);
            meterRegistry.counter(METRIC_BACKUP_RUNS, "outcome", "failed").increment();
        } catch (Exception e) {
            // pg_dump 不存在（如本地未安装客户端）不应影响主服务运行
            deleteQuietly(target);
            log.warn("[数据库备份] 无法执行 pg_dump：{}", e.getMessage());
            meterRegistry.counter(METRIC_BACKUP_RUNS, "outcome", "failed").increment();
        }
    }

    /** 轮转：只保留最近 retention 份，按修改时间从旧到新删。 */
    private void rotateOldBackups() {
        File dir = new File(outputDir);
        File[] backups = dir.listFiles((d, name) -> name.startsWith("aslp_backup_") && name.endsWith(".sql"));
        if (backups == null || backups.length <= retention) {
            return;
        }
        java.util.Arrays.stream(backups)
                .sorted(Comparator.comparingLong(File::lastModified))
                .limit(backups.length - retention)
                .forEach(old -> {
                    if (old.delete()) {
                        log.info("[数据库备份] 轮转删除旧备份：{}", old.getName());
                    }
                });
    }

    private static void deleteQuietly(File file) {
        if (file != null && file.exists() && !file.delete()) {
            log.warn("[数据库备份] 删除残留备份文件失败：{}", file);
        }
    }
}
