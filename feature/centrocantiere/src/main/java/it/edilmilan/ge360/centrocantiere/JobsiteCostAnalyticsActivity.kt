package it.edilmilan.ge360.centrocantiere

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import it.edilmilan.ge360.attrezzi.MaterialInventoryStore
import it.edilmilan.ge360.attrezzi.MaterialStockMovement
import it.edilmilan.ge360.attrezzi.MaterialsActivity
import it.edilmilan.ge360.cantieri.JobsiteRecord
import it.edilmilan.ge360.cantieri.JobsiteStore
import it.edilmilan.ge360.core.common.Ge360Contracts
import it.edilmilan.ge360.core.common.ui.Ge360Brand
import it.edilmilan.ge360.core.common.ui.applyGe360Card
import it.edilmilan.ge360.core.common.ui.applyGe360Primary
import it.edilmilan.ge360.core.common.ui.applyGe360Secondary
import it.edilmilan.ge360.core.common.ui.brandRounded
import it.edilmilan.ge360.core.common.ui.buildScreenHeaderWithSubtitle
import it.edilmilan.ge360.giornata.DailyStore
import it.edilmilan.ge360.giornata.JobsiteLaborActivity
import it.edilmilan.ge360.giornata.WorkSession
import it.edilmilan.ge360.giornata.laborDayWorkedPersonDaysForJobsite
import it.edilmilan.ge360.spese.ExpensesActivity
import it.edilmilan.ge360.spese.FinanceRecord
import it.edilmilan.ge360.spese.FinanceStore
import it.edilmilan.ge360.spese.FinanceType
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Cruscotto economico per singolo cantiere.
 *
 * Non salva un secondo bilancio: legge esclusivamente gli archivi già esistenti
 * (Spese, Giornata e Materiali 2.0) e calcola le statistiche quando la schermata viene aperta.
 */
class JobsiteCostAnalyticsActivity : AppCompatActivity() {
    private lateinit var jobsiteStore: JobsiteStore
    private lateinit var dailyStore: DailyStore
    private lateinit var financeStore: FinanceStore
    private lateinit var materialStore: MaterialInventoryStore
    private lateinit var jobsite: JobsiteRecord

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        jobsiteStore = JobsiteStore(this)
        dailyStore = DailyStore(this)
        financeStore = FinanceStore(this)
        materialStore = MaterialInventoryStore(this)
        jobsite = resolveJobsite() ?: run {
            Toast.makeText(this, "Cantiere non trovato", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        setContentView(buildScreen())
    }

    override fun onResume() {
        super.onResume()
        if (::jobsite.isInitialized) setContentView(buildScreen())
    }

    override fun onDestroy() {
        jobsiteStore.close()
        dailyStore.close()
        financeStore.close()
        materialStore.close()
        super.onDestroy()
    }

    private fun buildScreen(): View {
        val data = calculate()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(14))
            setBackgroundColor(Ge360Brand.Background)
        }
        root.addView(buildScreenHeaderWithSubtitle(
            context = this,
            title = "Costi / statistiche",
            subtitle = "${jobsite.code} · ${jobsite.displayTitle()}",
            onBack = { finish() }
        ))

