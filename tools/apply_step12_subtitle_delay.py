from pathlib import Path

path = Path("just-player-plus/app/src/main/java/com/brouken/player/PlayerActivity.java")
text = path.read_text(encoding="utf-8")


def replace_once(old: str, new: str, label: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one match, found {count}")
    text = text.replace(old, new, 1)


replace_once(
    """        // https://github.com/google/ExoPlayer/issues/8571
        DefaultExtractorsFactory extractorsFactory = new DefaultExtractorsFactory()
                .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS)
                .setTsExtractorTimestampSearchBytes(1500 * TsExtractor.TS_PACKET_SIZE);
""",
    """        // Shift subtitle cue timestamps during Media3 extraction. The standard parsers,
        // text renderer and the complete audio/video renderer pipeline remain unchanged.
        OffsetSubtitleParserFactory subtitleParserFactory = new OffsetSubtitleParserFactory(
                () -> (long) mPlusPrefs.subtitleDelayMs * 1_000L);

        // https://github.com/google/ExoPlayer/issues/8571
        DefaultExtractorsFactory extractorsFactory = new DefaultExtractorsFactory()
                .setSubtitleParserFactory(subtitleParserFactory)
                .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS)
                .setTsExtractorTimestampSearchBytes(1500 * TsExtractor.TS_PACKET_SIZE);
""",
    "progressive subtitle parser factory",
)

replace_once(
    """                .setTrackSelector(trackSelector)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(this, extractorsFactory));
""",
    """                .setTrackSelector(trackSelector)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(this, extractorsFactory)
                        .setSubtitleParserFactory(subtitleParserFactory));
""",
    "default media source subtitle parser factory",
)

replace_once(
    """                    playerBuilder.setMediaSourceFactory(new DefaultMediaSourceFactory(defaultHttpDataSourceFactory, extractorsFactory));
""",
    """                    playerBuilder.setMediaSourceFactory(
                            new DefaultMediaSourceFactory(
                                    defaultHttpDataSourceFactory, extractorsFactory)
                                    .setSubtitleParserFactory(subtitleParserFactory));
""",
    "authenticated media source subtitle parser factory",
)

path.write_text(text, encoding="utf-8")
