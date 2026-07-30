package com.brouken.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import androidx.media3.common.C;
import androidx.media3.common.Format;

import com.brouken.player.aisubtitles.SubtitleTrackIdentity;

import org.junit.Test;

public class SmartSelectionPolicyTest {

    @Test
    public void audioMediaDefaultRanksAfterExplicitLanguages() {
        String[] languages = {"ces"};

        assertEquals(0, SmartAudioSelector.languageRank("ces", false, languages, true));
        assertEquals(1, SmartAudioSelector.languageRank("eng", true, languages, true));
        assertEquals(-1, SmartAudioSelector.languageRank("deu", false, languages, true));
        assertEquals(-1, SmartAudioSelector.languageRank("eng", true, languages, false));
    }

    @Test
    public void dubbedLabelsIncludeSynchronizedVariants() {
        assertTrue(SmartAudioSelector.isDubbed(0, "Czech synchronized"));
        assertTrue(SmartAudioSelector.isDubbed(0, "Czech synchronised"));
        assertTrue(SmartAudioSelector.isDubbed(C.ROLE_FLAG_DUB, null));
        assertFalse(SmartAudioSelector.isDubbed(0, "Czech"));
    }

    @Test
    public void subtitleMediaDefaultKeepsItsConfiguredPosition() {
        assertEquals(0, SmartSubtitleSelector.languageRank(
                "eng", null, true, new String[]{Prefs.TRACK_DEFAULT}));
        assertEquals(1, SmartSubtitleSelector.languageRank(
                "eng", null, true, new String[]{"ces", Prefs.TRACK_DEFAULT}));
        assertEquals(0, SmartSubtitleSelector.languageRank(
                "ces", null, false, new String[]{"ces", Prefs.TRACK_DEFAULT}));
    }

    @Test
    public void audioAndSubtitleMemoryProvenanceAreIndependent() {
        RememberedTrackStore.Selection selection = new RememberedTrackStore.Selection();
        String originalAudio = selection.audioSignature();
        String originalSubtitle = selection.subtitleSignature();

        selection.audioAutomatic = false;
        assertNotEquals(originalAudio, selection.audioSignature());
        assertEquals(originalSubtitle, selection.subtitleSignature());

        String changedAudio = selection.audioSignature();
        selection.subtitleDisabled = false;
        selection.subtitleAutomatic = true;
        assertEquals(changedAudio, selection.audioSignature());
        assertNotEquals(originalSubtitle, selection.subtitleSignature());
    }

    @Test
    public void subtitleSourcePreferenceRecognizesMedia3PrefixedExternalIds() {
        Format external = new Format.Builder()
                .setId("1:plus-external:opensubtitles-v3:abc")
                .build();
        Format embedded = new Format.Builder().setId("1:embedded:ces").build();

        assertEquals(0, SmartSubtitleSelector.sourceRank(external, "external"));
        assertEquals(1, SmartSubtitleSelector.sourceRank(embedded, "external"));
        assertEquals(1, SmartSubtitleSelector.sourceRank(external, "embedded"));
        assertEquals(0, SmartSubtitleSelector.sourceRank(embedded, "embedded"));
    }

    @Test
    public void likelyRuntimeMatchOutranksUnknownTracks() {
        SubtitleTrackIdentity.resetOpenSubtitlesMatches();
        String likelyId = "plus-external:opensubtitles-v3:likely";
        SubtitleTrackIdentity.registerOpenSubtitlesMatch(
                likelyId, "ces", 0, C.ROLE_FLAG_SUBTITLE, "Czech", 1);

        assertEquals(1, SmartSubtitleSelector.matchRank(
                new Format.Builder().setId(likelyId).build()));
        assertEquals(2, SmartSubtitleSelector.matchRank(
                new Format.Builder().setId("embedded:ces").build()));
    }

    @Test
    public void automaticSubtitleScorePrioritizesLanguageThenSourceThenReleaseMatch() {
        long likely = SmartSubtitleSelector.candidateScore(0, 0, 1, 0);
        long unknown = SmartSubtitleSelector.candidateScore(0, 0, 2, 0);
        long preferredSourceUnknown =
                SmartSubtitleSelector.candidateScore(0, 0, 2, 0);
        long nonPreferredSourceLikely =
                SmartSubtitleSelector.candidateScore(0, 1, 1, 0);
        long nextLanguageLikely =
                SmartSubtitleSelector.candidateScore(1, 0, 1, 0);

        assertTrue(likely < unknown);
        assertTrue(preferredSourceUnknown < nonPreferredSourceLikely);
        assertTrue(nonPreferredSourceLikely < nextLanguageLikely);
    }
}
