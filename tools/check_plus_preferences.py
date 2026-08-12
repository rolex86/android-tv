#!/usr/bin/env python3
"""Fail CI when a JustPlayer Plus setting becomes UI-only or loses its runtime hook."""

from pathlib import Path
import hashlib
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "just-player-plus" / "app" / "src" / "main"
PREFS_PATH = APP / "java" / "com" / "brouken" / "player" / "PlusPrefs.java"
PLAYER_PATH = APP / "java" / "com" / "brouken" / "player" / "PlayerActivity.java"
XML_PATH = APP / "res" / "xml" / "root_preferences.xml"
OFFSET_PATH = APP / "java" / "com" / "brouken" / "player" / "OffsetSubtitleParserFactory.java"
TEST_PATH = (
    ROOT / "just-player-plus" / "app" / "src" / "test" / "java"
    / "com" / "brouken" / "player" / "SmartSelectionPolicyTest.java"
)
OFFSET_TEST_PATH = TEST_PATH.with_name("OffsetSubtitleParserFactoryTest.java")
END_TIME_TEST_PATH = TEST_PATH.with_name("PlaybackEndTimeTest.java")
STREMIO_TEST_PATH = TEST_PATH.with_name("StremioNextEpisodeTest.java")
STREMIO_ACCOUNT_SYNC_TEST_PATH = TEST_PATH.with_name("StremioAccountSyncTest.java")
STREMIO_ACCOUNT_SYNC_COORDINATOR_PATH = (
    APP / "java" / "com" / "brouken" / "player"
    / "StremioAccountSyncCoordinator.java"
)
STREMIO_ACCOUNT_SYNC_WORKER_PATH = STREMIO_ACCOUNT_SYNC_COORDINATOR_PATH.with_name(
    "StremioAccountSyncWorker.java"
)
STREMIO_BOOT_RECEIVER_PATH = STREMIO_ACCOUNT_SYNC_COORDINATOR_PATH.with_name(
    "StremioConnectorBootReceiver.java"
)
APPLICATION_PATH = STREMIO_ACCOUNT_SYNC_COORDINATOR_PATH.with_name(
    "JustPlayerPlusApplication.java"
)
MANIFEST_PATH = APP / "AndroidManifest.xml"
APP_BUILD_PATH = ROOT / "just-player-plus" / "app" / "build.gradle"
CHANGELOG_PATH = ROOT / "just-player-plus" / "PLUS_CHANGELOG.md"
STREMIO_STREAM_TEST_PATH = TEST_PATH.with_name("StremioStreamPipelineTest.java")
EXTERNAL_RESULT_TEST_PATH = TEST_PATH.with_name("ExternalPlaybackResultPolicyTest.java")
STREMIO_AGGREGATION_PREFS_PATH = APP / "java" / "com" / "brouken" / "player" / "StremioAggregationPreferences.java"
AI_TEST_PATH = (
    ROOT / "just-player-plus" / "app" / "src" / "test" / "java"
    / "com" / "brouken" / "player" / "aisubtitles" / "AiSubtitlePolicyTest.java"
)
OPEN_SUBTITLES_TEST_PATH = TEST_PATH.with_name("OpenSubtitlesV3ClientTest.java")
OPEN_SUBTITLES_REST_TEST_PATH = TEST_PATH.with_name("OpenSubtitlesRestClientTest.java")

plus_prefs = PREFS_PATH.read_text(encoding="utf-8")
player = PLAYER_PATH.read_text(encoding="utf-8")
preferences_xml = XML_PATH.read_text(encoding="utf-8")
offset_parser = OFFSET_PATH.read_text(encoding="utf-8")
external_java = "\n".join(
    path.read_text(encoding="utf-8")
    for path in sorted((APP / "java").rglob("*.java"))
    if path != PREFS_PATH
)

key_pattern = re.compile(r'static final String (KEY_[A-Z0-9_]+) = "([^"]+)";')
keys = dict(key_pattern.findall(plus_prefs))

