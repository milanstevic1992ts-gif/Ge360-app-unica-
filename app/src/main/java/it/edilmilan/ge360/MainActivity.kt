package it.edilmilan.ge360

import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import com.google.android.material.floatingactionbutton.FloatingActionButton
import it.edilmilan.ge360.agenda.AgendaActivity
import it.edilmilan.ge360.agenda.AgendaEntry
import it.edilmilan.ge360.backup.BackupScheduler
import it.edilmilan.ge360.backup.JobsiteReplicaCoordinator
import it.edilmilan.ge360.cantieri.JobsitesActivity
import it.edilmilan.ge360.core.common.ui.BaseGeActivity
import it.edilmilan.ge360.core.common.ui.Ge360Brand
import it.edilmilan.ge360.core.common.ui.GeRadius
import it.edilmilan.ge360.core.common.ui.GeSpace
import it.edilmilan.ge360.core.common.ui.GeType
import it.edilmilan.ge360.core.common.ui.dp
import it.edilmilan.ge360.giornata.CollaboratorPaymentReminderScheduler
import it.edilmilan.ge360.home.HomeFragment
import it.edilmilan.ge360.planimetrie.FloorPlansActivity
import it.edilmilan.ge360.preventivi.EstimatesActivity
import it.edilmilan.ge360.scanner.ScannerActivity
import it.edilmilan.ge360.spese.ExpensesActivity
import it.edilmilan.ge360.storage.JobsitePersistenceCoordinator
import it.edilmilan.ge360.core.common.R as CommonR

class MainActivity : BaseGeActivity() {

    /** Una voce della barra in basso: icona sopra, etichetta sotto. */
    private class NavTab(val container: LinearLayout, val icon: ImageView, val label: TextView)

    private var currentDestination = R.id.nav_home
    private lateinit var navTabs: Map<Int, NavTab>
    private var servicesStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Il guscio iniziale resta indipendente da XML complessi e database.
        val root = buildBootstrapShell()
        setContentView(root)

        currentDestination = savedInstanceState?.getInt(STATE_DESTINATION, R.id.nav_home)
            ?.takeIf(::isKnownDestination)
            ?: R.id.nav_home

        val restoredFragment = supportFragmentManager.findFragmentById(R.id.mainContainer)
        if (savedInstanceState == null || restoredFragment == null) {
            showDestination(currentDestination, force = true)
        }
        updateNavSelection(currentDestination)

