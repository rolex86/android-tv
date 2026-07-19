package com.brouken.player;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.preference.PreferenceManager;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Player;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.Tracks;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Bounded, privacy-conscious diagnostics for external-player integrations. */
final class ExternalPlayerDiagnostics {
    private static final String FILE_NAME = "external-player-diagnostics.log";
    private static final int MAX_CHARS = 64 * 1024;
    private static final int KEEP_CHARS = 48 * 1024;
    private static final Object LOCK = new Object();

    private final Context context;
    private final SharedPreferences preferences;

    ExternalPlayerDiagnostics(Context context) {
        this.context = context.getApplicationContext();
        preferences = PreferenceManager.getDefaultSharedPreferences(this.context);
    }

    boolean isEnabled() {
        return preferences.getBoolean(PlusPrefs.KEY_EXTERNAL_PLAYER_DIAGNOSTICS, false);
    }

    void recordLaunch(@Nullable Uri mediaUri,
                      @Nullable String title,
                      boolean fullApiAccess,
                      boolean partialApiAccess,
                      boolean returnResult,
                      long requestedPosition,
                      int suppliedSubtitleCount) {
        if (!isEnabled() || (!fullApiAccess && !partialApiAccess)) {
            return;
        }
        append("LAUNCH",
                "title=" + clean(title)
                        + " media=" + sanitizeUri(mediaUri)
                        + " fullApi=" + fullApiAccess
                        + " partialApi=" + partialApiAccess
                        + " returnResult=" + returnResult
                        + " requestedPositionMs=" + value(requestedPosition)
                        + " suppliedSubtitles=" + suppliedSubtitleCount);
    }

    void recordTracks(@Nullable Player player,
                      String audioReason,
                      String subtitleReason) {
        if (!isEnabled() || player == null) {
            return;
        }
        StringBuilder details = new StringBuilder();
        details.append("audioReason=").append(clean(audioReason))
                .append(" subtitleReason=").append(clean(subtitleReason));

        int groupIndex = 0;
        for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
            int type = group.getType();
            if (type != C.TRACK_TYPE_AUDIO && type != C.TRACK_TYPE_TEXT) {
                groupIndex++;
                continue;
            }
            TrackGroup trackGroup = group.getMediaTrackGroup();
            for (int trackIndex = 0; trackIndex < trackGroup.length; trackIndex++) {
                Format format = trackGroup.getFormat(trackIndex);
                details.append("\n  ")
                        .append(type == C.TRACK_TYPE_AUDIO ? "audio" : "subtitle")
                        .append("[").append(groupIndex).append(":").append(trackIndex).append("]")
                        .append(" selected=").append(group.isTrackSelected(trackIndex))
                        .append(" supported=").append(group.isTrackSupported(trackIndex))
                        .append(" id=").append(clean(format.id))
                        .append(" language=").append(clean(format.language))
                        .append(" label=").append(clean(format.label))
                        .append(" mime=").append(clean(format.sampleMimeType))
                        .append(" roleFlags=").append(format.roleFlags)
                        .append(" selectionFlags=").append(format.selectionFlags);
                if (type == C.TRACK_TYPE_AUDIO) {
                    details.append(" channels=").append(format.channelCount)
                            .append(" bitrate=").append(format.bitrate);
                } else {
                    details.append(" source=")
                            .append(format.id != null
                                    && format.id.startsWith(SmartSubtitleSelector.EXTERNAL_ID_PREFIX)
                                    ? "external" : "embedded");
                }
            }
            groupIndex++;
        }
        append("TRACKS", details.toString());
    }

    void recordResult(long position, long duration, String endBy) {
        if (!isEnabled()) {
            return;
        }
        append("RESULT",
                "positionMs=" + value(position)
                        + " durationMs=" + value(duration)
                        + " endBy=" + clean(endBy));
    }

    void recordError(Throwable error) {
        if (!isEnabled() || error == null) {
            return;
        }
        append("ERROR",
                error.getClass().getSimpleName() + ": " + clean(error.getMessage()));
    }

    void recordNextEpisode(String currentId,
                           String nextId,
                           boolean hasTitle,
                           boolean hasArtwork,
                           String state) {
        if (!isEnabled()) {
            return;
        }
        append("NEXT_EPISODE",
                "current=" + clean(currentId)
                        + " next=" + clean(nextId)
                        + " title=" + hasTitle
                        + " artwork=" + hasArtwork
                        + " state=" + clean(state));
    }

    void recordStremioConnector(String state, String detail) {
        if (!isEnabled()) {
            return;
        }
        append("STREMIO_CONNECTOR",
                "state=" + clean(state) + " detail=" + clean(detail));
    }

    static String read(Context context) {
        synchronized (LOCK) {
            return readUnlocked(context.getApplicationContext());
        }
    }

    static void clear(Context context) {
        synchronized (LOCK) {
            File file = context.getApplicationContext().getFileStreamPath(FILE_NAME);
            if (file.exists()) {
                file.delete();
            }
        }
    }

    private void append(String event, String details) {
        synchronized (LOCK) {
            String previous = readUnlocked(context);
            if (previous.length() > KEEP_CHARS) {
                previous = previous.substring(previous.length() - KEEP_CHARS);
                int firstLineBreak = previous.indexOf('\n');
                if (firstLineBreak >= 0 && firstLineBreak + 1 < previous.length()) {
                    previous = previous.substring(firstLineBreak + 1);
                }
            }

            String timestamp = new SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
            String entry = timestamp + " " + event + " " + details + "\n";
            String output = previous + entry;
            if (output.length() > MAX_CHARS) {
                output = output.substring(output.length() - MAX_CHARS);
            }
            try (FileOutputStream stream = context.openFileOutput(FILE_NAME, Context.MODE_PRIVATE)) {
                stream.write(output.getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // Diagnostics must never affect playback.
            }
        }
    }

    private static String readUnlocked(Context context) {
        File file = context.getFileStreamPath(FILE_NAME);
        if (!file.exists()) {
            return "";
        }
        try (FileInputStream input = context.openFileInput(FILE_NAME);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        } catch (IOException ignored) {
            return "";
        }
    }

    private static String sanitizeUri(@Nullable Uri uri) {
        if (uri == null) {
            return "-";
        }
        String scheme = clean(uri.getScheme());
        String host = clean(uri.getHost());
        if (scheme.isEmpty() || host.isEmpty()) {
            return "local-content";
        }
        // Paths frequently contain CDN signatures or bearer tokens. Host-level identity is
        // sufficient for diagnostics and cannot leak query, user-info or path credentials.
        return scheme + "://" + host;
    }

    private static String clean(@Nullable String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static String value(long value) {
        return value == C.TIME_UNSET ? "unset" : String.valueOf(value);
    }
}
