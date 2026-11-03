package it.edilmilan.ge360.giornata

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
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
import it.edilmilan.ge360.core.common.ui.Ge360Brand
import it.edilmilan.ge360.core.common.ui.buildScreenHeaderWithSubtitle
import it.edilmilan.ge360.spese.ExpensesActivity
import it.edilmilan.ge360.spese.FinanceRecord
import it.edilmilan.ge360.spese.FinanceScope
import it.edilmilan.ge360.spese.FinanceStore
import it.edilmilan.ge360.spese.FinanceType
import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

class ExternalCollaboratorsActivity : AppCompatActivity() {
    private lateinit var dailyStore: DailyStore
    private lateinit var financeStore: FinanceStore
    private lateinit var jobsiteStore: JobsiteStore
    private lateinit var body: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dailyStore = DailyStore(this)
        financeStore = FinanceStore(this)
        jobsiteStore = JobsiteStore(this)
        setContentView(buildScreen())
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::body.isInitialized) render()
    }

    override fun onDestroy() {
        dailyStore.close()
        financeStore.close()
        jobsiteStore.close()
        super.onDestroy()
    }

    private fun buildScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ge360Brand.Background)
            setPadding(dp(14), dp(10), dp(14), dp(18))
        }
        root.addView(
            buildScreenHeaderWithSubtitle(
                context = this,
                title = "Collaboratori esterni",
                subtitle = "Idraulici · elettricisti · artigiani · subappalti",
                onBack = { finish() }
            )
        )
        val scroll = ScrollView(this).apply { isFillViewport = true }
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun render() {
        body.removeAllViews()
        val people = externalPeople()
        val payments = ExternalCollaboratorLedger.allPayments(financeStore)
        val currentMonth = YearMonth.now().toString()
        val monthPaid = payments.filter { it.date.startsWith(currentMonth) }.sumOf { it.amount }

        body.addView(summaryCard(people.count { it.active }, payments.size, monthPaid))
        body.addView(MaterialButton(this).apply {
            text = "+ NUOVO COLLABORATORE ESTERNO"
            minHeight = dp(54)
            setOnClickListener { showPersonDialog(null) }
        }, full(dp(58)).apply { topMargin = dp(12); bottomMargin = dp(12) })

        body.addView(sectionTitle("COLLABORATORI", "Anagrafica separata dai collaboratori interni."))
        if (people.isEmpty()) {
            body.addView(emptyText("Nessun collaboratore esterno. Aggiungi, per esempio, idraulico, elettricista o trasportatore."))
        } else {
            people.forEach { person -> body.addView(personCard(person)) }
        }

        body.addView(sectionTitle("ULTIMI PAGAMENTI", "Ogni pagamento entra anche nelle Spese aziendali e può essere collegato a un cantiere."))
        if (payments.isEmpty()) {
            body.addView(emptyText("Nessun pagamento a collaboratori esterni registrato."))
        } else {
            payments.take(12).forEach { body.addView(paymentCard(it)) }
        }
        body.addView(MaterialButton(this).apply {
            text = "VEDI TUTTE LE SPESE"
            setOnClickListener { startActivity(Intent(this@ExternalCollaboratorsActivity, ExpensesActivity::class.java)) }
        }, full(dp(50)).apply { topMargin = dp(7); bottomMargin = dp(18) })
    }

    private fun summaryCard(activeCount: Int, paymentsCount: Int, monthPaid: Double): View = MaterialCardView(this).apply {
        radius = dp(20).toFloat()
        cardElevation = 0f
        strokeWidth = dp(1)
        strokeColor = Ge360Brand.Accent
        setCardBackgroundColor(Ge360Brand.Surface)
        addView(LinearLayout(this@ExternalCollaboratorsActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(15))
            addView(TextView(this@ExternalCollaboratorsActivity).apply {
                text = "Squadra esterna"
                textSize = 22f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Ge360Brand.Forest)
            })
            addView(TextView(this@ExternalCollaboratorsActivity).apply {
                text = "$activeCount attivi · $paymentsCount pagamenti registrati"
                textSize = 12f
                setTextColor(Ge360Brand.TextSecondary)
                setPadding(0, dp(4), 0, 0)
            })
            addView(TextView(this@ExternalCollaboratorsActivity).apply {
                text = "Pagato questo mese: ${money(monthPaid)}"
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Ge360Brand.Accent)
                setPadding(0, dp(10), 0, 0)
            })
        })
    }

    private fun personCard(person: PersonnelRecord): View {
        val payments = ExternalCollaboratorLedger.paymentsFor(financeStore, person)
        val last = payments.firstOrNull()
        return MaterialCardView(this).apply {
            radius = dp(18).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = if (person.active) Ge360Brand.Border else Ge360Brand.Border
            setCardBackgroundColor(Ge360Brand.Surface)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(9)
            }
            addView(LinearLayout(this@ExternalCollaboratorsActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(15), dp(14), dp(15), dp(14))

                addView(LinearLayout(this@ExternalCollaboratorsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(TextView(this@ExternalCollaboratorsActivity).apply {
                        text = person.name
                        textSize = 19f
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(Ge360Brand.Forest)
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(TextView(this@ExternalCollaboratorsActivity).apply {
                        text = if (person.active) "ATTIVO" else "NON ATTIVO"
                        textSize = 10f
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(if (person.active) Ge360Brand.Success else Ge360Brand.TextSecondary)
                    })
                })

                addView(TextView(this@ExternalCollaboratorsActivity).apply {
                    text = ExternalCollaboratorLedger.trade(person)
                    textSize = 13f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Ge360Brand.Accent)
                    setPadding(0, dp(4), 0, 0)
                })

                if (person.phone.isNotBlank()) {
                    addView(TextView(this@ExternalCollaboratorsActivity).apply {
                        text = "☎ ${person.phone}"
                        textSize = 12f
                        setTextColor(Ge360Brand.TextSecondary)
                        setPadding(0, dp(5), 0, 0)
                        isClickable = true
                        setOnClickListener {
                            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${person.phone}")))
                        }
                    })
                }
                if (person.note.isNotBlank()) {
                    addView(TextView(this@ExternalCollaboratorsActivity).apply {
                        text = person.note
                        textSize = 11.5f
                        setTextColor(Ge360Brand.TextSecondary)
                        setPadding(0, dp(5), 0, 0)
                        maxLines = 3
                    })
                }
                if (last != null) {
                    addView(TextView(this@ExternalCollaboratorsActivity).apply {
                        text = "Ultimo pagamento: ${money(last.amount)} · ${formatDate(last.date)}"
                        textSize = 11.5f
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(Ge360Brand.Forest)
                        setPadding(0, dp(7), 0, 0)
                    })
                }

                val actions = LinearLayout(this@ExternalCollaboratorsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                actions.addView(MaterialButton(this@ExternalCollaboratorsActivity).apply {
                    text = "REGISTRA PAGAMENTO"
                    isEnabled = person.active
                    setOnClickListener { showPaymentDialog(person) }
                }, LinearLayout.LayoutParams(0, dp(50), 1.45f).apply { marginEnd = dp(5) })
                actions.addView(MaterialButton(this@ExternalCollaboratorsActivity).apply {
                    text = "MODIFICA"
                    setOnClickListener { showPersonDialog(person) }
                }, LinearLayout.LayoutParams(0, dp(50), 0.9f).apply { marginStart = dp(5) })
                addView(actions, full(dp(54)).apply { topMargin = dp(10) })
            })
        }
    }

    private fun paymentCard(record: FinanceRecord): View = MaterialCardView(this).apply {
        radius = dp(14).toFloat()
        cardElevation = 0f
        strokeWidth = dp(1)
        strokeColor = Ge360Brand.Border
        setCardBackgroundColor(Ge360Brand.Surface)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(7)
        }
        addView(LinearLayout(this@ExternalCollaboratorsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(13), dp(11), dp(13), dp(11))
            addView(LinearLayout(this@ExternalCollaboratorsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@ExternalCollaboratorsActivity).apply {
                    text = ExternalCollaboratorLedger.personNameFrom(record)
                    textSize = 13f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Ge360Brand.Forest)
                })
                addView(TextView(this@ExternalCollaboratorsActivity).apply {
                    text = buildString {
                        append(formatDate(record.date))
                        if (record.jobsiteTitle.isNotBlank()) append(" · ${record.jobsiteTitle}")
                    }
                    textSize = 10.5f
                    setTextColor(Ge360Brand.TextSecondary)
                    maxLines = 2
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@ExternalCollaboratorsActivity).apply {
                text = money(record.amount)
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Ge360Brand.Accent)
            })
        })
    }

    private fun showPersonDialog(existing: PersonnelRecord?) {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
        }
        val nameInput = EditText(this).apply {
            hint = "Nome / azienda"
            isSingleLine = true
            setText(existing?.name.orEmpty())
        }
        val tradeInput = EditText(this).apply {
            hint = "Mestiere · es. Idraulico"
            isSingleLine = true
            setText(existing?.let { ExternalCollaboratorLedger.trade(it) }.orEmpty())
        }
        val phoneInput = EditText(this).apply {
            hint = "Telefono"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_PHONE
            setText(existing?.phone.orEmpty())
        }
        val noteInput = EditText(this).apply {
            hint = "Note · P.IVA, condizioni, riferimenti…"
            minLines = 3
            setText(existing?.note.orEmpty())
        }
        val activeCheck = CheckBox(this).apply {
            text = "Collaboratore attivo"
            isChecked = existing?.active ?: true
        }
        form.addView(nameInput, full(dp(54)))
        form.addView(tradeInput, full(dp(54)))
        form.addView(phoneInput, full(dp(54)))
        form.addView(noteInput, full(dp(88)))
        form.addView(activeCheck)

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Nuovo collaboratore esterno" else "Modifica collaboratore esterno")
            .setView(form)
            .setPositiveButton("SALVA", null)
            .setNegativeButton("Annulla", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text.toString().trim()
                val trade = tradeInput.text.toString().trim()
                if (name.isBlank()) {
                    nameInput.error = "Nome obbligatorio"
                    return@setOnClickListener
                }
                if (trade.isBlank()) {
                    tradeInput.error = "Indica il mestiere"
                    return@setOnClickListener
                }
                val record = existing ?: PersonnelRecord()
                record.name = name
                record.role = ExternalCollaboratorLedger.roleFor(trade)
                record.phone = phoneInput.text.toString().trim()
                record.note = noteInput.text.toString().trim()
                record.hourlyRate = 0.0
                record.active = activeCheck.isChecked
                runCatching { dailyStore.savePersonnel(record) }
                    .onSuccess {
                        dialog.dismiss()
                        Toast.makeText(this, "Collaboratore esterno salvato", Toast.LENGTH_SHORT).show()
                        render()
                    }
                    .onFailure {
                        nameInput.error = "Nome già presente o non valido"
                    }
            }
        }
        dialog.show()
    }

    private fun showPaymentDialog(person: PersonnelRecord) {
        val jobs = jobsiteStore.list().filter { it.status != JobsiteStore.STATUS_ARCHIVED }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
        }
        val amountInput = EditText(this).apply {
            hint = "Importo €"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val jobSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@ExternalCollaboratorsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Nessun cantiere") + jobs.map { "${it.code} · ${it.displayTitle()}" }
            )
        }
        val noteInput = EditText(this).apply {
            hint = "Nota · es. acconto impianto bagno"
            minLines = 2
        }
        form.addView(amountInput, full(dp(56)))
        form.addView(jobSpinner, full(dp(54)))
        form.addView(noteInput, full(dp(76)))

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Registra pagamento")
            .setMessage("${person.name} · ${ExternalCollaboratorLedger.trade(person)}")
            .setView(form)
            .setPositiveButton("SALVA", null)
            .setNegativeButton("Annulla", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val amount = parseMoney(amountInput.text.toString())
                if (amount <= 0.0) {
                    amountInput.error = "Inserisci un importo valido"
                    return@setOnClickListener
                }
                val job = selectedJob(jobSpinner, jobs)
                ExternalCollaboratorLedger.savePayment(
                    financeStore = financeStore,
                    person = person,
                    amount = amount,
                    job = job,
                    note = noteInput.text.toString(),
                    date = LocalDate.now()
                )
                dialog.dismiss()
                Toast.makeText(this, "Pagamento registrato · ${money(amount)}", Toast.LENGTH_LONG).show()
                render()
            }
        }
        dialog.show()
    }

    private fun selectedJob(spinner: Spinner, jobs: List<JobsiteRecord>): JobsiteRecord? =
        if (spinner.selectedItemPosition <= 0) null else jobs.getOrNull(spinner.selectedItemPosition - 1)

    private fun externalPeople(): List<PersonnelRecord> = dailyStore.personnel(false)
        .filter { ExternalCollaboratorLedger.isExternal(it) }
        .sortedWith(compareByDescending<PersonnelRecord> { it.active }.thenBy { it.name.lowercase(Locale.ITALY) })

    private fun sectionTitle(title: String, subtitle: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(3), dp(20), dp(3), dp(8))
        addView(TextView(this@ExternalCollaboratorsActivity).apply {
            text = title
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ge360Brand.Forest)
        })
        addView(TextView(this@ExternalCollaboratorsActivity).apply {
            text = subtitle
            textSize = 11f
            setTextColor(Ge360Brand.TextSecondary)
            setPadding(0, dp(2), 0, 0)
        })
    }

    private fun emptyText(value: String) = TextView(this).apply {
        text = value
        textSize = 12f
        setTextColor(Ge360Brand.TextSecondary)
        setPadding(dp(4), dp(10), dp(4), dp(10))
    }

    private fun parseMoney(value: String): Double {
        val raw = value.trim().replace("€", "").replace(" ", "")
        if (raw.isBlank()) return 0.0
        return if (raw.contains(',') && raw.contains('.')) {
            raw.replace(".", "").replace(',', '.').toDoubleOrNull() ?: 0.0
        } else {
            raw.replace(',', '.').toDoubleOrNull() ?: 0.0
        }
    }

    private fun money(value: Double): String = NumberFormat.getCurrencyInstance(Locale.ITALY).format(value)

    private fun formatDate(value: String): String = runCatching {
        LocalDate.parse(value).format(DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ITALY))
    }.getOrDefault(value)

    private fun full(height: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

object ExternalCollaboratorLedger {
    const val CATEGORY = "Collaboratori esterni"
    private const val ROLE_PREFIX = "Esterno · "
    private const val PAYMENT_PREFIX = "external-collab-pay:"
    private const val NOTE_NAME_PREFIX = "[Esterno:"

    fun isExternal(person: PersonnelRecord): Boolean = person.role.startsWith(ROLE_PREFIX, ignoreCase = true)

    fun roleFor(trade: String): String = ROLE_PREFIX + trade.trim().ifBlank { "Collaboratore" }

    fun trade(person: PersonnelRecord): String = person.role
        .removePrefix(ROLE_PREFIX)
        .trim()
        .ifBlank { "Collaboratore esterno" }

    fun savePayment(
        financeStore: FinanceStore,
        person: PersonnelRecord,
        amount: Double,
        job: JobsiteRecord?,
        note: String,
        date: LocalDate
    ): FinanceRecord {
        require(person.ge360Id.isNotBlank()) { "Collaboratore senza UUID GE360" }
        require(amount > 0.0) { "Importo non valido" }
        val record = FinanceRecord(
            ge360Id = "$PAYMENT_PREFIX${person.ge360Id}:${UUID.randomUUID()}",
            type = FinanceType.EXPENSE,
            amount = amount,
            category = CATEGORY,
            note = buildString {
                append("$NOTE_NAME_PREFIX${person.name}] ${trade(person)}")
                if (note.isNotBlank()) append(" · ${note.trim()}")
            },
            date = date.toString(),
            scope = FinanceScope.BUSINESS,
            jobsiteId = job?.ge360Id.orEmpty(),
            jobsiteCode = job?.code.orEmpty(),
            jobsiteTitle = job?.displayTitle().orEmpty()
        )
        financeStore.save(record)
        return record
    }

    fun paymentsFor(financeStore: FinanceStore, person: PersonnelRecord): List<FinanceRecord> {
        val prefix = "$PAYMENT_PREFIX${person.ge360Id}:"
        return financeStore.all()
            .filter { it.ge360Id.startsWith(prefix) && it.type == FinanceType.EXPENSE }
            .sortedWith(compareByDescending<FinanceRecord> { it.date }.thenByDescending { it.updatedAt })
    }

    fun allPayments(financeStore: FinanceStore): List<FinanceRecord> = financeStore.all()
        .filter { it.ge360Id.startsWith(PAYMENT_PREFIX) && it.type == FinanceType.EXPENSE }
        .sortedWith(compareByDescending<FinanceRecord> { it.date }.thenByDescending { it.updatedAt })

    fun personNameFrom(record: FinanceRecord): String {
        val start = record.note.indexOf(NOTE_NAME_PREFIX)
        if (start < 0) return "Collaboratore esterno"
        val from = start + NOTE_NAME_PREFIX.length
        val end = record.note.indexOf(']', from)
        return if (end > from) record.note.substring(from, end).trim() else "Collaboratore esterno"
    }
}
