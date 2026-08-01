package com.tvcs.fritzboxcallwidget.api

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Reliably bounds the wall-clock wait time on raw blocking Java I/O calls
 * that have no suspension points — e.g. DNS resolution (`InetSocketAddress`,
 * `InetAddress.getByName`), `Socket.connect()`, or synchronous OkHttp
 * `Call.execute()`.
 *
 * ## Why plain `withTimeout { ... }` is NOT enough here
 * `withTimeout` cancellation is cooperative: it only takes effect at a
 * genuine coroutine suspension point. A call like
 * `withTimeout(ms) { withContext(Dispatchers.IO) { blockingCall() } }`
 * can still block the *caller* for the full duration of `blockingCall()`
 * if that call never yields — which is exactly the case for DNS lookups
 * and classic `java.net.Socket` I/O. This is a well-known Kotlin coroutines
 * pitfall: cancelling a coroutine that is synchronously executing
 * non-cooperative blocking code does not interrupt that code, and — more
 * subtly — does not reliably make the *enclosing* `withTimeout` return
 * early either.
 *
 * This is exactly what caused real Android ANR dialogs ("App reagiert
 * nicht"): switching to a WiFi network with no working DNS left a
 * `goAsync()`-backed BroadcastReceiver waiting far longer than the ANR
 * watchdog allows, because the previous timeout wrapping did not actually
 * bound the wait.
 *
 * ## The fix
 * Run the blocking call in its own `async` coroutine on a small, dedicated
 * IO thread pool, and only ever `await()` it from within `withTimeoutOrNull`.
 * Cancelling a coroutine that is suspended in `Deferred.await()` is a
 * completely different (and always-reliable) case: `await()` is a genuine
 * suspension point registered as a listener on the other Job, so cancelling
 * the awaiting coroutine resumes it immediately — regardless of whether the
 * awaited `async` block (and its blocking call) has actually finished.
 *
 * The trade-off: if a call truly hangs (e.g. dead DNS server that never
 * responds and never times out at the OS level), its worker thread is
 * "leaked" — abandoned, still blocked, until it eventually resolves or the
 * process is killed. The dedicated pool below is capped so a string of bad
 * networks can't exhaust the shared [Dispatchers.IO] pool used by everything
 * else in the app; it only ever costs a few parked threads, never an ANR.
 */
object BlockingIoTimeout {

    private const val TAG = "BlockingIoTimeout"

    // A handful of threads is enough: normal calls finish in well under a
    // second and free their slot immediately. Only calls that are genuinely
    // stuck (e.g. dead DNS) hold a slot for longer, and there should rarely
    // be more than one or two of those in flight at once.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(6))

    /**
     * Runs [block] on the dedicated pool and waits at most [timeoutMs] for
     * it to finish. Returns `null` on timeout — the block itself keeps
     * running in the background and its eventual result (or exception) is
     * simply discarded.
     */
    suspend fun <T> runBounded(timeoutMs: Long, label: String, block: suspend () -> T): T? {
        val deferred = scope.async { block() }
        val result = withTimeoutOrNull(timeoutMs) { deferred.await() }
        if (result == null) {
            Log.w(TAG, "$label exceeded ${timeoutMs}ms — abandoning (thread may keep running in background)")
            deferred.cancel()
        }
        return result
    }
}
