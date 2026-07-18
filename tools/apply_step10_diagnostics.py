from pathlib import Path

JAVA = Path("just-player-plus/app/src/main/java/com/brouken/player/PlayerActivity.java")
SETTINGS = Path("just-player-plus/app/src/main/java/com/brouken/player/SettingsActivity.java")
XML = Path("just-player-plus/app/src/main/res/xml/root_preferences.xml")
STRINGS = Path("just-player-plus/app/src/main/res/values/strings.xml")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


text = JAVA.read_text(encoding="utf-8")
text = replace_once(
    text,
    """    private RememberedTrackStore rememberedTrackStore;
    private boolean trackMemoryArmed;
""",
    """    private RememberedTrackStore rememberedTrackStore;
    private ExternalPlayerDiagnostics externalDiagnostics;
    private boolean trackMemoryArmed;
""",
    "diagnostics field",
)
text = replace_once(
    text,
    """        mPlusPrefs = new PlusPrefs(this);
        rememberedTrackStore = new RememberedTrackStore(this);
        Utils.setOrientation(this, mPrefs.orientation);
""",
    """        mPlusPrefs = new PlusPrefs(this);
        rememberedTrackStore = new RememberedTrackStore(this);
        externalDiagnostics = new ExternalPlayerDiagnostics(this);
        Utils.setOrientation(this, mPrefs.orientation);
""",
    "diagnostics initialization",
)
text = replace_once(
    text,
    """        coordinatorLayout = findViewById(R.id.coordinatorLayout);
""",
    """        long requestedPosition = mPrefs.mediaUri != null
                ? mPrefs.getPosition() : C.TIME_UNSET;
        externalDiagnostics.recordLaunch(
                mPrefs.mediaUri,
                apiTitle,
                apiAccess,
                apiAccessPartial,
                intentReturnResult,
                requestedPosition,
                apiSubs.size());

        coordinatorLayout = findViewById(R.id.coordinatorLayout);
""",
    "launch diagnostics",
)

old_selection = """                    boolean restoredSubtitleSelection = false;
                    boolean restoredAudioSelection = false;
                    if (!apiAccess) {
                        restoredSubtitleSelection = "#none".equals(mPrefs.subtitleTrackId)
                                || getTrackGroupFromFormatId(
                                C.TRACK_TYPE_TEXT, mPrefs.subtitleTrackId) != null;
                        restoredAudioSelection = getTrackGroupFromFormatId(
                                C.TRACK_TYPE_AUDIO, mPrefs.audioTrackId) != null;
                        setSelectedTracks(mPrefs.subtitleTrackId, mPrefs.audioTrackId);
                    }

                    mPlusPrefs.reload();
                    RememberedTrackStore.Selection rememberedSelection =
                            rememberedTrackStore.load(
                                    mPlusPrefs.rememberTrackScope, apiTitle, mPrefs.mediaUri);
                    if (!restoredAudioSelection && rememberedSelection != null) {
                        restoredAudioSelection =
                                applyRememberedAudioSelection(rememberedSelection);
                    }
                    if (!restoredSubtitleSelection && rememberedSelection != null) {
                        restoredSubtitleSelection =
                                applyRememberedSubtitleSelection(rememberedSelection);
                    }
                    if (!restoredAudioSelection) {
                        applySmartAudioSelection();
                    }
                    if (!restoredSubtitleSelection) {
                        applySmartSubtitleSelection();
                    }
                    armTrackMemory();
"""
new_selection = """                    boolean restoredSubtitleSelection = false;
                    boolean restoredAudioSelection = false;
                    String audioSelectionReason = "media_default";
                    String subtitleSelectionReason = "smart_subtitle_policy";
                    if (!apiAccess) {
                        restoredSubtitleSelection = "#none".equals(mPrefs.subtitleTrackId)
                                || getTrackGroupFromFormatId(
                                C.TRACK_TYPE_TEXT, mPrefs.subtitleTrackId) != null;
                        restoredAudioSelection = getTrackGroupFromFormatId(
                                C.TRACK_TYPE_AUDIO, mPrefs.audioTrackId) != null;
                        setSelectedTracks(mPrefs.subtitleTrackId, mPrefs.audioTrackId);
                        if (restoredAudioSelection) {
                            audioSelectionReason = "saved_file_selection";
                        }
                        if (restoredSubtitleSelection) {
                            subtitleSelectionReason = "saved_file_selection";
                        }
                    }

                    mPlusPrefs.reload();
                    RememberedTrackStore.Selection rememberedSelection =
                            rememberedTrackStore.load(
                                    mPlusPrefs.rememberTrackScope, apiTitle, mPrefs.mediaUri);
                    if (!restoredAudioSelection && rememberedSelection != null) {
                        restoredAudioSelection =
                                applyRememberedAudioSelection(rememberedSelection);
                        if (restoredAudioSelection) {
                            audioSelectionReason = "remembered_" + mPlusPrefs.rememberTrackScope;
                        }
                    }
                    if (!restoredSubtitleSelection && rememberedSelection != null) {
                        restoredSubtitleSelection =
                                applyRememberedSubtitleSelection(rememberedSelection);
                        if (restoredSubtitleSelection) {
                            subtitleSelectionReason = "remembered_" + mPlusPrefs.rememberTrackScope;
                        }
                    }
                    if (!restoredAudioSelection) {
                        applySmartAudioSelection();
                        audioSelectionReason = "smart_audio_policy";
                    }
                    if (!restoredSubtitleSelection) {
                        applySmartSubtitleSelection();
                        subtitleSelectionReason = "smart_subtitle_policy";
                    }
                    armTrackMemory();
                    final String diagnosticAudioReason = audioSelectionReason;
                    final String diagnosticSubtitleReason = subtitleSelectionReason;
                    playerView.postDelayed(() -> externalDiagnostics.recordTracks(
                            player, diagnosticAudioReason, diagnosticSubtitleReason), 300L);
"""
text = replace_once(text, old_selection, new_selection, "selection diagnostics")

