package com.brouken.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ExternalPlaybackResultPolicyTest {

    @Test
    public void completedPlaybackDelegatesContinuationWithoutOldEpisodeProgress() {
        String endBy = ExternalPlaybackResultPolicy.endBy(true, false);

        assertEquals(ExternalPlaybackResultPolicy.END_BY_PLAYBACK_COMPLETION, endBy);
        assertFalse(ExternalPlaybackResultPolicy.shouldIncludeProgress(endBy));
    }

    @Test
    public void dismissedNextEpisodeCannotTriggerCallerContinuation() {
        String endBy = ExternalPlaybackResultPolicy.endBy(true, true);

        assertEquals(ExternalPlaybackResultPolicy.END_BY_USER, endBy);
        assertTrue(ExternalPlaybackResultPolicy.shouldIncludeProgress(endBy));
    }

    @Test
    public void explicitExitAlwaysReturnsUserWithProgress() {
        String endBy = ExternalPlaybackResultPolicy.endBy(true, true);

        assertEquals(ExternalPlaybackResultPolicy.END_BY_USER, endBy);
        assertTrue(ExternalPlaybackResultPolicy.shouldIncludeProgress(endBy));
        assertEquals(ExternalPlaybackResultPolicy.END_BY_USER,
                ExternalPlaybackResultPolicy.endBy(false, false));
    }
}