runtime_anchors = {
    "KEY_AUDIO_LANGUAGE_PRIMARY": "mPlusPrefs.getPreferredAudioLanguages()",
    "KEY_AUDIO_LANGUAGE_SECONDARY": "mPlusPrefs.getPreferredAudioLanguages()",
    "KEY_AUDIO_LANGUAGE_TERTIARY": "mPlusPrefs.getPreferredAudioLanguages()",
    "KEY_AUDIO_QUALITY": "mPlusPrefs.audioQualityPreference",
    "KEY_AUDIO_CONTENT_PREFERENCE": "mPlusPrefs.audioContentPreference",
    "KEY_IGNORE_COMMENTARY_AUDIO": "mPlusPrefs.ignoreCommentaryAudio",
    "KEY_IGNORE_AUDIO_DESCRIPTION": "mPlusPrefs.ignoreAudioDescription",
    "KEY_SUBTITLE_LANGUAGE_PRIMARY": "mPlusPrefs.getPreferredSubtitleLanguages()",
    "KEY_SUBTITLE_LANGUAGE_SECONDARY": "mPlusPrefs.getPreferredSubtitleLanguages()",
    "KEY_SUBTITLE_LANGUAGE_TERTIARY": "mPlusPrefs.getPreferredSubtitleLanguages()",
    "KEY_SUBTITLE_MODE": "mPlusPrefs.subtitleMode",
    "KEY_PREFER_FORCED_SUBTITLES": "mPlusPrefs.preferForcedSubtitles",
    "KEY_ALLOW_UNKNOWN_SUBTITLES": "mPlusPrefs.allowUnknownSubtitles",
    "KEY_IGNORE_SDH_SUBTITLES": "mPlusPrefs.ignoreSdhSubtitles",
    "KEY_SUBTITLE_SOURCE": "mPlusPrefs.subtitleSourcePreference",
    "KEY_SUBTITLE_DELAY_MS": "mPlusPrefs.subtitleDelayMs",
    "KEY_SUBTITLE_SCALE": "mPlusPrefs.subtitleScale",
    "KEY_SUBTITLE_POSITION": "mPlusPrefs.subtitlePosition",
    "KEY_OPENSUBTITLES_EXACT_MATCH": "mPlusPrefs.openSubtitlesExactMatch",
    "KEY_REMEMBER_TRACK_SCOPE": "mPlusPrefs.rememberTrackScope",
    "KEY_RESIZE_DEFAULT": "mPlusPrefs.resizeDefault",
    "KEY_SPEED_DEFAULT": "mPlusPrefs.speedDefault",
    "KEY_SEEK_INCREMENT_MS": "mPlusPrefs.seekIncrementMs",
    "KEY_FRAME_RATE_POLICY": "mPlusPrefs.frameRatePolicy",
    "KEY_NETWORK_BUFFER_PROFILE": "mPlusPrefs.networkBufferProfile",
    "KEY_SHOW_END_TIME": "mPlusPrefs.showEndTime",
    "KEY_BACK_BUTTON_BEHAVIOR": "mPlusPrefs.backButtonBehavior",
    "KEY_COMPLETION_RULE": "mPlusPrefs.completionRule",
    "KEY_EXTERNAL_PLAYER_DIAGNOSTICS": "PlusPrefs.KEY_EXTERNAL_PLAYER_DIAGNOSTICS",
    "KEY_STREMIO_CONNECTOR_ENABLED": "mPlusPrefs.stremioConnectorEnabled",
    "KEY_STREMIO_ACCOUNT_SYNC_ENABLED": "PlusPrefs.KEY_STREMIO_ACCOUNT_SYNC_ENABLED",
    "KEY_NEXT_EPISODE_NOTICE_SECONDS": "mPlusPrefs.nextEpisodeNoticeSeconds",
    "KEY_NEXT_EPISODE_POPUP_SIZE": "mPlusPrefs.nextEpisodePopupSize",
    "KEY_AI_SUBTITLES_ENABLED": "mPlusPrefs.aiSubtitlesEnabled",
    "KEY_AI_SUBTITLE_BACKEND_URL": "mPlusPrefs.aiSubtitleBackendUrl",
    "KEY_AI_SUBTITLE_TARGET_LANGUAGE": "mPlusPrefs.aiSubtitleTargetLanguage",
}

