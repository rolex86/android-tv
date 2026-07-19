package com.brouken.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class NetworkBufferConfigTest {
    @Test
    public void defaultProfileLeavesMedia3DefaultsUntouched() {
        NetworkBufferConfig config = NetworkBufferConfig.fromPreference("unknown");

        assertTrue(config.usesMedia3Defaults());
        assertEquals(NetworkBufferConfig.PROFILE_DEFAULT, config.profile);
    }

    @Test
    public void largerProfileUsesNinetySecondsAnd256Mib() {
        NetworkBufferConfig config = NetworkBufferConfig.fromPreference(
                NetworkBufferConfig.PROFILE_LARGER);

        assertFalse(config.usesMedia3Defaults());
        assertEquals(90_000, config.minBufferMs);
        assertEquals(90_000, config.maxBufferMs);
        assertEquals(3_000, config.bufferForPlaybackMs);
        assertEquals(5_000, config.bufferForPlaybackAfterRebufferMs);
        assertEquals(256 * 1024 * 1024, config.targetBufferBytes);
    }

    @Test
    public void maximumProfileUses120SecondsAnd384Mib() {
        NetworkBufferConfig config = NetworkBufferConfig.fromPreference(
                NetworkBufferConfig.PROFILE_MAXIMUM);

        assertFalse(config.usesMedia3Defaults());
        assertEquals(120_000, config.minBufferMs);
        assertEquals(120_000, config.maxBufferMs);
        assertEquals(5_000, config.bufferForPlaybackMs);
        assertEquals(10_000, config.bufferForPlaybackAfterRebufferMs);
        assertEquals(384 * 1024 * 1024, config.targetBufferBytes);
    }

    @Test
    public void networkFailuresUsePatientTimeoutsAndRetries() {
        assertEquals(25_000, NetworkBufferConfig.HTTP_CONNECT_TIMEOUT_MS);
        assertEquals(25_000, NetworkBufferConfig.HTTP_READ_TIMEOUT_MS);
        assertEquals(6, NetworkBufferConfig.LOAD_RETRY_COUNT);
    }
}
