package com.aslp.inventory.service;

/**
 * 补货邮件的发送节流（M2）。
 *
 * <h3>为什么需要一个接口，而不是直接在任务里操作 Redis</h3>
 * 节流的**语义**（谁有资格发、什么时候归还资格）与**实现**（Redis 还是进程内）是两件事。
 * 抽出接口后：
 * <ul>
 *   <li>任务只表达业务意图（"我要发信 → 拿到资格了吗"），单测里塞一个内存实现即可，
 *       不需要为了测节流去起 Redis；</li>
 *   <li>换实现（Redis → 数据库/DynamoDB）不动业务代码。</li>
 * </ul>
 *
 * <h3>为什么必须能跨实例</h3>
 * 原实现用进程内的 {@code AtomicReference} 记"上次发信时间"：**多实例部署时每个实例各记各的**，
 * 10 个副本就是 10 倍邮件量。节流是"全局唯一"的语义，必须落在共享存储上。
 *
 * <h3>三段式用法（对应"失败不计入窗口"的要求）</h3>
 * <pre>
 * if (!throttle.tryAcquire()) { 跳过本轮 }      // 原子占位，多实例并发只有一个能拿到
 * boolean sent = 发信();
 * if (!sent) throttle.release();                // 失败归还资格 → 下一轮立即重试，而不是白等 30 分钟
 * </pre>
 * {@link #markSent()} 供 {@code force=true} 的人工路径使用：人工发过之后，
 * 自动任务不该紧接着再发一封。
 */
public interface MailThrottle {

    /**
     * 原子地尝试占用本次发送资格（并立即开始计价窗口）。
     *
     * @return true = 获得资格（可以发信）；false = 仍在节流窗口内（跳过本轮）
     */
    boolean tryAcquire();

    /** 记录/刷新节流窗口（用于 {@code force=true} 发信成功后，让自动任务接着安静一段时间）。 */
    void markSent();

    /** 归还本次资格（发信失败时调用）：下一轮可立即重试，而不是白等一个完整窗口。 */
    void release();

    /** 距离下次可发信还有多少秒（0 = 现在就可以发）。诊断接口用。 */
    long secondsUntilAllowed();
}
