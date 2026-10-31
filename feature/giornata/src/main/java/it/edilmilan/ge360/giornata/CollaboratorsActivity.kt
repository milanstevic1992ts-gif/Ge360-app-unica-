package it.edilmilan.ge360.giornata

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import it.edilmilan.ge360.core.common.ui.buildScreenHeaderWithSubtitle
import it.edilmilan.ge360.spese.ExpensesActivity
import it.edilmilan.ge360.spese.FinanceRecord
import it.edilmilan.ge360.spese.FinanceStore
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class CollaboratorsActivity : AppCompatActivity() {
    private lateinit var dailyStore: DailyStore
    private lateinit var financeStore: FinanceStore
    private lateinit var body: LinearLayout
    private lateinit var slobodan: PersonnelRecord

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dailyStore = DailyStore(this)
        financeStore = FinanceStore(this)
        slobodan = SlobodanWeeklyPay.ensurePersonnel(dailyStore)
        CollaboratorPaymentReminderScheduler.schedule(applicationContext)
        setContentView(buildScreen())
        requestNotificationsIfNeeded()
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::body.isInitialized) render()
    }

    override fun onDestroy() {
        dailyStore.close()
        financeStore.close()
        super.onDestroy()
    }

    private fun buildScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F6F5F2"))
            setPadding(dp(14), dp(10), dp(14), dp(18))
        }
        root.addView(
            buildScreenHeaderWithSubtitle(
                context = this,
                title = "Collaboratori",
                subtitle = "Ore · pagamenti · promemoria",
                onBack = { finish() }
            )
        )
        val scroll = ScrollView(this)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun render() {
        body.removeAllViews()
        slobodan = SlobodanWeeklyPay.ensurePersonnel(dailyStore)
        val weekStart = SlobodanWeeklyPay.weekStart()
        val currentPayment = SlobodanWeeklyPay.paymentForWeek(financeStore, slobodan, weekStart)

        body.addView(sectionTitle("NUOVO REGISTRO COMPENSI",
            "Compensi settimanali e a cottimo, separati dalle ore e dai pagamenti storici."))
        body.addView(MaterialButton(this).apply {
            text = "APR I COMPENSI SETTIMANALI / A COTTIMO".replace("APR I", "APRI")
            minHeight = dp(54)
            setOnClickListener {
                startActivity(Intent(this@CollaboratorsActivity, CompensationActivity::class.java))
            }
        }, full(dp(58)))

        body.addView(sectionTitle("SLOBODAN", "Compenso fisso settimanale e stato del pagamento corrente."))
        body.addView(slobodanCard(currentPayment, weekStart))

        body.addView(sectionTitle("PROMEMORIA", "Se entro sabato sera il pagamento non risulta registrato, GE360 ti avvisa."))
        body.addView(reminderCard())

        body.addView(sectionTitle("ULTIMI PAGAMENTI", "I pagamenti vengono salvati anche come uscite aziendali nel modulo Spese."))
        val history = SlobodanWeeklyPay.history(financeStore, slobodan, 10)
        if (history.isEmpty()) {
            body.addView(emptyText("Ancora nessun pagamento settimanale registrato."))
        } else {
            history.forEach { body.addView(historyCard(it)) }
        }
        body.addView(MaterialButton(this).apply {
            text = "VEDI TUTTE LE SPESE"
            setOnClickListener { startActivity(Intent(this@CollaboratorsActivity, ExpensesActivity::class.java)) }
        }, full(dp(50)).apply { topMargin = dp(7) })

        val others = dailyStore.personnel(activeOnly = true)
            .filterNot { it.ge360Id == slobodan.ge360Id }
        if (others.isNotEmpty()) {
            body.addView(sectionTitle("ALTRI COLLABORATORI", "Personale già presente in Giornata."))
            others.forEach { person ->
                body.addView(simplePersonCard(person))
            }
        }
    }

    private fun slobodanCard(payment: FinanceRecord?, weekStart: LocalDate): View = MaterialCardView(this).apply {
        radius = dp(20).toFloat()
        cardElevation = 0f
        strokeWidth = dp(1)
        strokeColor = Color.parseColor(if (payment == null) "#E6A22A" else "#8DB69A")
        setCardBackgroundColor(Color.WHITE)
        addView(LinearLayout(this@CollaboratorsActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(15))

            addView(LinearLayout(this@CollaboratorsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@CollaboratorsActivity).apply {
                    text = "Slobodan"
                    textSize = 23f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.parseColor("#384234"))
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(TextView(this@CollaboratorsActivity).apply {
                    text = if (payment == null) "DA PAGARE" else "PAGATO"
                    textSize = 11f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.parseColor(if (payment == null) "#B87400" else "#277A49"))
                    setPadding(dp(10), dp(6), dp(10), dp(6))
                })
            })

            addView(TextView(this@CollaboratorsActivity).apply {
                text = "Compenso settimanale · ${money(SlobodanWeeklyPay.WEEKLY_AMOUNT)}"
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor("#1F2421"))
                setPadding(0, dp(8), 0, dp(2))
            })
            addView(TextView(this@CollaboratorsActivity).apply {
                text = "Settimana ${SlobodanWeeklyPay.periodLabel(weekStart)}"
                textSize = 12f
                setTextColor(Color.parseColor("#6B736C"))
            })

            if (payment != null) {
                addView(TextView(this@CollaboratorsActivity).apply {
                    text = "Registrato ${formatDate(payment.date)} · ${money(payment.amount)}"
                    textSize = 13f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.parseColor("#277A49"))
                    setPadding(0, dp(9), 0, 0)
                })
            }

            addView(MaterialButton(this@CollaboratorsActivity).apply {
                text = if (payment == null) "✓  PAGATO · ${money(SlobodanWeeklyPay.WEEKLY_AMOUNT)}" else "MODIFICA PAGAMENTO"
                minHeight = dp(54)
                setOnClickListener { showPaymentDialog(payment, weekStart) }
            }, full(dp(58)).apply { topMargin = dp(13) })
        })
    }

    private fun reminderCard(): View = MaterialCardView(this).apply {
        radius = dp(17).toFloat()
        cardElevation = 0f
        strokeWidth = dp(1)
        strokeColor = Color.parseColor("#E2E4DF")
        setCardBackgroundColor(Color.WHITE)
        addView(LinearLayout(this@CollaboratorsActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(13), dp(15), dp(13))
            addView(TextView(this@CollaboratorsActivity).apply {
                text = "🔔 Sabato · ore 19:00"
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor("#384234"))
            })
            addView(TextView(this@CollaboratorsActivity).apply {
                text = if (notificationsGranted()) {
                    "Notifica attiva. Se la settimana non è segnata come pagata, riceverai il promemoria."
                } else {
                    "Autorizza le notifiche Android per ricevere il promemoria del sabato sera."
                }
                textSize = 12f
                setTextColor(Color.parseColor("#6B736C"))
                setPadding(0, dp(5), 0, 0)
            })
            if (!notificationsGranted()) {
                addView(MaterialButton(this@CollaboratorsActivity).apply {
                    text = "ATTIVA NOTIFICHE"
                    setOnClickListener { requestNotificationsIfNeeded(force = true) }
                }, full(dp(48)).apply { topMargin = dp(8) })
            }
        })
    }

    private fun historyCard(record: FinanceRecord): View = MaterialCardView(this).apply {
        radius = dp(14).toFloat()
        cardElevation = 0f
        strokeWidth = dp(1)
        strokeColor = Color.parseColor("#E5E6E2")
        setCardBackgroundColor(Color.WHITE)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(7)
        }
        addView(LinearLayout(this@CollaboratorsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(13), dp(11), dp(13), dp(11))
            addView(LinearLayout(this@CollaboratorsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@CollaboratorsActivity).apply {
                    text = formatDate(record.date)
                    textSize = 13f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.parseColor("#384234"))
                })
                addView(TextView(this@CollaboratorsActivity).apply {
                    text = record.note
                    textSize = 10.5f
                    setTextColor(Color.parseColor("#747A73"))
                    maxLines = 2
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@CollaboratorsActivity).apply {
                text = money(record.amount)
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor("#B87400"))
            })
        })
    }

    private fun simplePersonCard(person: PersonnelRecord): View = MaterialCardView(this).apply {
        radius = dp(14).toFloat()
        cardElevation = 0f
        setCardBackgroundColor(Color.WHITE)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(7)
        }
        addView(TextView(this@CollaboratorsActivity).apply {
            text = buildString {
                append(person.name)
                if (person.role.isNotBlank()) append(" · ${person.role}")
                if (person.hourlyRate > 0) append(" · ${money(person.hourlyRate)}/h")
            }
            textSize = 13f
            setTextColor(Color.parseColor("#384234"))
            setPadding(dp(13), dp(12), dp(13), dp(12))
        })
    }

    private fun showPaymentDialog(existing: FinanceRecord?, weekStart: LocalDate) {
        val dialogBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
        }
        val amountInput = EditText(this).apply {
            hint = "Importo €"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(String.format(Locale.ITALY, "%.2f", existing?.amount ?: SlobodanWeeklyPay.WEEKLY_AMOUNT))
        }
        val noteInput = EditText(this).apply {
            hint = "Nota facoltativa"
            minLines = 2
        }
        dialogBody.addView(amountInput, full(dp(56)))
        dialogBody.addView(noteInput, full(dp(74)))

        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Registra pagamento" else "Modifica pagamento")
            .setMessage("Slobodan · settimana ${SlobodanWeeklyPay.periodLabel(weekStart)}")
            .setView(dialogBody)
            .setPositiveButton("SALVA", null)
            .setNegativeButton("Annulla", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val amount = parseMoney(amountInput.text.toString())
                if (amount <= 0.0) {
                    amountInput.error = "Inserisci un importo valido"
                    return@setOnClickListener
                }
                SlobodanWeeklyPay.savePayment(
                    financeStore = financeStore,
                    personnel = slobodan,
                    amount = amount,
                    note = noteInput.text.toString(),
                    paidDate = LocalDate.now(),
                    weekStart = weekStart
                )
                dialog.dismiss()
                Toast.makeText(this, "Pagamento Slobodan registrato · ${money(amount)}", Toast.LENGTH_LONG).show()
                render()
            }
        }
        dialog.show()
    }

    private fun requestNotificationsIfNeeded(force: Boolean = false) {
        if (Build.VERSION.SDK_INT < 33 || notificationsGranted()) return
        if (force || !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.POST_NOTIFICATIONS)) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIFICATIONS
            )
        }
    }

    private fun notificationsGranted(): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

    private fun sectionTitle(title: String, subtitle: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(3), dp(20), dp(3), dp(8))
        addView(TextView(this@CollaboratorsActivity).apply {
            text = title
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#384234"))
        })
        addView(TextView(this@CollaboratorsActivity).apply {
            text = subtitle
            textSize = 11f
            setTextColor(Color.parseColor("#747A73"))
            setPadding(0, dp(2), 0, 0)
        })
    }

    private fun emptyText(value: String) = TextView(this).apply {
        text = value
        textSize = 12f
        setTextColor(Color.parseColor("#747A73"))
        setPadding(dp(4), dp(10), dp(4), dp(10))
    }

    private fun parseMoney(value: String): Double = value.trim()
        .replace("€", "")
        .replace(" ", "")
        .replace(".", "")
        .replace(',', '.')
        .toDoubleOrNull() ?: 0.0

    private fun money(value: Double): String = NumberFormat.getCurrencyInstance(Locale.ITALY).format(value)

    private fun formatDate(value: String): String = runCatching {
        LocalDate.parse(value).format(DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ITALY))
    }.getOrDefault(value)

    private fun full(height: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_NOTIFICATIONS = 55021
    }
}
