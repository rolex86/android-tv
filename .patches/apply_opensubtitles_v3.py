#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PLAYER = ROOT / "just-player-plus/app/src/main/java/com/brouken/player/PlayerActivity.java"
RESOLVER = ROOT / "just-player-plus/app/src/main/java/com/brouken/player/aisubtitles/SelectedSubtitleResolver.java"
GRADLE = ROOT / "just-player-plus/app/build.gradle"
MARKER = ROOT / ".patches/run-opensubtitles-v3"
BUILD_WORKFLOW = ROOT / ".github/workflows/build-just-player-plus.yml"


def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one anchor, found {count}")
    return text.replace(old, new, 1)


player = PLAYER.read_text(encoding="utf-8")
player = replace_once(
    player,
    "        boolean preserveSelections = currentPlayer.getPlaybackState() == Player.STATE_READY\n"
    "                || trackMemoryArmed;\n"
    "        if (preserveSelections) {\n"
    "            openSubtitlesSelectionBeforeAttach = RememberedTrackStore.capture(\n"
    "                    currentPlayer.getCurrentTracks(),\n"
    "                    currentPlayer.getTrackSelectionParameters(),\n"
    "                    !audioSelectionExplicit,\n"
    "                    !subtitleSelectionExplicit);\n"
    "            openSubtitlesAudioExplicitBeforeAttach = audioSelectionExplicit;\n"
    "            openSubtitlesSubtitleExplicitBeforeAttach = subtitleSelectionExplicit;\n"
    "            openSubtitlesPersistAudioBeforeAttach = persistAudioSelectionPerFile;\n"
    "            openSubtitlesPersistSubtitleBeforeAttach = persistSubtitleSelectionPerFile;\n"
    "            openSubtitlesAttachPending = true;\n"
    "            trackMemoryArmed = false;\n"
    "            trackSelectionChangePending = false;\n"
    "        } else {\n"
    "            clearOpenSubtitlesAttachState();\n"
    "        }",
    "        boolean preserveSelections = currentPlayer.getPlaybackState() == Player.STATE_READY\n"
    "                || trackMemoryArmed\n"
    "                || audioSelectionExplicit\n"
    "                || subtitleSelectionExplicit;\n"
    "        openSubtitlesAudioExplicitBeforeAttach = audioSelectionExplicit;\n"
    "        openSubtitlesSubtitleExplicitBeforeAttach = subtitleSelectionExplicit;\n"
    "        openSubtitlesPersistAudioBeforeAttach = persistAudioSelectionPerFile;\n"
    "        openSubtitlesPersistSubtitleBeforeAttach = persistSubtitleSelectionPerFile;\n"
    "        openSubtitlesAttachPending = true;\n"
    "        trackMemoryArmed = false;\n"
    "        trackSelectionChangePending = false;\n"
    "        if (preserveSelections) {\n"
    "            openSubtitlesSelectionBeforeAttach = RememberedTrackStore.capture(\n"
    "                    currentPlayer.getCurrentTracks(),\n"
    "                    currentPlayer.getTrackSelectionParameters(),\n"
    "                    !audioSelectionExplicit,\n"
    "                    !subtitleSelectionExplicit);\n"
    "        } else {\n"
    "            openSubtitlesSelectionBeforeAttach = null;\n"
    "        }",
    "OpenSubtitles attach selection state",
)
player = replace_once(
    player,
    "        } else {\n"
    "            applySmartAudioSelection();\n"
    "            applySmartSubtitleSelection();\n"
    "        }\n\n"
    "        audioSelectionExplicit = openSubtitlesAudioExplicitBeforeAttach && audioRestored;",
    "        } else {\n"
    "            applySmartSubtitleSelection();\n"
    "        }\n\n"
    "        audioSelectionExplicit = openSubtitlesAudioExplicitBeforeAttach && audioRestored;",
    "OpenSubtitles initial smart selection",
)
PLAYER.write_text(player, encoding="utf-8")

resolver = RESOLVER.read_text(encoding="utf-8")
resolver = replace_once(
    resolver,
    "                Format format = trackGroup.getFormat(index);\n"
    "                String id = format.id;\n"
    "                String mime = normalizeMime(format.sampleMimeType);\n"
    "                if (isImageBased(mime)) {\n"
    "                    return Resolution.failed(Issue.IMAGE_BASED);\n"
    "                }\n"
    "                if (id == null || !id.startsWith(EXTERNAL_ID_PREFIX)) {\n"
    "                    return Resolution.failed(Issue.EMBEDDED);\n"
    "                }\n"
    "                if (!isSupportedMime(mime)) {\n"
    "                    return Resolution.failed(Issue.UNSUPPORTED_FORMAT);\n"
    "                }\n"
    "                MediaItem.SubtitleConfiguration configuration = findConfiguration(\n"
    "                        configurations, id);\n"
    "                if (configuration == null || !hasReadableScheme(configuration.uri)) {\n"
    "                    return Resolution.failed(Issue.URI_UNREADABLE);\n"
    "                }",
    "                Format format = trackGroup.getFormat(index);\n"
    "                String id = format.id;\n"
    "                if (id == null || !id.startsWith(EXTERNAL_ID_PREFIX)) {\n"
    "                    return Resolution.failed(Issue.EMBEDDED);\n"
    "                }\n"
    "                MediaItem.SubtitleConfiguration configuration = findConfiguration(\n"
    "                        configurations, id);\n"
    "                if (configuration == null || !hasReadableScheme(configuration.uri)) {\n"
    "                    return Resolution.failed(Issue.URI_UNREADABLE);\n"
    "                }\n"
    "                String trackMime = normalizeMime(format.sampleMimeType);\n"
    "                String configuredMime = normalizeMime(configuration.mimeType);\n"
    "                String mime = isSupportedMime(configuredMime)\n"
    "                        || isImageBased(configuredMime)\n"
    "                        ? configuredMime : trackMime;\n"
    "                if (isImageBased(mime)) {\n"
    "                    return Resolution.failed(Issue.IMAGE_BASED);\n"
    "                }\n"
    "                if (!isSupportedMime(mime)) {\n"
    "                    return Resolution.failed(Issue.UNSUPPORTED_FORMAT);\n"
    "                }",
    "AI subtitle MIME resolution",
)
RESOLVER.write_text(resolver, encoding="utf-8")

gradle = GRADLE.read_text(encoding="utf-8")
gradle = replace_once(
    gradle,
    "        versionCode 216",
    "        versionCode 217",
    "version code",
)
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
workflow = workflow.replace(
    "permissions:\n  contents: write",
    "permissions:\n  contents: read",
    1,
)
BUILD_WORKFLOW.write_text(workflow, encoding="utf-8")
