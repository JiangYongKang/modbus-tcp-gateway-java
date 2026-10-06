package com.github.highcumontoa.gateway.simulator;

import com.github.highcumontoa.gateway.model.RegisterType;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FaultOrchestratorTest {
    private static final Logger log = LoggerFactory.getLogger(FaultOrchestratorTest.class);

    @Test
    void matchesFaultByRegisterTypeAndAddressOverlap() {
        FaultOrchestrator orchestrator = new FaultOrchestrator();
        FaultProfile profile = new FaultProfile();
        profile.setType(FaultType.TIMEOUT);
        profile.setRegisterType(RegisterType.HOLDING);
        profile.setAddressStart(3);
        profile.setAddressEnd(4);
        orchestrator.addFault(profile);

        assertTrue(orchestrator.matchFault(RegisterType.HOLDING, 3, 2).isPresent());
        assertTrue(orchestrator.matchFault(RegisterType.HOLDING, 4, 1).isPresent());
        assertTrue(orchestrator.matchFault(RegisterType.HOLDING, 2, 2).isPresent());
        assertFalse(orchestrator.matchFault(RegisterType.HOLDING, 5, 1).isPresent());
        assertFalse(orchestrator.matchFault(RegisterType.INPUT, 3, 2).isPresent());
        log.info("FAULT_MATCH basis=address range [3,4] overlaps request, registerType must match");
    }

    @Test
    void nullRegisterTypeMatchesAnyBank() {
        FaultOrchestrator orchestrator = new FaultOrchestrator();
        FaultProfile profile = new FaultProfile();
        profile.setType(FaultType.MODBUS_EXCEPTION);
        profile.setAddressStart(0);
        orchestrator.addFault(profile);

        assertTrue(orchestrator.matchFault(RegisterType.HOLDING, 0, 1).isPresent());
        assertTrue(orchestrator.matchFault(RegisterType.INPUT, 0, 1).isPresent());
        assertFalse(orchestrator.matchFault(RegisterType.HOLDING, 1, 1).isPresent());
    }

    @Test
    void assignsIdAndDefaultsAddressEndToStart() {
        FaultOrchestrator orchestrator = new FaultOrchestrator();
        FaultProfile profile = new FaultProfile();
        profile.setType(FaultType.LATENCY);
        profile.setAddressStart(10);
        FaultProfile stored = orchestrator.addFault(profile);

        assertNotNull(stored.getId());
        assertEquals(10, stored.getAddressEnd());
        assertEquals(1, orchestrator.faults().size());
    }

    @Test
    void removeAndClearRestoreNormalBehaviour() {
        FaultOrchestrator orchestrator = new FaultOrchestrator();
        FaultProfile first = new FaultProfile();
        first.setType(FaultType.TIMEOUT);
        orchestrator.addFault(first);
        FaultProfile second = new FaultProfile();
        second.setType(FaultType.CONNECTION_DROP);
        second.setAddressStart(5);
        orchestrator.addFault(second);

        assertTrue(orchestrator.removeFault(first.getId()));
        assertFalse(orchestrator.removeFault(first.getId()));
        assertTrue(orchestrator.matchFault(RegisterType.HOLDING, 5, 1).isPresent());
        assertFalse(orchestrator.matchFault(RegisterType.HOLDING, 0, 1).isPresent());

        orchestrator.clearFaults();
        assertTrue(orchestrator.faults().isEmpty());
        assertFalse(orchestrator.matchFault(RegisterType.HOLDING, 5, 1).isPresent());
        log.info("FAULT_CLEAR basis=after clearFaults no request matches any fault");
    }

    @Test
    void rejectsInvalidProfiles() {
        FaultOrchestrator orchestrator = new FaultOrchestrator();
        assertThrows(IllegalArgumentException.class, () -> orchestrator.addFault(null));
        FaultProfile noType = new FaultProfile();
        assertThrows(IllegalArgumentException.class, () -> orchestrator.addFault(noType));
        FaultProfile badAddress = new FaultProfile();
        badAddress.setType(FaultType.TIMEOUT);
        badAddress.setAddressStart(-1);
        assertThrows(IllegalArgumentException.class, () -> orchestrator.addFault(badAddress));
        FaultProfile badFragment = new FaultProfile();
        badFragment.setType(FaultType.FRAGMENT);
        badFragment.setFragmentCount(1);
        assertThrows(IllegalArgumentException.class, () -> orchestrator.addFault(badFragment));
    }
}