errors = []

missing_anchor_definitions = sorted(set(keys) - set(runtime_anchors))
extra_anchor_definitions = sorted(set(runtime_anchors) - set(keys))
if missing_anchor_definitions:
    errors.append(
        "Missing runtime-audit anchors for: " + ", ".join(missing_anchor_definitions)
    )
if extra_anchor_definitions:
    errors.append(
        "Audit references unknown PlusPrefs keys: " + ", ".join(extra_anchor_definitions)
    )

for constant, xml_key in sorted(keys.items()):
    if plus_prefs.count(constant) < 2:
        errors.append(f"{constant} is declared but not loaded in PlusPrefs.reload()")

    xml_marker = f'app:key="{xml_key}"'
    xml_count = preferences_xml.count(xml_marker)
    if xml_count != 1:
        errors.append(
            f"{constant} ({xml_key}) must occur exactly once in root_preferences.xml; "
            f"found {xml_count}"
        )

    anchor = runtime_anchors.get(constant)
    if anchor and anchor not in external_java:
        errors.append(
            f"{constant} ({xml_key}) has no expected runtime hook: {anchor}"
        )

protected_snippets = (
    ".setExtensionRendererMode(mPrefs.decoderPriority)",
    ".setMapDV7ToHevc(mPrefs.mapDV7ToHevc)",
    ".setTunnelingEnabled(true)",
    "player.setAudioAttributes(audioAttributes, true);",
)
for snippet in protected_snippets:
    count = player.count(snippet)
    if count != 1:
        errors.append(f"Protected playback snippet must occur once: {snippet!r}; found {count}")

parser_injections = player.count(".setSubtitleParserFactory(subtitleParserFactory)")
if parser_injections != 4:
    errors.append(
        "Subtitle delay parser must cover normal playback and the isolated next-episode probe; "
        f"found {parser_injections} injections"
    )

for forbidden in ("Math.addExact", "Math.subtractExact"):
    if forbidden in offset_parser:
        errors.append(f"minSdk-unsafe timestamp helper remains: {forbidden}")

runtime_regression_anchors = (
    "mPlusPrefs.useMediaDefaultAudioFallback()",
    "mPlusPrefs.getSubtitleSelectionOrder()",
    "loadMediaFromIntent(intent, uri, type)",
    "persistAudioSelectionPerFile",
    "persistSubtitleSelectionPerFile",
    "isExternalPlayerLaunch()",
    "onTrackSelectionParametersChanged",
    "isCurrentPlayerSession",
    "activity.resetApiAccess()",
    "Collections.synchronizedList",
    "updateMeta(null, null, resizeMode, scale, speed)",
    "String permission = Manifest.permission.READ_EXTERNAL_STORAGE",
    "Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED",
    "NetworkBufferConfig.fromPreference(",
    "setLoadErrorHandlingPolicy(new DefaultLoadErrorHandlingPolicy(",
    "initializeNextEpisodeFeature();",
    "releaseNextEpisodeFeature();",
    "nextEpisodeHttpClient.dispatcher().cancelAll();",
    "!isNextEpisodeFeatureEnabled()",
    "updateExpectedEndTime(newPosition.positionMs)",
    "onPlaybackParametersChanged",
    "getTimeFormat(this)",
    "getStremioMediaIdentity(), getStremioLaunchIdentity())",
    "metadata_resolution_started",
    "setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS)",
    "if (mPlusPrefs.aiSubtitlesEnabled)",
    "releaseAiSubtitleController();",
    "aiSubtitleController.onTracksChanged();",
    "SelectedSubtitleResolver.AI_ID_PREFIX",
    "cancelRemoteJob(jobId)",
    "currentPlayer.setMediaItem(\n                    updatedItem, Math.max(0L, transaction.positionMs));",
    "StremioIdentitySubtitle.parse(subtitle.toString())",
    "StremioPreloadedSubtitle.parse(subtitle.toString())",
    "StremioIdentitySubtitle.responseJson(\n                            request, preload.candidates)",
    "call.timeout().timeout(LOOKUP_TIMEOUT_MS",
    "StremioConnectorOpenSubtitles.newHttpClient()",
    "findPreloadedSubtitles(",
    "startupSubtitlePreloadPending",
    "opensubtitles_preload_unavailable",
    "playback_media_item_kept_immutable",
)
for anchor in runtime_regression_anchors:
    if anchor not in external_java:
        errors.append(f"Missing regression fix runtime hook: {anchor}")

