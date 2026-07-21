#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PLAYER = ROOT / "just-player-plus/app/src/main/java/com/brouken/player/PlayerActivity.java"
CLIENT = ROOT / "just-player-plus/app/src/main/java/com/brouken/player/OpenSubtitlesV3Client.java"
TEST = ROOT / "just-player-plus/app/src/test/java/com/brouken/player/OpenSubtitlesV3ClientTest.java"
AUDIT = ROOT / "tools/check_plus_preferences.py"
GRADLE = ROOT / "just-player-plus/app/build.gradle"
MARKER = ROOT / ".patches/run-opensubtitles-v3"
BUILD_WORKFLOW = ROOT / ".github/workflows/build-just-player-plus.yml"

def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one anchor, found {count}")
    return text.replace(old, new, 1)

CLIENT.write_text((ROOT / ".patches/OpenSubtitlesV3Client.java").read_text(encoding="utf-8"), encoding="utf-8")
TEST.write_text((ROOT / ".patches/OpenSubtitlesV3ClientTest.java").read_text(encoding="utf-8"), encoding="utf-8")
METHODS = (ROOT / ".patches/methods.javafrag").read_text(encoding="utf-8")

player = PLAYER.read_text(encoding="utf-8")
player = replace_once(player,
    "    private NextEpisodeInfo nextEpisodeInfo;\n    private int nextEpisodeSession;",
    "    private NextEpisodeInfo nextEpisodeInfo;\n"
    "    private OpenSubtitlesV3Client openSubtitlesV3Client;\n"
    "    private RememberedTrackStore.Selection openSubtitlesSelectionBeforeAttach;\n"
    "    private boolean openSubtitlesAttachPending;\n"
    "    private boolean openSubtitlesAudioExplicitBeforeAttach;\n"
    "    private boolean openSubtitlesSubtitleExplicitBeforeAttach;\n"
    "    private boolean openSubtitlesPersistAudioBeforeAttach;\n"
    "    private boolean openSubtitlesPersistSubtitleBeforeAttach;\n"
    "    private int nextEpisodeSession;", "OpenSubtitles fields")
player = replace_once(player,
    "        if (nextEpisodeHttpClient != null\n"
    "                && nextEpisodeMetadataResolver != null\n"
    "                && nextEpisodeOverlay != null) {",
    "        if (nextEpisodeHttpClient != null\n"
    "                && nextEpisodeMetadataResolver != null\n"
    "                && nextEpisodeOverlay != null\n"
    "                && openSubtitlesV3Client != null) {", "feature initialization guard")
player = replace_once(player,
    "        nextEpisodeMetadataResolver = new NextEpisodeMetadataResolver(nextEpisodeHttpClient);\n"
    "        nextEpisodeOverlay = new NextEpisodeOverlay(",
    "        nextEpisodeMetadataResolver = new NextEpisodeMetadataResolver(nextEpisodeHttpClient);\n"
    "        openSubtitlesV3Client = new OpenSubtitlesV3Client(nextEpisodeHttpClient);\n"
    "        nextEpisodeOverlay = new NextEpisodeOverlay(", "client initialization")
player = replace_once(player,
    "        nextEpisodeMetadataResolver = null;\n        if (nextEpisodeHttpClient != null) {",
    "        if (openSubtitlesV3Client != null) {\n"
    "            openSubtitlesV3Client.release();\n"
    "            openSubtitlesV3Client = null;\n"
    "        }\n"
    "        clearOpenSubtitlesAttachState();\n"
    "        nextEpisodeMetadataResolver = null;\n"
    "        if (nextEpisodeHttpClient != null) {", "client release")
player = replace_once(player,
    "    private void resetNextEpisodeSession() {\n"
    "        nextEpisodeSession++;\n"
    "        cancelNextEpisodePopupMessage();",
    "    private void resetNextEpisodeSession() {\n"
    "        nextEpisodeSession++;\n"
    "        if (openSubtitlesV3Client != null) {\n"
    "            openSubtitlesV3Client.cancel();\n"
    "        }\n"
    "        clearOpenSubtitlesAttachState();\n"
    "        cancelNextEpisodePopupMessage();", "session reset")
player = replace_once(player,
    "            externalDiagnostics.recordStremioConnector(\n"
    "                    \"metadata_content\", content.type + \"/\" + content.id);\n"
    "            if (!content.isSeries()) {",
    "            externalDiagnostics.recordStremioConnector(\n"
    "                    \"metadata_content\", content.type + \"/\" + content.id);\n"
    "            requestOpenSubtitlesV3(session, content);\n"
    "            if (!content.isSeries()) {", "subtitle request start")
