package com.brouken.player;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.content.res.AppCompatResources;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Focus-safe Android TV presentation for a resolved next episode. */
final class NextEpisodeOverlay {
    private static final int MAX_ARTWORK_BYTES = 8 * 1024 * 1024;

    interface Listener {
        void onPlayNow();
        void onDismiss();
    }

    private final View card;
    private final ImageView artwork;
    private final LinearLayout content;
    private final TextView seriesTitle;
    private final TextView description;
    private final LinearLayout actions;
    private final Button playNow;
    private final Button dismiss;
    private final OkHttpClient client;
    private NextEpisodePopupSize popupSize = NextEpisodePopupSize.DEFAULT;
    private Call artworkCall;
    private int artworkGeneration;

    NextEpisodeOverlay(View root,
                       OkHttpClient client,
                       String popupSizePreference,
                       Listener listener) {
        this.client = client;
        card = root.findViewById(R.id.next_episode_card);
        artwork = root.findViewById(R.id.next_episode_artwork);
        content = root.findViewById(R.id.next_episode_content);
        seriesTitle = root.findViewById(R.id.next_episode_series_title);
        description = root.findViewById(R.id.next_episode_description);
        actions = root.findViewById(R.id.next_episode_actions);
        playNow = root.findViewById(R.id.next_episode_play_now);
        dismiss = root.findViewById(R.id.next_episode_dismiss);
        card.setClipToOutline(true);
        applySize(popupSizePreference);
        playNow.setOnClickListener(view -> listener.onPlayNow());
        dismiss.setOnClickListener(view -> listener.onDismiss());
    }

    void applySize(String preference) {
        popupSize = NextEpisodePopupSize.fromPreference(preference);

        ViewGroup.LayoutParams rawCardParams = card.getLayoutParams();
        if (rawCardParams instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams cardParams =
                    (ViewGroup.MarginLayoutParams) rawCardParams;
            int screenWidth = card.getResources().getDisplayMetrics().widthPixels;
            int outerMargin = screenWidth < dp(600) ? dp(16) : dp(popupSize.outerMarginDp);
            cardParams.width = Math.min(
                    dp(popupSize.cardWidthDp),
                    Math.max(1, screenWidth - 2 * outerMargin));
            cardParams.height = dp(popupSize.cardHeightDp);
            cardParams.setMarginEnd(outerMargin);
            cardParams.bottomMargin = outerMargin;
            card.setLayoutParams(cardParams);
        }

        content.setPadding(
                dp(popupSize.horizontalPaddingDp),
                dp(popupSize.verticalPaddingDp),
                dp(popupSize.horizontalPaddingDp),
                dp(popupSize.verticalPaddingDp));
        seriesTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, popupSize.titleTextSp);
        description.setTextSize(TypedValue.COMPLEX_UNIT_SP, popupSize.descriptionTextSp);

        ViewGroup.LayoutParams rawDescriptionParams = description.getLayoutParams();
        if (rawDescriptionParams instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams descriptionParams =
                    (ViewGroup.MarginLayoutParams) rawDescriptionParams;
            descriptionParams.topMargin = dp(popupSize.descriptionMarginTopDp);
            description.setLayoutParams(descriptionParams);
        }

        ViewGroup.LayoutParams actionsParams = actions.getLayoutParams();
        actionsParams.height = dp(popupSize.buttonHeightDp);
        actions.setLayoutParams(actionsParams);

        applyButtonSize(playNow, popupSize.playButtonMinWidthDp);
        applyButtonSize(dismiss, popupSize.dismissButtonMinWidthDp);
        ViewGroup.LayoutParams rawDismissParams = dismiss.getLayoutParams();
        if (rawDismissParams instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams dismissParams =
                    (ViewGroup.MarginLayoutParams) rawDismissParams;
            dismissParams.setMarginStart(dp(popupSize.buttonGapDp));
            dismiss.setLayoutParams(dismissParams);
        }

