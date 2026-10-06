package com.github.highcumontoa.gateway.model;

import java.util.List;

public record GatewayHealthSnapshot(
        boolean healthy,
        String connectionState,
        long totalPoints,
        long healthyPoints,
        List<PointHealthSnapshot> points) {
}
