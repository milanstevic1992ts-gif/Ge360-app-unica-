package it.edilmilan.ge360.scanner

import java.text.Normalizer
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlin.math.abs

/**
 * Riconoscimento dedicato ai formulari rifiuti (FIR), anche multipagina.
 *
 * Il numero canonico del formulario e' il codice tipografico stampato sul modello,
 * normalmente in alto a destra, ad esempio: ABCDE 000123 XY.
 * Le pagine successive possono contenere il tagliando della pesa: l'OCR viene quindi
 * analizzato come un unico fascicolo e non come pagine/documenti indipendenti.
 */
data class FirOcrResult(
    val number: String?,
    val confidence: Double,
    val markerCount: Int,
    val entryDate: String? = null,
    val entryTime: String? = null,
    val entryWeightKg: Double? = null,
    val exitDate: String? = null,
    val exitTime: String? = null,
    val exitWeightKg: Double? = null,
    val totalEntryWeightKg: Double? = null,
    val netWeightKg: Double? = null
) {
    val hasWeighing: Boolean
        get() = listOf(entryWeightKg, exitWeightKg, totalEntryWeightKg, netWeightKg).any { it != null }
}

object FirOcrClassifier {
    private val spacedCodeRx = Regex(
        "(?i)\\b([A-Z](?:[\\s._-]*[A-Z]){4})[\\s._-]+([0-9OQILSB](?:[\\s._-]*[0-9OQILSB]){5})[\\s._-]+([A-Z](?:[\\s._-]*[A-Z]){1})\\b"
    )
    private val compactCodeRx = Regex("(?i)\\b([A-Z]{5})([0-9OQILSB]{6})([A-Z]{2})\\b")
    private val dateValue = "([0-3]?\\d[/.-][01]?\\d[/.-](?:20)?\\d{2})"
    private val timeValue = "([0-2]?\\d[:.]?[0-5]\\d)"
    private val weightValue = "([0-9]{1,7}(?:[.,][0-9]{1,3})?)"

    fun detect(text: String): FirOcrResult? {
        if (text.isBlank()) return null
        val upper = normalizeText(text)
        val markers = markerCount(upper)
        val number = extractNumber(text)

        val strongHeader = "FORMULARIO RIFIUTI" in upper || "FORMULARIO DI IDENTIFICAZIONE" in upper
        val continuationHeader = "INTEGRAZIONE FORMULARIO RIFIUTI" in upper || "2° FOGLIO" in upper || "2 FOGLIO" in upper
        val firFooter = Regex("\\bFIR[- ]?20\\d{2}[-/]?\\d{2}\\b").containsMatchIn(upper)
        val weighing = parseWeighing(text)
        val isFir = strongHeader || continuationHeader || firFooter || markers >= 3 ||
            (number != null && (markers >= 1 || weighing.hasWeighing))
        if (!isFir) return null

        var confidence = 0.58
        if (strongHeader || continuationHeader) confidence += 0.14
        if (number != null) confidence += 0.20
        if (markers >= 3) confidence += 0.04
        if (firFooter) confidence += 0.02
        if (weighing.hasWeighing) confidence += 0.05

        return FirOcrResult(
            number = number,
            confidence = confidence.coerceAtMost(0.99),
            markerCount = markers,
            entryDate = weighing.entryDate,
            entryTime = weighing.entryTime,
            entryWeightKg = weighing.entryWeightKg,
            exitDate = weighing.exitDate,
            exitTime = weighing.exitTime,
            exitWeightKg = weighing.exitWeightKg,
            totalEntryWeightKg = weighing.totalEntryWeightKg,
            netWeightKg = weighing.netWeightKg
        )
    }

    /**
     * Cerca il numero in tutte le pagine. Ogni foglio FIR normalmente ripete lo stesso codice;
     * il primo codice valido trovato nella testata ha priorita'.
     */
    fun extractNumber(text: String): String? {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
        lines.take(30).forEach { line -> matchCode(line)?.let { return it } }
        return matchCode(text)
    }

    private data class WeighingData(
        val entryDate: String? = null,
        val entryTime: String? = null,
        val entryWeightKg: Double? = null,
        val exitDate: String? = null,
        val exitTime: String? = null,
        val exitWeightKg: Double? = null,
        val totalEntryWeightKg: Double? = null,
        val netWeightKg: Double? = null
    ) {
        val hasWeighing: Boolean
            get() = listOf(entryWeightKg, exitWeightKg, totalEntryWeightKg, netWeightKg).any { it != null }
    }

