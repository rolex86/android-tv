from pathlib import Path

path = Path("just-player-plus/app/src/main/java/com/brouken/player/PlayerActivity.java")
text = path.read_text(encoding="utf-8")
old = """        mPlusPrefs.reload();
        if (!mPlusPrefs.ignoreCommentaryAudio && !mPlusPrefs.ignoreAudioDescription) {
            return;
        }

        TrackSelectionOverride audioOverride = SmartAudioSelector.findPreferredAudio(
"""
new = """        mPlusPrefs.reload();

        TrackSelectionOverride audioOverride = SmartAudioSelector.findPreferredAudio(
"""
count = text.count(old)
if count != 1:
    raise RuntimeError(f"smart audio activation: expected exactly one match, found {count}")
path.write_text(text.replace(old, new, 1), encoding="utf-8")
