package com.library.controller;

import com.library.service.DataSyncService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/sync")
public class SyncController {

    @Autowired
    private DataSyncService dataSyncService;

    /**
     * 手动触发全量同步
     */
    @PostMapping("/full")
    public ResponseEntity<String> triggerFullSync() {
        dataSyncService.scheduledFullSync();
        return ResponseEntity.ok("全量同步已触发");
    }
}