        Drawable playDrawable = AppCompatResources.getDrawable(
                card.getContext(), R.drawable.ic_play_arrow_24dp);
        if (playDrawable != null) {
            int drawableSize = dp(popupSize.playDrawableSizeDp);
            playDrawable.setBounds(0, 0, drawableSize, drawableSize);
        }
        playNow.setCompoundDrawablesRelative(playDrawable, null, null, null);
        playNow.setCompoundDrawablePadding(dp(popupSize.playDrawablePaddingDp));
    }

    private void applyButtonSize(Button button, int minWidthDp) {
        ViewGroup.LayoutParams params = button.getLayoutParams();
        params.height = dp(popupSize.buttonHeightDp);
        button.setLayoutParams(params);
        button.setMinWidth(dp(minWidthDp));
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, popupSize.buttonTextSp);
        int paddingStart = dp(popupSize.buttonHorizontalPaddingDp);
        int paddingEnd = button == playNow && popupSize == NextEpisodePopupSize.LARGE
                ? dp(16) : paddingStart;
        button.setPaddingRelative(
                paddingStart, button.getPaddingTop(), paddingEnd, button.getPaddingBottom());
    }

    boolean isVisible() {
        return card.getVisibility() == View.VISIBLE;
    }

    /** Handles TV navigation explicitly so no hidden player control can consume it first. */
    boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        boolean navigationKey = keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                || keyCode == KeyEvent.KEYCODE_DPAD_UP
                || keyCode == KeyEvent.KEYCODE_DPAD_DOWN;
        boolean confirmKey = keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                || keyCode == KeyEvent.KEYCODE_BUTTON_A
                || keyCode == KeyEvent.KEYCODE_BUTTON_SELECT
                || keyCode == KeyEvent.KEYCODE_SPACE;
        if (!navigationKey && !confirmKey) {
            return false;
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            dismiss.requestFocus();
        } else if (navigationKey) {
            playNow.requestFocus();
        } else {
            (dismiss.hasFocus() ? dismiss : playNow).performClick();
        }
        return true;
    }

    void show(NextEpisodeInfo info) {
        seriesTitle.setText(info.seriesTitle != null
                ? info.seriesTitle : card.getContext().getString(R.string.next_episode_heading));
        String episodeCode = info.next.displayCode();
        description.setText(info.episodeTitle != null
                ? episodeCode + "  ·  " + info.episodeTitle : episodeCode);
        loadArtwork(info.artworkUrl);

        card.animate().cancel();
        card.bringToFront();
        card.setVisibility(View.VISIBLE);
        card.setAlpha(0f);
        card.setTranslationX(dp(36));
        // Focus before the animation as well as after it. On Android TV the hidden player
        // controller can otherwise retain focus until its own exit animation finishes.
        playNow.requestFocus();
        card.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(360L)
                .withEndAction(playNow::requestFocus)
                .start();
    }

    void hide(boolean animate) {
        cancelArtwork();
        card.animate().cancel();
        if (!animate || card.getVisibility() != View.VISIBLE) {
            clearButtonFocus();
            card.setVisibility(View.GONE);
            card.setAlpha(1f);
            card.setTranslationX(0f);
            return;
        }
        card.animate()
                .alpha(0f)
                .translationX(dp(24))
                .setDuration(180L)
                .withEndAction(() -> {
                    clearButtonFocus();
                    card.setVisibility(View.GONE);
                    card.setAlpha(1f);
                    card.setTranslationX(0f);
                })
                .start();
    }

    private void clearButtonFocus() {
        playNow.clearFocus();
        dismiss.clearFocus();
    }

    void release() {
        cancelArtwork();
        card.animate().cancel();
    }

    private void loadArtwork(String url) {
        cancelArtwork();
        artwork.setImageDrawable(null);
        artwork.setVisibility(url == null ? View.GONE : View.VISIBLE);
        if (url == null) {
            return;
        }
        int generation = ++artworkGeneration;
        Request request;
        try {
            request = new Request.Builder().url(url).build();
        } catch (IllegalArgumentException error) {
            artwork.setVisibility(View.GONE);
            return;
        }
        artworkCall = client.newCall(request);
        artworkCall.enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException error) {
                hideFailedArtwork(generation);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (Response closeable = response) {
                    ResponseBody body = closeable.body();
                    if (!closeable.isSuccessful() || body == null
                            || body.contentLength() > MAX_ARTWORK_BYTES) {
                        hideFailedArtwork(generation);
                        return;
                    }
                    byte[] bytes = readBounded(body.byteStream());
                    Bitmap bitmap = decodeSampled(
                            bytes, dp(popupSize.cardWidthDp), dp(popupSize.cardHeightDp));
                    if (bitmap == null) {
                        hideFailedArtwork(generation);
                        return;
                    }
                    artwork.post(() -> {
                        if (generation == artworkGeneration && isVisible()) {
                            artwork.setImageBitmap(bitmap);
                            artwork.setVisibility(View.VISIBLE);
                        } else {
                            bitmap.recycle();
                        }
                    });
                } catch (IOException error) {
                    hideFailedArtwork(generation);
                }
            }
        });
    }

    private void hideFailedArtwork(int generation) {
        artwork.post(() -> {
            if (generation == artworkGeneration) {
                artwork.setVisibility(View.GONE);
            }
        });
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > MAX_ARTWORK_BYTES) {
                throw new IOException("Artwork response is too large");
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static Bitmap decodeSampled(byte[] bytes, int targetWidth, int targetHeight) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= targetWidth
                && bounds.outHeight / (sample * 2) >= targetHeight) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    }

    private void cancelArtwork() {
        artworkGeneration++;
        if (artworkCall != null) {
            artworkCall.cancel();
            artworkCall = null;
        }
    }

    private int dp(int value) {
        return Math.round(value * card.getResources().getDisplayMetrics().density);
    }
}
