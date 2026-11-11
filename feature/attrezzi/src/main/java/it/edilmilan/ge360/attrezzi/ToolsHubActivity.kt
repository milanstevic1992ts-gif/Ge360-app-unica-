package it.edilmilan.ge360.attrezzi

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import it.edilmilan.ge360.core.common.ui.buildScreenHeaderWithSubtitle
import it.edilmilan.ge360.scanner.ToolWarrantyActivity

class ToolsHubActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val catalogStore = ToolsStore(this)
        StandardWorkCatalog.ensureCatalog(catalogStore)
        catalogStore.close()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(18))
        }
        root.addView(buildScreenHeaderWithSubtitle(
            context = this,
            title = "Attrezzi & materiali",
            subtitle = "Prepara, calcola e conserva ciò che serve al lavoro",
            onBack = { finish() }
        ))

        root.addView(modeCard(
            title = "🧠  RICORDA DA PORTARE / COMPRARE",
            subtitle = "Scrivi al volo cosa manca: attrezzo da prendere, materiale da caricare o da acquistare. La voce entra subito nella checklist del cantiere.",
            action = "＋ AGGIUNGI PROMEMORIA"
        ) { ResourceReminderDialog.show(this) })

        root.addView(modeCard(
            title = "📦  MATERIALI 2.0",
            subtitle = "Magazzino reale con giacenze, scorte minime, fornitori, costi, ubicazioni e movimenti. Le richieste dei cantieri entrano automaticamente nella lista da acquistare.",
            action = "APRI MATERIALI"
        ) { startActivity(Intent(this, MaterialsActivity::class.java)) })

        root.addView(modeCard(
            title = "🧮  CALCOLO MATERIALI",
            subtitle = "Calcola quantità per murature, pittura, cartongesso, autolivellante, massetto, piastrelle, rasatura e intonaco. Il cantiere è facoltativo.",
            action = "APRI CALCOLATORE"
        ) { startActivity(Intent(this, MaterialCalculatorActivity::class.java)) })

        root.addView(modeCard(
            title = "📋  PREPARA LAVORO",
            subtitle = "Scegli il cantiere, un pacchetto oppure singoli attrezzi e materiali. Puoi anche condividere la lista su WhatsApp.",
            action = "CREA / MODIFICA LISTA"
        ) { startActivity(Intent(this, WorkPreparationActivity::class.java)) })

        root.addView(modeCard(
            title = "✅  CHECKLIST DA PRENDERE",
            subtitle = "Mentre prepari il furgone premi PRESO ✓. La voce sparisce subito. Nessun rientro e nessuno stato permanente.",
            action = "APRI CHECKLIST"
        ) { startActivity(Intent(this, VanModeActivity::class.java)) })

        root.addView(modeCard(
            title = "🔧  Gestione catalogo",
            subtitle = "Aggiungi o modifica attrezzi, materiali, quantità e altre voci del catalogo.",
            action = "APRI GESTIONE"
        ) { startActivity(Intent(this, ToolsActivity::class.java)) })

        root.addView(modeCard(
            title = "🧾  SCONTRINI GARANZIA",
            subtitle = "Scansiona lo scontrino, indica nome e tipo di attrezzo e conserva la data d'acquisto insieme al PDF.",
            action = "SCANSIONA / APRI ARCHIVIO"
        ) { startActivity(Intent(this, ToolWarrantyActivity::class.java)) })

        root.addView(TextView(this).apply {
            text = "Materiali 2.0 usa le richieste già generate dai cantieri e dal Calcolo materiali; il magazzino e i movimenti vengono inclusi nello stesso backup Attrezzi di GE360."
            textSize = 12f
            setPadding(dp(6), dp(12), dp(6), 0)
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun modeCard(title: String, subtitle: String, action: String, open: () -> Unit) = MaterialCardView(this).apply {
        radius = dp(18).toFloat()
        cardElevation = dp(1).toFloat()
        setContentPadding(dp(16), dp(16), dp(16), dp(16))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14)
        }
        addView(LinearLayout(this@ToolsHubActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@ToolsHubActivity).apply {
                text = title
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(this@ToolsHubActivity).apply {
                text = subtitle
                textSize = 14f
                setPadding(0, dp(7), 0, dp(12))
            })
            addView(MaterialButton(this@ToolsHubActivity).apply {
                text = action
                minHeight = dp(54)
                setOnClickListener { open() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)))
        })
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
