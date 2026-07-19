package com.brouken.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import androidx.media3.common.C;

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
}
