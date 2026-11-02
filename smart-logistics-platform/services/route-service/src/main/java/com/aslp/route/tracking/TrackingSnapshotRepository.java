package com.aslp.route.tracking;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TrackingSnapshotRepository extends JpaRepository<TrackingSnapshotRecord, String> {

    /** 扫描候选：所有非终态运单（"卡住"判定在内存里按 stuckDays 精判，量级可接受）。 */
    List<TrackingSnapshotRecord> findByTerminalFalse();
}
