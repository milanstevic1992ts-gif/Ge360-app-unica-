package it.edilmilan.ge360.clienti

import android.Manifest
import androidx.appcompat.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import it.edilmilan.ge360.core.common.Ge360Contracts
import it.edilmilan.ge360.core.common.ui.buildScreenHeaderWithSubtitle
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ClientsActivity : AppCompatActivity() {
    private lateinit var store: ClientStore
    private lateinit var listView: ListView
    private lateinit var searchInput: EditText
    private lateinit var countLabel: TextView
    private lateinit var adapter: ClientListAdapter
    private var activeFilter = "Tutti"
    private var pendingContactPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ClientStore(this)
        ClientBackupManager.schedule(this)
        setContentView(buildScreen())
        loadClients()
        if (savedInstanceState == null) {
            val uuid = intent.getStringExtra(Ge360Contracts.EXTRA_CLIENT_ID).orEmpty()
            if (uuid.isNotBlank()) store.getByGe360Id(uuid)?.let { showClientDetail(it.id) }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized) loadClients()
    }

    private fun buildScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(10))
        }

        root.addView(buildScreenHeaderWithSubtitle(
            context = this,
            title = "Clienti",
            subtitle = "CRM GE360 · locale e offline",
            onBack = { finish() },
            trailingLabel = "Backup",
            onTrailingClick = { showBackupMenu() }
        ))

        searchInput = EditText(this).apply {
            hint = "Cerca nome, telefono, indirizzo, note…"
            isSingleLine = true
            setPadding(dp(14), dp(8), dp(14), dp(8))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = loadClients()
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        root.addView(searchInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(8) })

        val filterRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Tutti", "Caldi", "Da coltivare", "Follow-up", "Polso basso").forEach { filter ->
            filterRow.addView(MaterialButton(this).apply {
                text = filter
                textSize = 12f
                setOnClickListener {
                    activeFilter = filter
                    loadClients()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { marginEnd = dp(6) })
        }
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(filterRow)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))

        countLabel = TextView(this).apply {
            textSize = 13f
            setPadding(4, dp(4), 4, dp(8))
        }
        root.addView(countLabel)

        adapter = ClientListAdapter()
        listView = ListView(this).apply {
            dividerHeight = 0
            adapter = this@ClientsActivity.adapter
            setOnItemClickListener { _, _, position, _ -> showClientDetail(this@ClientsActivity.adapter.items[position].id) }
        }
        root.addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(MaterialButton(this@ClientsActivity).apply {
                text = "Importa rubrica"
                setOnClickListener { startContactImport() }
            }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(6) })
            addView(MaterialButton(this@ClientsActivity).apply {
                text = "+ Nuovo cliente"
                setOnClickListener { showEditor(ClientRecord()) }
            }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(6) })
        })
        return root
    }

    private fun loadClients() {
        if (!::store.isInitialized || !::adapter.isInitialized) return
        val source = store.search(if (::searchInput.isInitialized) searchInput.text.toString() else "")
        val filtered = source.filter { client ->
            when (activeFilter) {
                "Caldi" -> client.temperature == "Caldo"
                "Da coltivare" -> client.temperature == "Da coltivare"
                "Follow-up" -> client.followUp.isNotBlank()
                "Polso basso" -> client.pulse < 40
                else -> true
            }
        }
        adapter.items = filtered
        adapter.notifyDataSetChanged()
        if (::countLabel.isInitialized) {
            countLabel.text = "${filtered.size} ${if (filtered.size == 1) "cliente" else "clienti"} · $activeFilter"
        }
    }

    private fun showEditor(source: ClientRecord) {
        val client = source.copy()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        fun field(label: String, value: String, inputType: Int = InputType.TYPE_CLASS_TEXT, lines: Int = 1): EditText {
            content.addView(TextView(this).apply { text = label; setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(8), 0, 0) })
            return EditText(this).apply {
                setText(value)
                this.inputType = inputType
                minLines = lines
                if (lines > 1) gravity = Gravity.TOP
                content.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, if (lines > 1) dp(90) else dp(52)))
            }
        }

        val first = field("Nome", client.firstName)
        val last = field("Cognome", client.lastName)
        val phone = field("Telefono", client.phone, InputType.TYPE_CLASS_PHONE)
        val email = field("Email", client.email, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val address = field("Indirizzo / cantiere", client.address)
        val birthday = field("Compleanno", client.birthday)
        val followUp = field("Follow-up", client.followUp)
        val phase = field("Fase relazione", client.relationshipPhase)
        val notes = field("Note", client.notes, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, 3)

        content.addView(TextView(this).apply { text = "Temperatura"; setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(8), 0, 0) })
        val temperature = Spinner(this)
        val temps = listOf("Caldo", "Da coltivare", "Freddo", "Dormiente")
        temperature.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, temps)
        temperature.setSelection(temps.indexOf(client.temperature).takeIf { it >= 0 } ?: 1)
        content.addView(temperature, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))

        val pulse = field("Polso 0–100", client.pulse.toString(), InputType.TYPE_CLASS_NUMBER)

        val scroll = ScrollView(this).apply { addView(content) }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (client.id > 0) "Modifica cliente" else "Nuovo cliente")
            .setView(scroll)
            .setPositiveButton("Salva", null)
            .setNegativeButton("Annulla", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (first.text.toString().trim().isEmpty() && last.text.toString().trim().isEmpty()) {
                    first.error = "Inserisci almeno nome o cognome"
                    return@setOnClickListener
                }
                client.firstName = first.text.toString()
                client.lastName = last.text.toString()
                client.phone = phone.text.toString()
                client.email = email.text.toString()
                client.address = address.text.toString()
                client.birthday = birthday.text.toString()
                client.followUp = followUp.text.toString()
                client.relationshipPhase = phase.text.toString().ifBlank { "Nuovo contatto" }
                client.notes = notes.text.toString()
                client.temperature = temperature.selectedItem.toString()
                val oldPulse = client.pulse
                client.pulse = pulse.text.toString().toIntOrNull()?.coerceIn(0, 100) ?: 50
                val duplicate = if (client.id == 0L) store.findDuplicate(client.phone, client.email) else null
                if (duplicate != null) {
                    Toast.makeText(this, "Cliente già presente: ${duplicate.fullName()}", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                store.save(client)
                if (client.id > 0 && oldPulse != client.pulse) {
                    // Il nuovo valore resta salvato; la correzione manuale non altera ulteriormente il polso.
                }
                dialog.dismiss()
                loadClients()
                backupAfterChange()
            }
        }
        dialog.show()
    }

    private fun showClientDetail(clientId: Long) {
        val client = store.get(clientId) ?: return
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(16))
        }
        body.addView(TextView(this).apply {
            text = client.fullName()
            textSize = 23f
            setTypeface(typeface, Typeface.BOLD)
        })
        body.addView(TextView(this).apply {
            text = "${client.relationshipPhase} · ${client.temperature}"
            textSize = 14f
            setPadding(0, dp(3), 0, dp(10))
        })
        body.addView(TextView(this).apply {
            text = "Polso relazione: ${client.pulse}/100"
            setTypeface(typeface, Typeface.BOLD)
        })
        body.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = client.pulse
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(20)))

        fun info(label: String, value: String) {
            body.addView(TextView(this).apply {
                text = "$label  ·  ${value.ifBlank { "—" }}"
                textSize = 14f
                setPadding(0, dp(7), 0, 0)
            })
        }
        info("Telefono", client.phone)
        info("Email", client.email)
        info("Cantiere", client.address)
        info("Compleanno", client.birthday)
        info("Follow-up", client.followUp)
        info("IA", client.aiTemperature)
        info("Note", client.notes)

        body.addView(sectionTitle("Temperatura rapida"))
        val tempRow = wrapButtons(listOf("Caldo", "Da coltivare", "Freddo", "Dormiente")) { value ->
            store.updateTemperature(clientId, value)
            backupAfterChange()
            Toast.makeText(this, "Temperatura: $value", Toast.LENGTH_SHORT).show()
        }
        body.addView(tempRow)

        body.addView(sectionTitle("Segnale relazione"))
        body.addView(wrapButtons(listOf("Positivo", "Curioso", "Neutro", "Dubbioso", "Negativo", "Teso")) { signal ->
            showSignalDialog(clientId, signal)
        })

        val history = store.interactions(clientId, 10)
        body.addView(sectionTitle("Storico recente"))
        if (history.isEmpty()) {
            body.addView(TextView(this).apply { text = "Nessun segnale registrato." })
        } else {
            val formatter = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALY)
            history.forEach { row ->
                body.addView(TextView(this).apply {
                    val signed = if (row.delta > 0) "+${row.delta}" else row.delta.toString()
                    text = "${row.signal} $signed → ${row.pulseAfter}/100\n${row.reason}${if (row.detail.isBlank()) "" else " · ${row.detail}"}\n${formatter.format(Date(row.createdAt))}"
                    textSize = 13f
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                })
            }
        }

        body.addView(sectionTitle("Cronologia operativa · fatti registrati"))
        body.addView(TextView(this).apply {
            text = "Richieste, promesse, sopralluoghi e problemi documentati. " +
                "Nessun evento modifica automaticamente il punteggio del cliente."
            textSize = 12f
            setPadding(0, dp(5), 0, dp(8))
        })
        body.addView(MaterialButton(this).apply {
            text = "+ REGISTRA INTERAZIONE"
            setOnClickListener { showFactualEventDialog(client) }
        })
        val factualEvents = store.factualTimeline(client.ge360Id, 20)
        if (factualEvents.isEmpty()) {
            body.addView(TextView(this).apply {
                text = "Nessuna interazione operativa registrata."
                textSize = 13f
            })
        } else {
            val df = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALY)
            factualEvents.forEach { event ->
                body.addView(TextView(this).apply {
                    text = buildString {
                        append(event.kind)
                        append(" · ")
                        append(df.format(Date(event.occurredAt)))
                        append("\n")
                        append(event.description)
                        append("\nFonte: ")
                        append(event.source)
                        if (event.nextActionDate.isNotBlank()) {
                            append(" · Prossima azione: ")
                            append(event.nextActionDate)
                        }
                        if (!event.jobsiteGe360Id.isNullOrBlank()) {
                            append("\nCantiere UUID: ")
                            append(event.jobsiteGe360Id)
                        }
                    }
                    textSize = 13f
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                })
            }
        }

        body.addView(sectionTitle("Azioni"))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        actions.addView(MaterialButton(this).apply {
            text = "Modifica cliente"
            setOnClickListener { showEditor(store.get(clientId) ?: return@setOnClickListener) }
        })
        if (client.phone.isNotBlank()) {
            actions.addView(MaterialButton(this).apply {
                text = "Chiama"
                setOnClickListener { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(client.phone)}"))) }
            })
            actions.addView(MaterialButton(this).apply {
                text = "WhatsApp"
                setOnClickListener {
                    val number = ClientStore.normalizePhone(client.phone).replace("+", "")
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$number"))) }
                        .onFailure { Toast.makeText(this@ClientsActivity, "Impossibile aprire WhatsApp", Toast.LENGTH_LONG).show() }
                }
            })
        }
        actions.addView(MaterialButton(this).apply {
            text = "Note e Agenda cliente"
            setOnClickListener { openClientAgenda(client, null) }
        })
        actions.addView(MaterialButton(this).apply {
            text = "+ Nuova nota collegata"
            setOnClickListener { openClientAgenda(client, "NOTE") }
        })
        actions.addView(MaterialButton(this).apply {
            text = "Elimina cliente"
            setOnClickListener { confirmDelete(clientId, client.fullName()) }
        })
        body.addView(actions)

        AlertDialog.Builder(this)
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton("Chiudi") { _, _ -> loadClients() }
            .show()
    }

    private fun openClientAgenda(client: ClientRecord, createKind: String?) {
        if (client.ge360Id.isBlank()) {
            Toast.makeText(this, "Il cliente deve essere salvato prima del collegamento", Toast.LENGTH_LONG).show()
            return
        }
        val target = Intent().setClassName(packageName, "it.edilmilan.ge360.agenda.AgendaActivity")
            .putExtra(Ge360Contracts.EXTRA_CLIENT_ID, client.ge360Id)
            .putExtra(Ge360Contracts.EXTRA_AGENDA_CONTEXT_LABEL, client.fullName())
        if (createKind != null) target.putExtra(Ge360Contracts.EXTRA_AGENDA_CREATE_KIND, createKind)
        runCatching { startActivity(target) }
            .onFailure { Toast.makeText(this, "Agenda non disponibile in questa build", Toast.LENGTH_LONG).show() }
    }

    private fun showFactualEventDialog(client: ClientRecord) {
        if (client.ge360Id.isBlank()) {
            Toast.makeText(this, "Salva prima l'anagrafica cliente", Toast.LENGTH_LONG).show()
            return
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        val kind = Spinner(this).apply {
            adapter = ArrayAdapter(this@ClientsActivity,
                android.R.layout.simple_spinner_dropdown_item, ClientFactualEventV1.KINDS)
        }
        val source = Spinner(this).apply {
            adapter = ArrayAdapter(this@ClientsActivity,
                android.R.layout.simple_spinner_dropdown_item, ClientFactualEventV1.SOURCES)
        }
        val description = EditText(this).apply {
            hint = "Che cosa ha chiesto o comunicato il cliente? Quale impegno è stato preso?"
            minLines = 3
            maxLines = 6
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        val followUpDate = EditText(this).apply {
            hint = "Prossima azione AAAA-MM-GG (facoltativa)"
            isSingleLine = true
        }
        form.addView(TextView(this).apply { text = "EVENTO OSSERVATO" })
        form.addView(kind)
        form.addView(TextView(this).apply { text = "FONTE" })
        form.addView(source)
        form.addView(description)
        form.addView(followUpDate)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Aggiungi alla cronologia")
            .setMessage("Annota fatti e comunicazioni, non valutazioni della personalità.")
            .setView(ScrollView(this).apply { addView(form) })
            .setNegativeButton("Annulla", null)
            .setPositiveButton("REGISTRA", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                runCatching {
                    val event = ClientFactualEventV1.newManual(
                        clientGe360Id = client.ge360Id,
                        kind = kind.selectedItem.toString(),
                        description = description.text.toString(),
                        source = source.selectedItem.toString(),
                        nextActionDate = followUpDate.text.toString()
                    )
                    store.addFactualEvent(event)
                }.onSuccess {
                    dialog.dismiss()
                    loadClients()
                    backupAfterChange()
                    Toast.makeText(this, "Evento registrato. Riapri la scheda per vedere lo storico aggiornato.",
                        Toast.LENGTH_LONG).show()
                }.onFailure {
                    Toast.makeText(this, it.message ?: "Evento non salvato", Toast.LENGTH_LONG).show()
                }
            }
        }
        dialog.show()
    }

    private fun showSignalDialog(clientId: Long, signal: String) {
        val reasons = listOf("Sopralluogo", "Telefonata", "WhatsApp", "Preventivo", "Trattativa", "Lavori", "Pagamento", "Altro")
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val spinner = Spinner(this).apply { adapter = ArrayAdapter(this@ClientsActivity, android.R.layout.simple_spinner_dropdown_item, reasons) }
        val detail = EditText(this).apply { hint = "Dettaglio o aneddoto facoltativo"; minLines = 3; gravity = Gravity.TOP }
        content.addView(TextView(this).apply { text = "$signal · ${signed(RelationshipRules.deltaFor(signal))}"; setTypeface(typeface, Typeface.BOLD) })
        content.addView(spinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        content.addView(detail, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(90)))
        AlertDialog.Builder(this)
            .setTitle("Registra segnale")
            .setView(content)
            .setPositiveButton("Registra") { _, _ ->
                val pulse = store.addInteraction(clientId, signal, spinner.selectedItem.toString(), detail.text.toString())
                Toast.makeText(this, "Polso aggiornato: $pulse/100", Toast.LENGTH_SHORT).show()
                loadClients()
                backupAfterChange()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun confirmDelete(id: Long, name: String) {
        AlertDialog.Builder(this)
            .setTitle("Elimina cliente")
            .setMessage("Eliminare $name e il relativo storico relazionale?")
            .setPositiveButton("Elimina") { _, _ ->
                store.delete(id)
                loadClients()
                backupAfterChange()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun startContactImport() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            pendingContactPermission = true
            requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), REQ_CONTACT_PERMISSION)
            return
        }
        startActivityForResult(Intent(Intent.ACTION_PICK, ContactsContract.Contacts.CONTENT_URI), REQ_PICK_CONTACT)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CONTACT_PERMISSION && pendingContactPermission) {
            pendingContactPermission = false
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startContactImport()
            else Toast.makeText(this, "Permesso rubrica necessario", Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        when (requestCode) {
            REQ_PICK_CONTACT -> importContact(data.data!!)
            REQ_DRIVE_FOLDER -> {
                runCatching { ClientBackupManager.configureDrive(this, data.data!!) }
                    .onSuccess {
                        Toast.makeText(this, "Cartella Drive configurata", Toast.LENGTH_SHORT).show()
                        runBackupNow()
                    }
                    .onFailure { Toast.makeText(this, "Impossibile configurare la cartella", Toast.LENGTH_LONG).show() }
            }
            REQ_EXPORT -> writeExport(data.data!!)
            REQ_IMPORT -> readImport(data.data!!)
        }
    }

    private fun importContact(uri: Uri) {
        val id = contentResolver.query(uri, arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME), null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            c.getString(0) to c.getString(1).orEmpty()
        } ?: return
        val contactId = id.first
        val display = id.second.trim()
        val client = ClientRecord(contactUri = uri.toString())
        val nameParts = display.split(" ", limit = 2)
        client.firstName = nameParts.getOrElse(0) { "" }
        client.lastName = nameParts.getOrElse(1) { "" }

        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID}=?", arrayOf(contactId), null
        )?.use { if (it.moveToFirst()) client.phone = it.getString(0).orEmpty() }

        contentResolver.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),
            "${ContactsContract.CommonDataKinds.Email.CONTACT_ID}=?", arrayOf(contactId), null
        )?.use { if (it.moveToFirst()) client.email = it.getString(0).orEmpty() }

        contentResolver.query(
            ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS),
            "${ContactsContract.CommonDataKinds.StructuredPostal.CONTACT_ID}=?", arrayOf(contactId), null
        )?.use { if (it.moveToFirst()) client.address = it.getString(0).orEmpty() }

        val duplicate = store.findDuplicate(client.phone, client.email)
        if (duplicate != null) {
            Toast.makeText(this, "Contatto già presente: ${duplicate.fullName()}", Toast.LENGTH_LONG).show()
            showClientDetail(duplicate.id)
        } else {
            showEditor(client)
        }
    }

    private fun showBackupMenu() {
        val choices = arrayOf(
            "Backup automatico ora",
            "Configura cartella Google Drive",
            "Ripristina ultimo backup locale",
            "Esporta backup JSON",
            "Importa backup JSON"
        )
        AlertDialog.Builder(this)
            .setTitle("Backup Clienti")
            .setItems(choices) { _, which ->
                when (which) {
                    0 -> runBackupNow()
                    1 -> chooseDriveFolder()
                    2 -> confirmRestoreLatest()
                    3 -> createExportDocument()
                    4 -> chooseImportDocument()
                }
            }
            .show()
    }

    private fun runBackupNow() {
        Toast.makeText(this, "Backup in corso…", Toast.LENGTH_SHORT).show()
        Thread {
            val result = runCatching { ClientBackupManager.perform(applicationContext) }.getOrElse { "Errore backup: ${it.message}" }
            runOnUiThread { Toast.makeText(this, result, Toast.LENGTH_LONG).show() }
        }.start()
    }

    private fun backupAfterChange() {
        Thread { runCatching { ClientBackupManager.perform(applicationContext) } }.start()
    }

    private fun chooseDriveFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        }
        startActivityForResult(intent, REQ_DRIVE_FOLDER)
    }

    private fun confirmRestoreLatest() {
        val latest = ClientBackupManager.latestLocal(this)
        if (latest == null) {
            Toast.makeText(this, "Nessun backup locale disponibile", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Ripristina ${latest.name}")
            .setMessage("Unire il backup ai clienti attuali o sostituire tutto?")
            .setPositiveButton("Unisci") { _, _ -> restoreLatest(false) }
            .setNeutralButton("Sostituisci") { _, _ -> restoreLatest(true) }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun restoreLatest(replace: Boolean) {
        runCatching { ClientBackupManager.restoreLatest(this, replace) }
            .onSuccess { count -> Toast.makeText(this, "$count clienti ripristinati", Toast.LENGTH_LONG).show(); loadClients() }
            .onFailure { Toast.makeText(this, "Backup non valido: ${it.message}", Toast.LENGTH_LONG).show() }
    }

    private fun createExportDocument() {
        val date = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.ITALY).format(Date())
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "GE360-Clienti-backup_$date.json")
        }, REQ_EXPORT)
    }

    private fun writeExport(uri: Uri) {
        runCatching {
            contentResolver.openOutputStream(uri)?.use { it.write(store.exportJson().toByteArray(Charsets.UTF_8)) } ?: error("File non disponibile")
        }.onSuccess { Toast.makeText(this, "Backup esportato", Toast.LENGTH_SHORT).show() }
            .onFailure { Toast.makeText(this, "Errore esportazione", Toast.LENGTH_LONG).show() }
    }

    private fun chooseImportDocument() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }, REQ_IMPORT)
    }

    private fun readImport(uri: Uri) {
        val json = runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                }
                out.toString(Charsets.UTF_8.name())
            } ?: error("File non disponibile")
        }.getOrElse {
            Toast.makeText(this, "Impossibile leggere il backup", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Importa backup")
            .setMessage("Il formato è compatibile anche con i backup di EDIL Clienti. Vuoi unire o sostituire l'archivio?")
            .setPositiveButton("Unisci") { _, _ -> importJson(json, false) }
            .setNeutralButton("Sostituisci") { _, _ -> importJson(json, true) }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun importJson(json: String, replace: Boolean) {
        runCatching { store.importJson(json, replace) }
            .onSuccess { count ->
                Toast.makeText(this, "$count clienti importati", Toast.LENGTH_LONG).show()
                loadClients()
                backupAfterChange()
            }
            .onFailure { Toast.makeText(this, "Backup non valido: ${it.message}", Toast.LENGTH_LONG).show() }
    }

    private fun sectionTitle(textValue: String) = TextView(this).apply {
        text = textValue
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(16), 0, dp(5))
    }

    private fun wrapButtons(labels: List<String>, onClick: (String) -> Unit): View {
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        labels.forEach { label ->
            row.addView(MaterialButton(this).apply {
                text = label
                textSize = 11f
                setOnClickListener { onClick(label) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { marginEnd = dp(5) })
        }
        scroll.addView(row)
        return scroll
    }

    private fun signed(delta: Int): String = if (delta > 0) "+$delta" else delta.toString()
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private inner class ClientListAdapter : BaseAdapter() {
        var items: List<ClientRecord> = emptyList()
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): ClientRecord = items[position]
        override fun getItemId(position: Int): Long = items[position].id
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val client = items[position]
            val card = (convertView as? MaterialCardView) ?: MaterialCardView(this@ClientsActivity).apply {
                radius = dp(14).toFloat()
                cardElevation = dp(1).toFloat()
                layoutParams = android.widget.AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            card.removeAllViews()
            val body = LinearLayout(this@ClientsActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(11), dp(14), dp(11))
                addView(TextView(this@ClientsActivity).apply {
                    text = client.fullName()
                    textSize = 17f
                    setTypeface(typeface, Typeface.BOLD)
                })
                addView(TextView(this@ClientsActivity).apply {
                    text = "${client.temperature} · Polso ${client.pulse}/100${if (client.followUp.isBlank()) "" else " · Follow-up ${client.followUp}"}"
                    textSize = 13f
                    setPadding(0, dp(3), 0, 0)
                })
                addView(TextView(this@ClientsActivity).apply {
                    text = listOf(client.phone, client.address).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Nessun recapito o indirizzo" }
                    textSize = 12f
                    setPadding(0, dp(3), 0, 0)
                })
            }
            card.addView(body)
            card.setContentPadding(0, 0, 0, 0)
            return LinearLayout(this@ClientsActivity).apply {
                setPadding(0, dp(4), 0, dp(4))
                addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
    }

    companion object {
        private const val REQ_CONTACT_PERMISSION = 2101
        private const val REQ_PICK_CONTACT = 2102
        private const val REQ_DRIVE_FOLDER = 2103
        private const val REQ_EXPORT = 2104
        private const val REQ_IMPORT = 2105
    }
}