if "replaceMediaItem(transaction.mediaItemIndex, updatedItem)" in player:
    errors.append(
        "AI subtitle attachment must rebuild MergingMediaSource; "
        "replaceMediaItem silently drops newly added subtitle children"
    )

for forbidden in (
    "attachOpenSubtitles(",
    "opensubtitles_attached",
    "currentPlayer.setMediaItem(updatedItem, false)",
):
    if forbidden in player:
        errors.append(
            "Late OpenSubtitles must not rebuild the active media item: " + forbidden
        )

for forbidden in (
    "StremioNextEpisodeDeepLink",
    "next_episode_deep_link",
    "autoPlay=true",
):
    if forbidden in external_java:
        errors.append(
            "Next-episode continuation must stay inside JustPlayer Plus and a safe-failure "
            "link must never autoplay an unverified Stremio stream: " + forbidden
        )

for continuation_anchor in (
    'beginNextEpisodeTransition("natural_end")',
    'beginNextEpisodeTransition("play_now")',
    "NextEpisodePlaybackPlan.order(",
    "NextEpisodeTrackContract.fromSelection(",
    '"strict_next_episode_contract"',
    "new StremioWatchJournal(this).record(",
    "ExternalPlaybackResultPolicy.endBy(",
):
    if continuation_anchor not in player:
        errors.append(
            "Missing in-player next-episode continuation hook: " + continuation_anchor
        )

if not EXTERNAL_RESULT_TEST_PATH.exists():
    errors.append("External-player result regression tests are missing")
else:
    external_result_tests = EXTERNAL_RESULT_TEST_PATH.read_text(encoding="utf-8")
    for test_name in (
        "completedPlaybackDelegatesContinuationWithoutOldEpisodeProgress",
        "dismissedNextEpisodeCannotTriggerCallerContinuation",
    ):
        if test_name not in external_result_tests:
            errors.append(f"Missing external-player result regression test: {test_name}")

if not TEST_PATH.exists():
    errors.append("Smart-selection regression tests are missing")
else:
    tests = TEST_PATH.read_text(encoding="utf-8")
    for test_name in (
        "audioMediaDefaultRanksAfterExplicitLanguages",
        "dubbedLabelsIncludeSynchronizedVariants",
        "subtitleMediaDefaultKeepsItsConfiguredPosition",
        "audioAndSubtitleMemoryProvenanceAreIndependent",
        "rememberedSeriesTrackSurvivesMissingLanguageMetadataOnAnotherSource",
    ):
        if test_name not in tests:
            errors.append(f"Missing smart-selection regression test: {test_name}")

if not OFFSET_TEST_PATH.exists():
    errors.append("Subtitle-delay regression tests are missing")
else:
    offset_tests = OFFSET_TEST_PATH.read_text(encoding="utf-8")
    for test_name in (
        "cueTimesShiftInBothDirections",
        "unsetCueTimeBecomesRelativeDelay",
        "seekThresholdMovesOppositeToDelay",
        "timestampMathSaturatesInsteadOfOverflowing",
    ):
        if test_name not in offset_tests:
            errors.append(f"Missing subtitle-delay regression test: {test_name}")

if not END_TIME_TEST_PATH.exists():
    errors.append("Projected-end-time regression tests are missing")
