package it.edilmilan.ge360

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.card.MaterialCardView
import it.edilmilan.ge360.agenda.AgendaActivity
import it.edilmilan.ge360.attrezzi.VanModeActivity
import it.edilmilan.ge360.agenda.AgendaDayPlan
import it.edilmilan.ge360.agenda.AgendaDaySnapshot
import it.edilmilan.ge360.agenda.AgendaSourceProjection
import it.edilmilan.ge360.agenda.AgendaStore
import it.edilmilan.ge360.agenda.OperationalDayActivity
import it.edilmilan.ge360.agenda.WeeklySitePlannerActivity
import it.edilmilan.ge360.core.common.ui.Ge360Brand
import it.edilmilan.ge360.core.common.ui.GeRadius
import it.edilmilan.ge360.core.common.ui.GeSpace
import it.edilmilan.ge360.core.common.ui.GeTouch
import it.edilmilan.ge360.core.common.ui.GeType
import it.edilmilan.ge360.core.common.ui.applyGe360Card
import it.edilmilan.ge360.core.common.ui.applyGe360Subtitle
import it.edilmilan.ge360.core.common.ui.applyGe360Title
import it.edilmilan.ge360.core.common.ui.brandRounded
import it.edilmilan.ge360.core.common.ui.dp
import it.edilmilan.ge360.core.common.ui.ge360Eyebrow
import it.edilmilan.ge360.core.common.ui.geLoad
import it.edilmilan.ge360.galleria.GalleryActivity
import it.edilmilan.ge360.giornata.DailyWorkActivity
import it.edilmilan.ge360.jarvis.ProactiveAlertsActivity
import it.edilmilan.ge360.scanner.ScannerActivity
import it.edilmilan.ge360.spese.ExpensesActivity
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * "Oggi": la schermata che si apre per prima.
 *
 * Sostituisce la Home a catalogo. Quella elencava otto moduli numerati, di cui
 * tre erano gia' nella barra in basso, e non diceva niente della giornata: per
 * registrare le ore o scattare una foto servivano tre o quattro tocchi dentro
 * "Altro".
 *
 * Qui il lavoro quotidiano non e' piu' un posto in cui entrare ma un'azione:
 * ore, foto, spesa e scansione sono quattro bersagli grandi, un tocco ciascuno.
 * Cantieri, Preventivi e Rilievi non ci sono di proposito: stanno nella barra,
 * a un tocco, e ripeterli qui era rumore.
 *
 * **Vincolo di avvio.** Come la vecchia `SafeHomeFragment`, niente su questa
 * schermata apre SQLite prima del primo frame: titolo, azioni e collegamenti si
 * disegnano senza leggere nulla. Il piano del giorno arriva dopo, in background
 * e dentro runCatching per ogni fonte, quindi un modulo rotto o dati legacy
 * lasciano la schermata perfettamente usabile invece di impedire l'avvio.
 *
 * Il piano del giorno riusa [AgendaDayPlan], non lo riscrive: la vista completa
 * resta [OperationalDayActivity].
 */
class TodayFragment : Fragment() {

