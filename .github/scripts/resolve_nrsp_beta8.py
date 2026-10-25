#!/usr/bin/env python3
"""Exact reviewed five-conflict resolver for beta.7.3 -> beta.8.1 only."""
from pathlib import Path
import sys
root=Path(sys.argv[1])
prefix="app/src/"
paths={
"full/AndroidManifest.xml":"manifest",
"main/java/com/nuvio/tv/data/local/PlayerSettingsDataStore.kt":"settings",
"main/java/com/nuvio/tv/ui/components/StartupSplashScreen.kt":"splash",
"main/java/com/nuvio/tv/ui/screens/player/PlayerScreen.kt":"player",
"main/java/com/nuvio/tv/ui/screens/settings/PlaybackSubtitleSettings.kt":"subtitles",
}
for name,kind in paths.items():
 p=root/(prefix+name)
 body=p.read_text()
 pieces=body.splitlines(keepends=True)
 starts=[i for i,line in enumerate(pieces) if line.startswith("<<<<<<< ")]
 if len(starts)!=1:
  raise SystemExit(f"{name}: expected exactly one conflict, got {len(starts)}")
 start=starts[0]
 mid=next((i for i in range(start+1,len(pieces)) if pieces[i].startswith("=======")),None)
 end=next((i for i in range(mid+1,len(pieces)) if pieces[i].startswith(">>>>>>> ")),None) if mid is not None else None
 if mid is None or end is None: raise SystemExit(f"Unrecognized conflict in {name}")
 ours="".join(pieces[start+1:mid]); theirs="".join(pieces[mid+1:end])
 if kind=="manifest":
  assert "INSTALL_FAILED_DUPLICATE_PERMISSION" in ours
  assert "UpdateJobService" in theirs and "UpdateInstallResultReceiver" in theirs
  assert 'android:name="com.nuvio.tv.reshaped.ReshapedMigrationProvider"' in theirs
  import re
  theirs=re.sub(r'\s*<provider\s+android:name="com.nuvio.tv.reshaped.ReshapedMigrationProvider".*?/>', '', theirs, flags=re.S)
  replacement=ours+theirs
 elif kind=="settings":
  assert "plusResumeRewindSeconds" in ours and "useLibass" in ours and "useLibass = prefs[useLibassKey] ?: true" in theirs
  replacement=ours.replace("useLibass = prefs[useLibassKey] ?: false","useLibass = prefs[useLibassKey] ?: true")
 elif kind=="splash":
  assert "androidx.tv.material3.Text" in ours and "NuvioPrimitives" in theirs
  replacement=ours+theirs
 elif kind=="player":
  assert "customWhatDidTheySayPainter" in ours and "showEngineSwitchConfirm" in theirs
  replacement=ours+theirs
 elif kind=="subtitles":
  assert "PlusSubtitleSourceDialog" in ours and not theirs.strip()
  replacement=ours
 else: raise SystemExit("unsupported")
 updated="".join(pieces[:start])+replacement+"".join(pieces[end+1:])
 for token in ("<<<<<<< ",">>>>>>> ", "\n=======\n"):
  if token in updated: raise SystemExit(f"Unresolved marker {token} in {name}")
 if kind=="manifest":
  assert "ReshapedMigrationProvider" not in updated
  assert "UpdateJobService" in updated
 if kind=="player":
  assert "customWhatDidTheySayPainter" in updated and "showEngineSwitchConfirm" in updated
 if kind=="settings":
  assert "plusResumeRewindSeconds" in updated and "useLibass = prefs[useLibassKey] ?: true" in updated
 p.write_text(updated)
 print("Resolved reviewed beta.8 conflict:",name)
