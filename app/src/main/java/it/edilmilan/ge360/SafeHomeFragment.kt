package it.edilmilan.ge360

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import it.edilmilan.ge360.agenda.AgendaActivity
import it.edilmilan.ge360.agenda.AgendaEntry
import it.edilmilan.ge360.cantieri.JobsitesActivity
import it.edilmilan.ge360.core.common.ui.Ge360Brand
import it.edilmilan.ge360.jarvis.ProactiveAlertsActivity
import it.edilmilan.ge360.planimetrie.FloorPlansActivity
import it.edilmilan.ge360.preventivi.EstimatesActivity
import it.edilmilan.ge360.spese.ExpensesActivity

/**
 * Home di bootstrap: non apre SQLite, SharedPreferences di moduli o servizi di sync.
 * Serve a garantire che GE360 possa sempre avviarsi anche in presenza di dati legacy
 * o di un modulo operativo temporaneamente non compatibile.
 */
class SafeHomeFragment : Fragment() {

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext()
        val scroll = ScrollView(context).apply {
            setBackgroundColor(Ge360Brand.Background)
            isFillViewport = true
            clipToPadding = false
        }

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(108))
        }
        scroll.addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        body.addView(heroCard())

        val lastCrash = (requireActivity().application as? Ge360Application)
            ?.let { Ge360Application.lastCrash(it) }
            .orEmpty()
        if (lastCrash.isNotBlank()) {
            body.addView(space(12))
            body.addView(infoCard(
                title = "Avvio protetto attivo",
                subtitle = "GE360 ha registrato un arresto precedente. Questa Home resta leggera e non carica automaticamente il modulo che può averlo causato.",
                background = Ge360Brand.WarningSoft,
                accent = Ge360Brand.PrimaryDark
            ))
        }

        body.addView(space(12))
        body.addView(infoCard(
            title = "Pronto per lavorare",
            subtitle = "Sistema operativo. Apri una sezione: i dati restano invariati e vengono caricati solo quando servono.",
            background = Ge360Brand.ForestSoft,
            accent = Ge360Brand.Success
        ))

        body.addView(sectionTitle("ACCESSO RAPIDO"))
        body.addView(actionRow(
            actionCard("01", "Cantieri", "Lavori e centro cantiere") {
                startActivity(Intent(context, JobsitesActivity::class.java))
            },
            actionCard("02", "Preventivi", "Prezzario e lavorazioni") {
                startActivity(Intent(context, EstimatesActivity::class.java))
            }
        ))
        body.addView(space(10))
        body.addView(actionRow(
            actionCard("03", "Rilievi", "Planimetrie e misure") {
                startActivity(Intent(context, FloorPlansActivity::class.java))
            },
            actionCard("04", "Spese", "Entrate, uscite e costi") {
                startActivity(Intent(context, ExpensesActivity::class.java))
            }
        ))

        body.addView(space(10))
        body.addView(actionRow(
            actionCard("05", "Agenda", "Oggi, settimana e mese") {
                startActivity(Intent(context, AgendaActivity::class.java))
            },
            actionCard("06", "Nota veloce", "Scrivi senza scegliere cantiere") {
                startActivity(Intent(context, AgendaActivity::class.java)
                    .putExtra(AgendaActivity.EXTRA_CREATE_KIND, AgendaEntry.NOTE))
            }
        ))

        body.addView(sectionTitle("CONTROLLO OPERATIVO"))
        body.addView(proactiveCard {
            startActivity(Intent(context, ProactiveAlertsActivity::class.java))
        })
        body.addView(space(10))
        body.addView(dashboardCard {
            (activity as? MainActivity)?.openFullDashboard()
        })

        return scroll
    }

    private fun heroCard(): View = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(15), dp(16), dp(16))
        background = rounded(Ge360Brand.SurfaceWarm, Ge360Brand.BorderStrong, 22)

        addView(View(context).apply {
            background = rounded(Ge360Brand.Primary, Ge360Brand.Primary, 3, 0)
        }, LinearLayout.LayoutParams(dp(50), dp(5)).apply {
            bottomMargin = dp(12)
        })

        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(context).apply {
                setImageResource(R.drawable.edil_milan_stevic_logo)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                contentDescription = "Logo EDIL MILAN STEVIC"
            }, LinearLayout.LayoutParams(dp(86), dp(86)).apply {
                marginEnd = dp(13)
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = "EDIL MILAN STEVIC"
                    textSize = 11f
                    letterSpacing = 0.08f
                    setTextColor(Ge360Brand.PrimaryDark)
                    setTypeface(typeface, Typeface.BOLD)
                })
                addView(TextView(context).apply {
                    text = "Centro operativo"
                    textSize = 25f
                    setTextColor(Ge360Brand.ForestDark)
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, dp(2), 0, 0)
                })
                addView(TextView(context).apply {
                    text = "GE360 · Restauri & Costruzioni"
                    textSize = 12.5f
                    setTextColor(Ge360Brand.TextSecondary)
                    setPadding(0, dp(4), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })
    }

    private fun infoCard(title: String, subtitle: String, background: Int, accent: Int): View =
        LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(13), dp(14), dp(13))
            this.background = rounded(background, Ge360Brand.Border, 17)

            addView(View(context).apply {
                this.background = rounded(accent, accent, 4, 0)
            }, LinearLayout.LayoutParams(dp(5), dp(48)).apply {
                marginEnd = dp(12)
            })

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = title
                    textSize = 15.5f
                    setTextColor(Ge360Brand.ForestDark)
                    setTypeface(typeface, Typeface.BOLD)
                })
                addView(TextView(context).apply {
                    text = subtitle
                    textSize = 12.3f
                    setTextColor(Ge360Brand.TextSecondary)
                    setPadding(0, dp(3), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

    private fun actionRow(left: View, right: View): View = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.HORIZONTAL
        weightSum = 2f
        addView(left, LinearLayout.LayoutParams(0, dp(116), 1f).apply { marginEnd = dp(5) })
        addView(right, LinearLayout.LayoutParams(0, dp(116), 1f).apply { marginStart = dp(5) })
    }

    private fun actionCard(number: String, title: String, subtitle: String, action: () -> Unit): View =
        LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(Ge360Brand.Surface, Ge360Brand.Border, 18)
            elevation = 0f
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }

            addView(TextView(context).apply {
                text = number
                textSize = 10.5f
                setTextColor(Ge360Brand.PrimaryDark)
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(context).apply {
                text = title
                textSize = 17f
                setTextColor(Ge360Brand.ForestDark)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(5), 0, 0)
            })
            addView(TextView(context).apply {
                text = subtitle
                textSize = 11.5f
                setTextColor(Ge360Brand.TextSecondary)
                setPadding(0, dp(3), 0, 0)
                maxLines = 2
            })
        }

    private fun proactiveCard(action: () -> Unit): View = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = rounded(Ge360Brand.WarningSoft, Ge360Brand.PrimaryDark, 20)
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }

        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = "JARVIS PROATTIVO"
                textSize = 10.5f
                letterSpacing = 0.07f
                setTextColor(Ge360Brand.PrimaryDark)
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(context).apply {
                text = "Avvisi operativi"
                textSize = 18f
                setTextColor(Ge360Brand.ForestDark)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(3), 0, 0)
            })
            addView(TextView(context).apply {
                text = "Scadenze · materiali · attività · anomalie costi"
                textSize = 11.5f
                setTextColor(Ge360Brand.TextSecondary)
                setPadding(0, dp(3), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        addView(TextView(context).apply {
            text = "!"
            textSize = 24f
            setTextColor(Ge360Brand.PrimaryDark)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(38), dp(48)))
    }

    private fun dashboardCard(action: () -> Unit): View = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(15), dp(16), dp(15))
        background = rounded(Ge360Brand.Forest, Ge360Brand.Forest, 20)
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }

        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = "DASHBOARD COMPLETA"
                textSize = 10.5f
                letterSpacing = 0.07f
                setTextColor(Ge360Brand.Primary)
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(context).apply {
                text = "Apri il quadro generale"
                textSize = 18f
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(3), 0, 0)
            })
            addView(TextView(context).apply {
                text = "KPI · cantieri · economia · furgone · priorità"
                textSize = 11.5f
                setTextColor(Color.parseColor("#DDE5DA"))
                setPadding(0, dp(3), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        addView(TextView(context).apply {
            text = "›"
            textSize = 30f
            setTextColor(Ge360Brand.Primary)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(38), dp(48)))
    }

    private fun sectionTitle(textValue: String): View = TextView(requireContext()).apply {
        text = textValue
        textSize = 11f
        letterSpacing = 0.08f
        setTextColor(Ge360Brand.PrimaryDark)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(2), dp(24), 0, dp(9))
    }

    private fun space(height: Int): View = View(requireContext()).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(height))
    }

    private fun rounded(fill: Int, stroke: Int, radiusDp: Int = 18, strokeDp: Int = 1) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp).toFloat()
        setColor(fill)
        if (strokeDp > 0) setStroke(dp(strokeDp), stroke)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
