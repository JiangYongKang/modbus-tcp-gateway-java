package com.github.highcumontoa.gateway.web;

import com.github.highcumontoa.gateway.model.GatewayHealthSnapshot;
import com.github.highcumontoa.gateway.model.GatewayStatusSnapshot;
import com.github.highcumontoa.gateway.model.PointHealthSnapshot;
import com.github.highcumontoa.gateway.model.ReadingSnapshot;
import com.github.highcumontoa.gateway.runtime.ReadingStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.NOT_FOUND;

import java.util.List;

@RestController
@RequestMapping("/api")
public class ReadingController {
    private final ReadingStore store;

    public ReadingController(ReadingStore store) {
        this.store = store;
    }

    @GetMapping("/readings")
    public List<ReadingSnapshot> readings() {
        return store.snapshots();
    }

    @GetMapping("/readings/{pointName}")
    public ReadingSnapshot reading(@PathVariable String pointName) {
        ReadingSnapshot snapshot = store.snapshot(pointName);
        if (snapshot == null) {
            throw new ResponseStatusException(NOT_FOUND, "Unknown point: " + pointName);
        }
        return snapshot;
    }

    @GetMapping("/status")
    public GatewayStatusSnapshot status() {
        return store.status();
    }

    @GetMapping("/readings/{pointName}/health")
    public PointHealthSnapshot pointHealth(@PathVariable String pointName) {
        PointHealthSnapshot snapshot = store.healthSnapshot(pointName);
        if (snapshot == null) {
            throw new ResponseStatusException(NOT_FOUND, "Unknown point: " + pointName);
        }
        return snapshot;
    }

    @GetMapping("/health")
    public GatewayHealthSnapshot health() {
        return store.health();
    }
}