        val scroll = ScrollView(this).apply { clipToPadding = false }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(22))
        }
        scroll.addView(body)

        body.addView(heroCard(data))
        body.addView(sectionTitle("RISULTATO CANTIERE", "Entrate, costo operativo attribuito, utile e margine."))
        body.addView(kpiRow(
            Kpi("ENTRATE", money(data.income), Ge360Brand.SuccessSoft, Ge360Brand.Success),
            Kpi("COSTO TOTALE", money(data.totalCost), Ge360Brand.WarningSoft, Ge360Brand.PrimaryDark)
        ))
        body.addView(kpiRow(
            Kpi(if (data.profit >= 0) "UTILE" else "PERDITA", money(data.profit), if (data.profit >= 0) Ge360Brand.ForestSoft else Ge360Brand.DangerSoft, if (data.profit >= 0) Ge360Brand.Forest else Ge360Brand.Danger),
            Kpi("MARGINE", data.marginPct?.let { percent(it) } ?: "—", Ge360Brand.SurfaceWarm, Ge360Brand.ForestDark)
        ))

        body.addView(sectionTitle("RIPARTIZIONE COSTI", "Quanto pesa ogni voce sul costo complessivo del cantiere."))
        body.addView(chartCard(data.costSlices, data.totalCost, "COSTI"))

        body.addView(sectionTitle("MANODOPERA PER OPERATORE", "Costo interno attribuito in base alle ore e alle giornate registrate."))
        body.addView(chartCard(data.laborSlices, data.internalLaborCost, "MANODOPERA", emptyMessage = "Nessuna manodopera interna valorizzata."))

        body.addView(sectionTitle("MATERIALI CONSUMATI", "Valore dei prelievi Materiali 2.0 collegati a questo cantiere."))
        body.addView(chartCard(data.materialSlices, data.stockMaterialCost, "MAGAZZINO", emptyMessage = "Nessun consumo di magazzino valorizzato."))

        body.addView(sectionTitle("INDICATORI OPERATIVI", "Numeri utili per capire dove sta andando il margine."))
        body.addView(metricCard(data))

        body.addView(sectionTitle("COME VIENE CALCOLATO", "Regole anti-doppio-conteggio usate da GE360."))
        body.addView(methodCard(data))

        body.addView(sectionTitle("APRIRE I DATI", "Vai direttamente agli archivi che alimentano queste statistiche."))
        body.addView(MaterialButton(this).apply {
            text = "💶  SPESE / ENTRATE DEL CANTIERE"
            applyGe360Primary()
            setOnClickListener { openContext(ExpensesActivity::class.java) }
        }, full(dp(52)))
        body.addView(MaterialButton(this).apply {
            text = "👷  ORE / MANODOPERA"
            applyGe360Secondary()
            setOnClickListener { openContext(JobsiteLaborActivity::class.java) }
        }, full(dp(50)).apply { topMargin = dp(7) })
        body.addView(MaterialButton(this).apply {
            text = "📦  MATERIALI 2.0"
            applyGe360Secondary()
            setOnClickListener { startActivity(Intent(this@JobsiteCostAnalyticsActivity, MaterialsActivity::class.java)) }
        }, full(dp(50)).apply { topMargin = dp(7) })

        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun calculate(): Analytics {
        val financeRows = financeStore.forJobsite(jobsite.ge360Id)
        val income = financeRows.filter { it.type == FinanceType.INCOME }.sumOf { it.amount.coerceAtLeast(0.0) }
        val expenses = financeRows.filter { it.type == FinanceType.EXPENSE }

        val people = dailyStore.personnel(false)
        val peopleById = people.associateBy { it.ge360Id }
        val peopleByName = people.associateBy { it.name.trim().lowercase(Locale.ROOT) }
        fun isExternal(session: WorkSession): Boolean {
            val person = peopleById[session.personnelId]
                ?: peopleByName[session.collaborator.trim().lowercase(Locale.ROOT)]
            return person?.role?.trim()?.startsWith(EXTERNAL_ROLE_PREFIX, ignoreCase = true) == true
        }

        val sessions = dailyStore.sessionsForJobsite(jobsite.ge360Id)
        val internalSessions = sessions.filterNot(::isExternal)
        val externalSessions = sessions.filter(::isExternal)
        val internalLaborCost = internalSessions.sumOf { it.laborCost() }
        val externalLaborEstimate = externalSessions.sumOf { it.laborCost() }
        val internalMinutes = internalSessions.sumOf { it.durationMinutes() }
        val allMinutes = sessions.sumOf { it.durationMinutes() }

        val personnelPayments = expenses.filter { category(it) == "collaboratori" }.sumOf { it.amount }
        val externalFinance = expenses.filter {
            category(it) in setOf("collaboratori esterni", "subappalti")
        }.sumOf { it.amount }
        val materialFinance = expenses.filter { category(it) == "materiali" }.sumOf { it.amount }
        val waste = expenses.filter { category(it) == "smaltimenti" }.sumOf { it.amount }
        val transport = expenses.filter { category(it) in setOf("trasporti", "carburante") }.sumOf { it.amount }
        val tools = expenses.filter { category(it) == "attrezzi" }.sumOf { it.amount }
        val reserved = setOf(
            "collaboratori", "collaboratori esterni", "subappalti", "materiali",
            "smaltimenti", "trasporti", "carburante", "attrezzi"
        )
        val other = expenses.filter { category(it) !in reserved }.sumOf { it.amount }

        val materialMovements = materialStore.movements(limit = 1000)
            .filter { it.jobsiteId == jobsite.ge360Id && it.type == MaterialStockMovement.TYPE_CONSUME }
        var estimatedMaterialRows = 0
        val materialValueRows = materialMovements.map { row ->
            var unitCost = row.unitCost.coerceAtLeast(0.0)
            if (unitCost <= 0.0) {
                unitCost = materialStore.findByGe360(row.materialGe360Id)?.avgUnitCost?.coerceAtLeast(0.0) ?: 0.0
                if (unitCost > 0.0) estimatedMaterialRows++
            }
            val value = abs(row.quantity) * unitCost
            row to value
        }
        val stockMaterialCost = materialValueRows.sumOf { it.second }

        // Regola prudente: se lo stesso materiale compare sia come spesa sia come consumo di magazzino,
        // usare il valore maggiore evita di sommarlo due volte. Il dettaglio mostra entrambe le fonti.
        val attributedMaterialCost = max(materialFinance, stockMaterialCost)
        // Per gli esterni prevale il pagamento effettivamente registrato; se manca, usa il costo ore/giornate come stima.
        val externalCost = if (externalFinance > 0.0) externalFinance else externalLaborEstimate

        val totalCost = internalLaborCost + attributedMaterialCost + externalCost + waste + transport + tools + other
        val profit = income - totalCost
        val marginPct = if (income > 0.0001) profit / income * 100.0 else null

        val costSlices = listOf(
            DonutSlice("Manodopera interna", internalLaborCost, PALETTE[0]),
            DonutSlice("Materiali", attributedMaterialCost, PALETTE[1]),
            DonutSlice("Collaboratori esterni", externalCost, PALETTE[2], if (externalFinance > 0.0) "pagamenti" else "stima ore"),
            DonutSlice("Smaltimenti", waste, PALETTE[3]),
            DonutSlice("Trasporti / carburante", transport, PALETTE[4]),
            DonutSlice("Attrezzi", tools, PALETTE[5]),
            DonutSlice("Altre spese", other, PALETTE[6])
        ).filter { it.value > 0.0001 }

        val laborSlices = internalSessions
            .groupBy { it.personnelId.ifBlank { it.collaborator.trim().lowercase(Locale.ROOT) } }
            .map { (_, rows) ->
                val name = rows.firstOrNull()?.collaborator?.trim().orEmpty().ifBlank { "Senza nome" }
                val value = rows.sumOf { it.laborCost() }
                val minutes = rows.sumOf { it.durationMinutes() }
                name to Pair(value, minutes)
            }
            .filter { it.second.first > 0.0001 }
            .sortedByDescending { it.second.first }
            .mapIndexed { index, row ->
                DonutSlice(row.first, row.second.first, PALETTE[index % PALETTE.size], formatMinutes(row.second.second))
            }

        val materialSlices = materialValueRows
            .groupBy { it.first.materialName.ifBlank { "Materiale" } }
            .map { (name, rows) -> name to rows.sumOf { it.second } }
            .filter { it.second > 0.0001 }
            .sortedByDescending { it.second }
            .mapIndexed { index, row -> DonutSlice(row.first, row.second, PALETTE[(index + 1) % PALETTE.size]) }
            .ifEmpty {
                if (materialFinance > 0.0) listOf(DonutSlice("Acquisti materiali", materialFinance, PALETTE[1], "da Spese")) else emptyList()
            }

        return Analytics(
            income = income,
            financeExpenses = expenses.sumOf { it.amount },
            totalCost = totalCost,
            profit = profit,
            marginPct = marginPct,
            internalLaborCost = internalLaborCost,
            externalLaborEstimate = externalLaborEstimate,
            materialFinanceCost = materialFinance,
            stockMaterialCost = stockMaterialCost,
            attributedMaterialCost = attributedMaterialCost,
            externalFinanceCost = externalFinance,
            externalCost = externalCost,
            personnelPaymentsExcluded = personnelPayments,
            wasteCost = waste,
            transportCost = transport,
            toolsCost = tools,
            otherCost = other,
            internalMinutes = internalMinutes,
            allMinutes = allMinutes,
            personDays = dailyStore.laborDayWorkedPersonDaysForJobsite(jobsite.ge360Id),
            financeMovements = financeRows.size,
            materialMovements = materialMovements.size,
            estimatedMaterialRows = estimatedMaterialRows,
            costSlices = costSlices,
            laborSlices = laborSlices,
            materialSlices = materialSlices
        )
    }

    private fun heroCard(data: Analytics): View = MaterialCardView(this).apply {
        applyGe360Card(fill = Ge360Brand.SurfaceWarm, stroke = Ge360Brand.BorderStrong, radiusDp = 20)
        setContentPadding(dp(16), dp(14), dp(16), dp(14))
        addView(LinearLayout(this@JobsiteCostAnalyticsActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@JobsiteCostAnalyticsActivity).apply {
                text = if (data.profit >= 0) "Cantiere in utile" else "Cantiere in perdita"
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (data.profit >= 0) Ge360Brand.ForestDark else Ge360Brand.Danger)
            })
            addView(TextView(this@JobsiteCostAnalyticsActivity).apply {
                text = "${money(data.profit)}${data.marginPct?.let { " · margine ${percent(it)}" } ?: ""}"
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (data.profit >= 0) Ge360Brand.Success else Ge360Brand.Danger)
                setPadding(0, dp(4), 0, 0)
            })
            addView(TextView(this@JobsiteCostAnalyticsActivity).apply {
                text = "Aggiornato in tempo reale dai dati già registrati in GE360. Nessun bilancio parallelo."
                textSize = 11f
                setTextColor(Ge360Brand.TextSecondary)
                setPadding(0, dp(5), 0, 0)
            })
        })
    }

    private data class Kpi(val label: String, val value: String, val fill: Int, val color: Int)

    private fun kpiRow(left: Kpi, right: Kpi): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(kpiCard(left), LinearLayout.LayoutParams(0, dp(88), 1f).apply { marginEnd = dp(5); bottomMargin = dp(9) })
        addView(kpiCard(right), LinearLayout.LayoutParams(0, dp(88), 1f).apply { marginStart = dp(5); bottomMargin = dp(9) })
    }

    private fun kpiCard(kpi: Kpi): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(8), dp(8), dp(8))
        background = this@JobsiteCostAnalyticsActivity.brandRounded(fill = kpi.fill, stroke = kpi.fill, radiusDp = 16, strokeDp = 0)
        addView(TextView(this@JobsiteCostAnalyticsActivity).apply {
            text = kpi.value
            textSize = 19f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(kpi.color)
            gravity = Gravity.CENTER
            maxLines = 1
        })
        addView(TextView(this@JobsiteCostAnalyticsActivity).apply {
            text = kpi.label
            textSize = 9f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ge360Brand.TextSecondary)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        })
    }

    private fun chartCard(
        slices: List<DonutSlice>,
        total: Double,
        centerLabel: String,
        emptyMessage: String = "Nessun costo registrato."
    ): View = MaterialCardView(this).apply {
        applyGe360Card(fill = Ge360Brand.Surface, stroke = Ge360Brand.Border, radiusDp = 18)
        setContentPadding(dp(13), dp(13), dp(13), dp(13))
        addView(LinearLayout(this@JobsiteCostAnalyticsActivity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val chart = CostDonutView(this@JobsiteCostAnalyticsActivity).apply {
                setData(slices, total, centerLabel)
            }
            addView(chart, LinearLayout.LayoutParams(dp(252), dp(252)).apply { gravity = Gravity.CENTER_HORIZONTAL })
            if (slices.isEmpty()) {
                addView(TextView(this@JobsiteCostAnalyticsActivity).apply {
                    text = emptyMessage
                    textSize = 12f
                    setTextColor(Ge360Brand.TextSecondary)
                    gravity = Gravity.CENTER
                    setPadding(dp(8), 0, dp(8), dp(10))
                })
            } else {
                val sum = slices.sumOf { it.value }
                slices.forEach { slice -> addView(legendRow(slice, sum)) }
            }
        })
    }

    private fun legendRow(slice: DonutSlice, total: Double): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(3), dp(6), dp(3), dp(6))
        addView(View(this@JobsiteCostAnalyticsActivity).apply {
            background = this@JobsiteCostAnalyticsActivity.brandRounded(fill = slice.color, stroke = slice.color, radiusDp = 5, strokeDp = 0)
        }, LinearLayout.LayoutParams(dp(11), dp(11)).apply { marginEnd = dp(9) })
        addView(LinearLayout(this@JobsiteCostAnalyticsActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@JobsiteCostAnalyticsActivity).apply {
                text = slice.label
                textSize = 12.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Ge360Brand.ForestDark)
            })
            if (slice.detail.isNotBlank()) addView(TextView(this@JobsiteCostAnalyticsActivity).apply {
                text = slice.detail
                textSize = 9.5f
                setTextColor(Ge360Brand.TextSecondary)
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(this@JobsiteCostAnalyticsActivity).apply {
            val pct = if (total > 0.0001) slice.value / total * 100.0 else 0.0
            text = "${money(slice.value)}  ·  ${percent(pct)}"
            textSize = 11.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ge360Brand.TextSecondary)
            gravity = Gravity.END
        })
    }

    private fun metricCard(data: Analytics): View = MaterialCardView(this).apply {
        applyGe360Card(fill = Ge360Brand.SurfaceWarm, stroke = Ge360Brand.Border, radiusDp = 18)
        setContentPadding(dp(14), dp(13), dp(14), dp(13))
        addView(LinearLayout(this@JobsiteCostAnalyticsActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(metricLine("Ore manodopera totali", formatMinutes(data.allMinutes)))
            addView(metricLine("Ore personale interno", formatMinutes(data.internalMinutes)))
            addView(metricLine("Giornate-uomo da calendario", data.personDays.toString()))
            addView(metricLine("Costo medio personale / ora", if (data.internalMinutes > 0) money(data.internalLaborCost / (data.internalMinutes / 60.0)) else "—"))
            addView(metricLine("Movimenti economici", data.financeMovements.toString()))
            addView(metricLine("Prelievi materiale cantiere", data.materialMovements.toString()))
            addView(metricLine("Spese cash collegate", money(data.financeExpenses)))
        })
    }

    private fun methodCard(data: Analytics): View = MaterialCardView(this).apply {
        applyGe360Card(fill = Ge360Brand.Surface, stroke = Ge360Brand.Border, radiusDp = 18)
        setContentPadding(dp(14), dp(13), dp(14), dp(13))
        addView(LinearLayout(this@JobsiteCostAnalyticsActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(methodText("• Manodopera interna: usa ore/giornate e tariffa storica dell'operatore. I pagamenti categoria “Collaboratori” non vengono sommati di nuovo."))
            if (data.personnelPaymentsExcluded > 0.0) addView(methodHighlight("Pagamenti personale esclusi dal totale: ${money(data.personnelPaymentsExcluded)}"))
            addView(methodText("• Collaboratori esterni: usa i pagamenti collegati al cantiere; se non ce ne sono, usa il costo ore/giornate dell'esterno come stima."))
            if (data.externalFinanceCost <= 0.0 && data.externalLaborEstimate > 0.0) addView(methodHighlight("Esterni stimati da ore/giornate: ${money(data.externalLaborEstimate)}"))
            addView(methodText("• Materiali: confronta acquisti Materiali in Spese e consumi Materiali 2.0; usa il valore maggiore per evitare il doppio conteggio dello stesso materiale."))
            addView(methodHighlight("Materiali da Spese ${money(data.materialFinanceCost)} · da magazzino ${money(data.stockMaterialCost)} · attribuiti ${money(data.attributedMaterialCost)}"))
            if (data.estimatedMaterialRows > 0) addView(methodHighlight("${data.estimatedMaterialRows} consumi storici senza costo fotografato: valorizzati con il costo medio attuale e quindi indicativi."))
            addView(methodText("• Smaltimenti, trasporti, carburante, attrezzi e altre spese vengono presi direttamente dai movimenti collegati al cantiere."))
        })
    }

    private fun metricLine(label: String, value: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(6), 0, dp(6))
        addView(TextView(this@JobsiteCostAnalyticsActivity).apply {
            text = label
            textSize = 12f
            setTextColor(Ge360Brand.TextSecondary)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(this@JobsiteCostAnalyticsActivity).apply {
            text = value
            textSize = 12.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ge360Brand.ForestDark)
        })
    }

    private fun methodText(value: String) = TextView(this).apply {
        text = value
        textSize = 11.2f
        setTextColor(Ge360Brand.TextSecondary)
        setPadding(0, dp(5), 0, dp(5))
    }

    private fun methodHighlight(value: String) = TextView(this).apply {
        text = value
        textSize = 10.8f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Ge360Brand.PrimaryDark)
        setPadding(dp(10), dp(7), dp(10), dp(7))
        background = brandRounded(fill = Ge360Brand.WarningSoft, stroke = Ge360Brand.WarningSoft, radiusDp = 11, strokeDp = 0)
    }

    private fun sectionTitle(title: String, subtitle: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(3), dp(18), dp(3), dp(8))
        addView(TextView(this@JobsiteCostAnalyticsActivity).apply {
            text = title
            textSize = 11f
            letterSpacing = 0.07f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ge360Brand.PrimaryDark)
        })
        addView(TextView(this@JobsiteCostAnalyticsActivity).apply {
            text = subtitle
            textSize = 11f
            setTextColor(Ge360Brand.TextSecondary)
            setPadding(0, dp(2), 0, 0)
        })
    }

    private fun openContext(target: Class<*>) {
        startActivity(Intent(this, target).apply {
            putExtra(Ge360Contracts.EXTRA_JOBSITE_ID, jobsite.ge360Id)
            putExtra(Ge360Contracts.EXTRA_JOBSITE_LOCAL_ID, jobsite.id)
            putExtra(Ge360Contracts.EXTRA_JOBSITE_CODE, jobsite.code)
        })
    }

    private fun resolveJobsite(): JobsiteRecord? {
        val uuid = intent.getStringExtra(Ge360Contracts.EXTRA_JOBSITE_ID).orEmpty()
        val localId = intent.getLongExtra(Ge360Contracts.EXTRA_JOBSITE_LOCAL_ID, 0L)
        return jobsiteStore.list().firstOrNull {
            (uuid.isNotBlank() && it.ge360Id == uuid) || (localId > 0L && it.id == localId)
        }
    }

    private fun category(record: FinanceRecord): String = record.category.trim().lowercase(Locale.ROOT)
    private fun money(value: Double): String = NumberFormat.getCurrencyInstance(Locale.ITALY).format(value)
    private fun percent(value: Double): String = String.format(Locale.ITALY, "%.1f%%", value)
    private fun formatMinutes(minutes: Long): String {
        val h = minutes / 60
        val m = minutes % 60
        return if (m == 0L) "$h h" else "$h h ${m}m"
    }
    private fun full(height: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class Analytics(
        val income: Double,
        val financeExpenses: Double,
        val totalCost: Double,
        val profit: Double,
        val marginPct: Double?,
        val internalLaborCost: Double,
        val externalLaborEstimate: Double,
        val materialFinanceCost: Double,
        val stockMaterialCost: Double,
        val attributedMaterialCost: Double,
        val externalFinanceCost: Double,
        val externalCost: Double,
        val personnelPaymentsExcluded: Double,
        val wasteCost: Double,
        val transportCost: Double,
        val toolsCost: Double,
        val otherCost: Double,
        val internalMinutes: Long,
        val allMinutes: Long,
        val personDays: Int,
        val financeMovements: Int,
        val materialMovements: Int,
        val estimatedMaterialRows: Int,
        val costSlices: List<DonutSlice>,
        val laborSlices: List<DonutSlice>,
        val materialSlices: List<DonutSlice>
    )

    companion object {
        private const val EXTERNAL_ROLE_PREFIX = "Esterno ·"
        private val PALETTE = intArrayOf(
            Color.rgb(226, 151, 20),
            Color.rgb(61, 79, 58),
            Color.rgb(43, 132, 198),
            Color.rgb(190, 91, 56),
            Color.rgb(88, 112, 126),
            Color.rgb(112, 92, 145),
            Color.rgb(117, 122, 106),
            Color.rgb(201, 124, 51)
        )
    }
}