old_manual = """    private void rememberManualTrackSelection(Tracks tracks) {
        if (!trackMemoryArmed || player == null) {
            return;
        }
        mPlusPrefs.reload();
        if ("off".equals(mPlusPrefs.rememberTrackScope)) {
            return;
        }
        RememberedTrackStore.Selection selection = RememberedTrackStore.capture(
                tracks, player.getTrackSelectionParameters());
        String signature = selection.signature();
        if (signature.equals(trackMemorySignature)) {
            return;
        }
        trackMemorySignature = signature;
        rememberedTrackStore.save(
                mPlusPrefs.rememberTrackScope, apiTitle, mPrefs.mediaUri, selection);
    }
"""
new_manual = """    private void rememberManualTrackSelection(Tracks tracks) {
        if (!trackMemoryArmed || player == null) {
            return;
        }
        RememberedTrackStore.Selection selection = RememberedTrackStore.capture(
                tracks, player.getTrackSelectionParameters());
        String signature = selection.signature();
        if (signature.equals(trackMemorySignature)) {
            return;
        }
        trackMemorySignature = signature;
        externalDiagnostics.recordTracks(player, "manual_selection", "manual_selection");

        mPlusPrefs.reload();
        if ("off".equals(mPlusPrefs.rememberTrackScope)) {
            return;
        }
        rememberedTrackStore.save(
                mPlusPrefs.rememberTrackScope, apiTitle, mPrefs.mediaUri, selection);
    }
"""
text = replace_once(text, old_manual, new_manual, "manual diagnostics")

text = replace_once(
    text,
    """        public void onPlayerError(PlaybackException error) {
            updateLoading(false);
""",
    """        public void onPlayerError(PlaybackException error) {
            externalDiagnostics.recordError(error);
            updateLoading(false);
""",
    "error diagnostics",
)

text = replace_once(
    text,
    """        if (intentReturnResult) {
            Intent intent = new Intent("com.mxtech.intent.result.VIEW");
            intent.putExtra(API_END_BY, playbackFinished ? "playback_completion" : "user");
""",
    """        String resultEndBy = playbackFinished ? "playback_completion" : "user";
        if (apiAccess || apiAccessPartial || intentReturnResult) {
            externalDiagnostics.recordResult(resultPosition, resultDuration, resultEndBy);
        }
        if (intentReturnResult) {
            Intent intent = new Intent("com.mxtech.intent.result.VIEW");
            intent.putExtra(API_END_BY, resultEndBy);
""",
    "result diagnostics",
)
JAVA.write_text(text, encoding="utf-8")