player = replace_once(player,
    "    private void acceptMovieTitle(int session, String movieId, @Nullable String title) {",
    METHODS + "    private void acceptMovieTitle(int session, String movieId, @Nullable String title) {",
    "OpenSubtitles methods")
player = replace_once(player,
    "            if (apiAccess && apiSubs.size() > 0) {",
    "            if (isExternalPlayerLaunch() && apiSubs.size() > 0) {", "external subtitle media item")
player = replace_once(player,
    "        public void onTracksChanged(Tracks tracks) {\n            if (aiSubtitleController != null) {",
    "        public void onTracksChanged(Tracks tracks) {\n"
    "            finishOpenSubtitlesAttach(tracks);\n"
    "            if (aiSubtitleController != null) {", "track refresh hook")
player = replace_once(player,
    "    public void initializePlayer() {\n        releaseAiSubtitleController();",
    "    public void initializePlayer() {\n"
    "        releaseAiSubtitleController();\n"
    "        clearOpenSubtitlesAttachState();", "player initialization reset")
PLAYER.write_text(player, encoding="utf-8")

audit = AUDIT.read_text(encoding="utf-8")
audit = replace_once(audit,
    "AI_TEST_PATH = (\n"
    "    ROOT / \"just-player-plus\" / \"app\" / \"src\" / \"test\" / \"java\"\n"
    "    / \"com\" / \"brouken\" / \"player\" / \"aisubtitles\" / \"AiSubtitlePolicyTest.java\"\n"
    ")\n",
    "AI_TEST_PATH = (\n"
    "    ROOT / \"just-player-plus\" / \"app\" / \"src\" / \"test\" / \"java\"\n"
    "    / \"com\" / \"brouken\" / \"player\" / \"aisubtitles\" / \"AiSubtitlePolicyTest.java\"\n"
    ")\nOPEN_SUBTITLES_TEST_PATH = TEST_PATH.with_name(\"OpenSubtitlesV3ClientTest.java\")\n",
    "audit test path")
audit = replace_once(audit,
    "    \"cancelRemoteJob(jobId)\",\n)",
    "    \"cancelRemoteJob(jobId)\",\n"
    "    \"requestOpenSubtitlesV3(session, content);\",\n"
    "    \"finishOpenSubtitlesAttach(tracks);\",\n"
    "    \"OpenSubtitlesV3Client.TRACK_ID_PREFIX\",\n)", "audit runtime anchors")
audit = replace_once(audit,
    "if preferences_xml.count('app:key=\"aiSubtitleApiToken\"') != 1:\n",
    "if not OPEN_SUBTITLES_TEST_PATH.exists():\n"
    "    errors.append(\"OpenSubtitles v3 regression tests are missing\")\n"
    "else:\n"
    "    opensubtitles_tests = OPEN_SUBTITLES_TEST_PATH.read_text(encoding=\"utf-8\")\n"
    "    for test_name in (\n"
    "        \"acceptsOnlyImdbMovieAndEpisodeIds\",\n"
    "        \"normalizesStremioAndIsoLanguageVariants\",\n"
    "        \"filtersDeduplicatesAndCapsPreferredLanguages\",\n"
    "        \"preservesForcedAndSdhHintsForSmartSelection\",\n"
    "    ):\n"
    "        if test_name not in opensubtitles_tests:\n"
    "            errors.append(f\"Missing OpenSubtitles v3 regression test: {test_name}\")\n\n"
    "if preferences_xml.count('app:key=\"aiSubtitleApiToken\"') != 1:\n", "audit test checks")
AUDIT.write_text(audit, encoding="utf-8")

gradle = GRADLE.read_text(encoding="utf-8")
gradle = replace_once(gradle, "        versionCode 215", "        versionCode 216", "version code")
GRADLE.write_text(gradle, encoding="utf-8")

if MARKER.exists():
    MARKER.unlink()

workflow = BUILD_WORKFLOW.read_text(encoding="utf-8")
start_marker = "      # BEGIN ONE-SHOT OPENSUBTITLES V3 PATCH\n"
end_marker = "      # END ONE-SHOT OPENSUBTITLES V3 PATCH\n"
start = workflow.find(start_marker)
end = workflow.find(end_marker)
if start < 0 or end < start:
    raise SystemExit("One-shot workflow block is missing")
end += len(end_marker)
workflow = workflow[:start] + workflow[end:]
workflow = workflow.replace("permissions:\n  contents: write", "permissions:\n  contents: read", 1)
BUILD_WORKFLOW.write_text(workflow, encoding="utf-8")
