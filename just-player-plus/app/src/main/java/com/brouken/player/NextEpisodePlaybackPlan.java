package com.brouken.player;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Stable, cheap ordering for the direct streams that may play one exact next episode. */
final class NextEpisodePlaybackPlan {
    private NextEpisodePlaybackPlan() {
    }

    static List<StremioConnectorStore.StreamFallback> order(
            List<StremioConnectorStore.StreamFallback> candidates,
            @Nullable StremioConnectorStore.StreamFallback current,
            @Nullable String requiredAudioLanguage) {
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }
        List<RankedCandidate> ranked = new ArrayList<>();
        for (int index = 0; index < candidates.size(); index++) {
            StremioConnectorStore.StreamFallback candidate = candidates.get(index);
            ranked.add(new RankedCandidate(
                    candidate,
                    similarityRank(current, candidate),
                    languageHintRank(candidate.languages, requiredAudioLanguage),
                    index));
        }
        Collections.sort(ranked, Comparator
                .comparingInt((RankedCandidate value) -> value.similarityRank)
                .thenComparingInt(value -> value.languageHintRank)
                .thenComparingInt(value -> value.connectorOrder));
        List<StremioConnectorStore.StreamFallback> result = new ArrayList<>();
        for (RankedCandidate value : ranked) {
            result.add(value.candidate);
        }
        return result;
    }

    static int similarityRank(
            @Nullable StremioConnectorStore.StreamFallback current,
            StremioConnectorStore.StreamFallback candidate) {
        if (current == null) {
            return 3;
        }
        boolean sameSource = sameKnown(current.sourceId, candidate.sourceId);
        boolean sameQuality = sameKnown(current.quality, candidate.quality);
        if (sameSource && sameQuality) {
            return 0;
        }
        if (sameSource) {
            return 1;
        }
        if (sameQuality) {
            return 2;
        }
        return 3;
    }

    static int languageHintRank(List<String> hints, @Nullable String requiredLanguage) {
        String required = NextEpisodeTrackContract.normalizeLanguage(requiredLanguage);
        if (required.isEmpty() || hints == null || hints.isEmpty()) {
            return 1;
        }
        for (String hint : hints) {
            if (required.equals(NextEpisodeTrackContract.normalizeLanguage(hint))) {
                return 0;
            }
        }
        // A conflicting filename hint only lowers priority. Real Media3 tracks remain the final
        // authority, so an inaccurately labelled release is never discarded here.
        return 2;
    }

    private static boolean sameKnown(@Nullable String first, @Nullable String second) {
        return first != null && second != null
                && !first.isEmpty() && first.equalsIgnoreCase(second);
    }

    private static final class RankedCandidate {
        final StremioConnectorStore.StreamFallback candidate;
        final int similarityRank;
        final int languageHintRank;
        final int connectorOrder;

        RankedCandidate(StremioConnectorStore.StreamFallback candidate,
                        int similarityRank,
                        int languageHintRank,
                        int connectorOrder) {
            this.candidate = candidate;
            this.similarityRank = similarityRank;
            this.languageHintRank = languageHintRank;
            this.connectorOrder = connectorOrder;
        }
    }
}
