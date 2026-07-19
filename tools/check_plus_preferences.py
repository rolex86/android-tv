#!/usr/bin/env python3
"""Fail CI when a JustPlayer Plus setting becomes UI-only or loses its runtime hook."""

from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "just-player-plus" / "app" / "src" / "main"
PREFS_PATH = APP / "java" / "com" / "brouken" / "player" / "PlusPrefs.java"
PLAYER_PATH = APP / "java" / "com" / "brouken" / "player" / "PlayerActivity.java"
XML_PATH = APP / "res" / "xml" / "root_preferences.xml"
OFFSET_PATH = APP / "java" / "com" / "brouken" / "player" / "OffsetSubtitleParserFactory.java"

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
    "KEY_REMEMBER_TRACK_SCOPE": "mPlusPrefs.rememberTrackScope",
    "KEY_RESIZE_DEFAULT": "mPlusPrefs.resizeDefault",
    "KEY_SPEED_DEFAULT": "mPlusPrefs.speedDefault",
    "KEY_SEEK_INCREMENT_MS": "mPlusPrefs.seekIncrementMs",
    "KEY_FRAME_RATE_POLICY": "mPlusPrefs.frameRatePolicy",
    "KEY_BACK_BUTTON_BEHAVIOR": "mPlusPrefs.backButtonBehavior",
    "KEY_COMPLETION_RULE": "mPlusPrefs.completionRule",
    "KEY_EXTERNAL_PLAYER_DIAGNOSTICS": "PlusPrefs.KEY_EXTERNAL_PLAYER_DIAGNOSTICS",
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
    "player.setAudioAttributes(audioAttributes, true);",
)
for snippet in protected_snippets:
    count = player.count(snippet)
    if count != 1:
        errors.append(f"Protected playback snippet must occur once: {snippet!r}; found {count}")

parser_injections = player.count(".setSubtitleParserFactory(subtitleParserFactory)")
if parser_injections != 3:
    errors.append(
        "Subtitle delay parser must be injected into extractor, default media source and "
        f"authenticated media source paths; found {parser_injections} injections"
    )

for forbidden in ("Math.addExact", "Math.subtractExact"):
    if forbidden in offset_parser:
        errors.append(f"minSdk-unsafe timestamp helper remains: {forbidden}")

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