    private fun parseWeighing(text: String): WeighingData {
        val normalized = text.replace('\u00A0', ' ')

        // I tagliandi pesa spesso vengono letti dall'OCR per colonne: prima le due etichette
        // sulla stessa riga, poi i due valori sulla riga successiva. Questi pattern hanno
        // priorita' sui fallback generici per evitare che "01" di una data diventi 1 kg.
        val entryRow = Regex(
            "(?is)DATA\\s+ENTRATA.{0,45}?ORA\\s+ENTRATA.{0,90}?$dateValue\\s+$timeValue"
        ).find(normalized)
        val exitRow = Regex(
            "(?is)DATA\\s+USCITA.{0,45}?PESO\\s+USCITA.{0,90}?$dateValue\\s+$weightValue\\s*(?:KG|K6|K9)"
        ).find(normalized)
        val exitTimeTotalRow = Regex(
            "(?is)ORA\\s+USCITA.{0,45}?PESO\\s+TOT(?:ALE)?[.\\s]*ENTRATA.{0,90}?$timeValue\\s+$weightValue\\s*(?:KG|K6|K9)"
        ).find(normalized)
        val explicitNetRow = Regex(
            "(?is)PESO\\s+NETT[O0].{0,70}?([0-9]{2,7}(?:[.,][0-9]{1,3})?)\\s*(?:KG|K6|K9)"
        ).find(normalized)

        val entryDate = entryRow?.groupValues?.getOrNull(1)?.let(::normalizeDate)
            ?: labeledDate(normalized, "DATA\\s+ENTRATA")
        val entryTime = entryRow?.groupValues?.getOrNull(2)?.let(::normalizeTime)
            ?: labeledTime(normalized, "ORA\\s+ENTRATA")

        val exitDate = exitRow?.groupValues?.getOrNull(1)?.let(::normalizeDate)
            ?: labeledDate(normalized, "DATA\\s+USCITA")
        val exitTime = exitTimeTotalRow?.groupValues?.getOrNull(1)?.let(::normalizeTime)
            ?: labeledTime(normalized, "ORA\\s+USCITA")

        val totalEntryFromRow = exitTimeTotalRow?.groupValues?.getOrNull(2)?.toWeight()
        val totalEntry = totalEntryFromRow
            ?: labeledWeight(normalized, "PESO\\s+TOT(?:ALE)?[.\\s]*ENTRATA")
        val entry = labeledWeight(normalized, "PESO\\s+ENTRATA") ?: totalEntry
        val exit = exitRow?.groupValues?.getOrNull(2)?.toWeight()
            ?: labeledWeight(normalized, "PESO\\s+USCITA")
        val explicitNet = explicitNetRow?.groupValues?.getOrNull(1)?.toWeight()
            ?: labeledWeight(normalized, "PESO\\s+NETT[O0]")
        val derivedNet = if (explicitNet == null && entry != null && exit != null) abs(entry - exit) else null

        return WeighingData(
            entryDate = entryDate,
            entryTime = entryTime,
            entryWeightKg = entry,
            exitDate = exitDate,
            exitTime = exitTime,
            exitWeightKg = exit,
            totalEntryWeightKg = totalEntry ?: entry,
            netWeightKg = explicitNet ?: derivedNet
        )
    }

    private fun labeledWeight(text: String, label: String): Double? {
        // Richiediamo l'unita' di misura: evita che una data o un orario subito dopo
        // l'etichetta venga interpretato come peso.
        val rx = Regex("(?is)(?:$label).{0,55}?$weightValue\\s*(?:KG|K6|K9)")
        return rx.findAll(text).mapNotNull { match ->
            match.groupValues.getOrNull(1)?.toWeight()
        }.lastOrNull()
    }

    private fun labeledDate(text: String, label: String): String? {
        val rx = Regex("(?is)(?:$label)[^0-9]{0,30}$dateValue")
        return rx.findAll(text).mapNotNull { normalizeDate(it.groupValues.getOrNull(1).orEmpty()) }.lastOrNull()
    }

    private fun labeledTime(text: String, label: String): String? {
        val rx = Regex("(?is)(?:$label)[^0-9]{0,24}$timeValue")
        return rx.findAll(text).mapNotNull { normalizeTime(it.groupValues.getOrNull(1).orEmpty()) }.lastOrNull()
    }

    private fun String.toWeight(): Double? = trim().replace(',', '.').toDoubleOrNull()

    private fun normalizeDate(raw: String): String? {
        val clean = raw.trim().replace('.', '/').replace('-', '/')
        val formats = listOf("d/M/yyyy", "dd/MM/yyyy", "d/M/yy", "dd/MM/yy")
        formats.forEach { pattern ->
            try {
                return LocalDate.parse(clean, DateTimeFormatter.ofPattern(pattern)).toString()
            } catch (_: DateTimeParseException) {
                // prova il formato successivo
            }
        }
        return null
    }

    private fun normalizeTime(raw: String): String? {
        val digits = raw.filter(Char::isDigit)
        val pair = when (digits.length) {
            3 -> digits.substring(0, 1) to digits.substring(1)
            4 -> digits.substring(0, 2) to digits.substring(2)
            else -> return null
        }
        val h = pair.first.toIntOrNull() ?: return null
        val m = pair.second.toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return "%02d:%02d".format(Locale.ITALY, h, m)
    }

    private fun matchCode(value: String): String? {
        val match = spacedCodeRx.find(value) ?: compactCodeRx.find(value) ?: return null
        val prefix = match.groupValues[1].filter(Char::isLetter).uppercase(Locale.ITALY)
        val digits = match.groupValues[2]
            .filter(Char::isLetterOrDigit)
            .uppercase(Locale.ITALY)
            .map(::normalizeOcrDigit)
            .joinToString("")
        val suffix = match.groupValues[3].filter(Char::isLetter).uppercase(Locale.ITALY)
        if (prefix.length != 5 || digits.length != 6 || !digits.all(Char::isDigit) || suffix.length != 2) return null
        return "$prefix $digits $suffix"
    }

    private fun normalizeOcrDigit(value: Char): Char = when (value) {
        'O', 'Q' -> '0'
        'I', 'L' -> '1'
        'S' -> '5'
        'B' -> '8'
        else -> value
    }

    private fun markerCount(upper: String): Int = listOf(
        "FORMULARIO RIFIUTI",
        "PRODUTTORE",
        "DESTINATARIO",
        "TRASPORTATORE",
        "CARATTERISTICHE DEL RIFIUTO",
        "CODICE EER",
        "RISERVATO AL DESTINATARIO",
        "TRASBORDO PARZIALE",
        "TRASBORDO TOTALE",
        "SECONDO DESTINATARIO"
    ).count { it in upper }

    private fun normalizeText(value: String): String = Normalizer
        .normalize(value.replace('\u00A0', ' '), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .uppercase(Locale.ITALY)
}
