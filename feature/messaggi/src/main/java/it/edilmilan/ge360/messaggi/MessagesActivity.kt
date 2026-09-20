package it.edilmilan.ge360.messaggi

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import it.edilmilan.ge360.core.common.ui.buildScreenHeaderWithSubtitle
import it.edilmilan.ge360.cantieri.JobsiteRecord
import it.edilmilan.ge360.cantieri.JobsiteStore
import it.edilmilan.ge360.clienti.ClientRecord
import it.edilmilan.ge360.clienti.ClientStore
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MessagesActivity : AppCompatActivity() {
    private lateinit var store: TemplateStore
    private lateinit var searchInput: EditText
    private lateinit var countLabel: TextView
    private lateinit var listView: ListView
    private lateinit var adapter: TemplateAdapter
    private var activeFilter = "Tutti"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = TemplateStore(this)
        setContentView(buildScreen())
        loadTemplates()
        handleSharedText(intent)
    }

    private fun buildScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(10))
        }
        root.addView(buildScreenHeaderWithSubtitle(
            context = this,
            title = "Messaggi e prompt",
            subtitle = "Apri · cerca · compila · copia",
            onBack = { finish() },
            trailingLabel = "Backup",
            onTrailingClick = { showBackupMenu() }
        ))

        searchInput = EditText(this).apply {
            hint = "Cerca titolo, categoria, tag o testo…"
            isSingleLine = true
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = loadTemplates()
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        root.addView(searchInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(8) })

        val filters = listOf("Tutti", "Preferiti", "Recenti", "Più usati", "WhatsApp", "Prompt AI", "Cantiere", "Preventivi", "Social")
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        filters.forEach { filter ->
            row.addView(MaterialButton(this).apply {
                text = filter
                textSize = 11f
                setOnClickListener { activeFilter = filter; loadTemplates() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { marginEnd = dp(5) })
        }
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))

        countLabel = TextView(this).apply { textSize = 13f; setPadding(4, dp(4), 4, dp(6)) }
        root.addView(countLabel)

        adapter = TemplateAdapter()
        listView = ListView(this).apply {
            dividerHeight = 0
            adapter = this@MessagesActivity.adapter
            setOnItemClickListener { _, _, position, _ -> showUseDialog(this@MessagesActivity.adapter.items[position]) }
        }
        root.addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(MaterialButton(this).apply {
            text = "+ Nuovo messaggio / prompt"
            setOnClickListener { showEditor(MessageTemplate()) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)))
        return root
    }

    private fun loadTemplates() {
        if (!::adapter.isInitialized) return
        adapter.items = store.search(searchInput.text?.toString().orEmpty(), activeFilter)
        adapter.notifyDataSetChanged()
        countLabel.text = "${adapter.items.size} modelli · $activeFilter"
    }

    private fun showUseDialog(template: MessageTemplate) {
        val clients = ClientStore(this).search()
        val jobsites = JobsiteStore(this).list()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(6), dp(20), dp(8))
        }
        content.addView(TextView(this).apply {
            text = template.body
            textSize = 14f
            setPadding(0, 0, 0, dp(10))
        })

        fun label(text: String) = TextView(this).apply { this.text = text; setTypeface(typeface, Typeface.BOLD) }
        content.addView(label("Cliente (opzionale)"))
        val clientSpinner = Spinner(this)
        clientSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Nessun cliente") + clients.map { it.fullName() })
        content.addView(clientSpinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))

        content.addView(label("Cantiere (opzionale)"))
        val jobsiteSpinner = Spinner(this)
        jobsiteSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Nessun cantiere") + jobsites.map { "${it.code} · ${it.displayTitle()} · ${it.clientName}" })
        content.addView(jobsiteSpinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))

        val variables = extractVariables(template.body)
        val automatic = setOf("nome", "cognome", "cliente", "telefono", "email", "indirizzo", "cantiere", "lavoro", "tipo_lavoro", "codice_cantiere", "stato_cantiere", "data", "ora")
        val manualFields = linkedMapOf<String, EditText>()
        variables.filterNot { it in automatic }.forEach { variable ->
            content.addView(label(variable.replace('_', ' ').replaceFirstChar { it.uppercase() }))
            val field = EditText(this).apply {
                hint = "Valore per {{$variable}}"
                minLines = if (variable in setOf("testo", "aggiornamento")) 3 else 1
                if (minLines > 1) gravity = Gravity.TOP
            }
            manualFields[variable] = field
            content.addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, if (field.minLines > 1) dp(92) else dp(50)))
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(template.title)
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("Genera", null)
            .setNegativeButton("Annulla", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val client = clients.getOrNull(clientSpinner.selectedItemPosition - 1)
                val jobsite = jobsites.getOrNull(jobsiteSpinner.selectedItemPosition - 1)
                val values = automaticValues(client, jobsite).toMutableMap()
                manualFields.forEach { (key, field) -> values[key] = field.text.toString() }
                val result = renderTemplate(template.body, values)
                store.markUsed(template.id)
                dialog.dismiss()
                loadTemplates()
                showResult(template, result, client)
            }
        }
        dialog.show()
    }

    private fun automaticValues(client: ClientRecord?, jobsite: JobsiteRecord?): Map<String, String> {
        val now = Date()
        return mapOf(
            "nome" to client?.firstName.orEmpty(),
            "cognome" to client?.lastName.orEmpty(),
            "cliente" to client?.fullName().orEmpty(),
            "telefono" to client?.phone.orEmpty(),
            "email" to client?.email.orEmpty(),
            "indirizzo" to (jobsite?.address?.takeIf { it.isNotBlank() } ?: client?.address.orEmpty()),
            "cantiere" to jobsite?.displayTitle().orEmpty(),
            "lavoro" to jobsite?.workType.orEmpty(),
            "tipo_lavoro" to jobsite?.workType.orEmpty(),
            "codice_cantiere" to jobsite?.code.orEmpty(),
            "stato_cantiere" to jobsite?.status.orEmpty(),
            "data" to SimpleDateFormat("dd/MM/yyyy", Locale.ITALY).format(now),
            "ora" to SimpleDateFormat("HH:mm", Locale.ITALY).format(now)
        )
    }

    private fun showResult(template: MessageTemplate, generated: String, client: ClientRecord?) {
        val field = EditText(this).apply {
            setText(generated)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 7
            gravity = Gravity.TOP
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(4), dp(18), dp(8))
            addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(230)))
            addView(MaterialButton(this@MessagesActivity).apply {
                text = "Copia"
                setOnClickListener { copyText(field.text.toString()) }
            })
            addView(MaterialButton(this@MessagesActivity).apply {
                text = "WhatsApp"
                setOnClickListener { openWhatsApp(client, field.text.toString()) }
            })
            addView(MaterialButton(this@MessagesActivity).apply {
                text = "Condividi"
                setOnClickListener { shareText(field.text.toString()) }
            })
        }
        AlertDialog.Builder(this)
            .setTitle(template.title)
            .setView(box)
            .setPositiveButton("Chiudi", null)
            .show()
    }

    private fun showEditor(source: MessageTemplate) {
        val item = source.copy()
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(6), dp(20), dp(8))
        }
        fun field(label: String, value: String, lines: Int = 1): EditText {
            body.addView(TextView(this).apply { text = label; setTypeface(typeface, Typeface.BOLD) })
            return EditText(this).apply {
                setText(value)
                minLines = lines
                if (lines > 1) gravity = Gravity.TOP
                body.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, if (lines > 1) dp(160) else dp(50)))
            }
        }
        val title = field("Titolo", item.title)
        body.addView(TextView(this).apply { text = "Categoria"; setTypeface(typeface, Typeface.BOLD) })
        val categories = listOf("WhatsApp", "Prompt AI", "Cantiere", "Preventivi", "Social", "Altro")
        val category = Spinner(this).apply {
            adapter = ArrayAdapter(this@MessagesActivity, android.R.layout.simple_spinner_dropdown_item, categories)
            setSelection(categories.indexOf(item.category).takeIf { it >= 0 } ?: categories.lastIndex)
        }
        body.addView(category, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))
        val tags = field("Tag", item.tags)
        val text = field("Testo", item.body, 5)

        body.addView(TextView(this).apply { this.text = "Inserisci variabile"; setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(8), 0, 0) })
        val variables = listOf("nome", "data", "ora", "importo", "lavoro", "indirizzo", "codice_cantiere", "link", "aggiornamento", "prossimo_lavoro", "testo")
        val variableRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        variables.forEach { variable ->
            variableRow.addView(MaterialButton(this).apply {
                this.text = variable
                textSize = 10f
                setOnClickListener {
                    val token = "{{$variable}}"
                    val start = text.selectionStart.coerceAtLeast(0)
                    text.text.insert(start, token)
                }
            })
        }
        body.addView(HorizontalScrollView(this).apply { addView(variableRow) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (item.id > 0) "Modifica modello" else "Nuovo modello")
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton("Salva", null)
            .setNegativeButton("Annulla", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (title.text.toString().trim().isEmpty() || text.text.toString().trim().isEmpty()) {
                    Toast.makeText(this, "Titolo e testo sono obbligatori", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                item.title = title.text.toString()
                item.category = category.selectedItem.toString()
                item.tags = tags.text.toString()
                item.body = text.text.toString()
                store.save(item)
                dialog.dismiss()
                loadTemplates()
            }
        }
        dialog.show()
    }

    private fun showItemMenu(item: MessageTemplate) {
        val actions = arrayOf(
            if (item.favorite) "Rimuovi dai preferiti" else "Aggiungi ai preferiti",
            "Duplica", "Modifica", "Elimina"
        )
        AlertDialog.Builder(this).setTitle(item.title).setItems(actions) { _, which ->
            when (which) {
                0 -> { store.setFavorite(item.id, !item.favorite); loadTemplates() }
                1 -> { store.save(item.copy(id = 0, title = item.title + " copia", createdAt = System.currentTimeMillis())); loadTemplates() }
                2 -> showEditor(item)
                3 -> AlertDialog.Builder(this).setTitle("Elimina modello").setMessage("Eliminare ${item.title}?")
                    .setPositiveButton("Elimina") { _, _ -> store.delete(item.id); loadTemplates() }
                    .setNegativeButton("Annulla", null).show()
            }
        }.show()
    }

    private fun extractVariables(text: String): List<String> =
        Regex("\\{\\{([a-zA-Z0-9_]+)}}").findAll(text).map { it.groupValues[1] }.distinct().toList()

    private fun renderTemplate(text: String, values: Map<String, String>): String {
        var out = text
        extractVariables(text).forEach { key -> out = out.replace("{{$key}}", values[key].orEmpty()) }
        return out
    }

    private fun copyText(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("GE360 Messaggi", text))
        Toast.makeText(this, "Copiato", Toast.LENGTH_SHORT).show()
    }

    private fun openWhatsApp(client: ClientRecord?, text: String) {
        val number = client?.phone?.let { ClientStore.normalizePhone(it).replace("+", "") }.orEmpty()
        val uri = if (number.isNotBlank()) Uri.parse("https://wa.me/$number?text=${Uri.encode(text)}")
        else Uri.parse("https://wa.me/?text=${Uri.encode(text)}")
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            .onFailure { shareText(text) }
    }

    private fun shareText(text: String) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }, "Condividi con"))
    }

    private fun handleSharedText(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain") return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty().trim()
        if (text.isNotEmpty()) showEditor(MessageTemplate(title = "Da condivisione", body = text))
    }

    private fun showBackupMenu() {
        AlertDialog.Builder(this).setTitle("Messaggi · Dati").setItems(arrayOf("Esporta backup JSON", "Importa e unisci", "Importa e sostituisci")) { _, which ->
            when (which) {
                0 -> startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    type = "application/json"; putExtra(Intent.EXTRA_TITLE, "GE360-Messaggi-backup.json")
                }, REQ_EXPORT)
                1, 2 -> startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = "application/json"; addCategory(Intent.CATEGORY_OPENABLE); putExtra(EXTRA_REPLACE, which == 2)
                }, if (which == 2) REQ_IMPORT_REPLACE else REQ_IMPORT_MERGE)
            }
        }.show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data!!
        when (requestCode) {
            REQ_EXPORT -> runCatching {
                contentResolver.openOutputStream(uri)?.use { it.write(store.exportJson().toByteArray()) }
            }.onSuccess { Toast.makeText(this, "Backup creato", Toast.LENGTH_SHORT).show() }
             .onFailure { Toast.makeText(this, "Errore backup", Toast.LENGTH_LONG).show() }
            REQ_IMPORT_MERGE, REQ_IMPORT_REPLACE -> runCatching {
                val json = contentResolver.openInputStream(uri)?.use { input ->
                    val out = ByteArrayOutputStream(); input.copyTo(out); out.toString(Charsets.UTF_8.name())
                } ?: error("File non disponibile")
                store.importJson(json, requestCode == REQ_IMPORT_REPLACE)
            }.onSuccess { count -> Toast.makeText(this, "$count modelli importati", Toast.LENGTH_LONG).show(); loadTemplates() }
             .onFailure { Toast.makeText(this, "Backup non valido", Toast.LENGTH_LONG).show() }
        }
    }

    private inner class TemplateAdapter : BaseAdapter() {
        var items: List<MessageTemplate> = emptyList()
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = items[position].id
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val item = items[position]
            val card = MaterialCardView(this@MessagesActivity).apply {
                radius = dp(14).toFloat(); cardElevation = dp(1).toFloat()
                layoutParams = android.widget.AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val body = LinearLayout(this@MessagesActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(8)) }
            body.addView(TextView(this@MessagesActivity).apply {
                text = (if (item.favorite) "★ " else "") + item.title
                textSize = 17f; setTypeface(typeface, Typeface.BOLD)
            })
            body.addView(TextView(this@MessagesActivity).apply {
                text = "${item.category} · ${item.usageCount} usi${if (item.tags.isBlank()) "" else " · ${item.tags}"}"
                textSize = 12f
            })
            body.addView(TextView(this@MessagesActivity).apply {
                text = item.body.replace("\n", " ").take(150)
                textSize = 13f; setPadding(0, dp(5), 0, dp(5))
            })
            val actions = LinearLayout(this@MessagesActivity).apply { orientation = LinearLayout.HORIZONTAL }
            actions.addView(MaterialButton(this@MessagesActivity).apply {
                text = "Usa"
                setOnClickListener { showUseDialog(item) }
            }, LinearLayout.LayoutParams(0, dp(42), 1f))
            actions.addView(MaterialButton(this@MessagesActivity).apply {
                text = "⋮"
                setOnClickListener { showItemMenu(item) }
            }, LinearLayout.LayoutParams(dp(58), dp(42)))
            body.addView(actions)
            card.addView(body)
            return LinearLayout(this@MessagesActivity).apply {
                setPadding(0, 0, 0, dp(8)); addView(card)
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_EXPORT = 401
        private const val REQ_IMPORT_MERGE = 402
        private const val REQ_IMPORT_REPLACE = 403
        private const val EXTRA_REPLACE = "replace"
    }
}
