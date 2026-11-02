package com.aslp.report.client;

import com.aslp.report.dto.InventoryWarningSnapshot;

/**
 * 库存预警数据源抽象（单体共存改造，P2-7），契约同 {@link OrderStatsSource}。
 *
 * <p>低库存判定口径只有一处实现（inventory-service），报表在两种拓扑下
 * 都只是「搬运 + 整形」，不重复造阈值逻辑。
 */
public interface InventoryWarningSource {

    /** 数据源标识：会原样出现在报表响应的 {@code unavailable} 列表里。 */
    String getSource();

    /**
     * 拉取库存预警状态。
     *
     * @throws ReportSourceUnavailableException 数据源不可用
     */
    InventoryWarningSnapshot fetch();
}
