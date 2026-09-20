package it.edilmilan.ge360.cantieri

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

class JobsiteProgressActivity : AppCompatActivity() {
    private lateinit var store: JobsiteStore
    private lateinit var job: JobsiteRecord
    private val currency = NumberFormat.getCurrencyInstance(Locale.ITALY)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = JobsiteStore(applicationContext)
        val jobId = intent.getStringExtra(EXTRA_JOB_ID).orEmpty()
        val found = store.getByGe360Id(jobId)
        if (found == null) {
            Toast.makeText(this, "Cantiere non trovato", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        job = found
        store.ensureProgressTemplate(job)
        render()
    }

    override fun onDestroy() {
        if (::store.isInitialized) store.close()
        super.onDestroy()
    }

    private fun render() {
        if (isFinishing) return
        setContentView(buildScreen())
    }

    private fun buildScreen(): View {
        val summary = store.progressSummary(job.ge360Id)
        val items = summary?.items.orEmpty()
        val milestones = store.milestones(job.ge360Id)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#F6F7F9"))
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(34))
        }
        scroll.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(header())
        root.addView(text(job.displayTitle(), 18f, "#17191C", bold = true).apply { setPadding(0, dp(13), 0, 0) })
        root.addView(text(buildString {
            append(job.status)
            if (job.workType.isNotBlank()) append(" · ${job.workType}")
            if (job.code.isNotBlank()) append(" · ${job.code}")
        }, 12f, "#6D7278").apply { setPadding(0, dp(2), 0, 0) })

