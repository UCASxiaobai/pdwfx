package com.pdwfx.stream.batch;

import com.pdwfx.stream.config.StreamProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttitudeStabilityGateTest {

    private static AttitudeStabilityGate newGate() {
        StreamProperties.Batch cfg = new StreamProperties.Batch();
        cfg.setAttitudeRollTurnDeg(5.0);
        cfg.setAttitudeRollHoldSeconds(5.0);
        cfg.setAttitudeMinBatchSeconds(60.0);
        return new AttitudeStabilityGate(cfg);
    }

    @Test
    void staysStableAndAcceptsWhenRollWithinLimit() {
        AttitudeStabilityGate gate = newGate();
        assertFalse(gate.update(0.0, 1000));
        assertTrue(gate.shouldAccept());
        assertFalse(gate.update(4.9, 2000));
        assertEquals(AttitudeStabilityGate.State.STABLE, gate.getState());
        assertTrue(gate.shouldAccept());
    }

    @Test
    void briefHighRollDropsButDoesNotConfirmTurn() {
        AttitudeStabilityGate gate = newGate();
        assertFalse(gate.update(0.0, 0));
        assertFalse(gate.update(6.0, 1000));
        assertEquals(AttitudeStabilityGate.State.BANKING, gate.getState());
        assertFalse(gate.shouldAccept());
        assertFalse(gate.update(6.0, 4000));
        assertEquals(AttitudeStabilityGate.State.BANKING, gate.getState());
        assertFalse(gate.update(2.0, 5000));
        assertEquals(AttitudeStabilityGate.State.STABLE, gate.getState());
        assertTrue(gate.shouldAccept());
    }

    @Test
    void confirmsTurnAfterHighRollHeldFiveSeconds() {
        AttitudeStabilityGate gate = newGate();
        assertFalse(gate.update(1.0, 0));
        assertFalse(gate.update(6.0, 1000));
        assertFalse(gate.update(6.5, 4000));
        assertTrue(gate.update(7.0, 6000));
        assertEquals(AttitudeStabilityGate.State.MANEUVER, gate.getState());
        assertFalse(gate.shouldAccept());
        assertFalse(gate.update(8.0, 8000));
        assertEquals(AttitudeStabilityGate.State.MANEUVER, gate.getState());
    }

    @Test
    void leftBankAlsoConfirmsTurn() {
        AttitudeStabilityGate gate = newGate();
        assertFalse(gate.update(-6.0, 0));
        assertTrue(gate.update(-6.0, 5000));
        assertEquals(AttitudeStabilityGate.State.MANEUVER, gate.getState());
    }

    @Test
    void holdTimerResetsWhenRollDropsBelowLimit() {
        AttitudeStabilityGate gate = newGate();
        assertFalse(gate.update(6.0, 0));
        assertFalse(gate.update(6.0, 4000));
        assertFalse(gate.update(2.0, 4500));
        assertFalse(gate.update(6.0, 5000));
        assertFalse(gate.update(6.0, 9000));
        assertEquals(AttitudeStabilityGate.State.BANKING, gate.getState());
        assertTrue(gate.update(6.0, 10000));
        assertEquals(AttitudeStabilityGate.State.MANEUVER, gate.getState());
    }

    @Test
    void returnsToStableWhenRollDropsAfterTurn() {
        AttitudeStabilityGate gate = newGate();
        assertFalse(gate.update(8.0, 0));
        assertTrue(gate.update(8.0, 5000));
        assertEquals(AttitudeStabilityGate.State.MANEUVER, gate.getState());
        assertFalse(gate.update(1.0, 6000));
        assertEquals(AttitudeStabilityGate.State.STABLE, gate.getState());
        assertTrue(gate.shouldAccept());
    }

    @Test
    void nextTurnAfterResumeIsANewEdge() {
        AttitudeStabilityGate gate = newGate();
        assertFalse(gate.update(8.0, 0));
        assertTrue(gate.update(8.0, 5000));
        assertFalse(gate.update(1.0, 6000));
        assertFalse(gate.update(8.0, 7000));
        assertTrue(gate.update(8.0, 12000));
        assertEquals(AttitudeStabilityGate.State.MANEUVER, gate.getState());
    }

    @Test
    void batchLongEnoughUsesStartToTrigger() {
        AttitudeStabilityGate gate = newGate();
        assertFalse(gate.isBatchLongEnough(0L, 59_999L));
        assertTrue(gate.isBatchLongEnough(0L, 60_000L));
        assertFalse(gate.isBatchLongEnough(10_000L, 9_000L));
    }

    @Test
    void outOfOrderHighRollDoesNotResetConfirmedTurn() {
        AttitudeStabilityGate gate = newGate();
        assertFalse(gate.update(8.0, 0));
        assertTrue(gate.update(8.0, 5000));
        assertEquals(AttitudeStabilityGate.State.MANEUVER, gate.getState());
        assertFalse(gate.update(8.0, 4800));
        assertEquals(AttitudeStabilityGate.State.MANEUVER, gate.getState());
        assertFalse(gate.shouldAccept());
    }
}
