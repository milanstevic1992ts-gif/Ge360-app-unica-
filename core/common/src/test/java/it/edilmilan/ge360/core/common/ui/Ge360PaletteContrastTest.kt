package it.edilmilan.ge360.core.common.ui
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
/**
 * Contrasto della palette, nei due temi.
 *
 * Perché esiste: su 36 schermate non migrate nessuno può verificare a occhio
 * che ogni testo resti leggibile in chiaro e in scuro. Questo test lo fa sui
 * **file di risorse veri**, non su una copia dei valori, quindi una modifica
 * alla palette che rende illeggibile una combinazione fa fallire la build.
 *
 * Le soglie sono quelle delle linee guida di accessibilità: 4,5:1 per il testo
 * normale, 3:1 per elementi grandi e indicatori di stato. Non è pignoleria da
 * manuale: l'app si usa in cantiere, al sole, e un contrasto basso lì non si
 * legge affatto.
 *
 * Al primo giro questo test ha trovato tre problemi reali nella palette
 * ereditata: il testo bianco sul bottone primario stava a 3,79 (chiaro) e 3,91
 * (scuro), e l'ocra delle etichette di sezione a 3,48–3,79. I valori sono stati
 * scuriti mantenendo la tinta del marchio (37,8°).
 */
class Ge360PaletteContrastTest {
    private data class Pair(
        val foreground: String,
        val background: String,
        val minimum: Double,
        val label: String
    )
    private val pairs = listOf(
        Pair("ge360_text", "ge360_surface", 4.5, "testo su card"),
        Pair("ge360_text", "ge360_background", 4.5, "testo su fondo schermata"),
        Pair("ge360_text", "ge360_surface_warm", 4.5, "testo su card calda"),
        Pair("ge360_text_secondary", "ge360_surface", 4.5, "testo secondario su card"),
        Pair("ge360_text_secondary", "ge360_background", 4.5, "testo secondario su fondo"),
        Pair("ge360_forest_dark", "ge360_surface", 4.5, "titolo su card"),
        Pair("ge360_forest_dark", "ge360_background", 4.5, "titolo su fondo"),
        Pair("ge360_forest", "ge360_surface", 4.5, "verde del marchio su card"),
        Pair("ge360_forest", "ge360_forest_soft", 4.5, "testo del bottone secondario"),
        Pair("ge360_accent", "ge360_surface", 4.5, "etichetta ocra su card"),
        Pair("ge360_accent", "ge360_background", 4.5, "etichetta ocra su fondo"),
        Pair("ge360_accent", "ge360_primary_soft", 4.5, "ocra su pillola chiara"),
        Pair("ge360_on_primary", "ge360_primary_dark", 4.5, "testo sul bottone primario"),
        Pair("ge360_warning", "ge360_surface", 4.5, "avviso su card"),
        Pair("ge360_success", "ge360_surface", 3.0, "stato positivo"),
        Pair("ge360_success", "ge360_success_soft", 3.0, "stato positivo su badge"),
        Pair("ge360_danger", "ge360_surface", 3.0, "stato negativo"),
        Pair("ge360_danger", "ge360_danger_soft", 3.0, "stato negativo su badge"),
        Pair("ge360_warning", "ge360_warning_soft", 3.0, "avviso su badge"),
        Pair("ge360_technical_blue", "ge360_surface", 3.0, "blu tecnico"),
        Pair("ge360_border", "ge360_surface", 1.2, "bordo visibile su card"),
        Pair("ge360_border_strong", "ge360_surface", 1.3, "bordo marcato su card")
    )
    @Test
    fun `la palette chiara rispetta i contrasti minimi`() {
        verify("values", "chiaro")
    }
    @Test
    fun `la palette scura rispetta i contrasti minimi`() {
        verify("values-night", "scuro")
    }
    @Test
    fun `i due temi definiscono gli stessi token`() {
        val light = palette("values").keys
        val night = palette("values-night").keys
        val onlyLight = light - night
        val onlyNight = night - light
        assertTrue(
            "token presenti solo nel tema chiaro: $onlyLight · solo nello scuro: $onlyNight",
            onlyLight.isEmpty() && onlyNight.isEmpty()
        )
    }
    @Test
    fun `il fallback chiaro di Ge360Brand non puo divergere dalle risorse`() {
        // Ge360Brand.LIGHT_FALLBACK e' la rete di sicurezza se sync() non parte:
        // se diverge dalle risorse, l'app senza sync mostra colori che nessuno
        // ha verificato.
        val resources = palette("values")
        val source = sourceFile("src/main/java/it/edilmilan/ge360/core/common/ui/Ge360Brand.kt")
            .readText()
        val mapping = mapOf(
            "primary" to "ge360_primary",
            "primaryDark" to "ge360_primary_dark",
            "accent" to "ge360_accent",
            "forest" to "ge360_forest",
            "background" to "ge360_background",
            "text" to "ge360_text",
            "textSecondary" to "ge360_text_secondary",
            "success" to "ge360_success",
            "warning" to "ge360_warning",
            "danger" to "ge360_danger",
            "technicalBlue" to "ge360_technical_blue"
        )
        val problems = mutableListOf<String>()
        mapping.forEach { (property, token) ->
            val match = Regex("$property = Color\\.rgb\\((\\d+), (\\d+), (\\d+)\\)").find(source)
            if (match == null) {
                problems += "non trovo '$property' nel fallback di Ge360Brand"
                return@forEach
            }
            val fromSource = Triple(
                match.groupValues[1].toInt(),
                match.groupValues[2].toInt(),
                match.groupValues[3].toInt()
            )
            val fromResources = resources[token]
            if (fromSource != fromResources) {
                problems += "$property vale $fromSource nel fallback ma $token vale $fromResources"
            }
        }
        assertTrue(
            "fallback e risorse divergono:\n" + problems.joinToString("\n"),
            problems.isEmpty()
        )
    }
    // -----------------------------------------------------------------------
    private fun verify(folder: String, themeName: String) {
        val colors = palette(folder)
        val problems = mutableListOf<String>()
        pairs.forEach { pair ->
            val fg = colors[pair.foreground]
            val bg = colors[pair.background]
            if (fg == null || bg == null) {
                problems += "token mancante: ${pair.foreground} o ${pair.background}"
                return@forEach
            }
            val value = contrast(fg, bg)
            if (value < pair.minimum) {
                problems += "%s: %.2f, serve almeno %.1f (%s su %s)".format(
                    pair.label, value, pair.minimum, pair.foreground, pair.background
                )
            }
        }
        assertTrue(
            "contrasti insufficienti nel tema $themeName:\n" + problems.joinToString("\n"),
            problems.isEmpty()
        )
    }
    private fun palette(folder: String): Map<String, Triple<Int, Int, Int>> {
        val file = sourceFile("src/main/res/$folder/ge360_palette.xml")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("color")
        return (0 until nodes.length).associate { index ->
            val element = nodes.item(index) as Element
            element.getAttribute("name") to parseHex(element.textContent.trim())
        }
    }
    /**
     * Risolve un percorso del modulo sia che i test girino dalla cartella del
     * modulo sia dalla radice del progetto.
     */
    private fun sourceFile(relative: String): File {
        val candidates = listOf(
            File(relative),
            File("core/common/$relative"),
            File("../../$relative")
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("non trovo $relative da ${File(".").absolutePath}")
    }
    private fun parseHex(raw: String): Triple<Int, Int, Int> {
        val hex = raw.removePrefix("#").let { if (it.length == 8) it.substring(2) else it }
        require(hex.length == 6) { "colore non riconosciuto: $raw" }
        return Triple(
            hex.substring(0, 2).toInt(16),
            hex.substring(2, 4).toInt(16),
            hex.substring(4, 6).toInt(16)
        )
    }
    private fun channel(value: Int): Double {
        val c = value / 255.0
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
    private fun relativeLuminance(rgb: Triple<Int, Int, Int>): Double =
        0.2126 * channel(rgb.first) + 0.7152 * channel(rgb.second) + 0.0722 * channel(rgb.third)
    private fun contrast(a: Triple<Int, Int, Int>, b: Triple<Int, Int, Int>): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }
}
