package com.library.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ScheduledSyncService {

    @Autowired
    private DataSyncService dataSyncService;

    /**
     * 每天凌晨2点执行定时同步
     */
    @Scheduled(cron = "${library.sync.scheduled-cron:0 0 2 * * ?}")
    public void scheduledSync() {
        dataSyncService.scheduledFullSync();
    }
}