settings = SETTINGS.read_text(encoding="utf-8")
settings = replace_once(
    settings,
    """import android.content.res.Configuration;
""",
    """import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.Configuration;
""",
    "settings diagnostics imports",
)
settings = replace_once(
    settings,
    """import android.widget.LinearLayout;
""",
    """import android.widget.LinearLayout;
import android.widget.Toast;
""",
    "settings toast import",
)
settings = replace_once(
    settings,
    """            EditTextPreference subtitleDelay = findPreference(PlusPrefs.KEY_SUBTITLE_DELAY_MS);
            if (subtitleDelay != null) {
                subtitleDelay.setOnBindEditTextListener(editText -> editText.setInputType(
                        InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED));
                subtitleDelay.setSummaryProvider(preference -> {
                    EditTextPreference editTextPreference = (EditTextPreference) preference;
                    String value = editTextPreference.getText();
                    return (value == null || value.isEmpty() ? "0" : value) + " ms";
                });
            }
""",
    """            EditTextPreference subtitleDelay = findPreference(PlusPrefs.KEY_SUBTITLE_DELAY_MS);
            if (subtitleDelay != null) {
                subtitleDelay.setOnBindEditTextListener(editText -> editText.setInputType(
                        InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED));
                subtitleDelay.setSummaryProvider(preference -> {
                    EditTextPreference editTextPreference = (EditTextPreference) preference;
                    String value = editTextPreference.getText();
                    return (value == null || value.isEmpty() ? "0" : value) + " ms";
                });
            }

            Preference diagnostics = findPreference("externalPlayerDiagnosticsView");
            if (diagnostics != null) {
                diagnostics.setOnPreferenceClickListener(preference -> {
                    showDiagnostics();
                    return true;
                });
            }
""",
    "diagnostics preference listener",
)
settings = replace_once(
    settings,
    """        private void setupLanguagePreference(String key, boolean includeDefault,
""",
    """        private void showDiagnostics() {
            Context context = requireContext();
            String log = ExternalPlayerDiagnostics.read(context);
            String visibleLog = log.isEmpty()
                    ? getString(R.string.pref_external_player_diagnostics_empty) : log;
            new AlertDialog.Builder(context)
                    .setTitle(R.string.pref_external_player_diagnostics_view)
                    .setMessage(visibleLog)
                    .setPositiveButton(R.string.pref_external_player_diagnostics_copy,
                            (dialog, which) -> {
                                ClipboardManager clipboard = (ClipboardManager)
                                        context.getSystemService(Context.CLIPBOARD_SERVICE);
                                if (clipboard != null) {
                                    clipboard.setPrimaryClip(ClipData.newPlainText(
                                            "JustPlayer Plus diagnostics", visibleLog));
                                    Toast.makeText(context,
                                            R.string.pref_external_player_diagnostics_copied,
                                            Toast.LENGTH_SHORT).show();
                                }
                            })
                    .setNeutralButton(R.string.pref_external_player_diagnostics_clear,
                            (dialog, which) -> ExternalPlayerDiagnostics.clear(context))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }

        private void setupLanguagePreference(String key, boolean includeDefault,
""",
    "diagnostics dialog",
)
SETTINGS.write_text(settings, encoding="utf-8")

xml = XML.read_text(encoding="utf-8")
xml = replace_once(
    xml,
    """        <SwitchPreferenceCompat
            app:key="externalPlayerDiagnostics"
            app:defaultValue="false"
            app:summary="@string/pref_external_player_diagnostics_summary"
            app:title="@string/pref_external_player_diagnostics" />
""",
    """        <SwitchPreferenceCompat
            app:key="externalPlayerDiagnostics"
            app:defaultValue="false"
            app:summary="@string/pref_external_player_diagnostics_summary"
            app:title="@string/pref_external_player_diagnostics" />

        <Preference
            app:key="externalPlayerDiagnosticsView"
            app:dependency="externalPlayerDiagnostics"
            app:summary="@string/pref_external_player_diagnostics_view_summary"
            app:title="@string/pref_external_player_diagnostics_view" />
""",
    "diagnostics XML preference",
)
XML.write_text(xml, encoding="utf-8")

strings = STRINGS.read_text(encoding="utf-8")
marker = "</resources>"
addition = """    <string name="pref_external_player_diagnostics_view">View diagnostics</string>
    <string name="pref_external_player_diagnostics_view_summary">Inspect received intent data, available tracks, selection reasons, results and playback errors</string>
    <string name="pref_external_player_diagnostics_empty">No diagnostics have been recorded yet.</string>
    <string name="pref_external_player_diagnostics_copy">Copy</string>
    <string name="pref_external_player_diagnostics_copied">Diagnostics copied</string>
    <string name="pref_external_player_diagnostics_clear">Clear</string>
</resources>"""
if strings.count(marker) != 1:
    raise RuntimeError(f"strings closing tag: expected one match, found {strings.count(marker)}")
STRINGS.write_text(strings.replace(marker, addition, 1), encoding="utf-8")
