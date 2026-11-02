package it.edilmilan.ge360.attrezzi

import androidx.appcompat.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import it.edilmilan.ge360.cantieri.JobsiteRecord
import it.edilmilan.ge360.cantieri.JobsiteStore
import it.edilmilan.ge360.core.common.ui.buildScreenHeaderWithSubtitle
import java.text.DateFormat
import java.text.DecimalFormat
import java.util.Date

class MaterialCalculatorActivity : AppCompatActivity() {
    private lateinit var calculations: MaterialCalculationStore
    private lateinit var tools: ToolsStore
    private lateinit var jobs: JobsiteStore
    private lateinit var workSpinner: Spinner
    private lateinit var fieldsContainer: LinearLayout
    private lateinit var resultsContainer: LinearLayout
    private lateinit var statusLabel: TextView
    private val inputFields = linkedMapOf<String, EditText>()
    private var currentResults: List<MaterialResult> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        calculations = MaterialCalculationStore(this)
        tools = ToolsStore(this)
        jobs = JobsiteStore(this)
        setContentView(buildScreen())
        renderInputs(MaterialCalculationEngine.specs.first())
    }

    override fun onDestroy() {
        calculations.close()
        tools.close()
        jobs.close()
        super.onDestroy()
    }

    private fun buildScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(18))
            setBackgroundColor(Color.parseColor("#F6F7F9"))
        }
        root.addView(buildScreenHeaderWithSubtitle(
            context = this,
            title = "Calcolo materiali",
            subtitle = "Calcola prima. Collega al cantiere solo quando ti serve.",
            onBack = { finish() }
        ))

        root.addView(infoCard(
            "Archivio lavori",
            "Ogni calcolo può restare indipendente oppure essere collegato a un cantiere. Se lo colleghi, i materiali acquistabili vengono aggiunti alle richieste materiali del cantiere."
        ))

        workSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MaterialCalculatorActivity,
                android.R.layout.simple_spinner_dropdown_item,
                MaterialCalculationEngine.specs.map { it.title }
            )
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    renderInputs(MaterialCalculationEngine.specs[position])
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        root.addView(workSpinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply {
            topMargin = dp(12)
        })

        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)

        fieldsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(fieldsContainer)

        body.addView(MaterialButton(this).apply {
            text = "CALCOLA"
            minHeight = dp(54)
            setOnClickListener { calculate(showToast = true) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)).apply {
            topMargin = dp(10)
        })

        statusLabel = TextView(this).apply {
            text = "Inserisci le misure e premi CALCOLA."
            textSize = 13f
            setTextColor(Color.parseColor("#5F656B"))
            setPadding(dp(4), dp(10), dp(4), dp(8))
        }
        body.addView(statusLabel)

        resultsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(resultsContainer)

        body.addView(MaterialButton(this).apply {
            text = "SALVA CALCOLO SENZA CANTIERE"
            minHeight = dp(52)
            setOnClickListener { saveIndependent() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(12) })

        body.addView(MaterialButton(this).apply {
            text = "COLLEGA A CANTIERE + AGGIUNGI DA ACQUISTARE"
            minHeight = dp(54)
            setOnClickListener { chooseJobsiteAndAttach() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60)).apply { topMargin = dp(8) })

        body.addView(MaterialButton(this).apply {
            text = "STORICO CALCOLI"
            minHeight = dp(50)
            setOnClickListener { showHistory() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply {
            topMargin = dp(8)
            bottomMargin = dp(18)
        })

        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun selectedSpec(): MaterialWorkSpec = MaterialCalculationEngine.specs[workSpinner.selectedItemPosition.coerceAtLeast(0)]

    private fun renderInputs(spec: MaterialWorkSpec) {
        if (!::fieldsContainer.isInitialized || !::resultsContainer.isInitialized || !::statusLabel.isInitialized) return
        inputFields.clear()
        currentResults = emptyList()
        fieldsContainer.removeAllViews()
        resultsContainer.removeAllViews()

        fieldsContainer.addView(TextView(this).apply {
            text = spec.subtitle
            textSize = 14f
            setTextColor(Color.parseColor("#555B61"))
            setPadding(dp(3), dp(10), dp(3), dp(8))
        })

        spec.inputs.forEach { input ->
            fieldsContainer.addView(MaterialCardView(this).apply {
                radius = dp(14).toFloat()
                cardElevation = 0f
                strokeWidth = dp(1)
                strokeColor = Color.parseColor("#E2E4E7")
                setCardBackgroundColor(Color.WHITE)
                setContentPadding(dp(14), dp(9), dp(14), dp(9))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(7)
                }
                addView(LinearLayout(this@MaterialCalculatorActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(TextView(this@MaterialCalculatorActivity).apply {
                        text = input.label
                        textSize = 14f
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(Color.parseColor("#202327"))
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    val field = EditText(this@MaterialCalculatorActivity).apply {
                        setText(formatInput(input.defaultValue))
                        gravity = Gravity.END
                        isSingleLine = true
                        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                        hint = input.suffix
                    }
                    inputFields[input.key] = field
                    addView(field, LinearLayout.LayoutParams(dp(126), dp(50)))
                    if (input.suffix.isNotBlank()) {
                        addView(TextView(this@MaterialCalculatorActivity).apply {
                            text = input.suffix
                            textSize = 12f
                            setTextColor(Color.parseColor("#71767C"))
                            setPadding(dp(5), 0, 0, 0)
                        })
                    }
                })
            })
        }
        statusLabel.text = "Inserisci le misure e premi CALCOLA."
    }

    private fun readValues(): Map<String, Double>? {
        val out = linkedMapOf<String, Double>()
        selectedSpec().inputs.forEach { input ->
            val field = inputFields[input.key] ?: return@forEach
            val text = field.text.toString().trim().replace(',', '.')
            val value = text.toDoubleOrNull()
            if (value == null || value < 0.0) {
                field.error = "Valore non valido"
                return null
            }
            out[input.key] = value
        }
        return out
    }

    private fun calculate(showToast: Boolean): Pair<Map<String, Double>, List<MaterialResult>>? {
        val values = readValues() ?: return null
        val spec = selectedSpec()
        val results = runCatching { spec.calculate(values) }.getOrElse { error ->
            toast("Calcolo non riuscito: ${error.message}")
            return null
        }
        currentResults = results
        renderResults(results)
        statusLabel.text = "${spec.title} · ${results.count { it.purchase }} materiali acquistabili"
        if (showToast) toast("Calcolo aggiornato")
        return values to results
    }

    private fun renderResults(results: List<MaterialResult>) {
        resultsContainer.removeAllViews()
        resultsContainer.addView(TextView(this).apply {
            text = "RISULTATO"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#777C82"))
            setPadding(dp(2), dp(10), 0, dp(7))
        })
        results.forEach { result ->
            resultsContainer.addView(MaterialCardView(this).apply {
                radius = dp(15).toFloat()
                cardElevation = 0f
                strokeWidth = dp(1)
                strokeColor = Color.parseColor("#E2E4E7")
                setCardBackgroundColor(if (result.purchase) Color.WHITE else Color.parseColor("#F1F3F5"))
                setContentPadding(dp(14), dp(11), dp(14), dp(11))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(7) }
                addView(LinearLayout(this@MaterialCalculatorActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(this@MaterialCalculatorActivity).apply {
                        text = result.name
                        textSize = 16f
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(Color.parseColor("#202327"))
                    })
                    addView(TextView(this@MaterialCalculatorActivity).apply {
                        text = "${formatQuantity(result.quantity)} ${result.unit}${if (!result.purchase) " · dato tecnico" else ""}"
                        textSize = 15f
                        setTextColor(Color.parseColor(if (result.purchase) "#B85700" else "#646A70"))
                        setPadding(0, dp(3), 0, 0)
                    })
                    if (result.note.isNotBlank()) addView(TextView(this@MaterialCalculatorActivity).apply {
                        text = result.note
                        textSize = 11f
                        setTextColor(Color.parseColor("#74797F"))
                        setPadding(0, dp(3), 0, 0)
                    })
                })
            })
        }
    }

    private fun saveIndependent() {
        val calculated = calculate(showToast = false) ?: return
        saveCalculation(calculated.first, calculated.second, null)
        toast("Calcolo salvato nello storico")
    }

    private fun chooseJobsiteAndAttach() {
        val calculated = calculate(showToast = false) ?: return
        val available = jobs.list().filter { it.status !in setOf(JobsiteStore.STATUS_ARCHIVED, JobsiteStore.STATUS_COMPLETED) }
        if (available.isEmpty()) {
            toast("Non ci sono cantieri attivi o programmati")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Collega a un cantiere")
            .setItems(available.map { "${it.code} · ${it.displayTitle()}" }.toTypedArray()) { _, which ->
                attachToJobsite(calculated.first, calculated.second, available[which])
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun attachToJobsite(values: Map<String, Double>, results: List<MaterialResult>, job: JobsiteRecord) {
        saveCalculation(values, results, job)
        var added = 0
        results.filter { it.purchase && it.quantity > 0.0 }.forEach { result ->
            tools.saveRequest(ResourceRequest(
                jobsiteId = job.ge360Id,
                jobsiteCode = job.code,
                jobsiteTitle = job.displayTitle(),
                kind = ResourceKind.MATERIAL,
                name = result.name,
                quantity = result.quantity,
                unit = result.unit,
                note = "Da Calcolo materiali · ${selectedSpec().title}${result.note.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}",
                requestedBy = "Calcolo materiali"
            ))
            added++
        }
        statusLabel.text = "Collegato a ${job.displayTitle()} · $added materiali aggiunti da acquistare"
        toast("$added materiali aggiunti al cantiere")
    }

    private fun saveCalculation(values: Map<String, Double>, results: List<MaterialResult>, job: JobsiteRecord?) {
        val spec = selectedSpec()
        calculations.save(MaterialCalculationRecord(
            workType = spec.id,
            title = spec.title,
            inputsJson = MaterialCalculationEngine.inputsToJson(values),
            resultsJson = MaterialCalculationEngine.resultsToJson(results),
            jobsiteId = job?.ge360Id.orEmpty(),
            jobsiteCode = job?.code.orEmpty(),
            jobsiteTitle = job?.displayTitle().orEmpty()
        ))
    }

    private fun showHistory() {
        val rows = calculations.list(50)
        if (rows.isEmpty()) {
            toast("Nessun calcolo salvato")
            return
        }
        val labels = rows.map { row ->
            val job = row.jobsiteTitle.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
            "${row.title}$job\n${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(row.createdAt))}"
        }
        AlertDialog.Builder(this)
            .setTitle("Storico calcoli")
            .setItems(labels.toTypedArray()) { _, which -> showHistoryDetails(rows[which]) }
            .setPositiveButton("Chiudi", null)
            .show()
    }

    private fun showHistoryDetails(row: MaterialCalculationRecord) {
        val results = MaterialCalculationEngine.resultsFromJson(row.resultsJson)
        val text = buildString {
            append(row.title)
            if (row.jobsiteTitle.isNotBlank()) append("\nCantiere: ${row.jobsiteCode} · ${row.jobsiteTitle}")
            append("\n\n")
            results.forEach { append("• ${it.name}: ${formatQuantity(it.quantity)} ${it.unit}\n") }
        }
        AlertDialog.Builder(this)
            .setTitle("Calcolo salvato")
            .setMessage(text)
            .setPositiveButton("Chiudi", null)
            .show()
    }

    private fun infoCard(title: String, subtitle: String): View = MaterialCardView(this).apply {
        radius = dp(16).toFloat()
        cardElevation = 0f
        strokeWidth = dp(1)
        strokeColor = Color.parseColor("#E2E4E7")
        setCardBackgroundColor(Color.WHITE)
        setContentPadding(dp(14), dp(12), dp(14), dp(12))
        addView(LinearLayout(this@MaterialCalculatorActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MaterialCalculatorActivity).apply {
                text = title
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor("#202327"))
            })
            addView(TextView(this@MaterialCalculatorActivity).apply {
                text = subtitle
                textSize = 12f
                setTextColor(Color.parseColor("#666C72"))
                setPadding(0, dp(4), 0, 0)
            })
        })
    }

    private fun formatInput(value: Double): String = if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
    private fun formatQuantity(value: Double): String = DecimalFormat("0.##").format(value)
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
