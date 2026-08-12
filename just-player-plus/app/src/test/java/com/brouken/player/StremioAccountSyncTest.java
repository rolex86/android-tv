package com.brouken.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

public class StremioAccountSyncTest {
    @Test
    public void officialWatchedFieldRoundTripsAndPreservesExistingEpisodes() throws Exception {
        List<String> ids = episodeIds("tt2934286", 9);
        String watched = "tt2934286:1:5:5:eJyTZwAAAEAAIA==";

        boolean[] before = StremioWatchedBitfield.decode(watched, ids);
        for (int index = 0; index < ids.size(); index++) {
            assertEquals(index < 5, before[index]);
        }

        String updated = StremioWatchedBitfield.setWatched(
                watched, ids, "tt2934286:1:6", true);
        boolean[] after = StremioWatchedBitfield.decode(updated, ids);
        for (int index = 0; index < ids.size(); index++) {
            assertEquals(index < 6, after[index]);
        }
    }

    @Test
    public void anchorOffsetSurvivesAChangedVideoPrefix() throws Exception {
        List<String> oldIds = episodeIds("tt0", 4);
        boolean[] flags = {false, true, false, true};
        String serialized = StremioWatchedBitfield.encode(flags, oldIds);

        List<String> shifted = new ArrayList<>();
        shifted.add("tt0:0:1");
        shifted.addAll(oldIds);
        boolean[] decoded = StremioWatchedBitfield.decode(serialized, shifted);

        assertFalse(decoded[0]);
        assertFalse(decoded[1]);
        assertTrue(decoded[2]);
        assertFalse(decoded[3]);
        assertTrue(decoded[4]);
    }

    @Test
    public void partialCheckpointChangesOnlyResumeState() throws Exception {
        List<String> ids = episodeIds("tt10986410", 3);
        JSONObject before = libraryItem(
                "tt10986410:1:1:1:eJxjBAAAAgAC",
                "tt10986410:1:1",
                120_000L,
                300_000L,
                1);
        StremioLibraryItemPatch.Checkpoint checkpoint =
                new StremioLibraryItemPatch.Checkpoint(
                        StremioEpisodeId.parse("tt10986410:1:2"),
                        300_000L,
                        1_800_000L,
                        false,
                        1_700_000_000_000L);

        JSONObject updated = StremioLibraryItemPatch.build(
                before,
                checkpoint,
                ids,
                "2023-11-14T22:13:20.000Z",
                "2023-11-14T22:13:21.000Z");
        JSONObject state = updated.getJSONObject("state");

        assertEquals("tt10986410:1:2", state.getString("video_id"));
        assertEquals(300_000L, state.getLong("timeOffset"));
        assertEquals(1_800_000L, state.getLong("duration"));
        assertEquals(0L, state.getLong("timeWatched"));
        assertEquals(420_000L, state.getLong("overallTimeWatched"));
        assertEquals(0, state.getInt("flaggedWatched"));
        assertEquals(1, state.getInt("timesWatched"));
        assertEquals("tt10986410:1:1:1:eJxjBAAAAgAC", state.getString("watched"));

        Set<String> changed = StremioLibraryItemPatch.changedPaths(before, updated);
        assertEquals(new java.util.HashSet<>(Arrays.asList(
                "_mtime",
                "state.duration",
                "state.flaggedWatched",
                "state.lastWatched",
                "state.overallTimeWatched",
                "state.timeOffset",
                "state.timeWatched",
                "state.video_id")), changed);
    }

    @Test
    public void completedCheckpointAddsOnlyTheTargetWatchedBit() throws Exception {
        List<String> ids = episodeIds("tt10986410", 3);
        boolean[] watched = {true, false, false};
        JSONObject before = libraryItem(
                StremioWatchedBitfield.encode(watched, ids),
                "tt10986410:1:2",
                0L,
                300_000L,
                1);
        StremioLibraryItemPatch.Checkpoint checkpoint =
                new StremioLibraryItemPatch.Checkpoint(
                        StremioEpisodeId.parse("tt10986410:1:2"),
                        1_790_000L,
                        1_800_000L,
                        true,
                        1_700_000_000_000L);

        JSONObject updated = StremioLibraryItemPatch.build(
                before,
                checkpoint,
                ids,
                "2023-11-14T22:13:20.000Z",
                "2023-11-14T22:13:21.000Z");
        JSONObject state = updated.getJSONObject("state");
        boolean[] flags = StremioWatchedBitfield.decode(state.getString("watched"), ids);

        assertTrue(flags[0]);
        assertTrue(flags[1]);
        assertFalse(flags[2]);
        assertEquals(0L, state.getLong("timeOffset"));
        assertEquals(1, state.getInt("flaggedWatched"));
        assertEquals(2, state.getInt("timesWatched"));
        assertTrue(StremioLibraryItemPatch.verify(
                before, updated, new JSONObject(updated.toString()), checkpoint, ids));
    }

    @Test
    public void newerServerProgressAcceptsRfc3339FractionsAndOffsets() throws Exception {
        JSONObject item = new JSONObject().put("state", new JSONObject()
                .put("lastWatched", "2023-11-14T23:13:20.123456+01:00"));

        assertTrue(StremioAccountClient.isNewerPlaybackState(
                item, 1_700_000_000_122L));
        assertFalse(StremioAccountClient.isNewerPlaybackState(
                item, 1_700_000_000_123L));
    }

    private static List<String> episodeIds(String metaId, int count) {
        List<String> ids = new ArrayList<>();
        for (int episode = 1; episode <= count; episode++) {
            ids.add(metaId + ":1:" + episode);
        }
        return ids;
    }

    private static JSONObject libraryItem(
            String watched,
            String videoId,
            long timeWatched,
            long overallTimeWatched,
            int timesWatched) throws Exception {
        return new JSONObject()
                .put("_id", "tt10986410")
                .put("name", "Ted Lasso")
                .put("type", "series")
                .put("removed", false)
                .put("temp", false)
                .put("_mtime", "2023-11-14T22:00:00.000Z")
                .put("state", new JSONObject()
                        .put("lastWatched", "2023-11-14T22:00:00.000Z")
                        .put("timeWatched", timeWatched)
                        .put("timeOffset", 100_000L)
                        .put("overallTimeWatched", overallTimeWatched)
                        .put("timesWatched", timesWatched)
                        .put("flaggedWatched", 1)
                        .put("duration", 1_800_000L)
                        .put("video_id", videoId)
                        .put("watched", watched)
                        .put("noNotif", false))
                .put("behaviorHints", new JSONObject())
                .put("aliases", new JSONArray());
    }
}