else:
    end_time_tests = END_TIME_TEST_PATH.read_text(encoding="utf-8")
    for test_name in (
        "normalSpeedAddsRemainingMediaTime",
        "fasterPlaybackShortensWallClockRemainder",
        "invalidOrUnknownMediaCannotProduceEstimate",
        "hugeEstimateSaturatesInsteadOfOverflowing",
    ):
        if test_name not in end_time_tests:
            errors.append(f"Missing projected-end-time regression test: {test_name}")

if not STREMIO_TEST_PATH.exists():
    errors.append("Stremio metadata regression tests are missing")
else:
    stremio_tests = STREMIO_TEST_PATH.read_text(encoding="utf-8")
    for test_name in (
        "freshMovieRequestSupersedesStaleSeriesRequest",
        "launchIdentityIsStableAndDoesNotStoreTheRawTitle",
        "directFallbackQueuePreservesFinalConnectorOrderAcrossSources",
        "fallbackRequiresTheExactCurrentEpisodeStreamAndSkipsAttempts",
        "rememberedContentRejectsMalformedTypesAndIds",
        "movieMetadataUsesCinemetaName",
        "subtitleRequestRecoversEpisodeIdentityAndFilename",
        "subtitleFilenameFlowsFromConnectorEventIntoResolvedContent",
        "subtitleRequestSupportsCurrentAndLegacyIdentityFormats",
        "lateSubtitleRequestRefreshesAlreadyResolvedContent",
        "filenameRefreshRejectsStaleEventsAndKeepsExistingIdentity",
        "identitySubtitleCarriesMovieAndFilenameThroughCachedResponse",
        "identitySubtitleCarriesSeriesAndRejectsForeignUrls",
        "preloadedOpenSubtitlesRoundTripWithIdentityMarker",
        "preloadedOpenSubtitlesRejectForeignLoopbackAndSourceHosts",
    ):
        if test_name not in stremio_tests:
            errors.append(f"Missing Stremio metadata regression test: {test_name}")

if not STREMIO_ACCOUNT_SYNC_TEST_PATH.exists():
    errors.append("Stremio account sync regression tests are missing")
else:
    account_sync_tests = STREMIO_ACCOUNT_SYNC_TEST_PATH.read_text(encoding="utf-8")
    for test_name in (
        "officialWatchedFieldRoundTripsAndPreservesExistingEpisodes",
        "anchorOffsetSurvivesAChangedVideoPrefix",
        "partialCheckpointChangesOnlyResumeState",
        "completedCheckpointAddsOnlyTheTargetWatchedBit",
        "newerServerProgressAcceptsRfc3339FractionsAndOffsets",
    ):
        if test_name not in account_sync_tests:
            errors.append(f"Missing Stremio account sync regression test: {test_name}")

if not STREMIO_ACCOUNT_SYNC_COORDINATOR_PATH.exists():
    errors.append("Stremio account sync coordinator is missing")
else:
    account_sync_coordinator = STREMIO_ACCOUNT_SYNC_COORDINATOR_PATH.read_text(
        encoding="utf-8"
    )
    for anchor in (
        "NetworkType.CONNECTED",
        "BackoffPolicy.EXPONENTIAL",
        "ExistingWorkPolicy.REPLACE",
        "ExistingWorkPolicy.KEEP",
        "Executors.newSingleThreadExecutor",
        "static void flushAsync(Context context)",
        "cancelUniqueWork(UNIQUE_WORK_NAME)",
        "while (isEnabled(app))",
        "queue.removeIfCurrent(checkpoint)",
    ):
        if anchor not in account_sync_coordinator:
            errors.append(f"Missing durable Stremio retry hook: {anchor}")

if "StremioAccountSyncCoordinator.flush(this);" in player:
    errors.append("Player startup must not synchronously flush the Stremio account queue")
if player.count(
    "StremioAccountSyncCoordinator.flushAsync(PlayerActivity.this)"
) != 1:
    errors.append(
        "Stremio account queue must be flushed asynchronously once after playback starts"
    )
