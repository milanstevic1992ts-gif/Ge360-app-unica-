package it.edilmilan.ge360.scanner

import java.text.Normalizer

data class ParsedDocument(
    val type: String = "ALTRO",
    val documentDate: String? = null,
    val documentNumber: String? = null,
    val supplier: String? = null,
    val category: String? = null,
    val taxableAmount: Double? = null,
    val vatAmount: Double? = null,
    val totalAmount: Double? = null,
    val suggestedJob: String? = null,
    val confidence: Double = 0.0
)

object DocumentParser {
    private val dateRx = Regex("\\b([0-3]?\\d)[/.-]([01]?\\d)[/.-](20\\d{2}|\\d{2})\\b")
    private const val amountPattern = "(?:[0-9]{1,3}(?:[. ,][0-9]{3})+[,.][0-9]{2}|[0-9]+[,.][0-9]{2})"
    private val moneyRx = Regex("(?i)(?:€|eur)?\\s*($amountPattern)")

    // Totale finale della fattura: ha priorita' su imponibile, IVA, prezzo riga e scadenze.
    // Gestisce anche le abbreviazioni tipiche dei gestionali: "Tot. documento €".
    private val documentTotalRx = Regex(
        "(?i)(?:tot\\.?\\s*(?:documento|fattura)|totale\\s+(?:documento|fattura)|totale\\s+da\\s+pagare|importo\\s+totale)[^0-9€]{0,35}(?:€|eur)?\\s*($amountPattern)"
    )
    private val totalRx = Regex(
        "(?i)(?:da\\s+pagare|totale)[^0-9€]{0,30}(?:€|eur)?\\s*($amountPattern)"
    )

    // Numero documento esplicito: viene cercato prima della regola generica, per evitare
    // che diciture come "intestatario documento" vengano scambiate per il numero fattura.
    private val invoiceDocumentNumberRx = Regex(
        "(?i)(?:nr\\.?|n\\.?|numero)\\s*documento[^A-Z0-9]{0,18}([A-Z0-9][A-Z0-9/_-]{1,})"
    )
    private val numberRx = Regex("(?i)(?:fattura|invoice|documento|doc\\.?|n\\.?)[ #:]*(?:n\\.?[ #:]*)?([A-Z0-9/_-]{2,})")
    private val ddtNumberRx = Regex("(?i)(?:numero|n\\.?)[^0-9]{0,20}([0-9]{1,6}/[0-9]{2,4})")

