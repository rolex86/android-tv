package com.brouken.player.aisubtitles;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;

import com.brouken.player.BuildConfig;
import com.brouken.player.R;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

/** Owns the opt-in UI flow and all disposable state for one player session. */
public final class AiSubtitleController {
    public interface Host {
        Activity activity();
        @Nullable Player player();
        int playerGeneration();
        @Nullable Uri mediaUri();
        @Nullable String mediaTitle();
        boolean aiSubtitlesEnabled();
        String backendUrl();
        String targetLanguage();
    }

    private static final class Session {
        final long token;
        final int playerGeneration;
        final Uri mediaUri;
        final String sourceSubtitleId;
        final String targetLanguage;
        @Nullable final String mediaTitle;
        final boolean wasPlaying;

        Session(long token,
                int playerGeneration,
                Uri mediaUri,
                String sourceSubtitleId,
                String targetLanguage,
                @Nullable String mediaTitle,
                boolean wasPlaying) {
            this.token = token;
            this.playerGeneration = playerGeneration;
            this.mediaUri = mediaUri;
            this.sourceSubtitleId = sourceSubtitleId;
            this.targetLanguage = targetLanguage;
            this.mediaTitle = mediaTitle;
            this.wasPlaying = wasPlaying;
        }
    }

    @Nullable private Host host;
    @Nullable private ImageButton button;
    @Nullable private AlertDialog confirmationDialog;
    @Nullable private AlertDialog progressDialog;
    @Nullable private ProgressBar progressBar;
    @Nullable private TextView progressText;
    @Nullable private ExecutorService executor;
    @Nullable private OkHttpClient httpClient;
    @Nullable private AiSubtitleBackendClient backendClient;
    @Nullable private String pendingAiSubtitleId;
    @Nullable private Session activeSession;
    @Nullable private Player observedPlayer;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Player.Listener trackSelectionListener = new Player.Listener() {
        @Override
        public void onTrackSelectionParametersChanged(TrackSelectionParameters parameters) {
            mainHandler.post(AiSubtitleController.this::updateButtonState);
        }
    };
    private long operationToken;
    private boolean released;
    private boolean translating;
    private boolean resumeAfterTrackSelection;

    public AiSubtitleController(Host host, ImageButton button) {
        this.host = host;
        this.button = button;
        observedPlayer = host.player();
        if (observedPlayer != null) {
            observedPlayer.addListener(trackSelectionListener);
        }
        updateButtonState();
    }

    public static boolean isTranslationAvailable(@Nullable Player player) {
        SelectedSubtitleResolver.Resolution selected = SelectedSubtitleResolver.resolve(player);
        if (selected.isReady()) {
            return true;
        }
        if (player == null || player.getCurrentMediaItem() == null) {
            return false;
        }
        for (MediaItem.SubtitleConfiguration configuration
                : SelectedSubtitleResolver.subtitleConfigurations(player.getCurrentMediaItem())) {
            if (configuration.id != null
                    && configuration.id.startsWith(SelectedSubtitleResolver.EXTERNAL_ID_PREFIX)
                    && SelectedSubtitleResolver.isSupportedMime(configuration.mimeType)
                    && SelectedSubtitleResolver.hasReadableScheme(configuration.uri)) {
                return true;
            }
        }
        return false;
    }

