from pathlib import Path

path = Path("just-player-plus/app/src/main/java/com/brouken/player/PlayerActivity.java")
text = path.read_text(encoding="utf-8")
old = """                mPlusPrefs.getPreferredAudioLanguages(),
                mPlusPrefs.ignoreCommentaryAudio,
                mPlusPrefs.ignoreAudioDescription);
"""
new = """                mPlusPrefs.getPreferredAudioLanguages(),
                mPlusPrefs.ignoreCommentaryAudio,
                mPlusPrefs.ignoreAudioDescription,
                \"best\".equals(mPlusPrefs.audioQualityPreference));
"""
count = text.count(old)
if count != 1:
    raise RuntimeError(f"Expected one SmartAudioSelector call, found {count}")
path.write_text(text.replace(old, new, 1), encoding="utf-8")