    fun parse(text: String): ParsedDocument {
        val clean = text.replace('\u00A0', ' ').trim()
        val upper = stripAccents(clean).uppercase()
        val sem = isSemMuiesan(upper)

        // Il parser FIR e' separato dalle regole fatture/DDT: il codice stampato in testata
        // (es. ABCDE 000123 XY) e' l'identificativo canonico del formulario.
        val fir = FirOcrClassifier.detect(clean)
        val type = when {
            fir != null -> "FIR"
            "DOCUMENTO DI TRASPORTO" in upper || Regex("\\bDDT\\b").containsMatchIn(upper) -> "DDT"
            "FATTURA" in upper || "INVOICE" in upper -> "FATTURA"
            "SCONTRINO" in upper || "DOCUMENTO COMMERCIALE" in upper -> "SCONTRINO"
            "RICEVUTA" in upper -> "RICEVUTA"
            else -> "ALTRO"
        }

        val date = dateRx.find(clean)?.let { normalizeDate(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
        val number = when (type) {
            "FIR" -> fir?.number
            "DDT" -> ddtNumberRx.find(clean)?.groupValues?.getOrNull(1)
                ?: numberRx.find(clean)?.groupValues?.getOrNull(1)
            "FATTURA" -> invoiceDocumentNumberRx.find(clean)?.groupValues?.getOrNull(1)
                ?: numberRx.find(clean)?.groupValues?.getOrNull(1)
            else -> numberRx.find(clean)?.groupValues?.getOrNull(1)
        }

        // Sul FIR numeri come peso, EER, albo e targa non devono mai essere interpretati come importi.
        val total = if (type == "FIR") null else {
            documentTotalRx.findAll(clean).mapNotNull { it.groupValues.getOrNull(1)?.toMoney() }.lastOrNull()
                ?: totalRx.findAll(clean).mapNotNull { it.groupValues.getOrNull(1)?.toMoney() }.lastOrNull()
                ?: if (type == "SCONTRINO" || type == "RICEVUTA") {
                    moneyRx.findAll(clean).mapNotNull { it.groupValues.getOrNull(1)?.toMoney() }.maxOrNull()
                } else null
        }

        val taxable = if (type == "FIR") null else labeledMoney(
            clean,
            "tot\\.?\\s*imponibile|totale\\s+imponibile|imponibile"
        )
        val vat = if (type == "FIR") null else labeledMoney(
            clean,
            "tot\\.?\\s*(?:imposta|iva)|totale\\s+(?:imposta|iva)|imposta|iva"
        )
        val correctedTotal = when {
            total != null -> total
            taxable != null && vat != null -> roundMoney(taxable + vat)
            else -> null
        }

        // Le regole fornitori gia' affidabili per fatture/DDT rimangono invariate.
        // Un FIR contiene produttore/detentore/destinatario/trasportatore: non scegliamo uno di questi
        // come "fornitore" per evitare classificazioni sbagliate.
        val supplier = when {
            type == "FIR" -> null
            sem -> "SEM Società Edile Muiesan S.a.r.l. / MECSTORE"
            else -> detectSupplier(clean)
        }
        val category = when {
            type == "FIR" -> null
            sem || listOf("TECNOMAT", "BRICOMAN", "LEROY", "KERAKOLL", "MAPEI", "EDILIZ", "MECSTORE").any { it in upper } -> "Materiali"
            listOf("ENI", "Q8", "TAMOIL", "BENZINA", "DIESEL", "CARBURANTE").any { it in upper } -> "Carburante"
            else -> null
        }
        val suggestedJob = if (type == "FIR") null else extractDestination(clean)

        val score = if (type == "FIR") {
            ((fir?.confidence ?: 0.58) + if (date != null) 0.04 else 0.0).coerceAtMost(0.98)
        } else {
            var value = 0.20
            if (type != "ALTRO") value += .20
            if (date != null) value += .15
            if (correctedTotal != null) value += .20
            if (supplier != null) value += .10
            if (number != null) value += .05
            if (suggestedJob != null) value += .05
            if (sem) value += .05
            value.coerceAtMost(.95)
        }

        return ParsedDocument(
            type = type,
            documentDate = date,
            documentNumber = number,
            supplier = supplier,
            category = category,
            taxableAmount = taxable,
            vatAmount = vat,
            totalAmount = correctedTotal,
            suggestedJob = suggestedJob,
            confidence = score
        )
    }

    private fun labeledMoney(text: String, labels: String): Double? {
        val rx = Regex("(?i)(?:$labels)[^0-9]{0,45}(?:€|eur)?\\s*($amountPattern)")
        return rx.findAll(text).mapNotNull { it.groupValues.getOrNull(1)?.toMoney() }.lastOrNull()
    }

    private fun isSemMuiesan(upper: String): Boolean {
        val compact = upper.replace(Regex("[^A-Z0-9]"), "")
        return listOf("SEMMUIESAN", "SEMMUESAN", "SOCIETAEDILEMUIESAN", "MECSTORE", "MECST0RE").any { it in compact }
    }

    private fun extractDestination(text: String): String? {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
        val start = lines.indexOfFirst {
            val u = stripAccents(it).uppercase().replace('0', 'O')
            u.contains("DESTINAZIONE") && (u.contains("DIVERSA") || u.contains("CANTIERE"))
        }
        if (start < 0) return null
        return lines.drop(start + 1).take(3)
            .filterNot { line ->
                val u = stripAccents(line).uppercase()
                u.contains("P.IVA") || u.startsWith("TEL") || u.startsWith("DATA") || u.startsWith("NUMERO")
            }
            .joinToString(", ").takeIf { it.isNotBlank() }
    }

    private fun detectSupplier(text: String): String? = text.lineSequence()
        .map { it.trim() }
        .filter { it.length in 3..70 }
        .firstOrNull {
            it.any(Char::isLetter) &&
                !it.contains("fattura", true) &&
                !it.contains("scontrino", true) &&
                !it.contains("documento", true)
        }

    private fun normalizeDate(d: String, m: String, rawYear: String): String {
        val year = if (rawYear.length == 2) "20$rawYear" else rawYear
        return "$year-${m.padStart(2, '0')}-${d.padStart(2, '0')}"
    }

    private fun roundMoney(value: Double) = kotlin.math.round(value * 100.0) / 100.0

    private fun String.toMoney(): Double? {
        val clean = replace(" ", "")
        val pivot = maxOf(clean.lastIndexOf(','), clean.lastIndexOf('.'))
        if (pivot < 0) return clean.filter(Char::isDigit).toDoubleOrNull()
        val decimals = clean.length - pivot - 1
        return if (decimals == 2) {
            val integer = clean.substring(0, pivot).filter(Char::isDigit)
            val decimal = clean.substring(pivot + 1).filter(Char::isDigit)
            "$integer.$decimal".toDoubleOrNull()
        } else clean.filter(Char::isDigit).toDoubleOrNull()
    }

    private fun stripAccents(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
}
