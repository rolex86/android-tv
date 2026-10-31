package com.nuvio.tv.core.diagnostics

import android.content.Context
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.SecureRandom
import java.time.Instant
import java.util.ArrayDeque
import kotlin.concurrent.thread

/** Opt-in, local-network diagnostics. Never record raw stream URLs, headers or credentials. */
object RemoteDebugConsole {
    private const val PORT = 8765
    private const val MAX_LINES = 1200
    private val lock = Any()
    private val lines = ArrayDeque<String>()
    @Volatile private var listener: ServerSocket? = null
    @Volatile private var enabled = false
    private val tokenBytes = ByteArray(12)

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences("remote_debug", Context.MODE_PRIVATE).getBoolean("enabled", false)

    fun accessKey(context: Context): String {
        val prefs = context.getSharedPreferences("remote_debug", Context.MODE_PRIVATE)
        val stored = prefs.getString("key", null)
        if (stored != null) return stored
        SecureRandom().nextBytes(tokenBytes)
        val key = tokenBytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        prefs.edit().putString("key", key).apply()
        return key
    }

    @Synchronized fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences("remote_debug", Context.MODE_PRIVATE).edit().putBoolean("enabled", value).apply()
        if (value) start(context) else stop()
    }

    @Synchronized fun start(context: Context) {
        if (!isEnabled(context) || listener != null) return
        accessKey(context)
        try {
            val socket = ServerSocket(PORT)
            listener = socket
            enabled = true
            record("SYSTEM", "Remote console started")
            thread(name = "rs-remote-debug", isDaemon = true) {
                while (enabled) {
                    try {
                        val client = socket.accept()
                        thread(name = "rs-debug-client", isDaemon = true) { serve(client, context) }
                    } catch (_: SocketException) { break }
                    catch (_: Exception) { if (!enabled) break }
                }
            }
        } catch (e: Exception) {
            record("ERROR", "Remote console could not bind: ${e.javaClass.simpleName}")
        }
    }

    @Synchronized fun stop() {
        enabled = false
        listener?.close()
        listener = null
    }

    fun record(category: String, detail: String) {
        if (!enabled) return
        // Never ingest raw exception strings, links or values containing authorization material.
        val safe = detail
            .replace(Regex("https?://\\S+"), "[URL REDACTED]")
            .replace(Regex("(?i)(token|password|secret|authorization|apikey|api_key)[=: ]+\\S+"), "$1=[REDACTED]")
            .take(500)
        val entry = "${Instant.now()} [${category.take(24)}] $safe"
        synchronized(lock) {
            lines.addLast(entry)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
    }

    private fun serve(socket: Socket, context: Context) {
        socket.use { client ->
            client.soTimeout = 3000
            try {
                val reader = client.getInputStream().bufferedReader()
                val request = reader.readLine() ?: return
                val parts = request.split(' ')
                if (parts.size < 2 || parts[0] != "GET") return
                var header = reader.readLine()
                while (header != null && header.isNotEmpty()) header = reader.readLine()
                val uri = parts[1]
                val path = uri.substringBefore('?')
                val key = uri.substringAfter("key=", "").substringBefore('&')
                val valid = key.isNotEmpty() &&
                    java.security.MessageDigest.isEqual(key.toByteArray(), accessKey(context).toByteArray())
                val (status, type, body) = when {
                    !enabled -> Triple(503, "text/plain", "Disabled")
                    !valid -> Triple(403, "text/plain", "Invalid access key")
                    path == "/logs" -> Triple(200, "text/plain; charset=utf-8",
                        synchronized(lock) { lines.joinToString("\n") })
                    path == "/export" -> Triple(200, "text/plain; charset=utf-8",
                        synchronized(lock) { lines.joinToString("\n") })
                    path == "/" -> Triple(200, "text/html; charset=utf-8", page())
                    else -> Triple(404, "text/plain", "Not found")
                }
                val bytes = body.toByteArray(Charsets.UTF_8)
                val disposition = if (path == "/export") "Content-Disposition: attachment; filename=\"rs-debug.txt\"\r\n" else ""
                val headerText = "HTTP/1.1 $status OK\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nContent-Security-Policy: default-src 'self'; script-src 'unsafe-inline'; style-src 'unsafe-inline'\r\n$disposition" + "Connection: close\r\n\r\n"
                client.getOutputStream().apply { write(headerText.toByteArray()); write(bytes); flush() }
            } catch (_: Exception) { /* A disconnected browser must never affect playback. */ }
        }
    }

    private fun page(): String = """
        <!doctype html><html lang="cs"><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>RS Plus Debug</title><style>
        body{background:#10151f;color:#e4e9f3;font:15px system-ui;margin:24px}
        pre{background:#1b2330;padding:18px;border-radius:12px;white-space:pre-wrap;word-break:break-word;min-height:60vh}
        a,button{background:#4e81ee;color:white;border:0;padding:11px 16px;border-radius:8px;text-decoration:none}
        </style><h2>Nuvio RS Plus • Debug Console</h2>
        <p>Aktualizace každé 2 sekundy. Pouze lokální síť.</p>
        <a id="save">Stáhnout log</a> <button id="copy" type="button">Kopírovat</button> <span id="copyStatus" role="status"></span>
        <pre id="logs">Načítám...</pre>
        <script>
          const key=new URLSearchParams(location.search).get('key');
          document.getElementById('save').href='/export?key='+encodeURIComponent(key);
          document.getElementById('copy').addEventListener('click', async () => {
            const value=document.getElementById('logs').textContent;
            const status=document.getElementById('copyStatus');
            let copied=false;
            if (navigator.clipboard && window.isSecureContext) {
              try { await navigator.clipboard.writeText(value); copied=true; } catch (_) {}
            }
            if (!copied) {
              const input=document.createElement('textarea');
              input.value=value;
              input.setAttribute('readonly','');
              input.style.position='fixed';input.style.left='-9999px';
              document.body.appendChild(input);
              input.focus();input.select();input.setSelectionRange(0,input.value.length);
              try { copied=document.execCommand('copy'); } catch (_) {}
              input.remove();
            }
            status.textContent=copied?'Zkopírováno':'Kopírování blokováno – použij Stáhnout log';
          });
          async function refresh(){
            try{const r=await fetch('/logs?key='+encodeURIComponent(key),{cache:'no-store'});
              document.getElementById('logs').textContent=r.ok?await r.text():'Chyba '+r.status;
            }catch(e){document.getElementById('logs').textContent='Spojení přerušeno';}
          } refresh();setInterval(refresh,2000);
        </script></html>
    """.trimIndent()
}
