package it.edilmilan.ge360.core.common.ui

import android.database.sqlite.SQLiteOpenHelper
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Base comune delle schermate GE360.
 *
 * Fa tre cose che prima ogni Activity doveva ricordarsi da sola (e in 13 casi
 * su 44 non lo faceva):
 *
 * 1. allinea la palette al tema chiaro/scuro del telefono;
 * 2. imposta il fondo della finestra dal design system;
 * 3. chiude i database aperti dalla schermata quando viene distrutta, invece di
 *    lasciare connessioni SQLite appese.
 *
 * Migrare una schermata esistente vuol dire cambiare `AppCompatActivity` in
 * `BaseGeActivity` e aggiungere `.managed()` dove si crea uno store.
 */
abstract class BaseGeActivity : AppCompatActivity() {

    private val onDestroyActions = mutableListOf<() -> Unit>()

    override fun onCreate(savedInstanceState: Bundle?) {
        Ge360Brand.sync(this)
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Ge360Brand.Background))
    }

    /**
     * Registra una pulizia da eseguire in `onDestroy`.
     *
     * Gli errori vengono ignorati di proposito: una chiusura che fallisce non
     * deve impedire le altre ne' far crashare l'uscita dalla schermata.
     */
    protected fun closeOnDestroy(action: () -> Unit) {
        onDestroyActions += action
    }

    /**
     * Lega il ciclo di vita di un database a questa schermata.
     *
     * Uso: `store = FinanceStore(this).managed()`
     */
    protected fun <T : SQLiteOpenHelper> T.managed(): T {
        closeOnDestroy { close() }
        return this
    }

    override fun onDestroy() {
        onDestroyActions.forEach { action -> runCatching(action) }
        onDestroyActions.clear()
        super.onDestroy()
    }
}