if "stremioAccountStartupFlushDispatched" not in player:
    errors.append("Playback-start Stremio account flush is missing its one-shot gate")

if not STREMIO_ACCOUNT_SYNC_WORKER_PATH.exists():
    errors.append("Stremio account sync WorkManager worker is missing")
else:
    account_sync_worker = STREMIO_ACCOUNT_SYNC_WORKER_PATH.read_text(encoding="utf-8")
    for anchor in (
        "StremioAccountSyncCoordinator.drainQueue(getApplicationContext())",
        "Result.success()",
        "Result.retry()",
        "StremioAccountSyncCoordinator.cancelCurrentAttempt()",
    ):
        if anchor not in account_sync_worker:
            errors.append(f"Missing Stremio background worker hook: {anchor}")

if not STREMIO_BOOT_RECEIVER_PATH.exists() or (
    "StremioAccountSyncCoordinator.flush(context)"
    not in STREMIO_BOOT_RECEIVER_PATH.read_text(encoding="utf-8")
):
    errors.append("Shield reboot must restore pending Stremio account sync work")

app_build = APP_BUILD_PATH.read_text(encoding="utf-8")
if "androidx.work:work-runtime:2.11.2" not in app_build:
    errors.append("Stremio retry requires the audited WorkManager 2.11.2 runtime")

changelog = CHANGELOG_PATH.read_text(encoding="utf-8")
version_match = re.search(r"^\s*versionCode\s+(\d+)\s*$", app_build, re.MULTILINE)
step_match = re.search(r"^## Step (\d+)\b", changelog, re.MULTILINE)
if version_match is None or step_match is None:
    errors.append("Application version or latest changelog step could not be parsed")
else:
    version_code = int(version_match.group(1))
    latest_step = int(step_match.group(1))
    expected_version = 242 + latest_step
    if version_code != expected_version:
        errors.append(
            "Every new JustPlayer Plus step must increment versionCode: "
            f"Step {latest_step} requires {expected_version}, found {version_code}"
        )
    if f"Raised the application version code to {version_code}" not in changelog:
        errors.append("Latest application version is missing from PLUS_CHANGELOG.md")

manifest = MANIFEST_PATH.read_text(encoding="utf-8")
if 'android:name=".JustPlayerPlusApplication"' not in manifest:
    errors.append("WorkManager on-demand configuration Application is not registered")
if (
    'android:name="androidx.work.WorkManagerInitializer"' not in manifest
    or 'tools:node="remove"' not in manifest
):
    errors.append("Default WorkManager cold-start initialization must remain disabled")
if not APPLICATION_PATH.exists():
    errors.append("WorkManager on-demand configuration Application is missing")
else:
    application = APPLICATION_PATH.read_text(encoding="utf-8")
    for anchor in (
        "implements Configuration.Provider",
        "getWorkManagerConfiguration()",
        "new Configuration.Builder().build()",
    ):
        if anchor not in application:
            errors.append(f"Missing WorkManager on-demand initialization hook: {anchor}")

if not AI_TEST_PATH.exists():
    errors.append("AI subtitle policy regression tests are missing")
else:
    ai_tests = AI_TEST_PATH.read_text(encoding="utf-8")
    for test_name in (
        "supportsOnlyExternalFirstVersionTextMimeTypes",
        "readyBackendResponseIsStrictlyValidated",
        "backendAddressAllowsOnlyCredentialFreeHttpOrHttps",
        "optionalAccessTokenRejectsHeaderInjectionAndUnreasonableLength",
        "backendHttpErrorsMapToActionableFailures",
        "lateResultIsRejectedAfterDisableMovieChangeOrSourceChange",
    ):
        if test_name not in ai_tests:
            errors.append(f"Missing AI subtitle regression test: {test_name}")

if not OPEN_SUBTITLES_TEST_PATH.exists():
    errors.append("OpenSubtitles v3 regression tests are missing")
