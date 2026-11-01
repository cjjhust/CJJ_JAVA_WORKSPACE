package com.gdpr.audit.enums;

/**
 * 审计操作类型。
 */
public enum AuditAction {
    /** 查看 */
    VIEW,
    /** 创建 */
    CREATE,
    /** 修改 */
    MODIFY,
    /** 删除 */
    DELETE,
    /** 下载 */
    DOWNLOAD,
    /** 导出 */
    EXPORT,
    /** 登录 */
    LOGIN,
    /** 登出 */
    LOGOUT
}