        // I servizi non essenziali partono solo dopo che la prima UI è stata mostrata.
        root.postDelayed({ startNonCriticalServices() }, 1200L)
    }

    private fun buildBootstrapShell(): FrameLayout {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Ge360Brand.Background)
        }

        val content = FrameLayout(this).apply { id = R.id.mainContainer }
        root.addView(
            content,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply { bottomMargin = dp(BAR_HEIGHT_DP) }
        )

        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(GeSpace.SM), dp(GeSpace.SM), dp(GeSpace.SM), dp(GeSpace.SM))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Ge360Brand.Surface)
                cornerRadii = floatArrayOf(
                    dp(GeRadius.PILL).toFloat(), dp(GeRadius.PILL).toFloat(),
                    dp(GeRadius.PILL).toFloat(), dp(GeRadius.PILL).toFloat(),
                    0f, 0f, 0f, 0f
                )
                setStroke(dp(1), Ge360Brand.Border)
            }
            elevation = dp(12).toFloat()
        }
        root.addView(
            bottomBar,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(BAR_HEIGHT_DP)
            ).apply { gravity = Gravity.BOTTOM }
        )

        val tabs = linkedMapOf(
            R.id.nav_home to createNavTab("Oggi", R.drawable.ic_nav_home),
            R.id.nav_cantieri to createNavTab("Cantieri", R.drawable.ic_nav_jobsites),
            R.id.nav_preventivi to createNavTab("Preventivi", R.drawable.ic_nav_estimates),
            R.id.nav_rilievi to createNavTab("Rilievi", R.drawable.ic_nav_floorplans),
            R.id.nav_altro to createNavTab("Altro", R.drawable.ic_nav_more)
        )

        tabs.forEach { (destination, tab) ->
            bottomBar.addView(
                tab.container,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    marginStart = dp(2)
                    marginEnd = dp(2)
                }
            )
            tab.container.setOnClickListener {
                val reselected = destination == currentDestination
                showDestination(destination, force = reselected)
            }
        }
        navTabs = tabs

        val quickAdd = FloatingActionButton(this).apply {
            setImageResource(CommonR.drawable.ic_ge360_add)
            contentDescription = "Aggiungi rapidamente"
            backgroundTintList = android.content.res.ColorStateList.valueOf(Ge360Brand.PrimaryDark)
            imageTintList = android.content.res.ColorStateList.valueOf(Ge360Brand.OnPrimary)
            setOnClickListener { showQuickAdd() }
        }
        root.addView(
            quickAdd,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                marginEnd = dp(GeSpace.LG)
                bottomMargin = dp(BAR_HEIGHT_DP + GeSpace.MD)
            }
        )

        // Su telefoni a gesti la barra finiva sotto la maniglia di sistema:
        // qui le lasciamo lo spazio che il sistema dichiara.
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            bottomBar.updateLayoutParams<FrameLayout.LayoutParams> { height = dp(BAR_HEIGHT_DP) + bottom }
            bottomBar.setPadding(
                dp(GeSpace.SM),
                dp(GeSpace.SM),
                dp(GeSpace.SM),
                dp(GeSpace.SM) + bottom
            )
            content.updateLayoutParams<FrameLayout.LayoutParams> { bottomMargin = dp(BAR_HEIGHT_DP) + bottom }
            quickAdd.updateLayoutParams<FrameLayout.LayoutParams> {
                bottomMargin = dp(BAR_HEIGHT_DP + GeSpace.MD) + bottom
            }
            insets
        }

        return root
    }

    private fun createNavTab(label: String, iconRes: Int): NavTab {
        val icon = ImageView(this).apply {
            setImageResource(iconRes)
            setColorFilter(Ge360Brand.TextSecondary)
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
        }
        val text = TextView(this).apply {
            this.text = label
            textSize = GeType.MICRO
            includeFontPadding = false
            setTextColor(Ge360Brand.TextSecondary)
            gravity = Gravity.CENTER
            setPadding(0, dp(GeSpace.XS), 0, 0)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            contentDescription = label
            setPadding(dp(2), dp(GeSpace.SM), dp(2), dp(GeSpace.SM))
            addView(icon)
            addView(text)
        }
        return NavTab(container, icon, text)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_DESTINATION, currentDestination)
        super.onSaveInstanceState(outState)
    }

    private fun showDestination(itemId: Int, force: Boolean = false) {
        if (!isKnownDestination(itemId)) return
        if (!force && itemId == currentDestination && supportFragmentManager.findFragmentById(R.id.mainContainer) != null) {
            updateNavSelection(itemId)
            return
        }

        val fragment = when (itemId) {
            R.id.nav_cantieri -> MainSectionFragment.newInstance(MainSectionFragment.SECTION_JOBSITES)
            R.id.nav_preventivi -> MainSectionFragment.newInstance(MainSectionFragment.SECTION_ESTIMATES)
            R.id.nav_rilievi -> MainSectionFragment.newInstance(MainSectionFragment.SECTION_FLOORPLANS)
            R.id.nav_altro -> MainSectionFragment.newInstance(MainSectionFragment.SECTION_MORE)
            // "Oggi" e' la schermata iniziale: come SafeHomeFragment non apre
            // database prima del primo frame, ma invece di elencare moduli
            // mostra la giornata e le quattro azioni quotidiane.
            else -> TodayFragment()
        }

        runCatching {
            if (supportFragmentManager.isStateSaved) return@runCatching false
            supportFragmentManager.beginTransaction()
                .replace(R.id.mainContainer, fragment)
                .commitNow()
            true
        }.onSuccess { committed ->
            if (committed) {
                currentDestination = itemId
                updateNavSelection(itemId)
            }
        }.onFailure { error ->
            Log.e("GE360_NAV", "Errore apertura destinazione $itemId", error)
            // Se e' la schermata iniziale a non aprirsi, si ripiega sulla Home
            // di bootstrap invece di mostrare un errore: l'app deve sempre
            // partire. Per tutto il resto resta la schermata di recupero.
            if (itemId == R.id.nav_home && showBootstrapHome()) {
                currentDestination = itemId
                updateNavSelection(itemId)
            } else {
                showNavigationRecovery(error)
            }
        }
    }

    /** Ultima rete di sicurezza all'avvio: Home leggera, nessun dato letto. */
    private fun showBootstrapHome(): Boolean = runCatching {
        if (supportFragmentManager.isStateSaved) return@runCatching false
        supportFragmentManager.beginTransaction()
            .replace(R.id.mainContainer, SafeHomeFragment())
            .commitNow()
        true
    }.onFailure { Log.e("GE360_NAV", "Home di bootstrap non disponibile", it) }
        .getOrDefault(false)

    fun openFullDashboard() {
        runCatching {
            supportFragmentManager.beginTransaction()
                .replace(R.id.mainContainer, HomeFragment())
                .commitNow()
        }.onFailure { error ->
            Log.e("GE360_HOME", "Dashboard completa non caricabile", error)
            showNavigationRecovery(error)
        }
    }

    private fun updateNavSelection(itemId: Int) {
        if (!::navTabs.isInitialized) return
        navTabs.forEach { (destination, tab) ->
            val selected = destination == itemId
            val tint = if (selected) Ge360Brand.Accent else Ge360Brand.TextSecondary

            tab.icon.setColorFilter(tint)
            tab.label.setTextColor(tint)
            tab.label.setTypeface(tab.label.typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
            tab.container.background = if (selected) {
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(GeRadius.CARD).toFloat()
                    setColor(Ge360Brand.PrimarySoft)
                }
            } else null
        }
    }

    private fun showNavigationRecovery(error: Throwable) {
        val container = findViewById<FrameLayout>(R.id.mainContainer) ?: return
        container.removeAllViews()
        container.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(28), dp(28), dp(28), dp(28))
                setBackgroundColor(Ge360Brand.Background)

                addView(TextView(this@MainActivity).apply {
                    text = "GE360 è avviato"
                    textSize = 24f
                    setTextColor(Ge360Brand.ForestDark)
                    gravity = Gravity.CENTER
                    setTypeface(typeface, Typeface.BOLD)
                })

                addView(TextView(this@MainActivity).apply {
                    text = "Una sezione non si è caricata. I dati non sono stati cancellati."
                    textSize = 14f
                    setTextColor(Ge360Brand.TextSecondary)
                    gravity = Gravity.CENTER
                    setPadding(0, dp(10), 0, dp(8))
                })

                addView(TextView(this@MainActivity).apply {
                    text = "${error::class.java.simpleName}: ${error.message.orEmpty().take(160)}"
                    textSize = 12f
                    setTextColor(Ge360Brand.TextSecondary)
                    gravity = Gravity.CENTER
                    setPadding(0, 0, 0, dp(18))
                })

                addView(TextView(this@MainActivity).apply {
                    text = "Torna alla Home"
                    textSize = 14f
                    setTextColor(Ge360Brand.OnPrimary)
                    gravity = Gravity.CENTER
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(dp(20), dp(13), dp(20), dp(13))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(14).toFloat()
                        setColor(Ge360Brand.PrimaryDark)
                    }
                    setOnClickListener { showDestination(R.id.nav_home, force = true) }
                })
            },
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun startNonCriticalServices() {
        if (servicesStarted || isFinishing || isDestroyed) return
        servicesStarted = true

        runCatching { BackupScheduler.schedule(applicationContext) }
            .onFailure { Log.e("GE360_STARTUP", "Backup scheduler non avviato", it) }

        runCatching { JobsitePersistenceCoordinator.install(application) }
            .onFailure { Log.e("GE360_STARTUP", "Persistenza cantieri non avviata", it) }

        runCatching { JobsiteReplicaCoordinator.install(application) }
            .onFailure { Log.e("GE360_STARTUP", "Replica cantieri non avviata", it) }

        // Promemoria economico leggero: pianifica soltanto un AlarmManager, nessun DB viene aperto qui.
        runCatching { CollaboratorPaymentReminderScheduler.schedule(applicationContext) }
            .onFailure { Log.e("GE360_STARTUP", "Promemoria pagamento collaboratore non pianificato", it) }
    }

    private fun isKnownDestination(itemId: Int): Boolean = itemId == R.id.nav_home ||
        itemId == R.id.nav_cantieri ||
        itemId == R.id.nav_preventivi ||
        itemId == R.id.nav_rilievi ||
        itemId == R.id.nav_altro

    private fun showQuickAdd() {
        val actions = arrayOf(
            "Cantiere",
            "Preventivo",
            "Nuovo rilievo",
            "Spesa / entrata",
            "Scansiona documento",
            "Nota veloce",
            "Attività",
            "Appuntamento"
        )
        runCatching {
            AlertDialog.Builder(this)
                .setTitle("Aggiungi rapidamente")
                .setItems(actions) { _, which ->
                    when (which) {
                        0 -> startActivity(Intent(this, JobsitesActivity::class.java))
                        1 -> startActivity(Intent(this, EstimatesActivity::class.java))
                        2 -> startActivity(Intent(this, FloorPlansActivity::class.java).putExtra(FloorPlansActivity.EXTRA_CREATE_NEW, true))
                        3 -> startActivity(Intent(this, ExpensesActivity::class.java))
                        4 -> startActivity(Intent(this, ScannerActivity::class.java))
                        5 -> startActivity(Intent(this, AgendaActivity::class.java).putExtra(AgendaActivity.EXTRA_CREATE_KIND, AgendaEntry.NOTE))
                        6 -> startActivity(Intent(this, AgendaActivity::class.java).putExtra(AgendaActivity.EXTRA_CREATE_KIND, AgendaEntry.TASK))
                        7 -> startActivity(Intent(this, AgendaActivity::class.java).putExtra(AgendaActivity.EXTRA_CREATE_KIND, AgendaEntry.EVENT))
                    }
                }
                .setNegativeButton("Chiudi", null)
                .show()
        }.onFailure { error ->
            Log.e("GE360_QUICK_ADD", "Quick add non disponibile", error)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun openFloorPlans(view: View) {
        showDestination(R.id.nav_rilievi, force = true)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (currentDestination != R.id.nav_home) {
            showDestination(R.id.nav_home, force = true)
        } else {
            super.onBackPressed()
        }
    }

    companion object {
        private const val STATE_DESTINATION = "main_destination"

        /** Altezza della barra in basso, senza contare gli inset di sistema. */
        private const val BAR_HEIGHT_DP = 72
    }
}