else:
    opensubtitles_tests = OPEN_SUBTITLES_TEST_PATH.read_text(encoding="utf-8")
    for test_name in (
        "acceptsOnlyImdbMovieAndEpisodeIds",
        "normalizesStremioAndIsoLanguageVariants",
        "filtersDeduplicatesAndCapsPreferredLanguages",
        "preservesForcedAndSdhHintsForSmartSelection",
        "recognizesLikelyReleaseNamesButRejectsConflictingResolution",
        "releaseConfidenceDoesNotTreatTitleAndYearAsSynchronizationEvidence",
        "releaseConfidenceAcceptsSameSourceFingerprintWithoutExactText",
        "releaseConfidenceHandlesLanguageSuffixAndDoesNotHardRejectOtherGroup",
    ):
        if test_name not in opensubtitles_tests:
            errors.append(f"Missing OpenSubtitles v3 regression test: {test_name}")

if not OPEN_SUBTITLES_REST_TEST_PATH.exists():
    errors.append("OpenSubtitles REST regression tests are missing")
else:
    opensubtitles_rest_tests = OPEN_SUBTITLES_REST_TEST_PATH.read_text(encoding="utf-8")
    for test_name in (
        "acceptsOnlyExplicitMovieHashMatchesInPreferredLanguages",
        "prefersNormalTrustedPopularResultWithinLanguage",
        "credentialsRequireApiKeyAndCompleteOptionalAccount",
        "buildsCanonicalApiUrlsWithoutRedirects",
        "acceptsOnlyConservativeReleaseMatchesOutsideExactHashResults",
        "syntheticFilenameCannotCreateProbableMatches",
        "probableRestCandidateMapsBackToVisibleV3Track",
    ):
        if test_name not in opensubtitles_rest_tests:
            errors.append(f"Missing OpenSubtitles REST regression test: {test_name}")

for ui_key in ("openSubtitlesCredentials", "openSubtitlesTest"):
    if preferences_xml.count(f'app:key="{ui_key}"') != 1:
        errors.append(
            f"OpenSubtitles UI action {ui_key} must occur exactly once in root_preferences.xml"
        )
for secure_hook in (
    "new OpenSubtitlesCredentialsStore(requireContext())",
    "OpenSubtitlesRestClient.testCredentials",
):
    if secure_hook not in external_java:
        errors.append(f"OpenSubtitles UI action has no secure runtime hook: {secure_hook}")

if preferences_xml.count('app:key="aiSubtitleApiToken"') != 1:
    errors.append("AI subtitle access token must occur exactly once in root_preferences.xml")
if "AiSubtitlePreferences.KEY_API_TOKEN" not in external_java:
    errors.append("AI subtitle access token has no runtime hook")

aggregation_keys = (
    "stremioAggregationEnabled",
    "stremioAggregationSources",
    "stremioAggregationSortMode",
    "stremioAggregationPreferCached",
    "stremioAggregationSizeSort",
    "stremioAggregationPreferredLanguages",
    "stremioAggregationResolutions",
    "stremioAggregationStreamTypes",
    "stremioAggregationBlockedReleases",
    "stremioAggregationCodecs",
    "stremioAggregationHdrFormats",
    "stremioAggregationKeepUnknownTech",
    "stremioAggregationAllowedLanguages",
    "stremioAggregationKeepUnknownLanguage",
    "stremioAggregationMinSizeGb",
    "stremioAggregationMaxSizeGb",
    "stremioAggregationKeepUnknownSize",
    "stremioAggregationBlockedText",
    "stremioAggregationMaxTotal",
    "stremioAggregationMaxPerSource",
    "stremioAggregationMaxPerQuality",
    "stremioAggregationDeduplication",
    "stremioAggregationBingeGroup",
    "stremioAggregationDisplayFields",
    "stremioAggregationReset",
)
if not STREMIO_AGGREGATION_PREFS_PATH.exists():
    errors.append("Stremio aggregation preferences are missing")
