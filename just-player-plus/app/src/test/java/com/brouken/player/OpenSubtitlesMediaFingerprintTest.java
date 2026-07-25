package com.brouken.player;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class OpenSubtitlesMediaFingerprintTest {
    @Test
    public void computesUnsignedLittleEndianMovieHash() {
        byte[] head = new byte[OpenSubtitlesMediaFingerprint.HASH_CHUNK_BYTES];
        byte[] tail = new byte[OpenSubtitlesMediaFingerprint.HASH_CHUNK_BYTES];
        head[0] = 1;
        tail[0] = 2;

        assertEquals(
                "0000000000020003",
                OpenSubtitlesMediaFingerprint.computeHash(131072L, head, tail));
    }

    @Test
    public void parsesStandardContentRange() {
        assertEquals(75161993216L, OpenSubtitlesMediaFingerprint.contentRangeTotal(
                "bytes 0-65535/75161993216"));
        assertEquals(75161927680L, OpenSubtitlesMediaFingerprint.contentRangeStart(
                "bytes 75161927680-75161993215/75161993216"));
    }

    @Test
    public void rejectsUnknownContentRangeValues() {
        assertEquals(-1L, OpenSubtitlesMediaFingerprint.contentRangeTotal("bytes 0-1/*"));
        assertEquals(-1L, OpenSubtitlesMediaFingerprint.contentRangeStart(null));
    }
}
