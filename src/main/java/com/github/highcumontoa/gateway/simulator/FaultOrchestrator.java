package com.github.highcumontoa.gateway.simulator;

import com.github.highcumontoa.gateway.model.RegisterType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class FaultOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(FaultOrchestrator.class);

    private final ConcurrentHashMap<String, FaultProfile> faults = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    public FaultProfile addFault(FaultProfile profile) {
        validate(profile);
        if (profile.getAddressEnd() < profile.getAddressStart()) {
            profile.setAddressEnd(profile.getAddressStart());
        }
        if (profile.getId() == null || profile.getId().isBlank()) {
            profile.setId("fault-" + sequence.incrementAndGet()
                    + "-" + UUID.randomUUID().toString().substring(0, 8));
        }
        faults.put(profile.getId(), profile);
        log.warn("FAULT_INJECT id={} type={} registerType={} address=[{},{}] delayMs={} exceptionCode={} "
                        + "fragmentCount={} fragmentDelayMs={}",
                profile.getId(), profile.getType(),
                profile.getRegisterType() == null ? "ANY" : profile.getRegisterType(),
                profile.getAddressStart(), profile.getAddressEnd(),
                profile.getDelayMs(), profile.getExceptionCode(),
                profile.getFragmentCount(), profile.getFragmentDelayMs());
        return profile;
    }

    public boolean removeFault(String id) {
        FaultProfile removed = faults.remove(id);
        if (removed != null) {
            log.warn("FAULT_CLEAR id={} type={}", removed.getId(), removed.getType());
        }
        return removed != null;
    }

    public void clearFaults() {
        int count = faults.size();
        faults.clear();
        log.warn("FAULT_CLEAR_ALL removed={}", count);
    }

    public List<FaultProfile> faults() {
        return List.copyOf(faults.values());
    }

    public Optional<FaultProfile> matchFault(RegisterType registerType, int startAddress, int quantity) {
        int requestEnd = startAddress + Math.max(quantity, 1) - 1;
        for (FaultProfile profile : faults.values()) {
            if (profile.getRegisterType() != null && profile.getRegisterType() != registerType) {
                continue;
            }
            if (requestEnd >= profile.getAddressStart() && startAddress <= profile.getAddressEnd()) {
                return Optional.of(profile);
            }
        }
        return Optional.empty();
    }

    private static void validate(FaultProfile profile) {
        if (profile == null) {
            throw new IllegalArgumentException("Fault profile must not be null");
        }
        if (profile.getType() == null) {
            throw new IllegalArgumentException("Fault type must not be null");
        }
        if (profile.getAddressStart() < 0 || profile.getAddressStart() > 0xFFFF) {
            throw new IllegalArgumentException("addressStart out of range: " + profile.getAddressStart());
        }
        if (profile.getAddressEnd() < 0 || profile.getAddressEnd() > 0xFFFF) {
            throw new IllegalArgumentException("addressEnd out of range: " + profile.getAddressEnd());
        }
        if (profile.getDelayMs() < 0) {
            throw new IllegalArgumentException("delayMs must not be negative");
        }
        if (profile.getExceptionCode() < 0 || profile.getExceptionCode() > 0xFF) {
            throw new IllegalArgumentException("exceptionCode out of range: " + profile.getExceptionCode());
        }
        if (profile.getFragmentCount() < 2) {
            throw new IllegalArgumentException("fragmentCount must be >= 2");
        }
    }
}