        root.addView(sectionTitle("Avanzamento reale", "Le fasi hanno un peso: GE360 calcola il completamento reale del cantiere.", 24))
        root.addView(progressSummaryCard(summary))

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(22), 0, dp(9))
            addView(text("Fasi lavoro", 21f, "#15171A", bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(actionText("+ Fase") { editPhase(null) })
        })

        items.forEachIndexed { index, item ->
            if (index > 0) root.addView(space(8))
            root.addView(phaseCard(item))
        }

        root.addView(sectionTitle("SAL e acconti", "Programmazione incassi. Un SAL non diventa cassa finché l'incasso non viene registrato in Spese.", 27))
        root.addView(actionCard("+ NUOVO SAL / ACCONTO", "Importo, scadenza e stato della richiesta") { editMilestone(null) })

        if (milestones.isEmpty()) {
            root.addView(space(9))
            root.addView(emptyCard("Nessun SAL pianificato. Le statistiche possono comunque suggerire un acconto in base all'avanzamento reale."))
        } else {
            milestones.forEach { row ->
                root.addView(space(8))
                root.addView(milestoneCard(row))
            }
        }

        root.addView(infoCard())
        return scroll
    }

    private fun header(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(this@JobsiteProgressActivity).apply {
            text = "‹"
            textSize = 34f
            setTextColor(Color.parseColor("#17191C"))
            gravity = Gravity.CENTER
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(42), dp(46)))
        addView(LinearLayout(this@JobsiteProgressActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(text("Avanzamento & SAL", 27f, "#15171A", bold = true))
            addView(text("GE360 · controllo cantiere", 11f, "#B85700", bold = true))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(this@JobsiteProgressActivity).apply {
            text = "↻"
            textSize = 21f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#596067"))
            background = rounded("#FFFFFF", 13f, "#E1E4E7")
            setOnClickListener { render() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
    }

    private fun progressSummaryCard(summary: JobsiteProgressSummary?): View {
        val percent = summary?.percent ?: 0
        val done = summary?.items?.count { it.progressPercent >= 100 } ?: 0
        val total = summary?.items?.size ?: 0
        return MaterialCardView(this).apply {
            radius = dp(19).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = Color.parseColor("#22262B")
            setCardBackgroundColor(Color.parseColor("#17191C"))
            addView(LinearLayout(this@JobsiteProgressActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(17), dp(16), dp(17), dp(17))
                addView(text("AVANZAMENTO CANTIERE", 10f, "#ADB3BA", bold = true))
                addView(text("$percent%", 34f, "#FFFFFF", bold = true).apply { setPadding(0, dp(4), 0, 0) })
                addView(progressBar(percent, "#72C18C", dark = true).apply { setPadding(0, dp(8), 0, 0) })
                addView(text("$done/$total fasi completate · pesi totali ${summary?.totalWeight ?: 0}", 11f, "#B7BDC4").apply {
                    setPadding(0, dp(9), 0, 0)
                })
                addView(text("Usa questa percentuale come avanzamento fisico reale, non come percentuale di tempo trascorso.", 11f, "#90979F").apply {
                    setPadding(0, dp(5), 0, 0)
                })
            })
        }
    }

    private fun phaseCard(item: JobsiteProgressItem): View = whiteCard().apply {
        addView(LinearLayout(this@JobsiteProgressActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15))
            addView(LinearLayout(this@JobsiteProgressActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(LinearLayout(this@JobsiteProgressActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text(item.title, 15f, "#17191C", bold = true))
                    addView(text("Peso ${item.weight}%", 10f, "#72787E", bold = true))
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(text("${item.progressPercent}%", 20f, if (item.progressPercent >= 100) "#277A49" else "#B85700", bold = true))
                addView(actionText("  ✎") { editPhase(item) })
            })
            addView(progressBar(item.progressPercent, if (item.progressPercent >= 100) "#3E8B5B" else "#B85700").apply {
                setPadding(0, dp(9), 0, dp(7))
            })
            if (item.note.isNotBlank()) addView(text(item.note, 11f, "#62686E").apply { setPadding(0, 0, 0, dp(7)) })
            addView(quickProgress(item))
        })
    }

    private fun quickProgress(item: JobsiteProgressItem): View = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        addView(LinearLayout(this@JobsiteProgressActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf(0, 25, 50, 75, 100).forEachIndexed { index, value ->
                if (index > 0) addView(spaceHorizontal(6))
                addView(TextView(this@JobsiteProgressActivity).apply {
                    text = "$value%"
                    textSize = 11f
                    setTypeface(typeface, Typeface.BOLD)
                    val selected = item.progressPercent == value
                    setTextColor(Color.parseColor(if (selected) "#FFFFFF" else "#555C62"))
                    background = rounded(if (selected) "#17191C" else "#F2F4F5", 12f, if (selected) "#17191C" else "#E1E4E7")
                    setPadding(dp(11), dp(7), dp(11), dp(7))
                    setOnClickListener {
                        store.setProgress(item.id, value)
                        render()
                    }
                })
            }
        })
    }

    private fun editPhase(existing: JobsiteProgressItem?) {
        val titleInput = EditText(this).apply {
            hint = "Nome fase"
            setText(existing?.title.orEmpty())
        }
        val weightInput = EditText(this).apply {
            hint = "Peso (es. 15)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText((existing?.weight ?: 10).toString())
        }
        val noteInput = EditText(this).apply {
            hint = "Nota facoltativa"
            setText(existing?.note.orEmpty())
        }
        val box = dialogBox(titleInput, weightInput, noteInput)
        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Nuova fase" else "Modifica fase")
            .setView(box)
            .setPositiveButton("Salva") { _, _ ->
                val name = titleInput.text.toString().trim()
                if (name.isBlank()) return@setPositiveButton toast("Inserisci il nome della fase")
                val rows = store.progressItems(job.ge360Id)
                store.saveProgressItem(
                    (existing ?: JobsiteProgressItem(jobsiteId = job.ge360Id, sortOrder = rows.size)).copy(
                        title = name,
                        phase = name,
                        weight = weightInput.text.toString().toIntOrNull()?.coerceIn(1, 100) ?: 10,
                        note = noteInput.text.toString().trim()
                    )
                )
                render()
            }
            .setNegativeButton("Annulla", null)
            .apply {
                if (existing != null) setNeutralButton("Elimina") { _, _ ->
                    store.deleteProgressItem(existing.id)
                    render()
                }
            }
            .show()
    }

    private fun milestoneCard(row: JobsiteMilestone): View {
        val style = when (row.status) {
            JobsiteMilestone.STATUS_TO_REQUEST -> "#A85A00" to "#FFF4E8"
            JobsiteMilestone.STATUS_REQUESTED -> "#315F8A" to "#EEF5FB"
            JobsiteMilestone.STATUS_CLOSED -> "#277A49" to "#EDF8F1"
            JobsiteMilestone.STATUS_CANCELLED -> "#777D82" to "#F2F4F5"
            else -> "#5B6167" to "#F1F3F5"
        }
        return whiteCard().apply {
            addView(LinearLayout(this@JobsiteProgressActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(15))
                addView(LinearLayout(this@JobsiteProgressActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(LinearLayout(this@JobsiteProgressActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(text(row.title, 15f, "#17191C", bold = true))
                        addView(text(if (row.dueDate.isBlank()) "Nessuna scadenza" else "Scadenza ${row.dueDate}", 10f, "#72787E"))
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(text(currency.format(row.amount), 18f, "#17191C", bold = true))
                })
                addView(LinearLayout(this@JobsiteProgressActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(10), 0, 0)
                    addView(TextView(this@JobsiteProgressActivity).apply {
                        text = row.status.uppercase(Locale.ITALY)
                        textSize = 9f
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(Color.parseColor(style.first))
                        background = rounded(style.second, 9f)
                        setPadding(dp(8), dp(5), dp(8), dp(5))
                    })
                    addView(View(this@JobsiteProgressActivity), LinearLayout.LayoutParams(0, 1, 1f))
                    addView(actionText("Modifica") { editMilestone(row) })
                })
                if (row.note.isNotBlank()) addView(text(row.note, 11f, "#62686E").apply { setPadding(0, dp(8), 0, 0) })
                addView(statusRow(row).apply { setPadding(0, dp(10), 0, 0) })
            })
        }
    }

    private fun statusRow(row: JobsiteMilestone): View = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        addView(LinearLayout(this@JobsiteProgressActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf(
                JobsiteMilestone.STATUS_PLANNED,
                JobsiteMilestone.STATUS_TO_REQUEST,
                JobsiteMilestone.STATUS_REQUESTED,
                JobsiteMilestone.STATUS_CLOSED
            ).forEachIndexed { index, status ->
                if (index > 0) addView(spaceHorizontal(6))
                addView(TextView(this@JobsiteProgressActivity).apply {
                    text = status
                    textSize = 10f
                    val selected = row.status == status
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.parseColor(if (selected) "#FFFFFF" else "#555C62"))
                    background = rounded(if (selected) "#17191C" else "#F2F4F5", 12f)
                    setPadding(dp(10), dp(7), dp(10), dp(7))
                    setOnClickListener { store.setMilestoneStatus(row.id, status); render() }
                })
            }
        })
    }

    private fun editMilestone(existing: JobsiteMilestone?) {
        val titleInput = EditText(this).apply { hint = "Titolo (es. SAL impianti)"; setText(existing?.title ?: "SAL / Acconto") }
        val amountInput = EditText(this).apply {
            hint = "Importo €"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(existing?.amount?.takeIf { it > 0 }?.toString().orEmpty())
        }
        val dueInput = EditText(this).apply { hint = "Scadenza YYYY-MM-DD"; setText(existing?.dueDate.orEmpty()) }
        val noteInput = EditText(this).apply { hint = "Nota facoltativa"; setText(existing?.note.orEmpty()) }
        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Nuovo SAL / Acconto" else "Modifica SAL / Acconto")
            .setView(dialogBox(titleInput, amountInput, dueInput, noteInput))
            .setPositiveButton("Salva") { _, _ ->
                val amount = amountInput.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0
                if (amount <= 0.0) return@setPositiveButton toast("Inserisci un importo valido")
                store.saveMilestone(
                    (existing ?: JobsiteMilestone(jobsiteId = job.ge360Id)).copy(
                        title = titleInput.text.toString().trim().ifBlank { "SAL / Acconto" },
                        amount = amount,
                        dueDate = dueInput.text.toString().trim(),
                        note = noteInput.text.toString().trim()
                    )
                )
                render()
            }
            .setNegativeButton("Annulla", null)
            .apply {
                if (existing != null) setNeutralButton("Elimina") { _, _ -> store.deleteMilestone(existing.id); render() }
            }
            .show()
    }

    private fun infoCard(): View = MaterialCardView(this).apply {
        radius = dp(15).toFloat()
        cardElevation = 0f
        strokeWidth = dp(1)
        strokeColor = Color.parseColor("#E3D8C9")
        setCardBackgroundColor(Color.parseColor("#FFF8EF"))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(22) }
        addView(text(
            "Regola GE360: avanzamento = lavoro fisicamente eseguito. SAL = richiesta/pianificazione. Incassato = solo il movimento registrato nel modulo Spese. Tenere distinti questi tre dati rende affidabili forecast e cassa.",
            11f, "#684A2B", bold = true
        ).apply { setPadding(dp(14), dp(12), dp(14), dp(12)) })
    }

    private fun sectionTitle(title: String, subtitle: String, top: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(top), 0, dp(10))
        addView(text(title, 21f, "#15171A", bold = true))
        addView(text(subtitle, 11f, "#70757B").apply { setPadding(0, dp(2), 0, 0) })
    }

    private fun actionCard(title: String, subtitle: String, action: () -> Unit): View = whiteCard(clickable = true, action = action).apply {
        addView(LinearLayout(this@JobsiteProgressActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(15), dp(13), dp(13), dp(13))
            addView(LinearLayout(this@JobsiteProgressActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(title, 12f, "#B85700", bold = true))
                addView(text(subtitle, 11f, "#5F656B").apply { setPadding(0, dp(2), 0, 0) })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(text("→", 24f, "#17191C", bold = true))
        })
    }

    private fun emptyCard(message: String): View = whiteCard().apply {
        addView(text(message, 12f, "#666C72").apply { setPadding(dp(14), dp(12), dp(14), dp(12)) })
    }

    private fun whiteCard(clickable: Boolean = false, action: (() -> Unit)? = null): MaterialCardView = MaterialCardView(this).apply {
        radius = dp(16).toFloat()
        cardElevation = 0f
        strokeWidth = dp(1)
        strokeColor = Color.parseColor("#E1E4E7")
        setCardBackgroundColor(Color.WHITE)
        isClickable = clickable
        isFocusable = clickable
        if (action != null) setOnClickListener { action() }
    }

    private fun progressBar(percent: Int, color: String, dark: Boolean = false): View {
        val value = percent.coerceIn(0, 100)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val fill = value.coerceAtLeast(1)
            addView(View(this@JobsiteProgressActivity).apply { background = rounded(color, 4f) }, LinearLayout.LayoutParams(0, dp(7), fill.toFloat()))
            addView(View(this@JobsiteProgressActivity).apply { background = rounded(if (dark) "#353A40" else "#E9ECEF", 4f) }, LinearLayout.LayoutParams(0, dp(7), (100 - fill).coerceAtLeast(0).toFloat()))
        }
    }

    private fun dialogBox(vararg views: View): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(4), dp(20), 0)
        views.forEach { addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5) }) }
    }

    private fun actionText(label: String, action: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 12f
        setTextColor(Color.parseColor("#B85700"))
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(7), dp(6), dp(7), dp(6))
        setOnClickListener { action() }
    }

    private fun text(value: String, size: Float, color: String, bold: Boolean = false): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(Color.parseColor(color))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun rounded(fill: String, radiusDp: Float, stroke: String? = null): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp.toInt()).toFloat()
        setColor(Color.parseColor(fill))
        if (stroke != null) setStroke(dp(1), Color.parseColor(stroke))
    }

    private fun space(height: Int): View = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(height)) }
    private fun spaceHorizontal(width: Int): View = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(width), 1) }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    companion object {
        const val EXTRA_JOB_ID = "jobsite_ge360_id"
    }
}
