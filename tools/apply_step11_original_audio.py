from pathlib import Path

PREFS = Path("just-player-plus/app/src/main/java/com/brouken/player/PlusPrefs.java")
SELECTOR = Path("just-player-plus/app/src/main/java/com/brouken/player/SmartAudioSelector.java")
PLAYER = Path("just-player-plus/app/src/main/java/com/brouken/player/PlayerActivity.java")
XML = Path("just-player-plus/app/src/main/res/xml/root_preferences.xml")
STRINGS = Path("just-player-plus/app/src/main/res/values/strings.xml")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


prefs = PREFS.read_text(encoding="utf-8")
prefs = replace_once(
    prefs,
    '    static final String KEY_AUDIO_QUALITY = "audioQualityPreference";\n',
    '    static final String KEY_AUDIO_QUALITY = "audioQualityPreference";\n'
    '    static final String KEY_AUDIO_CONTENT_PREFERENCE = "audioContentPreference";\n',
    "audio content preference key",
)
prefs = replace_once(
    prefs,
    '    String audioQualityPreference;\n',
    '    String audioQualityPreference;\n'
    '    String audioContentPreference;\n',
    "audio content preference field",
)
prefs = replace_once(
    prefs,
    '        audioQualityPreference = preferences.getString(KEY_AUDIO_QUALITY, "best");\n',
    '        audioQualityPreference = preferences.getString(KEY_AUDIO_QUALITY, "best");\n'
    '        audioContentPreference = preferences.getString(\n'
    '                KEY_AUDIO_CONTENT_PREFERENCE, "language");\n',
    "audio content preference load",
)
PREFS.write_text(prefs, encoding="utf-8")

selector = SELECTOR.read_text(encoding="utf-8")
selector = replace_once(
    selector,
    """            boolean ignoreCommentary,
            boolean ignoreAudioDescription,
            boolean preferBestQuality) {
        Candidate best = findBest(
                tracks, preferredLanguages, ignoreCommentary,
                ignoreAudioDescription, preferBestQuality);
""",
    """            boolean ignoreCommentary,
            boolean ignoreAudioDescription,
            boolean preferBestQuality,
            String contentPreference) {
        Candidate best = findBest(
                tracks, preferredLanguages, ignoreCommentary,
                ignoreAudioDescription, preferBestQuality, contentPreference);
""",
    "selector public signature",
)
selector = replace_once(
    selector,
    """            best = findBest(
                    tracks, preferredLanguages, false, false, preferBestQuality);
""",
    """            best = findBest(
                    tracks, preferredLanguages, false, false,
                    preferBestQuality, contentPreference);
""",
    "selector fallback call",
)
selector = replace_once(
    selector,
    """            boolean ignoreCommentary,
            boolean ignoreAudioDescription,
            boolean preferBestQuality) {
        Candidate best = null;
""",
    """            boolean ignoreCommentary,
            boolean ignoreAudioDescription,
            boolean preferBestQuality,
            String contentPreference) {
        Candidate best = null;
""",
    "selector private signature",
)
selector = replace_once(
    selector,
    """                Candidate candidate = new Candidate(
                        trackGroup, trackIndex, format, languageRank,
                        sourceOrder++, preferBestQuality);
""",
    """                Candidate candidate = new Candidate(
                        trackGroup, trackIndex, format, languageRank,
                        contentRank(format, contentPreference),
                        sourceOrder++, preferBestQuality);
""",
    "candidate content rank",
)
selector = replace_once(
    selector,
    """    private static boolean isCommentary(Format format) {
""",
    """    private static int contentRank(Format format, String preference) {
        if (preference == null || "language".equals(preference)) {
            return 0;
        }
        boolean dubbed = isDubbed(format);
        boolean original = isExplicitOriginal(format);
        if ("original".equals(preference)) {
            if (original) {
                return 0;
            }
            return dubbed ? 2 : 1;
        }
        if ("dubbed".equals(preference)) {
            if (dubbed) {
                return 0;
            }
            return original ? 2 : 1;
        }
        return 0;
    }

    private static boolean isDubbed(Format format) {
        if ((format.roleFlags & C.ROLE_FLAG_DUB) != 0) {
            return true;
        }
        String label = normalizeLabel(format.label);
        return containsToken(label, "dub")
                || contains(label, "dubbed")
                || contains(label, "dubbing")
                || contains(label, "cz dub")
                || contains(label, "czech dub")
                || contains(label, "dabing")
                || contains(label, "dabovany")
                || contains(label, "cesky dabing");
    }

    private static boolean isExplicitOriginal(Format format) {
        String label = normalizeLabel(format.label);
        return label.equals("ov")
                || contains(label, "original")
                || contains(label, "original audio")
                || contains(label, "original language")
                || contains(label, "original version")
                || contains(label, "puvodni zneni")
                || contains(label, "puvodni audio");
    }

    private static boolean isCommentary(Format format) {
""",
    "audio content classification",
)
selector = replace_once(
    selector,
    """    private static boolean contains(String label, String phrase) {
        String normalizedPhrase = normalizeLabel(phrase);
        return !normalizedPhrase.isEmpty() && label.contains(normalizedPhrase);
    }
""",
    """    private static boolean contains(String label, String phrase) {
        String normalizedPhrase = normalizeLabel(phrase);
        return !normalizedPhrase.isEmpty() && label.contains(normalizedPhrase);
    }

    private static boolean containsToken(String label, String token) {
        String normalizedToken = normalizeLabel(token);
        return !normalizedToken.isEmpty()
                && (" " + label + " ").contains(" " + normalizedToken + " ");
    }
""",
    "audio label token helper",
)
selector = replace_once(
    selector,
    """                  Format format,
                  int languageRank,
                  int sourceOrder,
                  boolean preferBestQuality) {
""",
    """                  Format format,
                  int languageRank,
                  int contentRank,
                  int sourceOrder,
                  boolean preferBestQuality) {
""",
    "candidate constructor signature",
)
selector = replace_once(
    selector,
    """            long languageScore = (long) languageRank * 1_000_000_000L;
            if (!preferBestQuality) {
                this.score = languageScore + sourceOrder;
""",
    """            long contentScore = (long) contentRank * 10_000_000_000_000L;
            long languageScore = (long) languageRank * 1_000_000_000L;
            if (!preferBestQuality) {
                this.score = contentScore + languageScore + sourceOrder;
""",
    "candidate content score",
)
selector = replace_once(
    selector,
    "            this.score = languageScore + qualityScore + sourceOrder;\n",
    "            this.score = contentScore + languageScore + qualityScore + sourceOrder;\n",
    "candidate final score",
)
SELECTOR.write_text(selector, encoding="utf-8")

