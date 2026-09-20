package it.edilmilan.ge360.giornata

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CalendarView
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
import it.edilmilan.ge360.core.common.Ge360Contracts
import it.edilmilan.ge360.core.common.ui.Ge360Brand
import it.edilmilan.ge360.core.common.ui.applyGe360Card
import it.edilmilan.ge360.core.common.ui.buildScreenHeaderWithSubtitle
import java.text.NumberFormat
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class JobsiteLaborActivity : AppCompatActivity() {
    private lateinit var dailyStore: DailyStore
    private lateinit var jobsiteStore: JobsiteStore
    private lateinit var jobsite: JobsiteRecord
    private var people: List<PersonnelRecord> = emptyList()
    private var selectedDate: LocalDate = LocalDate.now()

    private lateinit var personnelSpinner: Spinner
    private lateinit var calendarView: CalendarView
    private lateinit var rateInput: EditText
    private lateinit var hoursInput: EditText
    private lateinit var noteInput: EditText
    private lateinit var dateLabel: TextView
    private lateinit var summaryBox: LinearLayout
    private lateinit var historyBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dailyStore = DailyStore(this)
        jobsiteStore = JobsiteStore(this)
        dailyStore.ensurePersonnelCatalog()
        jobsite = resolveJobsite() ?: run {
            Toast.makeText(this, "Cantiere non trovato", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        people = dailyStore.personnel(activeOnly = true)
        setContentView(buildScreen())
        refreshPersonRate()
        refreshSummaryAndHistory()
    }

    override fun onResume() {
        super.onResume()
        if (::summaryBox.isInitialized) refreshSummaryAndHistory()
    }

    override fun onDestroy() {
        dailyStore.close()
        jobsiteStore.close()
        super.onDestroy()
    }

    private fun buildScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(14))
            setBackgroundColor(Ge360Brand.Background)
        }
        root.addView(buildScreenHeaderWithSubtitle(
            context = this,
            title = "Ore / manodopera",
            subtitle = "${jobsite.code} · ${jobsite.displayTitle()}",
            onBack = { finish() }
        ))

        val scroll = ScrollView(this).apply { clipToPadding = false }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(20))
        }
        scroll.addView(body)

        body.addView(infoCard(
            "Costo gestionale del cantiere",
            "Registra anche a ritroso chi ha lavorato. Queste giornate entrano nelle ore e nel costo manodopera del cantiere, ma non creano un pagamento in Spese."
        ))

        body.addView(MaterialButton(this).apply {
            text = "⏱  TIMER ORE REALI / RAPPORTINO"
            setOnClickListener { openDailyTimer() }
        }, full(dp(52)).apply { topMargin = dp(8) })

        summaryBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(summaryBox)

        body.addView(sectionTitle("REGISTRA GIORNO", "Scegli l'operatore e una data anche nel passato. 8 ore = una giornata intera."))

        if (people.isEmpty()) {
            body.addView(infoCard("Nessun operatore", "Aggiungi prima un collaboratore nel reparto Collaboratori."))
        }

        personnelSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@JobsiteLaborActivity,
                android.R.layout.simple_spinner_dropdown_item,
                people.map { "${it.name}${if (it.role.isBlank()) "" else " · ${it.role}"}" }.ifEmpty { listOf("Nessun operatore") }
            )
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    refreshPersonRate()
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            }
        }
        body.addView(personnelSpinner, full(dp(54)))

        calendarView = CalendarView(this).apply {
            firstDayOfWeek = java.util.Calendar.MONDAY
            maxDate = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1
            minDate = LocalDate.of(2020, 1, 1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            date = selectedDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            setOnDateChangeListener { _, year, month, dayOfMonth ->
                selectedDate = LocalDate.of(year, month + 1, dayOfMonth)
                dateLabel.text = selectedDate.format(DATE_FORMAT)
                loadExistingForSelectedDay()
            }
        }
        body.addView(calendarView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })

        dateLabel = TextView(this).apply {
            text = selectedDate.format(DATE_FORMAT)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ge360Brand.ForestDark)
            setPadding(dp(4), dp(8), dp(4), dp(6))
        }
        body.addView(dateLabel)

        val rateRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rateInput = numberField("Costo giornata €")
        hoursInput = numberField("Ore lavorate").apply { setText("8") }
        rateRow.addView(labeledField("Tariffa giornaliera", rateInput), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(5) })
        rateRow.addView(labeledField("Ore", hoursInput), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(5) })
        body.addView(rateRow)

        body.addView(TextView(this).apply {
            text = "Il costo è proporzionale a una giornata standard di 8 ore. Slobodan parte da 92 €/giorno: 8h = 92 €, 4h = 46 €. La tariffa resta congelata nello storico."
            textSize = 11f
            setTextColor(Ge360Brand.TextSecondary)
            setPadding(dp(4), dp(3), dp(4), dp(8))
        })

        noteInput = EditText(this).apply {
            hint = "Nota facoltativa (es. demolizioni, posa, mezza giornata…)"
            minLines = 2
            maxLines = 4
            gravity = Gravity.TOP
        }
        body.addView(noteInput, full(dp(82)))

        body.addView(MaterialButton(this).apply {
            text = "REGISTRA / AGGIORNA GIORNO"
            isEnabled = people.isNotEmpty()
            setOnClickListener { saveSelectedDay() }
        }, full(dp(58)).apply { topMargin = dp(8) })

        body.addView(sectionTitle("STORICO CANTIERE", "Tocca una riga per modificarla. Tieni premuto per eliminarla."))
        historyBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(historyBox)

        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun refreshPersonRate() {
        val person = selectedPerson() ?: return
        if (!::rateInput.isInitialized) return
        val existing = dailyStore.laborDayEntry(person, jobsite.ge360Id, selectedDate)
        rateInput.setText(decimal(existing?.dailyRateSnapshot() ?: dailyStore.defaultDailyRateFor(person)))
        if (::hoursInput.isInitialized) hoursInput.setText(decimal(existing?.durationMinutes()?.toDouble()?.div(60.0) ?: 8.0))
        if (::noteInput.isInitialized) noteInput.setText(existing?.laborDayUserNote().orEmpty())
    }

    private fun loadExistingForSelectedDay() {
        val person = selectedPerson() ?: return
        val existing = dailyStore.laborDayEntry(person, jobsite.ge360Id, selectedDate)
        if (existing != null) {
            rateInput.setText(decimal(existing.dailyRateSnapshot()))
            hoursInput.setText(decimal(existing.durationMinutes().toDouble() / 60.0))
            noteInput.setText(existing.laborDayUserNote())
        } else {
            rateInput.setText(decimal(dailyStore.defaultDailyRateFor(person)))
            hoursInput.setText("8")
            noteInput.setText("")
        }
    }

    private fun saveSelectedDay() {
        val person = selectedPerson() ?: return toast("Aggiungi prima un operatore")
        val dailyRate = parseNumber(rateInput.text.toString())
        val hours = parseNumber(hoursInput.text.toString())
        if (dailyRate <= 0.0) {
            rateInput.error = "Inserisci il costo giornaliero"
            return
        }
        if (hours <= 0.0 || hours > 24.0) {
            hoursInput.error = "Ore non valide"
            return
        }
        val saved = runCatching {
            dailyStore.saveLaborDayEntry(
                jobsite = jobsite,
                personnel = person,
                workDate = selectedDate,
                hours = hours,
                dailyRate = dailyRate,
                userNote = noteInput.text.toString()
            )
        }.getOrElse { error ->
            toast(error.message ?: "Impossibile registrare la giornata")
            return
        }
        toast("${saved.collaborator} · ${selectedDate.format(DATE_FORMAT)} · ${money(saved.laborCost())}")
        refreshSummaryAndHistory()
    }

    private fun refreshSummaryAndHistory() {
        if (!::summaryBox.isInitialized || !::historyBox.isInitialized) return
        val rows = dailyStore.laborDayEntriesForJobsite(jobsite.ge360Id)
        val calendarHours = rows.sumOf { it.durationMinutes() }.toDouble() / 60.0
        val equivalentDays = calendarHours / 8.0
        val calendarCost = rows.sumOf { it.laborCost() }
        val totalLaborCost = dailyStore.laborCostForJobsite(jobsite.ge360Id)
        val totalMinutes = dailyStore.totalMinutesForJobsite(jobsite.ge360Id)

        summaryBox.removeAllViews()
        summaryBox.addView(MaterialCardView(this).apply {
            applyGe360Card(fill = Ge360Brand.SurfaceWarm, stroke = Ge360Brand.BorderStrong, radiusDp = 18)
            setContentPadding(dp(14), dp(13), dp(14), dp(13))
            addView(LinearLayout(this@JobsiteLaborActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@JobsiteLaborActivity).apply {
                    text = "MANODOPERA CANTIERE"
                    textSize = 10.5f
                    letterSpacing = 0.06f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Ge360Brand.PrimaryDark)
                })
                addView(metricRow(
                    "ORE TOTALI", formatMinutes(totalMinutes),
                    "GG CALEND.", decimal(equivalentDays),
                    "COSTO TOTALE", money(totalLaborCost)
                ))
                if (rows.isNotEmpty()) addView(TextView(this@JobsiteLaborActivity).apply {
                    text = "Da calendario: ${decimal(calendarHours)} h · ${money(calendarCost)}"
                    textSize = 11f
                    setTextColor(Ge360Brand.TextSecondary)
                    setPadding(0, dp(7), 0, 0)
                })
                dailyStore.laborBreakdownForJobsite(jobsite.ge360Id).forEach { item ->
                    addView(TextView(this@JobsiteLaborActivity).apply {
                        text = "${item.collaborator} · ${formatMinutes(item.minutes)} · ${money(item.cost)}"
                        textSize = 12f
                        setTextColor(Ge360Brand.TextSecondary)
                        setPadding(0, dp(4), 0, 0)
                    })
                }
            })
        })

        historyBox.removeAllViews()
        if (rows.isEmpty()) {
            historyBox.addView(infoCard("Ancora nessuna giornata retroattiva", "Scegli operatore e data dal calendario, poi registra la presenza."))
            return
        }
        rows.forEach { row ->
            historyBox.addView(MaterialCardView(this).apply {
                applyGe360Card(fill = Ge360Brand.Surface, stroke = Ge360Brand.Border, radiusDp = 15)
                setContentPadding(dp(13), dp(11), dp(13), dp(11))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(7) }
                isClickable = true
                isFocusable = true
                setOnClickListener { loadEntry(row) }
                setOnLongClickListener {
                    AlertDialog.Builder(this@JobsiteLaborActivity)
                        .setTitle("Eliminare la giornata?")
                        .setMessage("${row.collaborator} · ${formatDate(row.workDate)} · ${money(row.laborCost())}")
                        .setPositiveButton("Elimina") { _, _ ->
                            dailyStore.deleteLaborDayEntry(row.id)
                            refreshSummaryAndHistory()
                        }
                        .setNegativeButton("Annulla", null)
                        .show()
                    true
                }
                addView(LinearLayout(this@JobsiteLaborActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(this@JobsiteLaborActivity).apply {
                        text = "${formatDate(row.workDate)} · ${row.collaborator}"
                        textSize = 14.5f
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(Ge360Brand.ForestDark)
                    })
                    addView(TextView(this@JobsiteLaborActivity).apply {
                        text = "${decimal(row.durationMinutes().toDouble() / 60.0)} h · ${money(row.laborCost())} · tariffa ${money(row.dailyRateSnapshot())}/giorno"
                        textSize = 12f
                        setTextColor(Ge360Brand.PrimaryDark)
                        setPadding(0, dp(3), 0, 0)
                    })
                    row.laborDayUserNote().takeIf { it.isNotBlank() }?.let { cleanNote ->
                        addView(TextView(this@JobsiteLaborActivity).apply {
                            text = cleanNote
                            textSize = 11f
                            setTextColor(Ge360Brand.TextSecondary)
                            setPadding(0, dp(3), 0, 0)
                        })
                    }
                })
            })
        }
    }

    private fun loadEntry(row: WorkSession) {
        selectedDate = runCatching { LocalDate.parse(row.workDate) }.getOrDefault(LocalDate.now())
        val index = people.indexOfFirst { it.ge360Id == row.personnelId || it.name.equals(row.collaborator, ignoreCase = true) }
        if (index >= 0) personnelSpinner.setSelection(index)
        rateInput.setText(decimal(row.dailyRateSnapshot()))
        hoursInput.setText(decimal(row.durationMinutes().toDouble() / 60.0))
        noteInput.setText(row.laborDayUserNote())
        dateLabel.text = selectedDate.format(DATE_FORMAT)
        calendarView.date = selectedDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    private fun openDailyTimer() {
        startActivity(Intent(this, DailyWorkActivity::class.java).apply {
            putExtra(Ge360Contracts.EXTRA_JOBSITE_ID, jobsite.ge360Id)
            putExtra(Ge360Contracts.EXTRA_JOBSITE_LOCAL_ID, jobsite.id)
            putExtra(Ge360Contracts.EXTRA_JOBSITE_CODE, jobsite.code)
        })
    }

    private fun selectedPerson(): PersonnelRecord? = people.getOrNull(personnelSpinner.selectedItemPosition)

    private fun resolveJobsite(): JobsiteRecord? {
        val uuid = intent.getStringExtra(Ge360Contracts.EXTRA_JOBSITE_ID).orEmpty()
        val localId = intent.getLongExtra(Ge360Contracts.EXTRA_JOBSITE_LOCAL_ID, 0L)
        return jobsiteStore.list().firstOrNull {
            (uuid.isNotBlank() && it.ge360Id == uuid) || (localId > 0L && it.id == localId)
        }
    }

    private fun infoCard(title: String, text: String): View = MaterialCardView(this).apply {
        applyGe360Card(fill = Ge360Brand.SurfaceWarm, stroke = Ge360Brand.Border, radiusDp = 16)
        setContentPadding(dp(13), dp(11), dp(13), dp(11))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        addView(LinearLayout(this@JobsiteLaborActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@JobsiteLaborActivity).apply {
                this.text = title
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Ge360Brand.ForestDark)
            })
            addView(TextView(this@JobsiteLaborActivity).apply {
                this.text = text
                textSize = 11.5f
                setTextColor(Ge360Brand.TextSecondary)
                setPadding(0, dp(3), 0, 0)
            })
        })
    }

    private fun sectionTitle(title: String, subtitle: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(3), dp(18), dp(3), dp(8))
        addView(TextView(this@JobsiteLaborActivity).apply {
            text = title
            textSize = 11f
            letterSpacing = 0.06f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ge360Brand.PrimaryDark)
        })
        addView(TextView(this@JobsiteLaborActivity).apply {
            text = subtitle
            textSize = 11f
            setTextColor(Ge360Brand.TextSecondary)
            setPadding(0, dp(2), 0, 0)
        })
    }

    private fun numberField(hintText: String) = EditText(this).apply {
        hint = hintText
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
    }

    private fun labeledField(label: String, field: EditText): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(this@JobsiteLaborActivity).apply {
            text = label
            textSize = 10.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ge360Brand.TextSecondary)
            setPadding(dp(3), dp(5), dp(3), dp(2))
        })
        addView(field, full(dp(52)))
    }

    private fun metricRow(l1: String, v1: String, l2: String, v2: String, l3: String, v3: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(10), 0, 0)
        addView(metric(l1, v1), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(metric(l2, v2), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(metric(l3, v3), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun metric(label: String, value: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(this@JobsiteLaborActivity).apply {
            text = label
            textSize = 8.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ge360Brand.TextSecondary)
        })
        addView(TextView(this@JobsiteLaborActivity).apply {
            text = value
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ge360Brand.ForestDark)
            setPadding(0, dp(2), 0, 0)
        })
    }

    private fun full(height: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
    private fun parseNumber(value: String): Double = value.trim().replace(',', '.').toDoubleOrNull() ?: 0.0
    private fun decimal(value: Double): String = if (kotlin.math.abs(value - value.toInt()) < 0.0001) value.toInt().toString() else String.format(Locale.ITALY, "%.2f", value)
    private fun money(value: Double): String = NumberFormat.getCurrencyInstance(Locale.ITALY).format(value)
    private fun formatMinutes(minutes: Long): String {
        val h = minutes / 60
        val m = minutes % 60
        return if (m == 0L) "$h h" else "$h h ${m}m"
    }
    private fun formatDate(value: String): String = runCatching { LocalDate.parse(value).format(DATE_FORMAT) }.getOrDefault(value)
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ITALY)
    }
}
