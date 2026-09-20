package it.edilmilan.ge360.home

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
import it.edilmilan.ge360.attrezzi.VanModeActivity
import it.edilmilan.ge360.attrezzi.VanPreparationPlan
import it.edilmilan.ge360.attrezzi.VanPreparationStore
import it.edilmilan.ge360.core.common.ui.Ge360Brand
import it.edilmilan.ge360.core.common.ui.applyGe360Card
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class VanPreparationDashboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val planStore = VanPreparationStore(context)
    private val formatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ITALY)

    init {
        orientation = VERTICAL
        refresh()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refresh()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == View.VISIBLE) refresh()
    }

    fun refresh() {
        removeAllViews()
        val plans = planStore.listUpcoming(limit = 6)
        if (plans.isEmpty()) {
            addView(emptyCard())
            return
        }

        plans.forEachIndexed { index, plan ->
            if (index > 0) addView(space(dp(8)))
            addView(planCard(plan))
        }
    }

    private fun planCard(plan: VanPreparationPlan) = MaterialCardView(context).apply {
        applyGe360Card(fill = Ge360Brand.Surface, stroke = Ge360Brand.Border, radiusDp = 18)
        isClickable = true
        isFocusable = true
        setOnClickListener {
            context.startActivity(
                Intent(context, VanModeActivity::class.java)
                    .putExtra(VanModeActivity.EXTRA_JOB_ID, plan.jobsiteId)
                    .putExtra(VanModeActivity.EXTRA_DATE, plan.date)
            )
        }

        addView(LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(15), dp(13), dp(15), dp(13))

            addView(LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL

                addView(TextView(context).apply {
                    text = "🚚 ${dateLabel(plan.date)}"
                    textSize = 12f
                    letterSpacing = 0.03f
                    setTextColor(Ge360Brand.PrimaryDark)
                    setTypeface(typeface, Typeface.BOLD)
                }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

                addView(TextView(context).apply {
                    text = "APRI  ›"
                    textSize = 11.5f
                    setTextColor(Ge360Brand.PrimaryDark)
                    setTypeface(typeface, Typeface.BOLD)
                })
            })

            addView(TextView(context).apply {
                text = if (plan.jobsiteCode.isBlank()) plan.jobsiteTitle else "${plan.jobsiteCode} · ${plan.jobsiteTitle}"
                textSize = 17f
                setTextColor(Ge360Brand.ForestDark)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(5), 0, 0)
            })

            addView(TextView(context).apply {
                text = buildString {
                    append("🔧 ${plan.toolCount}  ·  📦 ${plan.materialCount}")
                    if (plan.missingCount > 0) append("  ·  ⚠ ${plan.missingCount} da risolvere")
                }
                textSize = 12.5f
                setTextColor(if (plan.missingCount > 0) Ge360Brand.PrimaryDark else Ge360Brand.TextSecondary)
                setPadding(0, dp(5), 0, 0)
            })

            if (plan.note.isNotBlank()) {
                addView(TextView(context).apply {
                    text = "📝 ${plan.note}"
                    textSize = 12f
                    setTextColor(Ge360Brand.TextSecondary)
                    setPadding(0, dp(5), 0, 0)
                })
            }
        })
    }

    private fun emptyCard() = MaterialCardView(context).apply {
        applyGe360Card(fill = Ge360Brand.SurfaceWarm, stroke = Ge360Brand.BorderStrong, radiusDp = 18)
        isClickable = true
        setOnClickListener { context.startActivity(Intent(context, VanModeActivity::class.java)) }

        addView(LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(15), dp(14), dp(15), dp(14))
            addView(TextView(context).apply {
                text = "Nessuna preparazione programmata"
                textSize = 15f
                setTextColor(Ge360Brand.ForestDark)
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(context).apply {
                text = "Apri Prepara furgone e scegli Oggi, Domani oppure una data dal calendario."
                textSize = 12f
                setTextColor(Ge360Brand.TextSecondary)
                setPadding(0, dp(3), 0, 0)
            })
        })
    }

    private fun dateLabel(raw: String): String {
        val date = runCatching { LocalDate.parse(raw) }.getOrNull() ?: return raw
        val today = LocalDate.now()
        return when (date) {
            today -> "OGGI"
            today.plusDays(1) -> "DOMANI"
            else -> date.format(formatter).replaceFirstChar { it.uppercase() }
        }
    }

    private fun space(height: Int) = View(context).apply {
        layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
