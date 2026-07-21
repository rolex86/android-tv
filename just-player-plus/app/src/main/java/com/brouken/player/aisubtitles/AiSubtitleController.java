package com.brouken.player.aisubtitles;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionOverride;
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

        Session(long token,
                int playerGeneration,
                Uri mediaUri,
                String sourceSubtitleId,
                String targetLanguage,
                @Nullable String mediaTitle) {
            this.token = token;
            this.playerGeneration = playerGeneration;
            this.mediaUri = mediaUri;
            this.sourceSubtitleId = sourceSubtitleId;
            this.targetLanguage = targetLanguage;
            this.mediaTitle = mediaTitle;
        }
    }

    @Nullable private Host host;
    @Nullable private ImageButton button;
    @Nullable private AlertDialog confirmationDialog;
    @Nullable private ExecutorService executor;
    @Nullable private OkHttpClient httpClient;
    @Nullable private AiSubtitleBackendClient backendClient;
    @Nullable private String pendingAiSubtitleId;
    @Nullable private Session activeSession;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private long operationToken;
    private boolean released;
    private boolean translating;

    public AiSubtitleController(Host host, ImageButton button) {
        this.host = host;
        this.button = button;
        updateButtonState();
    }

    public static boolean isTranslationAvailable(@Nullable Player player) {
        return SelectedSubtitleResolver.resolve(player).isReady();
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
                        (dialog, which) -> beginConfirmedTranslation(source, backendUrl))
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
        mainHandler.removeCallbacksAndMessages(null);
        if (confirmationDialog != null) {
            confirmationDialog.dismiss();
            confirmationDialog = null;
        }
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

    private void beginConfirmedTranslation(AiSubtitleSource source, String backendUrl) {
        Host currentHost = host;
        if (released || currentHost == null || !currentHost.aiSubtitlesEnabled()) {
            return;
        }
        Uri mediaUri = currentHost.mediaUri();
        if (mediaUri == null) {
            return;
        }
        cancelCurrentOperation();
        long token = ++operationToken;
        activeSession = new Session(
                token,
                currentHost.playerGeneration(),
                mediaUri,
                source.id,
                currentHost.targetLanguage(),
                currentHost.mediaTitle());
        translating = true;
        updateButtonState();
        show(R.string.ai_subtitle_translating);

        ensureWorkers(backendUrl);
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
                String fingerprint = AiSubtitleFileStore.sourceFingerprint(
                        subtitleText, session.targetLanguage);
                AiSubtitleFileStore store = new AiSubtitleFileStore(applicationContext);
                AiSubtitleFileStore.StoredSubtitle cached = store.find(
                        fingerprint, session.targetLanguage);
                mainHandler.post(() -> {
                    if (!isSessionCurrent(session)) {
                        return;
                    }
                    if (cached != null) {
                        attachSubtitle(session, cached);
                    } else {
                        requestTranslation(session, source, subtitleText, fingerprint, store);
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
                                    String fingerprint,
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
                if (isSessionCurrent(session) && progress > 0) {
                    show(R.string.ai_subtitle_progress, progress);
                }
            }

            @Override
            public void onReady(AiSubtitleResult result) {
                if (!isSessionCurrent(session) || executor == null) {
                    return;
                }
                executor.execute(() -> {
                    try {
                        AiSubtitleFileStore.StoredSubtitle stored =
                                store.store(fingerprint, result);
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
        translating = false;
        activeSession = null;
        selectPendingTrack();
        updateButtonState();
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
                show(R.string.ai_subtitle_ready);
                return;
            }
        }
    }

    private void ensureWorkers(String backendUrl) {
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
        if (backendClient == null) {
            backendClient = new AiSubtitleBackendClient(httpClient, backendUrl);
        }
    }

    private void cancelCurrentOperation() {
        operationToken++;
        activeSession = null;
        pendingAiSubtitleId = null;
        if (backendClient != null) {
            backendClient.cancel();
        }
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
        if (backendClient != null) {
            backendClient.cancel();
        }
        show(message);
        updateButtonState();
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
