#!/usr/bin/env python3
"""Fail-closed resolution of the reviewed NRSP beta.9 -> beta.10 sync.

Run only inside the upstream clone after Git has reported the exact three
expected conflicts. Stage :2 is our Plus code; :3 is upstream beta.10.
Never silently prefer either side for an unreviewed revision.
"""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path


SELECTOR = "app/src/main/java/com/nuvio/tv/core/player/StreamAutoPlaySelector.kt"
STREAMS = "app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerRuntimeControllerStreams.kt"
EXPECTED = {
    SELECTOR: (
        "5736fef859f3a6a8e2869147e7f97385116f7b1e",  # NRSP beta.9
        "e7e018564a783e91392e9f35626fa627d0b575ea",  # RS beta.10
    ),
    STREAMS: (
        "b45210d7109953cac4d3866f0e4d4ef93a840242",  # NRSP beta.9
        "4a8155ce946b49c4070cca80697fe98aea14846d",  # RS beta.10
    ),
}


def git(root: Path, *args: str) -> str:
    return subprocess.check_output(
        ["git", "-C", str(root), *args], text=True
    ).rstrip("\n")


def replace_exact(body: str, before: str, after: str, label: str) -> str:
    occurrences = body.count(before)
    if occurrences != 1:
        raise SystemExit(f"Unsafe beta.10 merge: {label}: expected 1 anchor, found {occurrences}")
    return body.replace(before, after, 1)


def extract_unique(body: str, start: str, end: str, label: str) -> str:
    if body.count(start) != 1 or body.count(end) != 1:
        raise SystemExit(f"Unsafe beta.10 merge: {label}: ambiguous Plus fragment")
    return body.split(start, 1)[1].split(end, 1)[0]


def resolve_selector(ours: str, theirs: str) -> str:
    # beta.10 introduces plugin identity matching. Keep it intact. The only
    # NRSP delta to the beta.9 selector besides the old MANUAL early guard is
    # remote debugging. Dropping the guard restores beta.10's binge matching
    # in MANUAL, while effectiveMode/FIRST_STREAM and fallback settings remain
    # controlled by the player controller.
    old_start = '        com.nuvio.tv.core.diagnostics.RemoteDebugConsole.record(\n            "AUTO_SOURCE",'
    old_end = '        return selected\n'
    addition = old_start + extract_unique(
        ours, old_start, old_end, "Plus AUTO_SOURCE block"
    ) + old_end
    theirs = replace_exact(
        theirs,
        "        return com.nuvio.tv.core.streams.ranking.RankedRegexWork.run(",
        "        val selected = com.nuvio.tv.core.streams.ranking.RankedRegexWork.run(",
        "selector asynchronous return",
    )
    theirs = replace_exact(
        theirs,
        "        }\n    }\n\n    fun selectAutoPlayStream(",
        "        }\n" + addition + "    }\n\n    fun selectAutoPlayStream(",
        "selector diagnostics insertion",
    )
    # The current NRSP manual-mode selector guard is a deliberate policy.
    # Auto-next runs with effectiveMode FIRST_STREAM when configured, so this
    # guard does not disable the existing auto-next settings or their fallback.
    theirs = replace_exact(
        theirs,
        "        if (streams.isEmpty()) return null\\n",
        "        if (mode == StreamAutoPlayMode.MANUAL || streams.isEmpty()) return null\\n",
        "preserve NRSP manual-mode selector behavior",
    )
    if "fun selectEarlyBingeGroupMatch(" not in theirs:
        raise SystemExit("Unsafe beta.10 merge: missing upstream early plugin matching")
    return theirs


