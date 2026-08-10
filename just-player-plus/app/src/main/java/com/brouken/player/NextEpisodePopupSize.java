package com.brouken.player;

/** Complete visual profiles for the next-episode card. */
enum NextEpisodePopupSize {
    SMALL("small", 310, 132, 32, 14, 12, 16, 12, 4, 12, 38, 108, 80, 8, 10, 4, 18),
    MEDIUM("medium", 380, 158, 40, 18, 14, 18, 13, 4, 13, 42, 122, 92, 8, 12, 5, 20),
    LARGE("large", 460, 190, 48, 22, 18, 22, 15, 5, 14, 48, 138, 105, 10, 14, 6, 24);

    static final NextEpisodePopupSize DEFAULT = MEDIUM;

    final String preferenceValue;
    final int cardWidthDp;
    final int cardHeightDp;
    final int outerMarginDp;
    final int horizontalPaddingDp;
    final int verticalPaddingDp;
    final int titleTextSp;
    final int descriptionTextSp;
    final int descriptionMarginTopDp;
    final int buttonTextSp;
    final int buttonHeightDp;
    final int playButtonMinWidthDp;
    final int dismissButtonMinWidthDp;
    final int buttonGapDp;
    final int buttonHorizontalPaddingDp;
    final int playDrawablePaddingDp;
    final int playDrawableSizeDp;

    NextEpisodePopupSize(String preferenceValue,
                         int cardWidthDp,
                         int cardHeightDp,
                         int outerMarginDp,
                         int horizontalPaddingDp,
                         int verticalPaddingDp,
                         int titleTextSp,
                         int descriptionTextSp,
                         int descriptionMarginTopDp,
                         int buttonTextSp,
                         int buttonHeightDp,
                         int playButtonMinWidthDp,
                         int dismissButtonMinWidthDp,
                         int buttonGapDp,
                         int buttonHorizontalPaddingDp,
                         int playDrawablePaddingDp,
                         int playDrawableSizeDp) {
        this.preferenceValue = preferenceValue;
        this.cardWidthDp = cardWidthDp;
        this.cardHeightDp = cardHeightDp;
        this.outerMarginDp = outerMarginDp;
        this.horizontalPaddingDp = horizontalPaddingDp;
        this.verticalPaddingDp = verticalPaddingDp;
        this.titleTextSp = titleTextSp;
        this.descriptionTextSp = descriptionTextSp;
        this.descriptionMarginTopDp = descriptionMarginTopDp;
        this.buttonTextSp = buttonTextSp;
        this.buttonHeightDp = buttonHeightDp;
        this.playButtonMinWidthDp = playButtonMinWidthDp;
        this.dismissButtonMinWidthDp = dismissButtonMinWidthDp;
        this.buttonGapDp = buttonGapDp;
        this.buttonHorizontalPaddingDp = buttonHorizontalPaddingDp;
        this.playDrawablePaddingDp = playDrawablePaddingDp;
        this.playDrawableSizeDp = playDrawableSizeDp;
    }

    static NextEpisodePopupSize fromPreference(String value) {
        if (value != null) {
            for (NextEpisodePopupSize size : values()) {
                if (size.preferenceValue.equals(value)) {
                    return size;
                }
            }
        }
        return DEFAULT;
    }
}
