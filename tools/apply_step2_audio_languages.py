from pathlib import Path

ROOT = Path("just-player-plus/app/src/main/java/com/brouken/player")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


# Add the ordered-language resolver to the Plus-only preference model.
plus_prefs = ROOT / "PlusPrefs.java"
text = plus_prefs.read_text(encoding="utf-8")
text = replace_once(
    text,
    "import android.preference.PreferenceManager;\n",
    "import android.preference.PreferenceManager;\n\nimport java.util.LinkedHashSet;\n",
    "PlusPrefs imports",
)
text = replace_once(
    text,
    "    private int parseInt(String key, int fallback) {\n",
    """    String[] getPreferredAudioLanguages() {
        LinkedHashSet<String> languages = new LinkedHashSet<>();

        if (!appendAudioLanguagePreference(languages, audioLanguagePrimary)) {
            return languages.toArray(new String[0]);
        }
        if (!appendAudioLanguagePreference(languages, audioLanguageSecondary)) {
            return languages.toArray(new String[0]);
        }
        appendAudioLanguagePreference(languages, audioLanguageTertiary);

        return languages.toArray(new String[0]);
    }

    private boolean appendAudioLanguagePreference(LinkedHashSet<String> languages,
                                                   String preference) {
        if (preference == null || TRACK_NONE.equals(preference)) {
            return true;
        }
        if (Prefs.TRACK_DEFAULT.equals(preference)) {
            // Media default acts as the fallback point in the ordered list.
            return false;
        }
        if (Prefs.TRACK_DEVICE.equals(preference)) {
            for (String language : Utils.getDeviceLanguages()) {
                if (language != null && !language.isEmpty()) {
                    languages.add(language);
                }
            }
        } else if (!preference.isEmpty()) {
            languages.add(preference);
        }
        return true;
    }

    private int parseInt(String key, int fallback) {
""",
    "PlusPrefs ordered audio languages",
)
plus_prefs.write_text(text, encoding="utf-8")


# Wire the preference model into the existing Media3 track selector. No renderer,
# audio sink, passthrough, FFmpeg, tunneling or Dolby Vision code is touched.
activity = ROOT / "PlayerActivity.java"
text = activity.read_text(encoding="utf-8")
text = replace_once(
    text,
    "    public Prefs mPrefs;\n",
    "    public Prefs mPrefs;\n    public PlusPrefs mPlusPrefs;\n",
    "PlayerActivity field",
)
text = replace_once(
    text,
    "        mPrefs = new Prefs(this);\n        Utils.setOrientation(this, mPrefs.orientation);\n",
    "        mPrefs = new Prefs(this);\n        mPlusPrefs = new PlusPrefs(this);\n        Utils.setOrientation(this, mPrefs.orientation);\n",
    "PlayerActivity initialization",
)
text = replace_once(
    text,
    "        } else if (requestCode == REQUEST_SETTINGS) {\n            mPrefs.loadUserPreferences();\n            updateSubtitleStyle(this);\n",
    "        } else if (requestCode == REQUEST_SETTINGS) {\n            mPrefs.loadUserPreferences();\n            mPlusPrefs.reload();\n            updateSubtitleStyle(this);\n",
    "PlayerActivity settings reload",
)
text = replace_once(
    text,
    """        switch (mPrefs.languageAudio) {
            case Prefs.TRACK_DEFAULT:
                break;
            case Prefs.TRACK_DEVICE:
                trackSelector.setParameters(trackSelector.buildUponParameters()
                        .setPreferredAudioLanguages(Utils.getDeviceLanguages())
                );
                break;
            default:
                trackSelector.setParameters(trackSelector.buildUponParameters()
                        .setPreferredAudioLanguages(mPrefs.languageAudio)
                );
        }
""",
    """        mPlusPrefs.reload();
        final String[] preferredAudioLanguages = mPlusPrefs.getPreferredAudioLanguages();
        if (preferredAudioLanguages.length > 0) {
            trackSelector.setParameters(trackSelector.buildUponParameters()
                    .setPreferredAudioLanguages(preferredAudioLanguages)
            );
        }
""",
    "PlayerActivity preferred audio languages",
)
activity.write_text(text, encoding="utf-8")
