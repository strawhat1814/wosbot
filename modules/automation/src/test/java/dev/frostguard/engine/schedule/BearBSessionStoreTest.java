package dev.frostguard.engine.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class BearBSessionStoreTest {

    @AfterEach
    void clear() {
        BearBSessionStore.clearAllForTests();
    }

    @Test
    void requireReusesSameWindowSessionAndLocksMarchOnce() {
        LocalDateTime activation = LocalDateTime.of(2026, 10, 27, 14, 0);
        LocalDateTime end = activation.plusMinutes(30);
        Instant endInstant = end.toInstant(java.time.ZoneOffset.UTC);

        BearBSession first = BearBSessionStore.require(9L, 2, activation, end, endInstant);
        BearBSession second = BearBSessionStore.require(9L, 2, activation, end, endInstant);
        assertSame(first, second);

        first.lockMarchSeconds(12);
        first.lockMarchSeconds(99);
        assertTrue(first.lockedMarchSeconds().isPresent());
        assertEquals(12, first.lockedMarchSeconds().getAsLong());
    }
}
