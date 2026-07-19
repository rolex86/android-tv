from pathlib import Path

path = Path("just-player-plus/app/src/main/java/com/brouken/player/PlayerActivity.java")
text = path.read_text(encoding="utf-8")
old = """        if (!mPlusPrefs.ignoreCommentaryAudio && !mPlusPrefs.ignoreAudioDescription) {
            return;
        }
"""
count = text.count(old)
if count != 1:
    raise RuntimeError(f"smart audio activation: expected exactly one match, found {count}")
path.write_text(text.replace(old, "", 1), encoding="utf-8")