    private lateinit var dayTitle: TextView
    private lateinit var dayBody: TextView
    private var dayAction: (() -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext()

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                context.dp(GeSpace.LG),
                context.dp(GeSpace.LG),
                context.dp(GeSpace.LG),
                context.dp(104)
            )
        }

        body.addView(buildHeader(context))
        body.addView(buildDayCard(context))
        body.addView(context.ge360Eyebrow("Azioni di oggi").apply {
            setPadding(
                context.dp(GeSpace.XS),
                context.dp(GeSpace.XL),
                context.dp(GeSpace.XS),
                context.dp(GeSpace.SM)
            )
        })
        body.addView(buildActions(context))
        body.addView(context.ge360Eyebrow("Apri").apply {
            setPadding(
                context.dp(GeSpace.XS),
                context.dp(GeSpace.XL),
                context.dp(GeSpace.XS),
                context.dp(GeSpace.SM)
            )
        })
        body.addView(buildShortcuts(context))

        return ScrollView(context).apply {
            setBackgroundColor(Ge360Brand.Background)
            isFillViewport = true
            clipToPadding = false
            addView(body)
        }
    }

    override fun onResume() {
        super.onResume()
        loadDay()
    }

    // -----------------------------------------------------------------------
    // Testata
    // -----------------------------------------------------------------------

    private fun buildHeader(context: Context): View {
        val today = LocalDate.now()
        val formatted = runCatching {
            today.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ITALIAN))
                .replaceFirstChar { it.uppercase() }
        }.getOrDefault(today.toString())

        val titles = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(context.ge360Eyebrow("EDIL MILAN STEVIC"))
            addView(TextView(context).apply {
                text = "Oggi"
                applyGe360Title(GeType.TITLE)
            })
            addView(TextView(context).apply {
                text = formatted
                applyGe360Subtitle(GeType.BODY_SM)
            })
        }

        val dashboard = TextView(context).apply {
            text = "Quadro\ncompleto"
            textSize = GeType.CAPTION
            gravity = Gravity.CENTER
            setTextColor(Ge360Brand.Accent)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(
                context.dp(GeSpace.MD),
                context.dp(GeSpace.SM),
                context.dp(GeSpace.MD),
                context.dp(GeSpace.SM)
            )
            background = context.brandRounded(
                fill = Ge360Brand.PrimarySoft,
                stroke = Ge360Brand.PrimarySoft,
                radiusDp = GeRadius.CONTROL,
                strokeDp = 0
            )
            isClickable = true
            isFocusable = true
            contentDescription = "Apri la dashboard completa"
            setOnClickListener { (activity as? MainActivity)?.openFullDashboard() }
        }

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                titles,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(dashboard)
        }
    }

    // -----------------------------------------------------------------------
    // Piano del giorno
    // -----------------------------------------------------------------------

    private fun buildDayCard(context: Context): View {
        dayTitle = TextView(context).apply {
            text = "Piano del giorno"
            applyGe360Title(GeType.H2)
        }
        dayBody = TextView(context).apply {
            text = "Lettura in corso…"
            applyGe360Subtitle(GeType.BODY_SM)
            setPadding(0, context.dp(GeSpace.SM), 0, 0)
        }

        return MaterialCardView(context).apply {
            applyGe360Card()
            isClickable = true
            isFocusable = true
            setOnClickListener {
                dayAction?.invoke()
                    ?: startActivity(Intent(context, OperationalDayActivity::class.java))
            }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(
                    context.dp(GeSpace.LG),
                    context.dp(GeSpace.LG),
                    context.dp(GeSpace.LG),
                    context.dp(GeSpace.LG)
                )
                addView(dayTitle)
                addView(dayBody)
            })
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = context.dp(GeSpace.LG) }
        }
    }

    /**
     * Legge il piano del giorno in background.
     *
     * Le due fonti sono protette separatamente: se l'agenda e' leggibile ma la
     * proiezione delle scadenze no, la carta mostra comunque gli impegni che si
     * riescono a leggere.
     */
    private fun loadDay() {
        val host = activity ?: return
        val appContext = host.applicationContext

        host.geLoad({
            val entries = runCatching { AgendaStore(appContext).list() }.getOrDefault(emptyList())
            val external = runCatching { AgendaSourceProjection.read(appContext).deadlines }
                .getOrDefault(emptyList())
            val partial = entries.isEmpty() && external.isEmpty()
            AgendaDayPlan.build(entries, external) to partial
        }) { outcome ->
            if (!isAdded || view == null) return@geLoad
            outcome
                .onSuccess { (snapshot, empty) -> renderDay(snapshot, empty) }
                .onFailure { renderDayUnavailable() }
        }
    }

    private fun renderDay(snapshot: AgendaDaySnapshot, empty: Boolean) {
        val overdue = snapshot.overdue.size
        val today = snapshot.today.size

        dayTitle.text = when {
            overdue > 0 -> "$overdue in arretrato"
            today > 0 -> "$today ${if (today == 1) "cosa" else "cose"} da fare oggi"
            empty -> "Giornata libera"
            else -> "Niente in scadenza oggi"
        }
        dayTitle.setTextColor(
            if (overdue > 0) Ge360Brand.Danger else Ge360Brand.ForestDark
        )

        val lines = (snapshot.overdue + snapshot.today).take(3).map { item ->
            "· ${item.title}" + if (item.description.isBlank()) "" else " — ${item.description}"
        }
        val rest = (overdue + today - lines.size).coerceAtLeast(0)
        val week = snapshot.upcoming.size

        dayBody.text = buildString {
            if (lines.isEmpty()) {
                append(
                    if (week > 0) {
                        "Nessuna scadenza per oggi. $week questa settimana."
                    } else {
                        "Nessuna scadenza registrata. Tocca per aprire la giornata operativa."
                    }
                )
            } else {
                append(lines.joinToString("\n"))
                if (rest > 0) append("\n+ altre $rest")
                if (week > 0) append("\n$week questa settimana")
            }
        }

        dayAction = {
            startActivity(Intent(requireContext(), OperationalDayActivity::class.java))
        }
    }

    private fun renderDayUnavailable() {
        dayTitle.text = "Piano del giorno non disponibile"
        dayTitle.setTextColor(Ge360Brand.ForestDark)
        dayBody.text = "Non riesco a leggere agenda e scadenze. Il resto dell'app funziona: tocca per riprovare dalla giornata operativa."
        dayAction = { loadDay() }
    }

    // -----------------------------------------------------------------------
    // Le quattro azioni quotidiane
    // -----------------------------------------------------------------------

    private fun buildActions(context: Context): View {
        val first = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(
                actionTile(context, "Ore", "Rapportino di oggi", R.drawable.ic_today_hours) {
                    startActivity(Intent(context, DailyWorkActivity::class.java))
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(
                actionTile(context, "Foto", "Galleria cantieri", R.drawable.ic_today_photo) {
                    startActivity(Intent(context, GalleryActivity::class.java))
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = context.dp(GeSpace.SM)
                }
            )
        }

        val second = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(
                actionTile(context, "Spesa", "Segna un movimento", R.drawable.ic_today_expense) {
                    startActivity(
                        Intent(context, ExpensesActivity::class.java)
                            .putExtra(ExpensesActivity.EXTRA_NEW_EXPENSE, true)
                    )
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(
                actionTile(context, "Scansiona", "Fattura o bolla", R.drawable.ic_today_scan) {
                    startActivity(
                        Intent(context, ScannerActivity::class.java)
                            .putExtra(ScannerActivity.EXTRA_SCAN_NOW, true)
                    )
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = context.dp(GeSpace.SM)
                }
            )
        }

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(first)
            addView(
                second,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = context.dp(GeSpace.SM) }
            )
        }
    }

    /** Bersaglio grande: si centra anche con una mano sola e con i guanti. */
    private fun actionTile(
        context: Context,
        label: String,
        hint: String,
        iconRes: Int,
        onClick: () -> Unit
    ): View = MaterialCardView(context).apply {
        applyGe360Card(fill = Ge360Brand.SurfaceWarm)
        isClickable = true
        isFocusable = true
        contentDescription = "$label · $hint"
        setOnClickListener { onClick() }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            minimumHeight = context.dp(112)
            setPadding(
                context.dp(GeSpace.MD),
                context.dp(GeSpace.LG),
                context.dp(GeSpace.MD),
                context.dp(GeSpace.LG)
            )
            addView(ImageView(context).apply {
                setImageResource(iconRes)
                setColorFilter(Ge360Brand.Accent)
                layoutParams = LinearLayout.LayoutParams(context.dp(30), context.dp(30))
            })
            addView(TextView(context).apply {
                text = label
                applyGe360Title(GeType.H3)
                gravity = Gravity.CENTER
                setPadding(0, context.dp(GeSpace.SM), 0, 0)
            })
            addView(TextView(context).apply {
                text = hint
                applyGe360Subtitle(GeType.MICRO)
                gravity = Gravity.CENTER
            })
        })
    }

    // -----------------------------------------------------------------------
    // Collegamenti secondari
    // -----------------------------------------------------------------------

    private fun buildShortcuts(context: Context): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL

            fun add(title: String, hint: String, target: Class<*>) {
                addView(
                    shortcutRow(context, title, hint) {
                        startActivity(Intent(context, target))
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { if (childCount > 0) topMargin = context.dp(GeSpace.SM) }
                )
            }

            add("La mia giornata", "Urgenze, impegni e attività", OperationalDayActivity::class.java)
            add("Agenda", "Oggi, settimana e mese", AgendaActivity::class.java)
            add("Checklist furgone", "Attrezzi da portare · materiali da comprare o caricare", VanModeActivity::class.java)
            add("Settimana cantieri", "Squadra, sopralluoghi, materiali", WeeklySitePlannerActivity::class.java)
            add("Avvisi Jarvis", "Scadenze, materiali, anomalie costi", ProactiveAlertsActivity::class.java)
        }

    private fun shortcutRow(
        context: Context,
        title: String,
        hint: String,
        onClick: () -> Unit
    ): View = MaterialCardView(context).apply {
        applyGe360Card()
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = context.dp(GeTouch.COMFORTABLE)
            setPadding(
                context.dp(GeSpace.LG),
                context.dp(GeSpace.MD),
                context.dp(GeSpace.LG),
                context.dp(GeSpace.MD)
            )
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = title
                    applyGe360Title(GeType.H3)
                })
                addView(TextView(context).apply {
                    text = hint
                    applyGe360Subtitle(GeType.CAPTION)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(context).apply {
                text = "›"
                textSize = GeType.TITLE
                setTextColor(Ge360Brand.TextSecondary)
            })
        })
    }
}
