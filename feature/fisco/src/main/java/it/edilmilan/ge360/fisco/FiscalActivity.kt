package it.edilmilan.ge360.fisco

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import it.edilmilan.ge360.core.common.ui.buildScreenHeaderWithSubtitle
import it.edilmilan.ge360.spese.FinanceStore
import java.text.NumberFormat
import java.util.Locale

class FiscalActivity : AppCompatActivity() {
    private lateinit var finances: FinanceStore
    private lateinit var dataSummary: TextView
    private lateinit var resultView: TextView
    private lateinit var reduction35: CheckBox
    private lateinit var passVat: CheckBox
    private lateinit var rateSpinner: Spinner
    private lateinit var privateShare: EditText
    private lateinit var stampInvoices: EditText
    private var lastComparison: FiscalComparison? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finances = FinanceStore(this)
        setContentView(buildScreen())
        loadPrefs()
        calculate()
    }

    override fun onResume() {
        super.onResume()
        if (::dataSummary.isInitialized) calculate()
    }

    override fun onDestroy() {
        if (::finances.isInitialized) finances.close()
        super.onDestroy()
    }

    private fun buildScreen(): View {
        val outer = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(24))
        }
        outer.addView(root)

        root.addView(buildScreenHeaderWithSubtitle(
            context = this,
            title = "Fisco",
            subtitle = "Controllo e simulazione · dati da Spese",
            onBack = { finish() }
        ))

        root.addView(card().apply {
            addView(LinearLayout(this@FiscalActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                addView(TextView(this@FiscalActivity).apply {
                    text = "Dati fiscali 2026"
                    setTypeface(typeface, Typeface.BOLD)
                    textSize = 17f
                })
                dataSummary = TextView(this@FiscalActivity).apply {
                    textSize = 14f
                    setPadding(0, dp(8), 0, 0)
                }
                addView(dataSummary)
                addView(TextView(this@FiscalActivity).apply {
                    text = "Vengono considerati solo i movimenti marcati Azienda con data 2026. I movimenti Privato sono esclusi."
                    textSize = 12f
                    setPadding(0, dp(7), 0, 0)
                })
            })
        })

        root.addView(sectionTitle("Ipotesi simulazione"))
        reduction35 = CheckBox(this).apply { text = "Riduzione INPS 35% attiva nel forfettario" }
        passVat = CheckBox(this).apply { text = "Nell'ordinario riesco a ribaltare l'IVA sui clienti privati"; isChecked = true }
        rateSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@FiscalActivity, android.R.layout.simple_spinner_dropdown_item, listOf("15% forfettario", "5% startup"))
        }
        privateShare = decimalField("Quota clienti privati %", "95")
        stampInvoices = integerField("Fatture con bollo nell'anno", "0")

        root.addView(label("Aliquota imposta sostitutiva")); root.addView(rateSpinner, lp52())
        root.addView(reduction35, lp52())
        root.addView(label("Quota ricavi da clienti privati")); root.addView(privateShare, lp52())
        root.addView(passVat, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(label("Numero fatture soggette a bollo")); root.addView(stampInvoices, lp52())

        root.addView(MaterialButton(this).apply {
            text = "Ricalcola confronto"
            setOnClickListener { savePrefs(); calculate() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply { topMargin = dp(10) })

        root.addView(sectionTitle("Confronto"))
        resultView = TextView(this).apply {
            textSize = 14f
            setTextIsSelectable(true)
            setPadding(dp(4), dp(4), dp(4), dp(6))
        }
        root.addView(resultView)

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(MaterialButton(this@FiscalActivity).apply {
                text = "Dettaglio costi"
                setOnClickListener { showCostDetail() }
            }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(5) })
            addView(MaterialButton(this@FiscalActivity).apply {
                text = "Condividi"
                setOnClickListener { shareReport() }
            }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(5) })
        })

        root.addView(TextView(this).apply {
            text = "Strumento gestionale di simulazione. Non compila dichiarazioni, non sostituisce il commercialista e non può ricostruire IVA/deducibilità che non sono state registrate nei movimenti Spese."
            textSize = 11f
            setPadding(dp(2), dp(14), dp(2), 0)
        })
        root.addView(TextView(this).apply {
            text = "Fonti 2026 verificate: IRPEF, INPS artigiani e addizionale FVG. Per Trieste la simulazione usa 0,8% con esenzione 12.500 €, ultimo dato pubblicato 2025; al controllo del 30/08/2026 il portale MEF non espone ancora una voce comunale 2026. Ricontrollare prima di usare il dato per adempimenti reali."
            textSize = 11f
            setPadding(dp(2), dp(8), dp(2), 0)
        })
        return outer
    }

    private fun calculate() {
        val settings = settingsFromUi()
        val input = runCatching { FiscalEngine.inputFromFinance(finances.all(), settings) }
            .getOrElse {
                toast(it.message ?: "Errore dati fiscali")
                return
            }
        val c = FiscalEngine.compare(input)
        lastComparison = c
        dataSummary.text = "Entrate Azienda: ${euro(input.revenue)}\nUscite Azienda: ${euro(input.cashExpenses)}\nMovimenti considerati: ${input.movements}"
        resultView.text = report(c)
    }

    private fun report(c: FiscalComparison): String = buildString {
        appendLine("Esito simulazione: ${c.recommended}")
        appendLine("Differenza netto stimato: ${euro(c.deltaNet)}")
        c.breakEvenCostPercent?.let { appendLine("Punto di pareggio costi stimato: ${fmt(it)}% dei ricavi") }
        appendLine()
        appendLine("FORFETTARIO")
        appendLine("Reddito fiscale dopo contributi: ${euro(c.forfettario.fiscalIncome)}")
        appendLine("INPS stimata: ${euro(c.forfettario.inps.total)}")
        appendLine("Imposta sostitutiva: ${euro(c.forfettario.tax)}")
        appendLine("Bolli: ${euro(c.forfettario.stampDuty)}")
        appendLine("Costo gestione stimato: ${euro(c.forfettario.accountantCost)}")
        appendLine("Netto di cassa stimato: ${euro(c.forfettario.netCashEstimate)}")
        appendLine("Pressione fiscale/contributiva stimata: ${fmt(c.forfettario.pressurePercent)}%")
        appendLine()
        appendLine("ORDINARIO / SEMPLIFICATA")
        appendLine("Ricavi netti effettivi stimati: ${euro(c.ordinary.effectiveRevenue)}")
        appendLine("Reddito fiscale dopo contributi: ${euro(c.ordinary.fiscalIncome)}")
        appendLine("INPS stimata: ${euro(c.ordinary.inps.total)}")
        appendLine("IRPEF: ${euro(c.ordinary.tax)}")
        appendLine("Addizionale FVG: ${euro(c.ordinary.regionalTax)}")
        appendLine("Addizionale Trieste: ${euro(c.ordinary.municipalTax)}")
        appendLine("IVA acquisti recuperabile stimata: ${euro(c.ordinary.recoverableVat)}")
        appendLine("Costo gestione stimato: ${euro(c.ordinary.accountantCost)}")
        appendLine("Netto di cassa stimato: ${euro(c.ordinary.netCashEstimate)}")
        appendLine("Pressione fiscale/contributiva stimata: ${fmt(c.ordinary.pressurePercent)}%")
        if (c.warnings.isNotEmpty()) {
            appendLine(); appendLine("ATTENZIONE")
            c.warnings.forEach { appendLine("• $it") }
        }
        if (c.categoryWarnings.isNotEmpty()) {
            appendLine(); appendLine("DATI DA VERIFICARE")
            c.categoryWarnings.take(8).forEach { appendLine("• $it") }
            if (c.categoryWarnings.size > 8) appendLine("• +${c.categoryWarnings.size - 8} altre categorie/assunzioni")
        }
    }.trim()

    private fun showCostDetail() {
        val c = lastComparison ?: return
        val grouped = c.input.expenses.groupBy { it.category }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
        val text = if (grouped.isEmpty()) "Nessuna uscita Azienda nel 2026." else grouped.entries.joinToString("\n\n") { (category, rows) ->
            val cash = rows.sumOf { it.cashAmount }
            val deductible = rows.sumOf { it.deductibleCost }
            val vat = rows.sumOf { it.recoverableVat }
            val warning = rows.map { it.warning }.firstOrNull { it.isNotBlank() }.orEmpty()
            buildString {
                append("$category\nCassa ${euro(cash)} · deducibile stimato ${euro(deductible)} · IVA recuperabile stimata ${euro(vat)}")
                if (warning.isNotBlank()) append("\n⚠ $warning")
            }
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Dettaglio categorie Spese")
            .setMessage(text)
            .setPositiveButton("Chiudi", null)
            .show()
    }

    private fun shareReport() {
        val c = lastComparison ?: return
        val text = "GE360 Fisco — simulazione ${c.input.year}\n\n${report(c)}"
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }, "Condividi simulazione fiscale"))
    }

    private fun settingsFromUi(): FiscalSettings {
        val rate = if (::rateSpinner.isInitialized && rateSpinner.selectedItemPosition == 1) FiscalParams2026.STARTUP_RATE else FiscalParams2026.FORFETTARIO_RATE
        val share = parseNumber(if (::privateShare.isInitialized) privateShare.text.toString() else "95") / 100.0
        return FiscalSettings(
            inpsReduction35 = ::reduction35.isInitialized && reduction35.isChecked,
            forfettarioRate = rate,
            privateClientShare = share.coerceIn(0.0, 1.0),
            passVatToPrivateClients = !::passVat.isInitialized || passVat.isChecked,
            stampInvoices = parseNumber(if (::stampInvoices.isInitialized) stampInvoices.text.toString() else "0").toInt().coerceAtLeast(0)
        )
    }

    private fun savePrefs() {
        val s = settingsFromUi()
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean("inps35", s.inpsReduction35)
            .putBoolean("passVat", s.passVatToPrivateClients)
            .putInt("rate", if (s.forfettarioRate == FiscalParams2026.STARTUP_RATE) 5 else 15)
            .putFloat("privateShare", (s.privateClientShare * 100.0).toFloat())
            .putInt("stamps", s.stampInvoices)
            .apply()
    }

    private fun loadPrefs() {
        val p = getSharedPreferences(PREFS, MODE_PRIVATE)
        reduction35.isChecked = p.getBoolean("inps35", false)
        passVat.isChecked = p.getBoolean("passVat", true)
        rateSpinner.setSelection(if (p.getInt("rate", 15) == 5) 1 else 0)
        privateShare.setText(fmt(p.getFloat("privateShare", 95f).toDouble()))
        stampInvoices.setText(p.getInt("stamps", 0).toString())
    }

    private fun card() = MaterialCardView(this).apply {
        radius = dp(14).toFloat()
        cardElevation = dp(1).toFloat()
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10); bottomMargin = dp(8)
        }
    }
    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text; textSize = 18f; setTypeface(typeface, Typeface.BOLD); setPadding(dp(2), dp(16), dp(2), dp(6))
    }
    private fun label(text: String) = TextView(this).apply { this.text = text; setTypeface(typeface, Typeface.BOLD); setPadding(dp(2), dp(6), 0, 0) }
    private fun decimalField(hintText: String, value: String) = EditText(this).apply {
        hint = hintText; setText(value); isSingleLine = true; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
    }
    private fun integerField(hintText: String, value: String) = EditText(this).apply {
        hint = hintText; setText(value); isSingleLine = true; inputType = InputType.TYPE_CLASS_NUMBER
    }
    private fun lp52() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
    private fun parseNumber(value: String): Double = value.trim().replace("%", "").replace(" ", "").replace(',', '.').filter { it.isDigit() || it == '.' || it == '-' }.toDoubleOrNull() ?: 0.0
    private fun euro(v: Double) = NumberFormat.getCurrencyInstance(Locale.ITALY).format(v)
    private fun fmt(v: Double) = if (kotlin.math.abs(v - v.toInt()) < 0.001) v.toInt().toString() else String.format(Locale.ITALY, "%.2f", v)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    companion object { private const val PREFS = "ge360_fisco_settings" }
}