package it.edilmilan.ge360.planimetrie

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class FloorPlansActivity : AppCompatActivity() {
    private lateinit var store: FloorPlanStore
    private lateinit var listRoot: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = FloorPlanStore(applicationContext)
        setContentView(buildUi())
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_CREATE_NEW, false)) {
            listRoot.post { if (!isFinishing && !isDestroyed) newPlanDialog() }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::listRoot.isInitialized) renderList()
    }

    override fun onDestroy() {
        store.close()
        super.onDestroy()
    }

    private fun buildUi(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(28))
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(MaterialButton(this@FloorPlansActivity).apply {
                text = "‹"
                setOnClickListener { finish() }
                minWidth = dp(48)
            }, LinearLayout.LayoutParams(dp(54), dp(48)))
            addView(LinearLayout(this@FloorPlansActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@FloorPlansActivity).apply {
                    text = "Rilievi / Planimetrie"
                    textSize = 24f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.parseColor("#202327"))
                })
                addView(TextView(this@FloorPlansActivity).apply {
                    text = "Il rilievo nasce indipendente. Poi lo colleghi a preventivo o cantiere."
                    textSize = 12f
                    setTextColor(Color.parseColor("#74797F"))
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        })

        root.addView(MaterialButton(this).apply {
            text = "+ Nuovo rilievo"
            setOnClickListener { newPlanDialog() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply { topMargin = dp(18) })

        listRoot = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listRoot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) })
        return ScrollView(this).apply { addView(root) }
    }

    private fun renderList() {
        listRoot.removeAllViews()
        val plans = store.list()
        if (plans.isEmpty()) {
            listRoot.addView(TextView(this).apply {
                text = "Nessun rilievo ancora. Puoi crearne uno anche prima di avere un cantiere."
                textSize = 14f
                setTextColor(Color.parseColor("#74797F"))
                setPadding(dp(4), dp(18), dp(4), dp(18))
            })
            return
        }
        plans.forEach { plan -> listRoot.addView(planCard(plan)) }
    }

    private fun planCard(plan: FloorPlanRecord): MaterialCardView {
        val roomCount = store.rooms(plan.ge360Id).size
        val linked = when {
            plan.jobsiteId.isNotBlank() && plan.estimateId.isNotBlank() -> "Cantiere + preventivo collegati"
            plan.jobsiteId.isNotBlank() -> "Cantiere collegato"
            plan.estimateId.isNotBlank() -> "Preventivo collegato"
            else -> "Non collegato"
        }
        return MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = Color.parseColor("#E4E6E8")
            setCardBackgroundColor(Color.WHITE)
            isClickable = true
            isFocusable = true
            setOnClickListener { openPlan(plan.ge360Id) }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
            addView(LinearLayout(this@FloorPlansActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(16))
                addView(TextView(this@FloorPlansActivity).apply {
                    text = plan.title
                    textSize = 17f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.parseColor("#202327"))
                })
                addView(TextView(this@FloorPlansActivity).apply {
                    text = listOf(plan.kind, "$roomCount ambienti", linked).joinToString(" · ")
                    textSize = 12f
                    setTextColor(Color.parseColor(if (linked == "Non collegato") "#B85700" else "#277A49"))
                    setPadding(0, dp(5), 0, 0)
                })
                if (plan.clientName.isNotBlank()) addView(TextView(this@FloorPlansActivity).apply {
                    text = "Cliente: ${plan.clientName}"
                    textSize = 12f
                    setTextColor(Color.parseColor("#666B71"))
                    setPadding(0, dp(4), 0, 0)
                })
                addView(TextView(this@FloorPlansActivity).apply {
                    text = "Aggiornato ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.ITALY).format(Date(plan.updatedAt))}"
                    textSize = 11f
                    setTextColor(Color.parseColor("#8A8E93"))
                    setPadding(0, dp(4), 0, 0)
                })
            })
        }
    }

    private fun newPlanDialog() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        val title = EditText(this).apply { hint = "Nome rilievo / indirizzo"; isSingleLine = true }
        val kind = Spinner(this).apply { adapter = ArrayAdapter(this@FloorPlansActivity, android.R.layout.simple_spinner_dropdown_item, FloorPlanKinds.ALL) }
        val client = EditText(this).apply { hint = "Cliente (facoltativo)"; isSingleLine = true }
        val notes = EditText(this).apply { hint = "Note sopralluogo"; minLines = 3 }
        root.addView(title, lp(52)); root.addView(kind, lp(52)); root.addView(client, lp(52)); root.addView(notes, lp(92))

        val dialog = AlertDialog.Builder(this)
            .setTitle("Nuovo rilievo")
            .setView(root)
            .setPositiveButton("Crea", null)
            .setNegativeButton("Annulla", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val clean = title.text.toString().trim()
                if (clean.isBlank()) { title.error = "Inserisci un nome"; return@setOnClickListener }
                val plan = FloorPlanRecord(
                    title = clean,
                    kind = kind.selectedItem.toString(),
                    clientName = client.text.toString().trim(),
                    notes = notes.text.toString().trim()
                )
                store.save(plan)
                dialog.dismiss()
                openPlan(plan.ge360Id)
            }
        }
        dialog.show()
    }

    private fun openPlan(id: String) {
        startActivity(Intent(this, FloorPlanEditorActivity::class.java).putExtra(FloorPlanEditorActivity.EXTRA_PLAN_ID, id))
    }

    private fun lp(h: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(h)).apply { bottomMargin = dp(8) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_CREATE_NEW = "create_new_floor_plan"
    }
}
