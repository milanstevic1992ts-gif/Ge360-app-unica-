package it.edilmilan.ge360.core.common.ui

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicLong

/**
 * Esecuzione di lavoro lento fuori dal thread della UI.
 *
 * Le schermate GE360 leggevano il database direttamente in `onCreate`,
 * `onResume` e a ogni carattere digitato nella ricerca: finche' l'archivio e'
 * piccolo non si nota, ma cresce ogni anno e prima o poi diventa un ANR.
 *
 * Qui il lavoro va su un pool di background e il risultato torna sul thread
 * della UI **solo se l'Activity e' ancora viva**, cosa che il vecchio schema
 * `thread { ... runOnUiThread { ... } }` non controllava mai.
 */
object GeAsync {

    private val pool = Executors.newFixedThreadPool(
        (Runtime.getRuntime().availableProcessors() - 1).coerceIn(2, 4),
        object : ThreadFactory {
            private val counter = AtomicLong(0)
            override fun newThread(r: Runnable) = Thread(r, "ge360-io-${counter.incrementAndGet()}").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY - 1
            }
        }
    )

    private val main = Handler(Looper.getMainLooper())

    internal fun execute(work: () -> Unit) {
        pool.execute(Runnable { work() })
    }

    internal fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            main.post(Runnable { block() })
        }
    }

    internal fun onMainDelayed(delayMs: Long, token: Any, block: () -> Unit) {
        main.removeCallbacksAndMessages(token)
        main.postAtTime(
            Runnable { block() },
            token,
            SystemClock.uptimeMillis() + delayMs
        )
    }

    internal fun cancel(token: Any) {
        main.removeCallbacksAndMessages(token)
    }
}

/** True se l'Activity non e' ne' in chiusura ne' distrutta. */
fun Activity.geIsAlive(): Boolean = !isFinishing && !isDestroyed

/**
 * Esegue [work] in background e consegna l'esito su thread UI.
 *
 * [onResult] riceve un [Result], quindi un errore nel database non fa crashare
 * l'app e la schermata puo' mostrare un messaggio invece di restare vuota senza
 * spiegazioni. Se l'Activity viene chiusa nel frattempo, [onResult] non viene
 * chiamata affatto: niente `setText` su viste gia' buttate via.
 */
fun <T> Activity.geLoad(work: () -> T, onResult: (Result<T>) -> Unit) {
    if (!geIsAlive()) return
    GeAsync.execute {
        val outcome = runCatching(work)
        GeAsync.onMain {
            if (geIsAlive()) onResult(outcome)
        }
    }
}

/**
 * Come [geLoad] ma aspetta [debounceMs] prima di partire, annullando la
 * richiesta precedente con lo stesso [token].
 *
 * Serve per i campi di ricerca: senza di questo, scrivere "materiali" lanciava
 * nove letture complete del database, una per lettera.
 */
fun <T> Activity.geLoadDebounced(
    token: Any,
    debounceMs: Long = 220L,
    work: () -> T,
    onResult: (Result<T>) -> Unit
) {
    if (!geIsAlive()) return
    GeAsync.onMainDelayed(debounceMs, token) {
        geLoad(work, onResult)
    }
}

/** Annulla un caricamento ritardato ancora in attesa. */
fun geCancelPending(token: Any) = GeAsync.cancel(token)
