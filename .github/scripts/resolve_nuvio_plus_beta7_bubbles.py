#!/usr/bin/env python3
"""Reviewed, one-time Plus preservation patch for Nuvio RS beta.7 autosync.1 -> .2.

Invoked only after the workflow confirmed exactly the two expected conflicts
and checked out the incoming upstream copies. The upstream Git blob IDs are
pinned; an unexpected change fails closed rather than silently losing Plus code.
"""
from __future__ import annotations

import hashlib
import sys
from pathlib import Path

DIRECTORY = Path("app/src/main/java/com/nuvio/tv/ui/screens/player/autosync/bubble")
EXPECTED_UPSTREAM_BLOBS = {
    "AutoSyncBubbleToast.kt": "b46bbe18b0276c9c2b94131286cc9b34ff01fd9d",
    "AutoSyncBubbleToasts.kt": "79099c8c2201b7bb87f5567e41a73424b31bf319",
}


def once(source: str, before: str, after: str, filename: str) -> str:
    count = source.count(before)
    if count != 1:
        raise RuntimeError(f"{filename}: expected a unique reviewed anchor; found {count}")
    return source.replace(before, after, 1)


def patch_bubble(source: str) -> str:
    filename = "AutoSyncBubbleToast.kt"
    source = once(
        source,
        """/** How long the working bubble keeps its words before settling to just the droplet. */
private const val WORKING_LABEL_MS = 7_000L
/** A run that never reports back fades away after this. */
private const val WORKING_TIMEOUT_MS = 15_000L""".replace("\/", "/"),
        """/** Plus: informational progress stays visible briefly, then fully clears the video. */
private const val WORKING_PREVIEW_MS = 4_500L""".replace("\/", "/"),
        filename,
    )
    source = once(
        source,
        """    LaunchedEffect(message.id) {
        if (kind != AutoSyncBubbleKind.Working) return@LaunchedEffect
        labelVisible = true
        delay(WORKING_LABEL_MS)
        labelFoldedAtMs = SystemClock.uptimeMillis()
        labelVisible = false
    }
    // Progress updates keep the same session: they must not restart the loading timeout.
    LaunchedEffect(message.session, kind) {
        if (kind != AutoSyncBubbleKind.Working) return@LaunchedEffect
        delay(WORKING_TIMEOUT_MS)
        dismiss(hideWorking = true)
    }""",
        """    // Plus: routine progress updates must not prolong the working notification.
    // A new explicitly requested preview may restart this short window.
    LaunchedEffect(message.workingPreviewId, kind) {
        if (kind != AutoSyncBubbleKind.Working) return@LaunchedEffect
        labelVisible = true
        delay(WORKING_PREVIEW_MS)
        dismiss(hideWorking = true)
    }""",
        filename,
    )
    for required in (
        "WORKING_PREVIEW_MS = 4_500L",
        "LaunchedEffect(message.workingPreviewId, kind)",
        "dismiss(hideWorking = true)",
        "AutoSyncBubbleToasts.finished(latestMessage.id)",
    ):
        if required not in source:
            raise RuntimeError(f"{filename}: missing preserved feature {required}")
    if "WORKING_TIMEOUT_MS" in source:
        raise RuntimeError(f"{filename}: unwanted upstream progress timeout remains")
    return source


def patch_messages(source: str) -> str:
    filename = "AutoSyncBubbleToasts.kt"
    source = once(
        source,
        """    val headline: String,
    val detail: String?,
)""",
        """    val headline: String,
    val detail: String?,
    /** Original start of the AutoSync session; progress updates never reset this. */
    val startedAtUptimeMs: Long,
    /** Changes only for an explicitly requested working preview. */
    val workingPreviewId: Long,
)""",
        filename,
    )
    source = once(
        source,
        "    fun post(kind: AutoSyncBubbleKind, text: String): Boolean {",
        """    fun post(
        kind: AutoSyncBubbleKind,
        text: String,
        forceWorkingPreview: Boolean = false,
    ): Boolean {""",
        filename,
    )
    source = once(
        source,
        """        _current.update { previous ->
            // A run continues while the bubble is still working; anything else (a result, or a
            // bubble already fading away) starts a new bubble.
            val session = if (
                previous != null && previous.kind == AutoSyncBubbleKind.Working &&
                previous.session != leavingSession
            ) previous.session else id
            AutoSyncBubbleMessage(id, session, kind, headline, detail)
        }""",
        """        _current.update { previous ->
            // Preserve the original Plus start time and working-preview window.
            // Ordinary status updates must not keep the bubble in front of the video.
            val continuesSession =
                previous != null &&
                    previous.kind == AutoSyncBubbleKind.Working &&
                    previous.session != leavingSession
            val session = if (continuesSession) previous!!.session else id
            val startedAt = if (continuesSession) {
                previous!!.startedAtUptimeMs
            } else {
                android.os.SystemClock.uptimeMillis()
            }
            val workingPreviewId = when {
                kind != AutoSyncBubbleKind.Working -> previous?.workingPreviewId ?: id
                !continuesSession || forceWorkingPreview -> id
                else -> previous!!.workingPreviewId
            }
            AutoSyncBubbleMessage(
                id = id,
                session = session,
                kind = kind,
                headline = headline,
                detail = detail,
                startedAtUptimeMs = startedAt,
                workingPreviewId = workingPreviewId,
            )
        }""",
        filename,
    )
    source = once(
        source,
        """    duration: Int = Toast.LENGTH_SHORT,
) {
    val appContext = context.applicationContext""",
        """    duration: Int = Toast.LENGTH_SHORT,
    forceWorkingPreview: Boolean = false,
) {
    val appContext = context.applicationContext""",
        filename,
    )
    source = once(
        source,
        "        if (!AutoSyncBubbleToasts.post(kind, message)) {",
        "        if (!AutoSyncBubbleToasts.post(kind, message, forceWorkingPreview)) {",
        filename,
    )
    for required in (
        "KEY_FAILURES_ONLY",
        "fun beginRun()",
        "fun hideWorking(session: Long)",
        "shouldNotify(kind, _failuresOnly.value)",
        "forceWorkingPreview: Boolean = false",
        "startedAtUptimeMs: Long",
        "workingPreviewId: Long",
    ):
        if required not in source:
            raise RuntimeError(f"{filename}: missing preserved upstream/Plus feature {required}")
    return source


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: resolve_nuvio_plus_beta7_bubbles.py <upstream-checkout>")
    checkout = Path(sys.argv[1]).resolve()
    replacements = {}
    for filename, patcher in (
        ("AutoSyncBubbleToast.kt", patch_bubble),
        ("AutoSyncBubbleToasts.kt", patch_messages),
    ):
        path = checkout / DIRECTORY / filename
        raw = path.read_bytes()
        blob = hashlib.sha1(f"blob {len(raw)}\\0".encode() + raw).hexdigest()
        if blob != EXPECTED_UPSTREAM_BLOBS[filename]:
            raise RuntimeError(f"{filename}: upstream blob changed ({blob}); refusing unsafe merge")
        patched = patcher(raw.decode("utf-8"))
        if "<<<<<<<" in patched or ">>>>>>>" in patched:
            raise RuntimeError(f"{filename}: unresolved conflict marker")
        replacements[path] = patched
    # Only write files after both reviewed patches and all invariants succeeded.
    for path, content in replacements.items():
        path.write_text(content, encoding="utf-8")
        print(f"Applied reviewed Plus + upstream AutoSync merge: {path.name}")


if __name__ == "__main__":
    main()