    public void startTranslation() {
        Host currentHost = host;
        if (released || currentHost == null || !currentHost.aiSubtitlesEnabled()) {
            return;
        }
        SelectedSubtitleResolver.Resolution resolution =
                SelectedSubtitleResolver.resolve(currentHost.player());
        if (!resolution.isReady()) {
            showResolutionError(resolution.issue);
            updateButtonState();
            return;
        }
        String backendUrl = currentHost.backendUrl().trim();
        if (backendUrl.isEmpty()) {
            show(R.string.ai_subtitle_backend_missing);
            return;
        }
        if (!AiSubtitleBackendClient.isValidBackendUrl(backendUrl)) {
            show(R.string.ai_subtitle_backend_invalid);
            return;
        }
        String apiToken = AiSubtitlePreferences.readApiToken(
                currentHost.activity()).trim();
        if (!AiSubtitleBackendClient.isValidApiToken(apiToken)) {
            show(R.string.pref_ai_subtitle_token_invalid);
            return;
        }
        AiSubtitleSource source = resolution.source;
        if (source == null) {
            return;
        }
        Activity activity = currentHost.activity();
        confirmationDialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.ai_subtitle_dialog_title)
                .setMessage(activity.getString(
                        R.string.ai_subtitle_dialog_message,
                        source.displayName(),
                        activity.getString(R.string.ai_subtitle_target_czech)))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.ai_subtitle_translate,
                        (dialog, which) -> beginConfirmedTranslation(
                                source, backendUrl, apiToken))
                .create();
        confirmationDialog.setOnDismissListener(dialog -> confirmationDialog = null);
        confirmationDialog.show();
    }

    public void onTracksChanged() {
        if (released) {
            return;
        }
        selectPendingTrack();
        updateButtonState();
    }

    public void updateButtonState() {
        ImageButton currentButton = button;
        Host currentHost = host;
        if (released || currentButton == null || currentHost == null) {
            return;
        }
        currentButton.setEnabled(!translating
                && isTranslationAvailable(currentHost.player()));
        currentButton.setAlpha(currentButton.isEnabled() ? 1f : 0.45f);
    }

    public void release() {
        if (released) {
            return;
        }
        released = true;
        operationToken++;
        translating = false;
        activeSession = null;
        pendingAiSubtitleId = null;
        if (observedPlayer != null) {
            observedPlayer.removeListener(trackSelectionListener);
            observedPlayer = null;
        }
        mainHandler.removeCallbacksAndMessages(null);
        if (confirmationDialog != null) {
            confirmationDialog.dismiss();
            confirmationDialog = null;
        }
        dismissProgressDialog();
        if (backendClient != null) {
            backendClient.release();
            backendClient = null;
        }
        if (httpClient != null) {
            httpClient.dispatcher().cancelAll();
            httpClient.connectionPool().evictAll();
            httpClient = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        button = null;
        host = null;
    }

    private void beginConfirmedTranslation(AiSubtitleSource source,
                                               String backendUrl,
                                               String apiToken) {
        Host currentHost = host;
        if (released || currentHost == null || !currentHost.aiSubtitlesEnabled()) {
            return;
        }
        Uri mediaUri = currentHost.mediaUri();
        Player player = currentHost.player();
        if (mediaUri == null || player == null) {
            return;
        }
        cancelCurrentOperation(false);
        boolean wasPlaying = player.isPlaying();
        if (wasPlaying) {
            player.pause();
        }
        long token = ++operationToken;
        activeSession = new Session(
                token,
                currentHost.playerGeneration(),
                mediaUri,
                source.id,
                currentHost.targetLanguage(),
                currentHost.mediaTitle(),
                wasPlaying);
        translating = true;
        updateButtonState();
        showProgressDialog(0);

        ensureWorkers(backendUrl, apiToken);
        ExecutorService currentExecutor = executor;
        OkHttpClient currentHttpClient = httpClient;
        Session session = activeSession;
        if (currentExecutor == null || currentHttpClient == null || session == null) {
            failSession(session, R.string.ai_subtitle_failed);
            return;
        }
        Context applicationContext = currentHost.activity().getApplicationContext();
        currentExecutor.execute(() -> {
            try {
                String subtitleText = new AiSubtitleSourceLoader(
                        applicationContext, currentHttpClient).load(source);
                AiSubtitleFileStore store = new AiSubtitleFileStore(applicationContext);
                mainHandler.post(() -> {
                    if (isSessionCurrent(session)) {
                        requestTranslation(session, source, subtitleText, store);
                    }
                });
            } catch (AiSubtitleSourceLoader.LoadException error) {
                int message = error.failure == AiSubtitleSourceLoader.Failure.TOO_LARGE
                        ? R.string.ai_subtitle_too_large
                        : R.string.ai_subtitle_uri_unreadable;
                mainHandler.post(() -> failSession(session, message));
            } catch (IOException | RuntimeException error) {
                mainHandler.post(() -> failSession(
                        session, R.string.ai_subtitle_uri_unreadable));
            }
        });
    }

    private void requestTranslation(Session session,
                                    AiSubtitleSource source,
                                    String subtitleText,
                                    AiSubtitleFileStore store) {
        Host currentHost = host;
        AiSubtitleBackendClient currentBackend = backendClient;
        if (!isSessionCurrent(session) || currentHost == null || currentBackend == null) {
            return;
        }
        AiSubtitleJob job = new AiSubtitleJob(
                subtitleText,
                source.sourceFormat,
                source.language,
                session.targetLanguage,
                session.mediaTitle,
                null,
                null,
                null,
                null,
                BuildConfig.VERSION_NAME);
        currentBackend.start(job, new AiSubtitleBackendClient.Listener() {
            @Override
            public void onProgress(int progress) {
                if (isSessionCurrent(session)) {
                    updateProgressDialog(progress);
                }
            }

            @Override
            public void onReady(AiSubtitleResult result) {
                if (!isSessionCurrent(session) || executor == null) {
                    return;
                }
                executor.execute(() -> {
                    try {
                        AiSubtitleFileStore.StoredSubtitle stored = store.store(result);
                        mainHandler.post(() -> attachSubtitle(session, stored));
                    } catch (IOException error) {
                        mainHandler.post(() -> failSession(
                                session, R.string.ai_subtitle_failed));
                    }
                });
            }

            @Override
            public void onFailure(AiSubtitleBackendClient.Failure failure,
                                  @Nullable String message) {
                int errorMessage = failure == AiSubtitleBackendClient.Failure.TIMEOUT
                        ? R.string.ai_subtitle_timeout
                        : failure == AiSubtitleBackendClient.Failure.UNAVAILABLE
                        ? R.string.ai_subtitle_service_unavailable
                        : failure == AiSubtitleBackendClient.Failure.UNAUTHORIZED
                        ? R.string.ai_subtitle_unauthorized
                        : failure == AiSubtitleBackendClient.Failure.TOO_LARGE
                        ? R.string.ai_subtitle_too_large
                        : failure == AiSubtitleBackendClient.Failure.INVALID_REQUEST
                        ? R.string.ai_subtitle_invalid_request
                        : R.string.ai_subtitle_failed;
                failSession(session, errorMessage);
            }
        });
    }

    private void attachSubtitle(Session session,
                                AiSubtitleFileStore.StoredSubtitle stored) {
        if (!isSessionCurrent(session)) {
            return;
        }
        Host currentHost = host;
        Player player = currentHost == null ? null : currentHost.player();
        MediaItem mediaItem = player == null ? null : player.getCurrentMediaItem();
        if (player == null || mediaItem == null) {
            return;
        }
        MediaItem.SubtitleConfiguration configuration =
                AiSubtitleTrackFactory.create(stored);
        List<MediaItem.SubtitleConfiguration> existing =
                SelectedSubtitleResolver.subtitleConfigurations(mediaItem);
        boolean duplicate = false;
        for (MediaItem.SubtitleConfiguration item : existing) {
            if (configuration.id.equals(item.id)) {
                duplicate = true;
                break;
            }
        }
        pendingAiSubtitleId = configuration.id;
        if (!duplicate) {
            List<MediaItem.SubtitleConfiguration> updated = new ArrayList<>(existing);
            updated.add(configuration);
            MediaItem updatedItem = mediaItem.buildUpon()
                    .setSubtitleConfigurations(updated)
                    .build();
            player.setMediaItem(updatedItem, false);
        }
        resumeAfterTrackSelection = session.wasPlaying;
        activeSession = null;
        updateProgressDialog(100);
        selectPendingTrack();
    }

    private void selectPendingTrack() {
        String pendingId = pendingAiSubtitleId;
        Host currentHost = host;
        Player player = currentHost == null ? null : currentHost.player();
        if (pendingId == null || player == null) {
            return;
        }
        for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
            if (group.getType() != C.TRACK_TYPE_TEXT) {
                continue;
            }
            TrackGroup trackGroup = group.getMediaTrackGroup();
            for (int index = 0; index < trackGroup.length; index++) {
                Format format = trackGroup.getFormat(index);
                if (!pendingId.equals(format.id)) {
                    continue;
                }
                pendingAiSubtitleId = null;
                TrackSelectionOverride override = new TrackSelectionOverride(
                        trackGroup, Collections.singletonList(index));
                player.setTrackSelectionParameters(
                        player.getTrackSelectionParameters().buildUpon()
                                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                .setOverrideForType(override)
                                .build());
                translating = false;
                dismissProgressDialog();
                if (resumeAfterTrackSelection) {
                    player.play();
                }
                resumeAfterTrackSelection = false;
                show(R.string.ai_subtitle_ready);
                updateButtonState();
                return;
            }
        }
    }

    private void ensureWorkers(String backendUrl, String apiToken) {
        if (executor == null) {
            executor = Executors.newSingleThreadExecutor();
        }
        if (httpClient == null) {
            httpClient = new OkHttpClient.Builder()
                    .connectTimeout(20, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .build();
        }
        if (backendClient != null) {
            backendClient.release();
        }
        backendClient = new AiSubtitleBackendClient(httpClient, backendUrl, apiToken);
    }

    private void cancelCurrentOperation(boolean restorePlayback) {
        Session session = activeSession;
        operationToken++;
        activeSession = null;
        pendingAiSubtitleId = null;
        translating = false;
        resumeAfterTrackSelection = false;
        if (backendClient != null) {
            backendClient.cancel();
        }
        dismissProgressDialog();
        if (restorePlayback && session != null && session.wasPlaying) {
            Player player = host == null ? null : host.player();
            if (player != null) {
                player.play();
            }
        }
        updateButtonState();
    }

    private void cancelByUser() {
        if (!translating) {
            return;
        }
        cancelCurrentOperation(true);
        show(R.string.ai_subtitle_cancelled);
    }

    private boolean isSessionCurrent(@Nullable Session session) {
        Host currentHost = host;
        if (session == null || currentHost == null) {
            return false;
        }
        Activity activity = currentHost.activity();
        SelectedSubtitleResolver.Resolution selected =
                SelectedSubtitleResolver.resolve(currentHost.player());
        String currentSourceId = selected.source == null ? null : selected.source.id;
        return AiSubtitleSessionGuard.isCurrent(
                currentHost.aiSubtitlesEnabled(),
                released,
                session.token,
                operationToken,
                session.playerGeneration,
                currentHost.playerGeneration(),
                session.mediaUri.toString(),
                currentHost.mediaUri() == null ? null : currentHost.mediaUri().toString(),
                session.sourceSubtitleId,
                currentSourceId,
                session.targetLanguage,
                currentHost.targetLanguage())
                && !activity.isFinishing()
                && !activity.isDestroyed()
                && Objects.equals(session.mediaUri, currentHost.mediaUri());
    }

    private void failSession(@Nullable Session session, int message) {
        if (!isSessionCurrent(session)) {
            return;
        }
        translating = false;
        activeSession = null;
        resumeAfterTrackSelection = false;
        if (backendClient != null) {
            backendClient.cancel();
        }
        dismissProgressDialog();
        if (session.wasPlaying) {
            Player player = host == null ? null : host.player();
            if (player != null) {
                player.play();
            }
        }
        show(message);
        updateButtonState();
    }

    private void showProgressDialog(int progress) {
        Host currentHost = host;
        if (released || currentHost == null) {
            return;
        }
        Activity activity = currentHost.activity();
        int padding = Math.round(24 * activity.getResources().getDisplayMetrics().density);
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(padding, padding / 2, padding, 0);

        progressText = new TextView(activity);
        progressText.setGravity(Gravity.CENTER_HORIZONTAL);
        progressText.setTextSize(18);
        layout.addView(progressText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        progressBar = new ProgressBar(activity, null,
                android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barParams.topMargin = padding / 2;
        layout.addView(progressBar, barParams);

        progressDialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.ai_subtitle_translating)
                .setView(layout)
                .setNegativeButton(android.R.string.cancel,
                        (dialog, which) -> cancelByUser())
                .create();
        progressDialog.setCancelable(false);
        progressDialog.setCanceledOnTouchOutside(false);
        progressDialog.show();
        updateProgressDialog(progress);
    }

    private void updateProgressDialog(int progress) {
        int bounded = Math.max(0, Math.min(100, progress));
        if (progressBar != null) {
            progressBar.setProgress(bounded);
        }
        if (progressText != null && host != null) {
            progressText.setText(host.activity().getString(
                    R.string.ai_subtitle_progress, bounded));
        }
    }

    private void dismissProgressDialog() {
        if (progressDialog != null) {
            progressDialog.dismiss();
            progressDialog = null;
        }
        progressBar = null;
        progressText = null;
    }

    private void showResolutionError(@Nullable SelectedSubtitleResolver.Issue issue) {
        if (issue == SelectedSubtitleResolver.Issue.EMBEDDED) {
            show(R.string.ai_subtitle_embedded_unsupported);
        } else if (issue == SelectedSubtitleResolver.Issue.IMAGE_BASED) {
            show(R.string.ai_subtitle_image_unsupported);
        } else if (issue == SelectedSubtitleResolver.Issue.UNSUPPORTED_FORMAT) {
            show(R.string.ai_subtitle_format_unsupported);
        } else if (issue == SelectedSubtitleResolver.Issue.URI_UNREADABLE) {
            show(R.string.ai_subtitle_uri_unreadable);
        } else {
            show(R.string.ai_subtitle_select_external);
        }
    }

    private void show(int stringResource, Object... arguments) {
        Host currentHost = host;
        if (released || currentHost == null) {
            return;
        }
        Activity activity = currentHost.activity();
        String message = arguments.length == 0
                ? activity.getString(stringResource)
                : activity.getString(stringResource, arguments);
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
    }
}
