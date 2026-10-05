package com.github.highcumontoa.gateway.fault;

import com.github.highcumontoa.gateway.model.RegisterType;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FaultRegistryTest {
    private static final Logger log = LoggerFactory.getLogger(FaultRegistryTest.class);

    @Test
    void claimsMatchingFaultAndRevokeRestoresNormal() {
        FaultRegistry registry = new FaultRegistry();
        FaultSpec spec = registry.inject(new FaultSpec(null, RegisterType.HOLDING, 3, 2,
                FaultType.TIMEOUT, 0, 0, 1, 0, FaultSpec.UNLIMITED));
        log.info("FAULT_RULE id={} type={} range=[{},{}) basis=injected via registry",
                spec.id(), spec.type(), spec.address(), spec.address() + spec.span());

        assertTrue(registry.claim(RegisterType.HOLDING, 3, 1).isPresent());
        assertTrue(registry.claim(RegisterType.HOLDING, 4, 2).isPresent(),
                "request overlapping the rule range must match");
        assertFalse(registry.claim(RegisterType.HOLDING, 0, 2).isPresent(),
                "disjoint range must not match");
        assertFalse(registry.claim(RegisterType.INPUT, 3, 1).isPresent(),
                "different register bank must not match");

        assertTrue(registry.revoke(spec.id()));
        Optional<FaultSpec> after = registry.claim(RegisterType.HOLDING, 3, 1);
        assertTrue(after.isEmpty(), "revoked rule must not match again");
        log.info("FAULT_RULE id={} revoked basis=registry empty after revoke", spec.id());
    }

    @Test
    void consumesLimitedTimesThenStopsMatching() {
        FaultRegistry registry = new FaultRegistry();
        FaultSpec spec = registry.inject(new FaultSpec(null, null, 0, 64,
                FaultType.MODBUS_EXCEPTION, 0, 0x02, 1, 0, 2));

        assertTrue(registry.claim(RegisterType.HOLDING, 0, 1).isPresent());
        assertTrue(registry.claim(RegisterType.INPUT, 10, 4).isPresent(),
                "null registerType matches any bank");
        assertFalse(registry.claim(RegisterType.HOLDING, 0, 1).isPresent(),
                "times=2 must be exhausted after two claims");
        log.info("FAULT_RULE id={} exhausted basis=times=2 consumed", spec.id());
    }

    @Test
    void clearRemovesAllFaults() {
        FaultRegistry registry = new FaultRegistry();
        registry.inject(new FaultSpec(null, RegisterType.HOLDING, 0, 1,
                FaultType.TIMEOUT, 0, 0, 1, 0, FaultSpec.UNLIMITED));
        registry.inject(new FaultSpec(null, RegisterType.INPUT, 0, 1,
                FaultType.CONNECTION_DROP, 0, 0, 1, 0, FaultSpec.UNLIMITED));
        assertEquals(2, registry.list().size());

        registry.clear();
        assertEquals(0, registry.list().size());
        assertTrue(registry.claim(RegisterType.HOLDING, 0, 1).isEmpty());
    }
}