else:
    aggregation_preferences = STREMIO_AGGREGATION_PREFS_PATH.read_text(encoding="utf-8")
    for aggregation_key in aggregation_keys:
        if aggregation_key not in aggregation_preferences:
            errors.append(f"Aggregation preference has no runtime definition: {aggregation_key}")
        if preferences_xml.count(f'app:key="{aggregation_key}"') != 1:
            errors.append(
                f"Aggregation preference must occur once in XML: {aggregation_key}"
            )

for aggregation_hook in (
    "StremioAggregationPreferences.isEnabled(this)",
    "streamResponse(\n                aggregationEnabled",
    "new StremioStreamSourceStore(requireContext())",
    "StremioAddonClient.parseManifestUrl(url)",
    "aggregator.shutdown()",
):
    if aggregation_hook not in external_java:
        errors.append(f"Missing Stremio aggregation runtime hook: {aggregation_hook}")

if not STREMIO_STREAM_TEST_PATH.exists():
    errors.append("Stremio stream aggregation regression tests are missing")
else:
    stream_tests = STREMIO_STREAM_TEST_PATH.read_text(encoding="utf-8")
    for test_name in (
        "disabledGateReturnsExactVersion258ResponseWithoutInvokingAggregator",
        "qualityOrderingInterleavesSourcesAndKeepsOriginalPlaybackFields",
        "fullUpstreamRelevanceKeepsActualBlueyAheadOfLargerFalseTitles",
        "safeDeduplicationUsesExactPlaybackIdentityAndHigherSourcePriority",
        "filtersReleaseLanguageSizeTypeAndUserTextConservatively",
        "bingeModesAreStableAndNonePreservesOriginalHint",
        "streamEndpointDerivationPreservesConfiguredPathAndQuery",
    ):
        if test_name not in stream_tests:
            errors.append(f"Missing Stremio stream regression test: {test_name}")

# These binaries contain the protected Media3 renderer/audio path and extension decoders.
# An intentional upstream refresh must review the playback regression matrix and update hashes.
protected_aar_hashes = {
    "lib-decoder-av1-release.aar": "4a5143035adabc917211a54deaae45f2dfbc8aefcf60b7614e32afa5db08133c",
    "lib-decoder-ffmpeg-release.aar": "0d8c7f957f8314627034129e1f536b7ca02fbe62907bae1f9bd35090e4c2d214",
    "lib-decoder-iamf-release.aar": "7e589f4e8ff13e56b82f8dd0792525b8d135cd1708b14ac17e52720a86eaee07",
    "lib-decoder-mpegh-release.aar": "fd675df8df5f39523fcab658ddd02d607665522392e299dff940bdcd38b23436",
    "lib-exoplayer-release.aar": "2895e3f09aef4ca72edfffec6f682aea85a707d6ce4c8d4fc46048ac2b3ec565",
    "lib-ui-release.aar": "726fbd10e34c6e35d414cdb99216bd511fb3d601ef1b3199ed652174a636e0b4",
}
libs_dir = ROOT / "just-player-plus" / "app" / "libs"
for filename, expected_hash in protected_aar_hashes.items():
    path = libs_dir / filename
    if not path.exists():
        errors.append(f"Protected playback binary is missing: {filename}")
        continue
    actual_hash = hashlib.sha256(path.read_bytes()).hexdigest()
    if actual_hash != expected_hash:
        errors.append(
            f"Protected playback binary changed without audit: {filename} "
            f"({actual_hash} != {expected_hash})"
        )

leftover_helpers = sorted((ROOT / "tools").glob("apply_step*.py"))
if leftover_helpers:
    errors.append(
        "Temporary patch helpers remain in the repository: "
        + ", ".join(str(path.relative_to(ROOT)) for path in leftover_helpers)
    )

if errors:
    print("JustPlayer Plus preference audit failed:", file=sys.stderr)
    for error in errors:
        print(f"- {error}", file=sys.stderr)
    raise SystemExit(1)

print(
    f"JustPlayer Plus preference audit passed: {len(keys)} settings are present in XML "
    "and have runtime hooks; protected playback construction is intact."
)
