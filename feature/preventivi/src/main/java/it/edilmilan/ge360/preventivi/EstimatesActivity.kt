package it.edilmilan.ge360.preventivi

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.CheckBox
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
import it.edilmilan.ge360.cantieri.JobsiteRecord
import it.edilmilan.ge360.cantieri.JobsiteStore
import it.edilmilan.ge360.clienti.ClientStore
import it.edilmilan.ge360.core.common.ui.buildScreenHeaderWithSubtitle
import it.edilmilan.ge360.giornata.DailyStore
import it.edilmilan.ge360.spese.FinanceStore
import java.text.NumberFormat
import java.util.Locale
import java.util.UUID

class EstimatesActivity : AppCompatActivity() {
    private lateinit var store: EstimateStore
    private lateinit var clients: ClientStore
    private lateinit var jobsites: JobsiteStore
    private lateinit var finances: FinanceStore
    private lateinit var daily: DailyStore
    private lateinit var search: EditText
    private lateinit var adapter: EstimateAdapter
    private lateinit var statusText: TextView
    private var filter = "Tutti"
    private var replaceOnImport = false
    private var pendingEvidenceEstimate = ""
    private var pendingEvidenceEconomics = ""
    private var pendingEvidenceCorrection = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = EstimateStore(this)
        clients = ClientStore(this)
        jobsites = JobsiteStore(this)
        finances = FinanceStore(this)
        daily = DailyStore(this)
        pendingEvidenceEstimate = savedInstanceState?.getString("pending_evidence_estimate").orEmpty()
        pendingEvidenceEconomics = savedInstanceState?.getString("pending_evidence_economics").orEmpty()
        pendingEvidenceCorrection = savedInstanceState?.getString("pending_evidence_correction").orEmpty()
        setContentView(buildScreen())
        refresh()
        if (savedInstanceState == null) {
            intent.getStringExtra(EXTRA_ESTIMATE_ID)?.takeIf { it.isNotBlank() }
                ?.let(store::getByGe360Id)
                ?.let(::actions)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pending_evidence_estimate", pendingEvidenceEstimate)
        outState.putString("pending_evidence_economics", pendingEvidenceEconomics)
        outState.putString("pending_evidence_correction", pendingEvidenceCorrection)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized) refresh()
    }

    override fun onDestroy() {
        runCatching { store.close() }
        runCatching { clients.close() }
        runCatching { jobsites.close() }
        runCatching { finances.close() }
        runCatching { daily.close() }
        super.onDestroy()
    }

    private fun buildScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(10))
        }
        root.addView(
            buildScreenHeaderWithSubtitle(
                context = this,
                title = "Preventivi",
                subtitle = "Configuratori · Capitolato · Cliente · Cantiere · Costi · Margine",
                onBack = { finish() },
                trailingLabel = "Dati",
                onTrailingClick = { dataMenu() }
            )
        )

        statusText = TextView(this).apply {
            textSize = 14f
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        root.addView(statusText)

        search = EditText(this).apply {
            hint = "Cerca numero, cliente, cantiere, lavoro…"
            isSingleLine = true
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = refresh()
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        root.addView(search, lp52())

        val filters = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        (listOf("Tutti") + EstimateStatuses.ALL).forEach { value ->
            filters.addView(
                MaterialButton(this).apply {
                    text = value
                    textSize = 11f
                    setOnClickListener { filter = value; refresh() }
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { marginEnd = dp(6) }
            )
        }
        root.addView(
            HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(filters)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
        )

        adapter = EstimateAdapter()
        root.addView(
            ListView(this).apply {
                dividerHeight = 0
                adapter = this@EstimatesActivity.adapter
                setOnItemClickListener { _, _, position, _ -> actions(this@EstimatesActivity.adapter.rows[position]) }
                setOnItemLongClickListener { _, _, position, _ ->
                    deleteConfirm(this@EstimatesActivity.adapter.rows[position]); true
                }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(MaterialButton(this@EstimatesActivity).apply {
                text = "Capitolato"
                setOnClickListener { showStandardCatalog() }
            }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(5) })
            addView(MaterialButton(this@EstimatesActivity).apply {
                text = "Prezzario"
                setOnClickListener { showPriceList() }
            }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(5) })
        })
        root.addView(MaterialButton(this).apply {
            text = "✏ SOPRALLUOGO SMART · 8 PASSI"
            setOnClickListener { openSmartSurveys() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)).apply { topMargin = dp(8) })
        root.addView(MaterialButton(this).apply {
            text = "+ Nuovo preventivo classico"
            setOnClickListener { editHeader(EstimateRecord()) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply { topMargin = dp(6) })
        return root
    }

    private fun refresh() {
        if (!::adapter.isInitialized) return
        adapter.rows = store.list(if (::search.isInitialized) search.text.toString() else "", filter)
        adapter.notifyDataSetChanged()
        val all = store.list()
        val confirmed = all.filter { it.status in setOf(EstimateStatuses.ACCEPTED, EstimateStatuses.IN_PROGRESS, EstimateStatuses.COMPLETED) }
        statusText.text = "${all.size} preventivi · ${confirmed.size} confermati/in lavoro · ${euro(confirmed.sumOf { it.finalTotal })}"
    }

    private fun actions(r: EstimateRecord) {
        AlertDialog.Builder(this)
            .setTitle("${r.number} · ${r.clientName.ifBlank { "Senza cliente" }}")
            .setItems(arrayOf("Apri / modifica", "Lavorazioni guidate, capitolato e prezzi", "Economia cantiere", "Condividi preventivo", "Prompt IA", "Duplica", "Varianti di metratura", "✓ LAVORO ACCETTATO → CANTIERE")) { _, i ->
                when (i) {
                    0 -> editHeader(r)
                    1 -> editItems(r)
                    2 -> economics(r)
                    3 -> share(customerSummary(r), "Preventivo ${r.number}")
                    4 -> showAiSopralluogoPrompt(r)
                    5 -> { store.duplicate(r); refresh(); toast("Preventivo duplicato") }
                    6 -> showMetricVariants(r.ge360Id)
                    7 -> EstimateLifecycleUi.acceptWork(this, r) { refresh() }
                }
            }.show()
    }

    /** Read-only economic comparison. Only technical acknowledgment is writable. */
    private fun showMetricVariants(estimateGe360Id: String) {
        val variants = store.metricVariants(estimateGe360Id)
        if (variants.isEmpty()) {
            textDialog("Varianti di metratura", "Nessuna variante proposta. " +
                "Dal Rilievo seleziona una nuova revisione della stessa superficie.")
            return
        }
        val economicsRows = store.metricVariantEconomics(estimateGe360Id)
        val economics = economicsRows.associateBy { it.variantGe360Id }
        val allCorrections = store.agreementCorrections(estimateGe360Id)
        val recorded = economicsRows.filter {
            it.status == MetricVariantEconomicsV4.STATUS_EVIDENCE_RECORDED
        }.map { econ ->
            MetricAgreementRevisionChainV6.effectiveSignedAmount(
                econ, allCorrections.filter { it.economicsGe360Id == econ.ge360Id }
            )
        }
        val proposed = economicsRows.filter {
            it.status == MetricVariantEconomicsV4.STATUS_PROPOSED
        }
        fun euro(value: Double) = String.format(Locale.ITALY, "%+.2f €", value)
        val overview = "ACCORDI ESTERNI ANNOTATI (NON CONTABILIZZATI)\n" +
            "Extra: " + euro(recorded.filter { it > 0 }.sum()) +
            " · riduzioni: " + euro(recorded.filter { it < 0 }.sum()) + "\n" +
            "Saldo indicativo accordi annotati (rettifiche una sola volta): " +
            euro(recorded.sum()) + "\n" +
            "Proposte in attesa di accordo: ${proposed.size}" +
            "\nQuesti importi NON sono inclusi nel preventivo, SAL o fatturazione."
        val labels = variants.map { v ->
            val linked = economics[v.ge360Id]
            v.workCode + " · " + v.status + " · " +
                String.format(Locale.ITALY, "%+.2f m²", v.deltaQuantityM2()) +
                (linked?.let {
                    " · " + it.status + " " +
                        String.format(Locale.ITALY, "%+.2f €", it.proposedSignedAmountEuro)
                } ?: " · senza proposta economica")
        }
        AlertDialog.Builder(this).setTitle("Varianti · preventivo invariato")
            .setItems(labels.toTypedArray()) { _, position ->
                reviewMetricVariant(estimateGe360Id, variants[position])
            }
            .setNeutralButton("RIEPILOGO") { _, _ ->
                val events = store.metricVariantEvents(estimateGe360Id)
                val history = events.joinToString("\n\n") { event ->
                    event.action + " · " + event.variantGe360Id.take(8) + "\n" +
                        event.reason
                }.ifBlank { "Nessun evento" }
                val correctionEvents = store.agreementCorrectionEvents(estimateGe360Id)
                    .joinToString("\n\n") { event ->
                        event.action + " · " + event.correctionGe360Id.take(8) +
                            " · " + String.format(Locale.ITALY, "%+.2f €",
                                event.signedAmountEuro) + "\n" + event.reason
                    }.ifBlank { "Nessuna rettifica" }
                val commercialEvents = store.metricVariantEconomicEvents(estimateGe360Id)
                    .joinToString("\n\n") { event ->
                        event.action + " · " + event.economicsGe360Id.take(8) +
                            " · " + String.format(Locale.ITALY, "%+.2f €",
                                event.signedAmountEuro) +
                            (if (event.evidenceReference.isNotBlank())
                                " · " + event.evidenceReference else "") +
                            "\n" + event.reason
                    }.ifBlank { "Nessun evento" }
                textDialog("Riepilogo varianti e accordi",
                    overview + "\n\nSTORICO TECNICO\n" + history +
                        "\n\nSTORICO ECONOMICO\n" + commercialEvents +
                        "\n\nRETTIFICHE\n" + correctionEvents)
            }
            .setNegativeButton("Chiudi", null)
            .show()
    }

    private fun reviewMetricVariant(
        estimateGe360Id: String, variant: MetricVariantV3
    ) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        box.addView(TextView(this).apply {
            text = "Stato: ${variant.status}\n" +
                "Lavorazione: ${variant.workCode} · ${variant.surface}\n" +
                "Quantità originale: " +
                String.format(Locale.ITALY, "%.2f m²", variant.previousQuantityM2) + "\n" +
                "Quantità proposta: " +
                String.format(Locale.ITALY, "%.2f m²", variant.proposedQuantityM2) + "\n" +
                "Differenza: " +
                String.format(Locale.ITALY, "%+.2f m²", variant.deltaQuantityM2()) + "\n" +
                "Prezzo storico: " +
                String.format(Locale.ITALY, "%.2f €/m²", variant.priorUnitPrice) + "\n" +
                "Impatto INDICATIVO: " +
                String.format(Locale.ITALY, "%+.2f €", variant.referenceDeltaEuro()) + "\n" +
                "Motivo proposta: ${variant.reason}\n\n" +
                "La verifica tecnica NON approva corrispettivi e non modifica " +
                "le somme del preventivo, nemmeno se già accettato."
            textSize = 14f
        })
        if (variant.status == MetricVariantV3.STATUS_TECH_REVIEWED) {
            box.addView(TextView(this).apply {
                text = "Verifica tecnica già registrata: " + variant.reviewReason
            })
            AlertDialog.Builder(this).setTitle("Variante già verificata")
                .setView(ScrollView(this).apply { addView(box) })
                .setPositiveButton("APRI ECONOMIA") { _, _ ->
                    openMetricVariantEconomics(estimateGe360Id, variant)
                }
                .setNegativeButton("Chiudi", null).show()
            return
        }
        val note = EditText(this).apply {
            hint = "Esito della verifica tecnica e motivo (almeno 8 caratteri)"
            minLines = 3
        }
        val confirm = CheckBox(this).apply {
            text = "Ho controllato le nuove quantità. " +
                "Confermo solo la verifica tecnica, NON l'importo economico."
        }
        box.addView(note)
        box.addView(confirm)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Verifica tecnica variante")
            .setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton("Annulla", null)
            .setPositiveButton("CONFERMA VERIFICA", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                runCatching {
                    require(confirm.isChecked) { "Selezionare la conferma esplicita" }
                    store.reviewMetricVariant(estimateGe360Id,
                        variant.ge360Id, note.text.toString().trim())
                }.onSuccess {
                    dialog.dismiss()
                    toast("Verifica tecnica registrata; nessuna modifica agli importi")
                    showMetricVariants(estimateGe360Id)
                }.onFailure { toast(it.message ?: "Verifica non salvata") }
            }
        }
        dialog.show()
    }

    /** Commercial proposal and manual customer-evidence entry: separate from the quote. */
    private fun openMetricVariantEconomics(
        estimateGe360Id: String, variant: MetricVariantV3
    ) {
        val existing = store.metricVariantEconomics(estimateGe360Id)
            .singleOrNull { it.variantGe360Id == variant.ge360Id }
        if (existing != null) {
            showMetricVariantEconomics(estimateGe360Id, existing)
            return
        }
        if (variant.status != MetricVariantV3.STATUS_TECH_REVIEWED) {
            toast("Serve prima la verifica tecnica delle quantità")
            return
        }
        val quote = store.getByGe360Id(estimateGe360Id) ?: return
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(12))
        }
        box.addView(TextView(this).apply {
            text = "Lavorazione: ${variant.workCode}\n" +
                "Variazione: " +
                String.format(Locale.ITALY, "%+.2f m²", variant.deltaQuantityM2()) +
                "\nTipo: " +
                (if (variant.deltaQuantityM2() > 0) "LAVORO AGGIUNTIVO" else "RIDUZIONE") +
                "\nPrezzo storico (solo riferimento): " +
                String.format(Locale.ITALY, "%.2f €/m²", variant.priorUnitPrice) +
                "\nImporto indicativo con prezzo storico: " +
                String.format(Locale.ITALY, "%+.2f €", variant.referenceDeltaEuro()) +
                "\n\nIl prezzo concordabile può essere diverso da quello storico. " +
                "Non viene aggiornato il totale del preventivo."
            textSize = 14f
        })
        val modes = listOf(
            "Prezzo €/m²" to MetricVariantEconomicsV4.MODE_UNIT,
            "Importo a corpo €" to MetricVariantEconomicsV4.MODE_LUMP_SUM
        )
        val mode = Spinner(this).apply {
            adapter = ArrayAdapter(this@EstimatesActivity,
                android.R.layout.simple_spinner_dropdown_item, modes.map { it.first })
        }
        val price = EditText(this).apply {
            hint = "Prezzo unitario o importo a corpo, positivo €"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            isSingleLine = true
        }
        val reason = EditText(this).apply {
            hint = "Motivo e contenuto della proposta economica"
            minLines = 2
        }
        val confirm = CheckBox(this).apply {
            text = "Confermo che si tratta solo di una proposta economica: " +
                "nessun pagamento, addebito o modifica al preventivo originale."
        }
        box.addView(mode)
        box.addView(price)
        box.addView(reason)
        box.addView(confirm)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Nuova proposta economica variante")
            .setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton("Annulla", null)
            .setPositiveButton("REGISTRA PROPOSTA", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                runCatching {
                    require(confirm.isChecked) { "Confermare espressamente la proposta" }
                    val value = parseAmount(price.text.toString())
                    store.proposeMetricVariantEconomics(
                        estimateGe360Id, variant.ge360Id, quote.updatedAt,
                        modes[mode.selectedItemPosition].second, value,
                        reason.text.toString().trim()
                    )
                }.onSuccess {
                    dialog.dismiss()
                    toast("Proposta economica registrata separatamente dal preventivo")
                    showMetricVariantEconomics(estimateGe360Id, it)
                }.onFailure { toast(it.message ?: "Proposta non registrata") }
            }
        }
        dialog.show()
    }

    private fun pickAgreementEvidence(quoteId: String, economicId: String,
        correctionId: String = ""
    ) {
        pendingEvidenceEstimate = quoteId
        pendingEvidenceEconomics = economicId
        pendingEvidenceCorrection = correctionId
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "application/pdf", "image/jpeg", "image/png"
            ))
        }
        startActivityForResult(picker, REQ_EVIDENCE_FILE)
    }

    private fun addAgreementFiles(
        box: LinearLayout, quoteId: String,
        economicId: String, correctionId: String
    ) {
        val files = store.agreementAttachments(quoteId).filter {
            it.economicsGe360Id == economicId &&
                it.correctionGe360Id == correctionId
        }
        box.addView(TextView(this).apply {
            text = "PROVE DOCUMENTALI: ${files.size} copie locali (PDF, JPG, PNG)"
            textSize = 12f
        })
        files.forEach { attachment ->
            box.addView(MaterialButton(this).apply {
                text = "APRI · " + attachment.fileName.take(45)
                setOnClickListener {
                    runCatching {
                        val uri = MetricAgreementFilesV5.contentUri(
                            this@EstimatesActivity, attachment
                        )
                        startActivity(Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, attachment.mimeType)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        })
                    }.onFailure { toast(it.message ?: "Documento non disponibile") }
                }
            })
        }
        box.addView(MaterialButton(this).apply {
            text = "+ ALLEGA PDF / FOTO ACCORDO"
            setOnClickListener { pickAgreementEvidence(quoteId, economicId, correctionId) }
        })
        box.addView(TextView(this).apply {
            text = "Le prove sono copie private con SHA-256. Allegarle non equivale " +
                "alla firma o all'accettazione dell'accordo."
            textSize = 11f
        })
    }

    private fun showMetricVariantEconomics(
        estimateGe360Id: String, economics: MetricVariantEconomicsV4
    ) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(12))
        }
        box.addView(TextView(this).apply {
            text = "Stato: ${economics.status}\n" +
                "Categoria: " + (if (economics.kind == MetricVariantEconomicsV4.KIND_EXTRA)
                    "LAVORO AGGIUNTIVO" else "RIDUZIONE") + "\n" +
                "Differenza: " +
                String.format(Locale.ITALY, "%+.2f m²", economics.deltaM2) + "\n" +
                "Importo ${if (economics.status == MetricVariantEconomicsV4.STATUS_PROPOSED)
                    "proposto" else "con accordo annotato"}: " +
                String.format(Locale.ITALY, "%+.2f €", economics.proposedSignedAmountEuro) +
                "\nMotivo: ${economics.proposalReason}" +
                (if (economics.externalAgreementReference.isNotBlank())
                    "\nRiferimento esterno: ${economics.externalAgreementReference}" +
                        "\nData accordo: ${economics.externalAgreementDate}" +
                        "\nAnnotazione: ${economics.evidenceNote}"
                else "") +
                "\n\nQuesta scheda NON modifica il totale del preventivo, " +
                "non è una firma e non genera fatture."
            textSize = 14f
        })
        addAgreementFiles(box, estimateGe360Id, economics.ge360Id, "")
        if (economics.status == MetricVariantEconomicsV4.STATUS_EVIDENCE_RECORDED) {
            val corrections = MetricAgreementRevisionChainV6.ordered(
                economics, store.agreementCorrections(estimateGe360Id).filter {
                    it.economicsGe360Id == economics.ge360Id
                }
            )
            val latest = corrections.lastOrNull()
            val effective = MetricAgreementRevisionChainV6.effectiveSignedAmount(
                economics, corrections
            )
            box.addView(TextView(this).apply {
                text = "\nImporto annotato attuale: " +
                    String.format(Locale.ITALY, "%+.2f €", effective) +
                    "\nStorico rettifiche: ${corrections.size}\n" +
                    corrections.mapIndexed { index, row ->
                        "${index + 1}. ${row.status}: " +
                            String.format(Locale.ITALY, "%+.2f → %+.2f €",
                                row.priorSignedAmountEuro, row.correctedSignedAmountEuro)
                    }.joinToString("\n")
                textSize = 14f
            })
            AlertDialog.Builder(this).setTitle("Accordo esterno annotato")
                .setView(ScrollView(this).apply { addView(box) })
                .setPositiveButton(if (latest?.status == MetricAgreementCorrectionV5.STATUS_PROPOSED)
                    "APRI PROPOSTA" else "NUOVA RETTIFICA") { _, _ ->
                    if (latest?.status == MetricAgreementCorrectionV5.STATUS_PROPOSED) {
                        showAgreementCorrection(estimateGe360Id, economics, latest)
                    } else {
                        proposeAgreementCorrection(estimateGe360Id, economics)
                    }
                }
                .setNegativeButton("Chiudi", null).show()
            return
        }
        val reference = EditText(this).apply {
            hint = "Riferimento documentale esterno: email, protocollo, file"
            isSingleLine = true
        }
        val date = EditText(this).apply {
            hint = "Data dell'accordo YYYY-MM-DD"
            setText(java.time.LocalDate.now().toString())
            isSingleLine = true
        }
        val note = EditText(this).apply {
            hint = "Dove si trova la prova e come è stata verificata"
            minLines = 2
        }
        val confirm = CheckBox(this).apply {
            text = "Ho effettivamente controllato un accordo esterno con il cliente, " +
                "riferito a questo preciso importo. Sto annotando un'evidenza, " +
                "non generando firma, fattura o pagamento."
        }
        box.addView(reference)
        box.addView(date)
        box.addView(note)
        box.addView(confirm)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Annota accordo esterno")
            .setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton("Annulla", null)
            .setPositiveButton("CONFERMA ANNOTAZIONE", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                runCatching {
                    store.attestMetricVariantAgreement(
                        estimateGe360Id, economics.ge360Id,
                        reference.text.toString(), date.text.toString(),
                        note.text.toString(), confirm.isChecked
                    )
                }.onSuccess {
                    dialog.dismiss()
                    toast("Accordo esterno annotato: nessuna modifica a preventivo e contabilità")
                    showMetricVariantEconomics(estimateGe360Id, it)
                }.onFailure { toast(it.message ?: "Accordo non annotato") }
            }
        }
        dialog.show()
    }

    /** A correction changes the *effective preview*, never historical agreement rows. */
    private fun proposeAgreementCorrection(
        quoteId: String, economics: MetricVariantEconomicsV4
    ) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(12))
        }
        root.addView(TextView(this).apply {
            text = "Accordo precedente: " +
                String.format(Locale.ITALY, "%+.2f €",
                    MetricAgreementRevisionChainV6.effectiveSignedAmount(
                        economics, store.agreementCorrections(quoteId).filter {
                            it.economicsGe360Id == economics.ge360Id
                        }
                    )) +
                "\nLa nuova cifra deve mantenere il segno: un extra resta extra, " +
                "una riduzione resta riduzione. Se devi cambiare tipologia " +
                "occorre un nuovo iter contrattuale, non una rettifica."
            textSize = 14f
        })
        val amount = amountField(
            MetricAgreementRevisionChainV6.effectiveSignedAmount(
                economics, store.agreementCorrections(quoteId).filter {
                    it.economicsGe360Id == economics.ge360Id
                }
            ), "Nuovo importo complessivo con segno €", signed = true
        )
        val reason = EditText(this).apply {
            hint = "Motivo della rettifica (obbligatorio)"
            minLines = 2
        }
        val confirm = CheckBox(this).apply {
            text = "Confermo: salvo una PROPOSTA di rettifica, non sostituisco " +
                "la prova originale e non modifico contabilità, SAL o preventivo."
        }
        root.addView(amount)
        root.addView(reason)
        root.addView(confirm)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Proponi rettifica economica")
            .setView(ScrollView(this).apply { addView(root) })
            .setNegativeButton("Annulla", null)
            .setPositiveButton("SALVA RETTIFICA", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                runCatching {
                    require(confirm.isChecked) { "Conferma obbligatoria" }
                    store.proposeAgreementCorrection(
                        quoteId, economics.ge360Id,
                        parseAmount(amount.text.toString()),
                        reason.text.toString().trim()
                    )
                }.onSuccess {
                    dialog.dismiss()
                    toast("Rettifica proposta, importo precedente conservato")
                    showAgreementCorrection(quoteId, economics, it)
                }.onFailure { toast(it.message ?: "Rettifica non consentita") }
            }
        }
        dialog.show()
    }

    private fun showAgreementCorrection(
        quoteId: String,
        economic: MetricVariantEconomicsV4,
        correction: MetricAgreementCorrectionV5
    ) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(12))
        }
        root.addView(TextView(this).apply {
            text = "STATO: ${correction.status}\n" +
                "Importo precedente annotato: " + String.format(Locale.ITALY, "%+.2f €",
                    correction.priorSignedAmountEuro) + "\n" +
                "Importo rettificato: " + String.format(Locale.ITALY, "%+.2f €",
                    correction.correctedSignedAmountEuro) + "\n" +
                "Variazione rispetto all'importo precedente: " +
                String.format(Locale.ITALY, "%+.2f €", correction.adjustmentEuro()) +
                "\nMotivo: ${correction.reason}\n" +
                (if (correction.status == MetricAgreementCorrectionV5.STATUS_ATTESTED)
                    "\nRiferimento: ${correction.agreementReference}\n" +
                        "Data: ${correction.agreementDateIso}\n" +
                        "Verifica: ${correction.evidenceNote}\n"
                else "Rettifica ancora NON attestata: vale l'accordo precedente.\n") +
                "\nNessuna somma verrà modificata nel preventivo o nella contabilità."
            textSize = 14f
        })
        addAgreementFiles(root, quoteId, economic.ge360Id, correction.ge360Id)
        if (correction.status == MetricAgreementCorrectionV5.STATUS_ATTESTED) {
            AlertDialog.Builder(this).setTitle("Rettifica annotata")
                .setView(ScrollView(this).apply { addView(root) })
                .setPositiveButton("NUOVA RETTIFICA") { _, _ ->
                    proposeAgreementCorrection(quoteId, economic)
                }
                .setNegativeButton("Chiudi", null).show()
            return
        }
        val reference = EditText(this).apply {
            hint = "Riferimento documento della rettifica"
            isSingleLine = true
        }
        val date = EditText(this).apply {
            hint = "Data dell'accordo rettificato yyyy-MM-dd"
            setText(java.time.LocalDate.now().toString())
            isSingleLine = true
        }
        val note = EditText(this).apply {
            hint = "Come è stata verificata l'approvazione della rettifica?"
            minLines = 2
        }
        val confirm = CheckBox(this).apply {
            text = "Ho controllato un nuovo accordo esterno e allegato il PDF " +
                "o la foto corrispondente. Annoto solo la prova, non genero " +
                "firma digitale, credito, SAL o fattura."
        }
        root.addView(reference)
        root.addView(date)
        root.addView(note)
        root.addView(confirm)
        val dialog = AlertDialog.Builder(this).setTitle("Annota accordo rettificato")
            .setView(ScrollView(this).apply { addView(root) })
            .setNegativeButton("Annulla", null)
            .setPositiveButton("CONFERMA RETTIFICA", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                runCatching {
                    store.attestAgreementCorrection(
                        quoteId, correction.ge360Id,
                        reference.text.toString(), date.text.toString(),
                        note.text.toString(), confirm.isChecked
                    )
                }.onSuccess {
                    dialog.dismiss()
                    toast("Nuovo accordo annotato. Originale mantenuto nello storico.")
                    showAgreementCorrection(quoteId, economic, it)
                }.onFailure { toast(it.message ?: "Annotazione non consentita") }
            }
        }
        dialog.show()
    }

    private fun editHeader(source: EstimateRecord) {
        if (source.id > 0 && !EstimateLifecycleUi.ensureEditable(this, source)) return
        val r = source.copy(items = source.items.map { it.copy() }.toMutableList())
        val clientRows = clients.search()
        val allJobs = jobsites.list()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(8))
        }
        fun label(t: String) = TextView(this).apply {
            text = t
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(8), 0, 0)
        }
        fun input(value: String, hintText: String, lines: Int = 1) = EditText(this).apply {
            setText(value)
            hint = hintText
            if (lines == 1) isSingleLine = true else { minLines = lines; gravity = Gravity.TOP }
        }

        box.addView(label("Cliente GE360"))
        val clientSpin = Spinner(this).apply {
            adapter = ArrayAdapter(this@EstimatesActivity, android.R.layout.simple_spinner_dropdown_item,
                mutableListOf("Nessun cliente").apply { addAll(clientRows.map { it.fullName() }) })
            setSelection(clientRows.indexOfFirst { it.id == r.clientId }.let { if (it >= 0) it + 1 else 0 })
        }
        box.addView(clientSpin, lp52())

        box.addView(label("Cantiere GE360"))
        val jobSpin = Spinner(this)
        var visibleJobs: List<JobsiteRecord> = emptyList()
        fun loadJobs(keep: String = r.jobsiteId) {
            val c = clientRows.getOrNull(clientSpin.selectedItemPosition - 1)
            visibleJobs = if (c == null) allJobs else allJobs.filter { it.clientId == c.id || it.clientGe360Id == c.ge360Id }
            jobSpin.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
                mutableListOf("Nessun cantiere").apply { addAll(visibleJobs.map { "${it.code} · ${it.displayTitle()}" }) })
            val index = visibleJobs.indexOfFirst { it.ge360Id == keep }
            jobSpin.setSelection(if (index >= 0) index + 1 else 0)
        }
        loadJobs()
        clientSpin.onItemSelectedListener = Selected { loadJobs(if (r.id > 0) r.jobsiteId else "") }
        box.addView(jobSpin, lp52())

        val title = input(r.title, "Titolo preventivo")
        val workSpin = Spinner(this).apply {
            adapter = ArrayAdapter(this@EstimatesActivity, android.R.layout.simple_spinner_dropdown_item, WORK_TYPES_UI)
            setSelection(WORK_TYPES_UI.indexOf(r.workType).let { if (it >= 0) it else 0 })
        }
        val statusSpin = Spinner(this).apply {
            adapter = ArrayAdapter(this@EstimatesActivity, android.R.layout.simple_spinner_dropdown_item, EstimateStatuses.ALL)
            setSelection(EstimateStatuses.ALL.indexOf(r.status).coerceAtLeast(0))
        }
        val docValues = listOf("Provvisorio", "Definitivo", "Variante", "Extra")
        val docSpin = Spinner(this).apply {
            adapter = ArrayAdapter(this@EstimatesActivity, android.R.layout.simple_spinner_dropdown_item, docValues)
            setSelection(docValues.indexOf(r.documentType).coerceAtLeast(0))
        }
        val validity = input(r.validityDays.toString(), "Validità giorni").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        val description = input(r.generalDescription, "Descrizione generale dell'intervento", 3)
        val materials = input(r.materialsIncluded, "Materiali inclusi", 2)
        val clientMaterials = input(r.materialsClient, "Materiali a carico cliente", 2)
        val exclusions = input(r.exclusions, "Esclusioni", 2)
        val notes = input(r.finalNotes, "Note finali", 2)
        listOf(
            "Titolo" to title, "Tipo lavoro" to workSpin, "Stato" to statusSpin, "Tipo documento" to docSpin,
            "Validità" to validity, "Descrizione generale" to description, "Materiali inclusi" to materials,
            "Materiali cliente" to clientMaterials, "Esclusioni" to exclusions, "Note" to notes
        ).forEach { (l, v) ->
            box.addView(label(l))
            box.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, if (v is EditText && v.minLines > 1) dp(88) else dp(52)))
        }

        fun collect() {
            val c = clientRows.getOrNull(clientSpin.selectedItemPosition - 1)
            val j = visibleJobs.getOrNull(jobSpin.selectedItemPosition - 1)
            r.clientId = c?.id ?: 0
            r.clientGe360Id = c?.ge360Id.orEmpty()
            if (c != null) r.clientName = c.fullName()
            r.jobsiteId = j?.ge360Id.orEmpty()
            r.jobsiteCode = j?.code.orEmpty()
            r.jobsiteTitle = j?.displayTitle().orEmpty()
            if (c == null && j != null) r.clientName = j.clientName
            r.title = title.text.toString().trim()
            r.workType = workSpin.selectedItem.toString()
            r.status = statusSpin.selectedItem.toString()
            r.documentType = docSpin.selectedItem.toString()
            r.validityDays = validity.text.toString().toIntOrNull()?.coerceIn(1, 365) ?: 30
            r.generalDescription = description.text.toString().trim()
            r.materialsIncluded = materials.text.toString().trim()
            r.materialsClient = clientMaterials.text.toString().trim()
            r.exclusions = exclusions.text.toString().trim()
            r.finalNotes = notes.text.toString().trim()
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (r.id > 0) "Modifica ${r.number}" else "Nuovo preventivo")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Salva", null)
            .setNeutralButton("Lavorazioni", null)
            .setNegativeButton("Annulla", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                collect()
                if (r.status in setOf(EstimateStatuses.ACCEPTED, EstimateStatuses.IN_PROGRESS, EstimateStatuses.COMPLETED) &&
                    source.status !in setOf(EstimateStatuses.ACCEPTED, EstimateStatuses.IN_PROGRESS, EstimateStatuses.COMPLETED)) {
                    toast("Per rendere il lavoro effettivo usa LAVORO ACCETTATO: crea il contratto e il cantiere insieme.")
                    return@setOnClickListener
                }
                runCatching { store.save(r) }
                    .onSuccess { dialog.dismiss(); refresh() }
                    .onFailure { toast(it.message ?: "Impossibile salvare il preventivo") }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                collect(); editItems(r) { dialog.dismiss() }
            }
        }
        dialog.show()
    }

    private fun editItems(source: EstimateRecord, afterSave: (() -> Unit)? = null) {
        if (source.id > 0 && !EstimateLifecycleUi.ensureEditable(this, source)) return
        val r = if (source.id > 0) store.get(source.id) ?: source else source
        val metricConfirmations = mutableMapOf<String, String>()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(6), dp(16), dp(8))
        }
        val total = TextView(this).apply {
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(total)
        val provenanceHistory = store.metricEvents(r.ge360Id)
        if (provenanceHistory.isNotEmpty()) root.addView(MaterialButton(this).apply {
            text = "STORICO CONFERME METRICHE · ${provenanceHistory.size}"
            setOnClickListener {
                val details = provenanceHistory.joinToString("\n\n") { event ->
                    "${event.action} · ${event.itemGe360Id.take(8)} · " +
                        "${java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALY)
                            .format(java.util.Date(event.occurredAt))}\n" +
                        "Prezzo: ${event.priorPrice} → ${event.newPrice} € · " +
                        "Incluso: ${event.wasIncluded} → ${event.nowIncluded}\n" + event.reason
                }
                textDialog("Registro verifiche metriche", details)
            }
        })
        root.addView(TextView(this).apply {
            text = "Tocca una voce per modificarla · pressione lunga per rimuoverla"
            textSize = 11f
            setPadding(0, 0, 0, dp(6))
        })
        val itemAdapter = ItemAdapter(r.items)
        root.addView(ListView(this).apply {
            adapter = itemAdapter
            dividerHeight = 0
            setOnItemClickListener { _, _, p, _ -> editItem(r.items[p], metricConfirmations) {
                itemAdapter.notifyDataSetChanged(); updateTotal(r, total)
            } }
            setOnItemLongClickListener { _, _, p, _ ->
                if (store.metricProvenance(r.items[p].ge360Id) != null) {
                    toast("La metratura collegata non si elimina qui: gestire una variante dal rilievo")
                } else {
                    r.items.removeAt(p); itemAdapter.notifyDataSetChanged(); updateTotal(r, total)
                }
                true
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(310)))

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(MaterialButton(this@EstimatesActivity).apply {
                text = "+ Configura"
                textSize = 11f
                setOnClickListener {
                    GuidedWorkConfigurator.openForEstimate(this@EstimatesActivity, r) {
                        itemAdapter.notifyDataSetChanged()
                        updateTotal(r, total)
                    }
                }
            }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = dp(4) })
            addView(MaterialButton(this@EstimatesActivity).apply {
                text = "+ Capitolato"
                textSize = 11f
                setOnClickListener { pickStandardWorks(r) { itemAdapter.notifyDataSetChanged(); updateTotal(r, total) } }
            }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginStart = dp(4) })
        })
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(MaterialButton(this@EstimatesActivity).apply {
                text = "+ Prezzario"
                textSize = 11f
                setOnClickListener { pickPrice(r) { itemAdapter.notifyDataSetChanged(); updateTotal(r, total) } }
            }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = dp(4); topMargin = dp(4) })
            addView(MaterialButton(this@EstimatesActivity).apply {
                text = "+ Libera"
                textSize = 11f
                setOnClickListener {
                    val x = EstimateItem(ge360Id = UUID.randomUUID().toString())
                    r.items += x
                    editItem(x, metricConfirmations) {
                        itemAdapter.notifyDataSetChanged(); updateTotal(r, total)
                    }
                }
            }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginStart = dp(4); topMargin = dp(4) })
        })

        val discount = amountField(if (r.discountType == "percent" && r.discountValue > 0) r.discountValue else null, "Sconto %")
        val surcharge = amountField(if (r.surchargeType == "percent" && r.surchargeValue > 0) r.surchargeValue else null, "Maggiorazione %")
        val rounding = amountField(if (r.rounding != 0.0) r.rounding else null, "Arrotondamento € (+/-)", true)
        root.addView(discount)
        root.addView(surcharge)
        root.addView(rounding)
        updateTotal(r, total)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Lavorazioni guidate, capitolato e prezzi")
            .setView(root)
            .setPositiveButton("Salva", null)
            .setNegativeButton("Annulla", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                r.discountValue = parseAmount(discount.text.toString()).coerceAtLeast(0.0)
                r.discountType = if (r.discountValue > 0) "percent" else "none"
                r.surchargeValue = parseAmount(surcharge.text.toString()).coerceAtLeast(0.0)
                r.surchargeType = if (r.surchargeValue > 0) "percent" else "none"
                r.rounding = parseAmount(rounding.text.toString())
                runCatching { store.save(r, metricConfirmations) }
                    .onSuccess {
                        dialog.dismiss()
                        refresh()
                        afterSave?.invoke()
                    }
                    .onFailure { error ->
                        toast(error.message ?: "Salvataggio non consentito: verificare le quantità")
                    }
            }
        }
        dialog.show()
    }

    private fun editItem(
        item: EstimateItem,
        metricConfirmations: MutableMap<String, String> = mutableMapOf(),
        done: () -> Unit
    ) {
        val measuredOrigin = store.metricProvenance(item.ge360Id)
        val isMeasured = measuredOrigin != null ||
            item.internalNote.contains(MeasuredEstimateDraftV1.SOURCE_MARKER)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        fun f(value: String, hintText: String) = EditText(this).apply {
            setText(value); hint = hintText; isSingleLine = true
        }
        fun label(textValue: String) = TextView(this).apply {
            text = textValue
            setTypeface(typeface, Typeface.BOLD)
            textSize = 12f
            setPadding(0, dp(7), 0, 0)
        }

        val name = f(item.name, "Lavorazione")
        val category = f(item.category, "Categoria")
        val room = f(item.room, "Ambiente / stanza")
        val quantity = amountField(item.quantity, "Quantità")
        val price = amountField(item.unitPrice, "Prezzo")
        val unit = Spinner(this).apply {
            adapter = ArrayAdapter(this@EstimatesActivity, android.R.layout.simple_spinner_dropdown_item, EstimateStore.UNITS)
            setSelection(EstimateStore.UNITS.indexOf(item.unit).coerceAtLeast(0))
        }
        val section = Spinner(this).apply {
            adapter = ArrayAdapter(this@EstimatesActivity, android.R.layout.simple_spinner_dropdown_item, SECTION_VALUES_UI)
            setSelection(SECTION_VALUES_UI.indexOf(item.section).let { if (it >= 0) it else 0 })
        }
        val description = EditText(this).apply {
            hint = "Descrizione tecnica visibile al cliente"
            setText(item.description)
            minLines = 4
            gravity = Gravity.TOP
        }
        val internalNote = EditText(this).apply {
            hint = "Nota interna — non viene condivisa con il cliente"
            setText(item.internalNote)
            minLines = 2
            gravity = Gravity.TOP
        }
        val included = CheckBox(this).apply { text = "Inclusa nel totale"; isChecked = item.included }
        val optional = CheckBox(this).apply { text = "Opzionale / fuori totale"; isChecked = item.optional }
        val confirmedMeasuredPrice = CheckBox(this).apply {
            text = "Confermo di aver ricontrollato quantità, detrazioni e prezzo " +
                "prima di modificare l'importo o includere nel totale."
        }
        val confirmationReason = EditText(this).apply {
            hint = "Motivo della revisione economica (almeno 8 caratteri)"
            minLines = 2
        }
        if (isMeasured) {
            // Preserve the immutable source reference: changing scope/quantity requires
            // removal and a new confirmation from the source floor plan.
            room.isEnabled = false
            quantity.isEnabled = false
            unit.isEnabled = false
            internalNote.isEnabled = false
            root.addView(TextView(this).apply {
                text = "VOCE IMPORTATA DAL RILIEVO · geometria e quantità sono " +
                    "una fotografia confermata. Per cambiare i metri riparti dal rilievo; " +
                    "controlla che muri e aperture siano ancora corretti."
                textSize = 12f
                setPadding(0, dp(8), 0, dp(8))
            })
        }

        listOf(
            "Lavorazione" to name,
            "Categoria" to category,
            "Ambiente" to room,
            "Quantità" to quantity,
            "Unità" to unit,
            "Prezzo" to price,
            "Sezione documento" to section
        ).forEach { (l, v) -> root.addView(label(l)); root.addView(v, lp52()) }
        root.addView(label("Descrizione tecnica / capitolato"))
        root.addView(description, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(128)))
        root.addView(label("Nota interna"))
        root.addView(internalNote, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(82)))
        root.addView(included, lp52())
        root.addView(optional, lp52())
        if (isMeasured) {
            root.addView(confirmedMeasuredPrice)
            root.addView(confirmationReason)
            if (measuredOrigin?.trust != MetricProvenanceV2.TRUST_VERIFIED)
                root.addView(TextView(this).apply {
                    text = "PROVENIENZA LEGACY NON VERIFICATA: " +
                        "non è consentito cambiare prezzo o includere la voce. " +
                        "Occorre ripetere la selezione dal rilievo."
                    textSize = 12f
                })
        }

        val isTechnical = item.priceListCode.startsWith(StandardWorkCatalog.PREFIX) ||
            item.priceListCode.startsWith(BathroomWorkCatalog.PREFIX) ||
            GuidedWorkCatalog.isGuidedCode(item.priceListCode)
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (isTechnical) "Voce tecnica ${item.priceListCode}" else "Voce preventivo")
            .setView(ScrollView(this).apply { addView(root) })
            .setPositiveButton("Salva", null)
            .setNegativeButton("Annulla", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (name.text.toString().isBlank()) { name.error = "Nome obbligatorio"; return@setOnClickListener }
                val newPrice = parseAmount(price.text.toString())
                val financialChanged = isMeasured && (
                    included.isChecked != item.included ||
                        kotlin.math.abs(newPrice - item.unitPrice) > 0.001
                    )
                if (financialChanged) {
                    if (measuredOrigin?.trust != MetricProvenanceV2.TRUST_VERIFIED ||
                        !confirmedMeasuredPrice.isChecked ||
                        confirmationReason.text.toString().trim().length < 8) {
                        toast("Serve una provenienza verificata e una conferma motivata")
                        return@setOnClickListener
                    }
                    if (included.isChecked && !optional.isChecked && newPrice <= 0.0) {
                        price.error = "Inserisci un prezzo positivo per includere nel totale"
                        return@setOnClickListener
                    }
                    metricConfirmations[item.ge360Id] =
                        confirmationReason.text.toString().trim()
                } else metricConfirmations.remove(item.ge360Id)
                item.name = name.text.toString().trim()
                item.category = category.text.toString().trim().ifBlank { "Personalizzate" }
                item.room = room.text.toString().trim()
                if (!isMeasured)
                    item.quantity = parseAmount(quantity.text.toString()).coerceAtLeast(0.0)
                if (!isMeasured) item.unit = unit.selectedItem.toString()
                item.unitPrice = parseAmount(price.text.toString()).coerceAtLeast(0.0)
                item.priceMode = if (item.unit in listOf("corpo", "forfait")) "body" else "unit"
                item.section = section.selectedItem.toString()
                item.description = description.text.toString().trim()
                if (!isMeasured)
                    item.internalNote = internalNote.text.toString().trim()
                item.included = included.isChecked
                item.optional = optional.isChecked
                item.total = calculateItemTotal(item)
                dialog.dismiss()
                done()
            }
        }
        dialog.show()
    }

    private fun pickStandardWorks(r: EstimateRecord, done: () -> Unit) {
        val groups = StandardWorkCatalog.groups
        val labels = groups.map { group -> "$group · ${StandardWorkCatalog.search(group = group).size} voci" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Capitolato standard · scegli categoria")
            .setItems(labels) { _, position -> pickStandardGroup(groups[position], r, done) }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun pickStandardGroup(group: String, r: EstimateRecord, done: () -> Unit) {
        val rows = StandardWorkCatalog.search(group = group)
        val existingCodes = r.items.map { it.priceListCode }.toSet()
        val checked = BooleanArray(rows.size)
        val labels = rows.map {
            "${it.code} · ${it.title}\nUnità: ${it.unit}${if (it.code in existingCodes) " · GIÀ PRESENTE" else ""}"
        }.toTypedArray()
        val dialog = AlertDialog.Builder(this)
            .setTitle(group)
            .setMultiChoiceItems(labels, checked) { d, which, isChecked ->
                if (isChecked && rows[which].code in existingCodes) {
                    checked[which] = false
                    (d as? AlertDialog)?.listView?.setItemChecked(which, false)
                    toast("${rows[which].code} è già presente nel preventivo")
                } else checked[which] = isChecked
            }
            .setPositiveButton("Aggiungi selezionate", null)
            .setNeutralButton("Consulta", null)
            .setNegativeButton("Annulla", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val selected = rows.filterIndexed { index, _ -> checked[index] }.filter { it.code !in existingCodes }
                if (selected.isEmpty()) {
                    toast("Nessuna nuova voce selezionata")
                    return@setOnClickListener
                }
                r.items += selected.map { it.toEstimateItem() }
                dialog.dismiss()
                done()
                toast("Aggiunte ${selected.size} voci di capitolato")
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { showStandardGroup(group) }
        }
        dialog.show()
    }

    private fun showStandardCatalog() {
        val groups = StandardWorkCatalog.groups
        val labels = groups.map { "$it · ${StandardWorkCatalog.search(group = it).size}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Capitolato standard ${StandardWorkCatalog.VERSION}")
            .setItems(labels) { _, p -> showStandardGroup(groups[p]) }
            .setNeutralButton("Clausola generale") { _, _ -> textDialog("Clausola tecnica generale", StandardWorkCatalog.GENERAL_CLAUSE) }
            .setPositiveButton("Chiudi", null)
            .show()
    }

    private fun showStandardGroup(group: String) {
        val text = StandardWorkCatalog.search(group = group).joinToString("\n\n") { entry ->
            buildString {
                append(entry.code).append(" · ").append(entry.title).append(" [").append(entry.unit).append("]\n")
                append(entry.description)
                if (entry.references.isNotEmpty()) append("\nRif.: ").append(entry.references.joinToString("; "))
            }
        }
        textDialog(group, text)
    }

    private fun pickPrice(r: EstimateRecord, done: () -> Unit) {
        val rows = store.priceList()
        if (rows.isEmpty()) { toast("Prezzario vuoto"); return }
        val categories = rows.map { it.category }.distinct()
        AlertDialog.Builder(this)
            .setTitle("Prezzario · scegli categoria")
            .setItems(categories.map { category -> "$category · ${rows.count { it.category == category }}" }.toTypedArray()) { _, p ->
                pickPriceCategory(categories[p], r, done)
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun pickPriceCategory(category: String, r: EstimateRecord, done: () -> Unit) {
        val rows = store.priceList().filter { it.category == category }
        val labels = rows.map { "${it.name} · ${it.suggestedPrice?.let(::euro) ?: "manuale"}/${it.unit}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(category)
            .setItems(labels) { _, p ->
                val x = rows[p]
                val item = EstimateItem(
                    ge360Id = UUID.randomUUID().toString(),
                    priceListCode = x.code,
                    category = x.category,
                    name = x.name,
                    unit = x.unit,
                    unitPrice = x.suggestedPrice ?: 0.0,
                    priceMode = x.priceMode
                )
                r.items += item
                editItem(item) { done() }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun updateTotal(r: EstimateRecord, view: TextView) {
        val t = recalculateEstimate(r)
        val standard = r.items.count { it.priceListCode.startsWith(StandardWorkCatalog.PREFIX) }
        val bathroomSpecific = r.items.count { it.priceListCode.startsWith(BathroomWorkCatalog.PREFIX) }
        val guided = r.items.count { GuidedWorkCatalog.isGuidedCode(it.priceListCode) }
        view.text = "Subtotale ${euro(t.subtotal)} · Totale ${euro(t.finalTotal)} · ${r.items.count { it.included && !it.optional }} voci · ${standard + bathroomSpecific + guided} tecniche"
    }

    private fun showPriceList() {
        val rows = store.priceList()
        if (rows.isEmpty()) { toast("Prezzario vuoto"); return }
        val categories = rows.map { it.category }.distinct()
        AlertDialog.Builder(this)
            .setTitle("Prezzario GE360")
            .setItems(categories.map { "$it · ${rows.count { row -> row.category == it }}" }.toTypedArray()) { _, p ->
                val category = categories[p]
                val text = rows.filter { it.category == category }.joinToString("\n\n") {
                    "${it.code} · ${it.name}\n${it.suggestedPrice?.let(::euro) ?: "prezzo manuale"}/${it.unit}"
                }
                textDialog(category, text)
            }
            .setNeutralButton("+ Nuova voce") { _, _ -> editPrice(PriceEntry()) }
            .setPositiveButton("Chiudi", null)
            .show()
    }

    private fun editPrice(p: PriceEntry) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        val name = EditText(this).apply { hint = "Nome" }
        val category = EditText(this).apply { hint = "Categoria"; setText("Personalizzate") }
        val price = amountField(null, "Prezzo suggerito")
        val unit = Spinner(this).apply {
            adapter = ArrayAdapter(this@EstimatesActivity, android.R.layout.simple_spinner_dropdown_item, EstimateStore.UNITS)
        }
        listOf<View>(name, category, unit, price).forEach { root.addView(it, lp52()) }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Nuova voce prezzario")
            .setView(root)
            .setPositiveButton("Salva", null)
            .setNegativeButton("Annulla", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (name.text.toString().isBlank()) { name.error = "Nome obbligatorio"; return@setOnClickListener }
                p.name = name.text.toString().trim()
                p.category = category.text.toString().trim().ifBlank { "Personalizzate" }
                p.unit = unit.selectedItem.toString()
                p.suggestedPrice = price.text.toString().takeIf { it.isNotBlank() }?.let(::parseAmount)
                p.priceMode = if (p.unit in listOf("corpo", "forfait")) "body" else "unit"
                store.savePrice(p)
                dialog.dismiss()
                toast("Voce salvata")
            }
        }
        dialog.show()
    }

    private fun economics(r: EstimateRecord) {
        if (r.jobsiteId.isBlank()) {
            toast("Collega prima il preventivo a un cantiere")
            return
        }
        val finance = finances.jobsiteSummary(r.jobsiteId)
        val breakdown = daily.laborBreakdownForJobsite(r.jobsiteId)
        val minutes = breakdown.sumOf { it.minutes }
        val laborCost = breakdown.sumOf { it.cost }
        val unratedMinutes = breakdown.sumOf { it.unratedMinutes }
        val knownCosts = finance.expense + laborCost
        val margin = r.finalTotal - knownCosts
        val laborLines = if (breakdown.isEmpty()) {
            "Nessuna ora registrata in Giornata"
        } else breakdown.joinToString("\n") { row ->
            val rateInfo = when {
                row.sessionRates.isEmpty() -> "TARIFFA NON CONFIGURATA"
                row.sessionRates.size == 1 -> "${euro(row.sessionRates.first())}/h"
                else -> "tariffe ${row.sessionRates.joinToString(" / ") { euro(it) }}"
            }
            val missing = if (row.unratedMinutes > 0) " · ⚠ ${formatMinutes(row.unratedMinutes)} senza tariffa" else ""
            "• ${row.collaborator}: ${formatMinutes(row.minutes)} · $rateInfo · costo ${euro(row.cost)}$missing"
        }
        val warning = if (unratedMinutes > 0) {
            "\n\n⚠ ATTENZIONE: ${formatMinutes(unratedMinutes)} di lavoro non hanno una tariffa fotografata. Il margine sotto è quindi più alto del reale finché non valorizzi quelle ore."
        } else ""
        textDialog(
            "Economia cantiere",
            "${r.jobsiteCode} · ${r.jobsiteTitle}\n\n" +
                "Valore preventivo: ${euro(r.finalTotal)}\n" +
                "Costi registrati in Spese: ${euro(finance.expense)}\n" +
                "Ore registrate in Giornata: ${formatMinutes(minutes)}\n\n" +
                "MANODOPERA PER COLLABORATORE\n$laborLines\n\n" +
                "Manodopera valorizzata: ${euro(laborCost)}\n" +
                "Costi conosciuti: ${euro(knownCosts)}\n\n" +
                "MARGINE OPERATIVO STIMATO: ${euro(margin)}\n\n" +
                "Incassi registrati: ${euro(finance.income)}\n" +
                "Saldo di cassa: ${euro(finance.balance)}" + warning + "\n\n" +
                "Nota: non è utile netto. Imposte, costi indiretti, ammortamenti e variazioni restano separati. " +
                "Se un collaboratore è già contabilizzato in Spese, evita di conteggiare due volte lo stesso costo."
        )
    }

    private fun customerSummary(r: EstimateRecord): String {
        val t = recalculateEstimate(r)
        val included = r.items.filter { it.included && !it.optional }
        val grouped = included.groupBy { it.section.ifBlank { "Lavorazioni" } }
        val workText = grouped.entries.joinToString("\n\n") { (section, rows) ->
            section.uppercase(Locale.ITALY) + "\n" + rows.joinToString("\n\n") { item -> customerItem(item) }
        }.ifBlank { "Nessuna voce inserita" }
        val optionalRows = r.items.filter { it.optional }
        val optionalText = if (optionalRows.isEmpty()) "" else "\n\nOPZIONI NON COMPRESE NEL TOTALE\n" + optionalRows.joinToString("\n\n") { customerItem(it) }
        val materialsText = buildString {
            if (r.materialsIncluded.isNotBlank()) append("\n\nMATERIALI INCLUSI\n").append(r.materialsIncluded)
            if (r.materialsClient.isNotBlank()) append("\n\nMATERIALI A CARICO CLIENTE\n").append(r.materialsClient)
        }
        val exclusionsText = if (r.exclusions.isBlank()) "" else "\n\nESCLUSIONI\n${r.exclusions}"
        val descriptionText = if (r.generalDescription.isBlank()) "" else "\nOGGETTO DELLE OPERE\n${r.generalDescription}\n"
        val technicalText = technicalClosing(r)
        val notesText = if (r.finalNotes.isBlank()) "" else "\n\nNOTE FINALI\n${r.finalNotes}"
        return "EDIL MILAN STEVIC — Preventivo ${r.number}\n" +
            "Cliente: ${r.clientName.ifBlank { "—" }}\n" +
            "Cantiere: ${listOf(r.jobsiteCode, r.jobsiteTitle).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "—" }}\n" +
            "Titolo: ${r.title.ifBlank { r.workType }}\n" +
            "Stato: ${r.status} · Validità: ${r.validityDays} giorni\n" +
            descriptionText +
            "\nCAPITOLATO DELLE LAVORAZIONI\n$workText$optionalText$materialsText$exclusionsText\n\n" +
            "RIEPILOGO ECONOMICO\nSubtotale: ${euro(t.subtotal)}\nTotale: ${euro(t.finalTotal)}" +
            technicalText + notesText
    }

    private fun customerItem(item: EstimateItem): String {
        val pricing = if (item.priceMode == "body" || item.unit in listOf("corpo", "forfait")) {
            euro(item.unitPrice)
        } else {
            "${qty(item.quantity)} ${item.unit} × ${euro(item.unitPrice)} = ${euro(item.total)}"
        }
        return buildString {
            append("• ").append(item.name)
            if (item.room.isNotBlank()) append(" (").append(item.room).append(')')
            append(" — ").append(pricing)
            if (item.description.isNotBlank()) append("\n  ").append(item.description.replace("\n", "\n  "))
        }
    }

    private fun technicalClosing(r: EstimateRecord): String {
        val technicalItems = r.items.filter {
            it.included && (
                it.priceListCode.startsWith(StandardWorkCatalog.PREFIX) ||
                    it.priceListCode.startsWith(BathroomWorkCatalog.PREFIX) ||
                    GuidedWorkCatalog.isGuidedCode(it.priceListCode)
                )
        }
        if (technicalItems.isEmpty()) return ""
        val standardRefs = StandardWorkCatalog.referencesFor(technicalItems.filter { it.priceListCode.startsWith(StandardWorkCatalog.PREFIX) })
        val bathroomRefs = BathroomWorkCatalog.referencesFor(technicalItems.filter { it.priceListCode.startsWith(BathroomWorkCatalog.PREFIX) })
        val guidedRefs = GuidedWorkCatalog.referencesFor(technicalItems.filter { GuidedWorkCatalog.isGuidedCode(it.priceListCode) })
        val refs = (standardRefs + bathroomRefs + guidedRefs).distinct()
        return buildString {
            append("\n\nCLAUSOLA TECNICA GENERALE\n").append(StandardWorkCatalog.GENERAL_CLAUSE)
            if (refs.isNotEmpty()) {
                append("\n\nRIFERIMENTI TECNICI RICHIAMATI DALLE VOCI SELEZIONATE\n")
                refs.forEach { append("• ").append(it).append('\n') }
            }
        }.trimEnd()
    }

    private fun openSmartSurveys() {
        val surveys = SmartSurveyLocalStore(this).list()
        if (surveys.isEmpty()) {
            startActivity(Intent(this, SmartSurveyActivity::class.java))
            return
        }
        val choices = arrayOf("＋ NUOVO SOPRALLUOGO") + surveys.map { draft ->
            val client = draft.clientName.ifBlank { "Cliente da indicare" }
            val title = draft.title.ifBlank { "Sopralluogo senza titolo" }
            val state = if (draft.generatedEstimateId.isBlank()) "IN LAVORAZIONE" else "BOZZA CREATA"
            "$title · $client\n${draft.rooms.size} ambienti · passo ${draft.step + 1}/8 · $state"
        }
        AlertDialog.Builder(this)
            .setTitle("Sopralluoghi Smart")
            .setMessage("Riprendi un sopralluogo salvato, oppure creane uno nuovo. Nessun dato viene inviato automaticamente.")
            .setItems(choices) { _, index ->
                val intent = Intent(this, SmartSurveyActivity::class.java)
                if (index > 0) intent.putExtra(SmartSurveyActivity.EXTRA_SURVEY_ID, surveys[index - 1].id)
                startActivity(intent)
            }
            .setNegativeButton("Chiudi", null).show()
    }

    private fun showAiSopralluogoPrompt(r: EstimateRecord) {
        val roomStore = EstimateRoomStore(this)
        val rooms = try { roomStore.list(r.ge360Id) } finally { roomStore.close() }
        val personal = PreventiviCustomizationStore(this)
        val report = EstimateSopralluogoPromptV1.build(
            record = r, rooms = rooms,
            measures = personal::manualMeasuresSummary,
            presentationMode = personal.presentationMode(r.ge360Id)
        )
        val checks = EstimateSopralluogoPromptV1.checks(r, rooms, personal::manualMeasuresSummary)
        AlertDialog.Builder(this)
            .setTitle("GE360 · Prompt IA sopralluogo")
            .setMessage(
                "Prompt per GPT Preventivi Premium V3.1, creato in locale.\n" +
                "Nessuna chiamata IA automatica. Controlla i dati prima di condividerli.\n\n" +
                if (checks.isEmpty()) "Nessuna anomalia preliminare." else
                    "Verifiche suggerite (${checks.size}):\n" + checks.take(6).joinToString("\n") { "• $it" }
            )
            .setPositiveButton("Condividi") { _, _ -> share(report, "Prompt sopralluogo ${r.number}") }
            .setNeutralButton("Copia prompt") { _, _ ->
                val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("GE360 prompt sopralluogo", report))
                toast("Prompt copiato: verifica i dati cliente prima di incollarli")
            }
            .setNegativeButton("Anteprima") { _, _ -> textDialog("Prompt sopralluogo", report) }
            .show()
    }

    private fun dataMenu() = AlertDialog.Builder(this)
        .setTitle("Dati Preventivi")
        .setItems(arrayOf("Esporta backup JSON", "Importa e unisci", "Importa e sostituisci")) { _, i ->
            when (i) {
                0 -> startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_TITLE, "ge360-preventivi-backup.json")
                }, REQ_EXPORT)
                1 -> { replaceOnImport = false; openImport() }
                2 -> { replaceOnImport = true; openImport() }
            }
        }.show()

    private fun openImport() = startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        type = "application/json"
        addCategory(Intent.CATEGORY_OPENABLE)
    }, REQ_IMPORT)

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK || data?.data == null) return
        val uri = data.data!!
        when (requestCode) {
            REQ_EVIDENCE_FILE -> runCatching {
                require(pendingEvidenceEstimate.isNotBlank() &&
                    pendingEvidenceEconomics.isNotBlank()) {
                    "Selezione allegato scaduta: aprire di nuovo la variante"
                }
                store.attachAgreementFile(
                    pendingEvidenceEstimate, pendingEvidenceEconomics,
                    pendingEvidenceCorrection, uri
                )
            }.onSuccess {
                toast("Documento copiato e verificato SHA-256: ${it.fileName}")
                val quoteId = pendingEvidenceEstimate
                val economicId = pendingEvidenceEconomics
                val correctionId = pendingEvidenceCorrection
                val economics = store.metricVariantEconomics(quoteId)
                    .singleOrNull { it.ge360Id == economicId }
                if (economics != null) {
                    if (correctionId.isNotBlank()) {
                        val correction = store.agreementCorrections(quoteId)
                            .singleOrNull { it.ge360Id == correctionId }
                        if (correction != null) showAgreementCorrection(quoteId, economics, correction)
                    } else showMetricVariantEconomics(quoteId, economics)
                }
            }.onFailure { toast(it.message ?: "Allegato non salvato") }
                .also {
                    pendingEvidenceEstimate = ""
                    pendingEvidenceEconomics = ""
                    pendingEvidenceCorrection = ""
                }
            REQ_EXPORT -> runCatching {
                require(store.list().none {
                    store.agreementAttachments(it.ge360Id).isNotEmpty()
                }) {
                    "Questo preventivo contiene PDF/foto di accordi: " +
                        "esporta da Backup GE360 ZIP per non perdere gli allegati"
                }
                contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(store.exportJson()) }
                    ?: error("File non scrivibile")
            }.onSuccess { toast("Backup esportato") }.onFailure { toast(it.message ?: "Errore") }
            REQ_IMPORT -> runCatching {
                val txt = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("File non leggibile")
                store.importJson(txt, replaceOnImport)
            }.onSuccess { toast("Importati $it preventivi"); refresh() }
                .onFailure { toast("Import fallito: ${it.message}") }
        }
    }

    private fun amountField(value: Double?, hintText: String, signed: Boolean = false) = EditText(this).apply {
        hint = hintText
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or
            if (signed) InputType.TYPE_NUMBER_FLAG_SIGNED else 0
        if (value != null) setText(value.toString().replace('.', ','))
    }

    private fun parseAmount(raw: String): Double {
        var s = raw.trim().replace("€", "").replace(" ", "")
        if (s.isBlank()) return 0.0
        s = when {
            s.contains(',') && s.contains('.') -> if (s.lastIndexOf(',') > s.lastIndexOf('.')) s.replace(".", "").replace(',', '.') else s.replace(",", "")
            s.contains(',') -> s.replace(',', '.')
            else -> s
        }
        return s.filter { it.isDigit() || it == '.' || it == '-' }.toDoubleOrNull() ?: 0.0
    }

    private fun deleteConfirm(r: EstimateRecord) = AlertDialog.Builder(this)
        .setTitle("Elimina preventivo")
        .setMessage("Eliminare ${r.number}?")
        .setPositiveButton("Elimina") { _, _ ->
            runCatching { store.delete(r.id) }
                .onSuccess { refresh() }
                .onFailure { toast(it.message ?: "Cancellazione non consentita") }
        }
        .setNegativeButton("Annulla", null)
        .show()

    private fun textDialog(title: String, text: String) = AlertDialog.Builder(this)
        .setTitle(title)
        .setMessage(text)
        .setPositiveButton("Chiudi", null)
        .setNeutralButton("Condividi") { _, _ -> share(text, title) }
        .show()

    private fun share(text: String, title: String) = startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }, title))

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    private fun lp52() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
    private fun euro(v: Double) = NumberFormat.getCurrencyInstance(Locale.ITALY).format(v)
    private fun qty(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() else String.format(Locale.ITALY, "%.2f", v)
    private fun formatMinutes(minutes: Long) = "%02d:%02d".format(minutes / 60, minutes % 60)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private inner class EstimateAdapter : BaseAdapter() {
        var rows: List<EstimateRecord> = emptyList()
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = rows[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val r = rows[position]
            val f = if (r.jobsiteId.isBlank()) null else finances.jobsiteSummary(r.jobsiteId)
            val minutes = if (r.jobsiteId.isBlank()) 0L else daily.totalMinutesForJobsite(r.jobsiteId)
            val labor = if (r.jobsiteId.isBlank()) 0.0 else daily.laborCostForJobsite(r.jobsiteId)
            val unrated = if (r.jobsiteId.isBlank()) 0L else daily.unratedMinutesForJobsite(r.jobsiteId)
            val standardCount = r.items.count { it.priceListCode.startsWith(StandardWorkCatalog.PREFIX) }
            val bathroomCount = r.items.count { it.priceListCode.startsWith(BathroomWorkCatalog.PREFIX) }
            val guidedCount = r.items.count { GuidedWorkCatalog.isGuidedCode(it.priceListCode) }
            return MaterialCardView(this@EstimatesActivity).apply {
                radius = dp(14).toFloat()
                cardElevation = dp(1).toFloat()
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, dp(4), 0, dp(4))
                }
                addView(LinearLayout(this@EstimatesActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    addView(TextView(this@EstimatesActivity).apply {
                        text = "${r.number} · ${r.clientName.ifBlank { "Senza cliente" }} · ${euro(r.finalTotal)}"
                        setTypeface(typeface, Typeface.BOLD)
                        textSize = 16f
                    })
                    addView(TextView(this@EstimatesActivity).apply {
                        text = "${r.status} · ${r.title.ifBlank { r.workType.ifBlank { "Preventivo" } }}${if (r.jobsiteCode.isBlank()) "" else "\n${r.jobsiteCode} · ${r.jobsiteTitle}"}"
                        textSize = 12f
                        setPadding(0, dp(4), 0, 0)
                    })
                    if (standardCount > 0 || bathroomCount > 0 || guidedCount > 0) addView(TextView(this@EstimatesActivity).apply {
                        text = "Lavorazioni tecniche: ${standardCount + bathroomCount + guidedCount}${if (bathroomCount > 0) " · bagno configurato" else if (guidedCount > 0) " · configurazione guidata" else ""}"
                        textSize = 11f
                        setPadding(0, dp(4), 0, 0)
                    })
                    if (f != null) addView(TextView(this@EstimatesActivity).apply {
                        val margin = r.finalTotal - f.expense - labor
                        text = "Spese ${euro(f.expense)} · Ore ${formatMinutes(minutes)} · Manodopera ${euro(labor)} · Margine ${euro(margin)}" +
                            if (unrated > 0) " · ⚠ ${formatMinutes(unrated)} senza tariffa" else ""
                        textSize = 11f
                        setPadding(0, dp(5), 0, 0)
                    })
                })
            }
        }
    }

    private inner class ItemAdapter(private val rows: List<EstimateItem>) : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = rows[position].id
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val i = rows[position]
            val technicalCode = i.priceListCode.takeIf {
                it.startsWith(StandardWorkCatalog.PREFIX) ||
                    it.startsWith(BathroomWorkCatalog.PREFIX) ||
                    GuidedWorkCatalog.isGuidedCode(it)
            }.orEmpty()
            return LinearLayout(this@EstimatesActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(7), dp(8), dp(7))
                addView(TextView(this@EstimatesActivity).apply {
                    text = "${if (i.optional) "○" else if (i.included) "✓" else "×"} ${i.name} · ${euro(i.total)}"
                    setTypeface(typeface, Typeface.BOLD)
                })
                addView(TextView(this@EstimatesActivity).apply {
                    text = "${i.category}${if (i.room.isBlank()) "" else " · ${i.room}"} · ${qty(i.quantity)} ${i.unit} × ${euro(i.unitPrice)}" +
                        if (technicalCode.isBlank()) "" else " · $technicalCode"
                    textSize = 11f
                })
                if (i.description.isNotBlank()) addView(TextView(this@EstimatesActivity).apply {
                    text = i.description.lineSequence().firstOrNull().orEmpty().take(120)
                    textSize = 10f
                    maxLines = 2
                    setPadding(0, dp(2), 0, 0)
                })
            }
        }
    }

    private class Selected(private val block: () -> Unit) : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = block()
        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
    }

    companion object {
        const val EXTRA_ESTIMATE_ID = "ge360_estimate_id"
        private const val REQ_EXPORT = 601
        private const val REQ_IMPORT = 602
        private const val REQ_EVIDENCE_FILE = 603

        private val WORK_TYPES_UI = listOf(
            "Ristrutturazione bagno",
            "Ristrutturazione intero appartamento",
            "Ristrutturazione cucina",
            "Terrazze e balconi",
            "Pitturazione e rasatura",
            "Pavimenti e battiscopa",
            "Piastrellature e rivestimenti",
            "Massetti e sottofondi",
            "Impermeabilizzazioni",
            "Cartongesso e opere murarie",
            "Demolizioni e ripristini",
            "Lavorazioni personalizzate"
        )

        private val SECTION_VALUES_UI = listOf(
            "Generale",
            "Ingresso",
            "Soggiorno",
            "Cucina",
            "Camera",
            "Bagno",
            "Lavanderia",
            "Disimpegno",
            "Resto della casa",
            "Balcone/Terrazzo",
            "Esterni",
            "Opere extra"
        )
    }
}