player = PLAYER.read_text(encoding="utf-8")
player = replace_once(
    player,
    """        mPlusPrefs.reload();
        if (!mPlusPrefs.ignoreCommentaryAudio && !mPlusPrefs.ignoreAudioDescription) {
            return;
        }

        TrackSelectionOverride audioOverride = SmartAudioSelector.findPreferredAudio(
""",
    """        mPlusPrefs.reload();

        TrackSelectionOverride audioOverride = SmartAudioSelector.findPreferredAudio(
""",
    "remove obsolete selector guard",
)
player = replace_once(
    player,
    """                mPlusPrefs.ignoreAudioDescription,
                "best".equals(mPlusPrefs.audioQualityPreference));
""",
    """                mPlusPrefs.ignoreAudioDescription,
                "best".equals(mPlusPrefs.audioQualityPreference),
                mPlusPrefs.audioContentPreference);
""",
    "player selector call",
)
PLAYER.write_text(player, encoding="utf-8")

xml = XML.read_text(encoding="utf-8")
xml = replace_once(
    xml,
    """        <ListPreference
            app:key="audioQualityPreference"
""",
    """        <ListPreference
            app:key="audioContentPreference"
            app:defaultValue="language"
            app:entries="@array/plus_audio_content_preference_entries"
            app:entryValues="@array/plus_audio_content_preference_values"
            app:title="@string/pref_audio_content_preference"
            app:useSimpleSummaryProvider="true" />

        <ListPreference
            app:key="audioQualityPreference"
""",
    "audio preference XML",
)
XML.write_text(xml, encoding="utf-8")

strings = STRINGS.read_text(encoding="utf-8")
marker = "</resources>"
addition = """    <string name="pref_audio_content_preference">Original audio or dubbing</string>
    <string name="pref_audio_content_language_order">Follow language order</string>
    <string name="pref_audio_content_original">Prefer explicitly marked original audio</string>
    <string name="pref_audio_content_dubbed">Prefer explicitly marked dubbed audio</string>
</resources>"""
if strings.count(marker) != 1:
    raise RuntimeError(f"strings closing tag: expected one match, found {strings.count(marker)}")
STRINGS.write_text(strings.replace(marker, addition, 1), encoding="utf-8")
