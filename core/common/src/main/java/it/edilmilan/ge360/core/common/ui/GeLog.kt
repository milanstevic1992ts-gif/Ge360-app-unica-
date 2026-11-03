package it.edilmilan.ge360.core.common.ui

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registro degli errori, leggibile dal telefono.
 *
 * Il problema che risolve: nell'app ci sono centinaia di `runCatching` e
 * `catch (Throwable)` contro una dozzina di chiamate a `Log`. Quasi tutti gli
 * errori venivano ingoiati in silenzio, quindi quando qualcosa non funzionava
 * in cantiere non restava traccia da nessuna parte — e `adb logcat` non e'
 * un'opzione mentre si lavora.
 *
 * Qui ogni errore finisce in due posti: in logcat subito (utile quando il
 * telefono e' collegato) e in un file a rotazione dentro i dati privati
 * dell'app, che il Centro diagnostico mostra.
 *
 * ## Regole che questo registro rispetta
 *
 * - **Non puo' far fallire chi lo chiama.** Ogni operazione e' protetta: se il
 *   disco e' pieno o il file non e' scrivibile, si perde la riga, non l'azione
 *   dell'utente.
 * - **Non scrive sul thread della UI.** La riga va su logcat subito e sul file
 *   in background.
 * - **Non registra tracce di stack.** Di un errore salva classe e messaggio:
 *   basta a capire cosa e' andato storto senza riempire il file di percorsi.
 * - **Non e' un canale di telemetria.** Il file resta sul telefono, non viene
 *   inviato da nessuna parte, e il Centro diagnostico lo tiene fuori dal
 *   rapporto condivisibile.
 */
object GeLog {

    private const val FILE_NAME = "ge360-diagnostics.log"

    /** Oltre questa dimensione il file viene dimezzato tenendo le righe recenti. */
    private const val MAX_BYTES = 128 * 1024L

    /** Quanto si tiene quando si ruota. */
    private const val KEEP_BYTES = 64 * 1024

    /** Tetto per singola riga: un messaggio lunghissimo non monopolizza il file. */
    private const val MAX_ENTRY_CHARS = 600

    /** Segna il punto in cui il registro e' stato tagliato. */
    internal const val HEADER = "… righe piu' vecchie eliminate\n"

    private val LOCK = Any()

    @Volatile
    private var logFile: File? = null

    /**
     * Collega il registro ai dati privati dell'app.
     *
     * Da chiamare una volta in `Application.onCreate`. Prima di questa chiamata
     * gli errori finiscono solo in logcat, senza essere persi.
     */
    fun install(context: Context) {
        runCatching { logFile = File(context.applicationContext.filesDir, FILE_NAME) }
    }

    /** Registra un errore. */
    fun e(tag: String, message: String, error: Throwable? = null) =
        write("E", tag, message, error)

    /** Registra una condizione anomala che non ha impedito l'operazione. */
    fun w(tag: String, message: String, error: Throwable? = null) =
        write("W", tag, message, error)

    private fun write(level: String, tag: String, message: String, error: Throwable?) {
        val detail = error?.let { "${it::class.java.simpleName}: ${it.message.orEmpty()}" }.orEmpty()
        val full = if (detail.isBlank()) message else "$message :: $detail"
        val entry = full.replace('\n', ' ').take(MAX_ENTRY_CHARS)

        // Subito in logcat: se il processo muore un istante dopo, la riga c'e'.
        runCatching {
            if (level == "E") Log.e("GE360/$tag", entry, error) else Log.w("GE360/$tag", entry)
        }

        val target = logFile ?: return
        val line = "${timestamp()} $level/$tag $entry\n"
        GeAsync.execute {
            runCatching {
                synchronized(LOCK) {
                    target.appendText(line)
                    if (target.length() > MAX_BYTES) rotate(target)
                }
            }
        }
    }

    /**
     * Dimezza il file tenendo la coda.
     *
     * Le righe vecchie non servono: quando qualcosa si rompe interessa cosa e'
     * successo adesso.
     */
    private fun rotate(target: File) {
        target.writeText(rotated(target.readText(), KEEP_BYTES))
    }

    /**
     * La coda del registro, tagliata all'inizio di una riga.
     *
     * Separata e testabile perche' e' il punto in cui si potrebbero perdere
     * righe o lasciarne una troncata a meta'.
     */
    internal fun rotated(content: String, keep: Int): String {
        if (content.length <= keep) return content
        val tail = content.takeLast(keep)
        // Il taglio cade quasi sempre in mezzo a una riga: si parte dalla prima
        // riga intera, invece di tenerne un frammento senza data ne' livello.
        val fromLineStart = if (tail.contains('\n')) tail.substringAfter('\n') else ""
        return HEADER + fromLineStart
    }

    /** Le righe piu' recenti, dalla piu' nuova alla piu' vecchia. */
    fun recent(maxChars: Int = 8_000): String = runCatching {
        val target = logFile ?: return@runCatching ""
        if (!target.exists()) return@runCatching ""
        synchronized(LOCK) {
            target.readLines()
                .asReversed()
                .joinToString("\n")
                .take(maxChars)
        }
    }.getOrDefault("")

    /** Quante righe contiene il registro. Per il rapporto, che non include i testi. */
    fun entryCount(): Int = runCatching {
        val target = logFile ?: return@runCatching 0
        if (!target.exists()) return@runCatching 0
        synchronized(LOCK) { target.readLines().count { it.isNotBlank() } }
    }.getOrDefault(0)

    /** Svuota il registro. */
    fun clear() {
        runCatching {
            synchronized(LOCK) { logFile?.takeIf { it.exists() }?.delete() }
        }
    }

    private fun timestamp(): String = runCatching {
        SimpleDateFormat("dd/MM HH:mm:ss", Locale.ITALY).format(Date())
    }.getOrDefault("")
}
