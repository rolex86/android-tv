# OpenSubtitles v3 integration

JustPlayer Plus loads a bounded set of preferred-language subtitle tracks directly from the official OpenSubtitles v3 Stremio addon after the local Stremio connector resolves the current IMDb content ID.

The fetched tracks remain ordinary external Media3 subtitle tracks. Existing smart audio/subtitle selection and remembered manual audio/subtitle combinations remain authoritative. OpenSubtitles results are limited, deduplicated and can be translated by the optional AI subtitle workflow.
