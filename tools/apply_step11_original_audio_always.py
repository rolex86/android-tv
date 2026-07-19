from pathlib import Path
import re

path = Path("just-player-plus/app/src/main/java/com/brouken/player/PlayerActivity.java")
text = path.read_text(encoding="utf-8")
pattern = re.compile(
    r"\n\s*if \(!mPlusPrefs\.ignoreCommentaryAudio\s*&&\s*"
    r"!mPlusPrefs\.ignoreAudioDescription\)\s*\{\s*return;\s*\}\s*"
)
text, count = pattern.subn("\n", text, count=1)
if count != 1:
    raise RuntimeError(f"smart audio activation: expected exactly one match, found {count}")
path.write_text(text, encoding="utf-8")
