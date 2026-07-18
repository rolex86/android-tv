from pathlib import Path

path = Path("just-player-plus/app/src/main/java/com/brouken/player/PlayerActivity.java")
text = path.read_text(encoding="utf-8")
old = "        trackSelector.setParameters(subtitleParameters);\n"
new = "        trackSelector.setParameters(subtitleParameters.build());\n"
count = text.count(old)
if count != 1:
    raise RuntimeError(f"Expected one subtitleParameters call, found {count}")
path.write_text(text.replace(old, new, 1), encoding="utf-8")