def resolve_streams(ours: str, theirs: str) -> str:
    # Keep Plus source-selection diagnostics on both HTTP and torrent paths.
    theirs = replace_exact(
        theirs,
        '    val playbackRequest = PlayerMediaSourceFactory.normalizePlaybackRequest(url, headers)\n',
        '    com.nuvio.tv.core.diagnostics.RemoteStreamDiagnostics.selected("http_playback", stream)\n'
        '    val playbackRequest = PlayerMediaSourceFactory.normalizePlaybackRequest(url, headers)\n',
        "HTTP stream diagnostics",
    )
    theirs = replace_exact(
        theirs,
        '    val infoHash = stream.getEffectiveInfoHash() ?: return\n'
        '    sourceRecoveryPickerError = null\n',
        '    val infoHash = stream.getEffectiveInfoHash() ?: return\n'
        '    com.nuvio.tv.core.diagnostics.RemoteStreamDiagnostics.selected("torrent_playback", stream)\n'
        '    sourceRecoveryPickerError = null\n',
        "torrent stream diagnostics",
    )

    # Restore the beta.9 NRSP filename/release-family fallback diagnostics,
    # keeping beta.10's selectEarlyBingeGroupMatch() for plugin identities.
    marker = '                com.nuvio.tv.core.diagnostics.RemoteDebugConsole.record(\n                    "BINGE_MATCH",'
    ending = '                if (nextFilename != null) {'
    match_block = marker + extract_unique(ours, marker, ending, "Plus BINGE_MATCH")
    theirs = replace_exact(
        theirs, ending, match_block + ending, "binge diagnostics insertion"
    )

    marker = '                    com.nuvio.tv.core.diagnostics.RemoteDebugConsole.record(\n                        "BINGE_FILENAME_RESULT",'
    ending = '                    if (bingeAndFilename != null) return bingeAndFilename'
    filename_block = marker + extract_unique(
        ours, marker, ending, "Plus BINGE_FILENAME_RESULT"
    )
    theirs = replace_exact(
        theirs, ending, filename_block + ending, "filename diagnostics insertion"
    )

    marker = '                // Same release family:'
    ending = '                // Early fallback: binge group alone'
    family_block = marker + extract_unique(
        ours, marker, ending, "Plus same-release-family matcher"
    )
    theirs = replace_exact(
        theirs, ending, family_block + ending, "same-release-family insertion"
    )

    # Approved policy: prioritize the original local plugin, but do not
    # exclude installed addons, server sources or alternative plugin streams.
    # preferredPluginOnly orders/tries the preferred scraper first and runs
    # other scrapers if it returns nothing; continuePluginOnly removes addons
    # and server sources, which is NOT the policy for NRSP.
    filtered = "data.withoutAddonsWhenContinuingPlugin(currentStreamBingeGroup)"
    if theirs.count(filtered) != 3:
        raise SystemExit("Unsafe beta.10 merge: unexpected plugin-filter call sites")
    theirs = theirs.replace(filtered, "data")

    exclusive = "continuePluginOnly = continuingPlugin"
    if theirs.count(exclusive) != 2:
        raise SystemExit("Unsafe beta.10 merge: unexpected plugin-only search call sites")
    theirs = theirs.replace(exclusive, "continuePluginOnly = false")

    # Remove now-unused filtering helper: no silent source exclusion.
    helper = "private fun List<AddonStreams>.withoutAddonsWhenContinuingPlugin("
    if theirs.count(helper) != 1:
        raise SystemExit("Unsafe beta.10 merge: plugin-only filter helper missing")
    index = theirs.index(helper)
    if theirs[index:].count("}\n") < 2:
        raise SystemExit("Unsafe beta.10 merge: unexpected plugin filter helper structure")
    if theirs[:index].rstrip().endswith("}"):
        theirs = theirs[:index].rstrip() + "\n"
    else:
        raise SystemExit("Unsafe beta.10 merge: plugin filter helper not at end")

    required = [
        "FilenameEpisodeMatcher.matchesNextRelease(",
        "BINGE_MATCH", "BINGE_FILENAME_RESULT", "BINGE_RELEASE_RESULT",
        "BINGE_FALLBACK", "preferredPluginOnly = continuingPlugin",
        "continuePluginOnly = false",
        "StreamAutoPlaySelector.selectEarlyBingeGroupMatch(",
    ]
    for token in required:
        if token not in theirs:
            raise SystemExit(f"Unsafe beta.10 merge: lost required capability: {token}")
    return theirs


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("Usage: resolve_nrsp_beta10.py UPSTREAM_GIT_TREE")
    root = Path(sys.argv[1]).resolve()
    expected_conflicts = sorted([SELECTOR, STREAMS, "scripts/perf_baseline.sh"])
    unresolved = sorted(git(root, "diff", "--name-only", "--diff-filter=U").splitlines())
    if unresolved != expected_conflicts:
        raise SystemExit(f"Unsafe beta.10 merge: unexpected conflicts: {unresolved}")

    contents: dict[str, tuple[str, str]] = {}
    for path, (expected_ours, expected_theirs) in EXPECTED.items():
        ours_sha = git(root, "rev-parse", f":2:{path}")
        theirs_sha = git(root, "rev-parse", f":3:{path}")
        if (ours_sha, theirs_sha) != (expected_ours, expected_theirs):
            raise SystemExit(
                f"Unsafe beta.10 merge: unreviewed blobs in {path}: "
                f"ours={ours_sha} theirs={theirs_sha}"
            )
        ours = git(root, "show", f":2:{path}") + "\n"
        theirs = git(root, "show", f":3:{path}") + "\n"
        contents[path] = (ours, theirs)

    selector = resolve_selector(*contents[SELECTOR])
    streams = resolve_streams(*contents[STREAMS])

    for path, body in ((SELECTOR, selector), (STREAMS, streams)):
        if any(mark in body for mark in ("<<<<<<< ", "=======", ">>>>>>> ")):
            raise SystemExit(f"Unsafe beta.10 merge: conflict marker in {path}")
        (root / path).write_text(body, encoding="utf-8")

    # Non-product helper: beta.9 Plus had deleted it. Keep beta.10 upstream's
    # changed helper as-is rather than silently discarding upstream changes.
    subprocess.check_call(
        ["git", "-C", str(root), "checkout", "--theirs", "--", "scripts/perf_baseline.sh"]
    )
    subprocess.check_call(
        ["git", "-C", str(root), "add", "--", SELECTOR, STREAMS, "scripts/perf_baseline.sh"]
    )
    if git(root, "diff", "--name-only", "--diff-filter=U"):
        raise SystemExit("Unsafe beta.10 merge: unresolved conflicts remain")
    subprocess.check_call(["git", "-C", str(root), "diff", "--cached", "--check"])
    print("Resolved exact beta.9 -> beta.10 conflicts; preserved NRSP logging, filename family fallback and all-source recovery.")


if __name__ == "__main__":
    main()
