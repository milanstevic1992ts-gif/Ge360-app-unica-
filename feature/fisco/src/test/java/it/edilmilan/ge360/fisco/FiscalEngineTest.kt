package it.edilmilan.ge360.fisco

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contratti del motore fiscale, parametri 2026.
 *
 * Questo modulo calcola imposte e contributi: un errore di una cifra qui non fa
 * crashare niente, produce solo un numero sbagliato su cui si prendono
 * decisioni. Prima di questi test non era coperto da nulla.
 *
 * I valori attesi non sono stati copiati dall'implementazione: derivano dalle
 * regole, e quelle dei contributi sono state verificate sulla circolare INPS
 * 14/2026 (minimale 18.808, massimale 122.295, aliquota 24% con maggiorazione
 * al 25% oltre 56.224, fisso IVS 4.513,92, maternita' 7,44). Gli scaglioni
 * IRPEF 2026 sono 23% fino a 28.000, 33% da 28.000 a 50.000, 43% oltre.
 *
 * Dove serve, il calcolo a mano e' scritto nel commento, cosi' il test resta
 * verificabile senza rieseguire il codice.
 */
class FiscalEngineTest {

    private val cents = 0.005

    // -----------------------------------------------------------------------
    // INPS artigiani
    // -----------------------------------------------------------------------

    @Test
    fun `il contributo fisso e dovuto anche senza reddito`() {
        // Il fisso sul minimale non dipende dal reddito prodotto.
        // 4.513,92 + 7,44 = 4.521,36
        val result = FiscalEngine.calculateInps(0.0, reduction35 = false)

        assertEquals(4_513.92, result.fixed, cents)
        assertEquals(0.0, result.excess, cents)
        assertEquals(4_521.36, result.total, cents)
    }

    @Test
    fun `al minimale esatto non c'e ancora eccedenza`() {
        val result = FiscalEngine.calculateInps(18_808.0, reduction35 = false)

        assertEquals(0.0, result.excess, cents)
        assertEquals(4_521.36, result.total, cents)
    }

    @Test
    fun `sopra il minimale l'eccedenza e al 24 percento`() {
        // (30.000 - 18.808) x 24% = 11.192 x 0,24 = 2.686,08
        // totale = 4.513,92 + 2.686,08 + 7,44 = 7.207,44
        val result = FiscalEngine.calculateInps(30_000.0, reduction35 = false)

        assertEquals(2_686.08, result.excess, cents)
        assertEquals(7_207.44, result.total, cents)
    }

    @Test
    fun `oltre la soglia si applica la maggiorazione di un punto`() {
        // primo scaglione: (56.224 - 18.808) x 24% = 37.416 x 0,24 = 8.979,84
        // secondo:         (60.000 - 56.224) x 25% =  3.776 x 0,25 =   944,00
        // eccedenza = 9.923,84 · totale = 14.445,20
        val result = FiscalEngine.calculateInps(60_000.0, reduction35 = false)

        assertEquals(9_923.84, result.excess, cents)
        assertEquals(14_445.20, result.total, cents)
    }

    @Test
    fun `alla soglia esatta non scatta ancora il 25 percento`() {
        // 56.224 e' il confine: solo il primo scaglione.
        val result = FiscalEngine.calculateInps(56_224.0, reduction35 = false)

        assertEquals(8_979.84, result.excess, cents)
        assertEquals(13_501.20, result.total, cents)
    }

    @Test
    fun `il reddito viene fermato al massimale`() {
        // base limitata a 122.295, non 200.000:
        // 8.979,84 + (122.295 - 56.224) x 25% = 8.979,84 + 16.517,75 = 25.497,59
        val result = FiscalEngine.calculateInps(200_000.0, reduction35 = false)

        assertEquals(122_295.0, result.base, cents)
        assertEquals(25_497.59, result.excess, cents)
        assertEquals(30_018.95, result.total, cents)
    }

    @Test
    fun `la riduzione 35 percento non tocca la maternita`() {
        // fisso     4.513,92 x 0,65 = 2.934,05
        // eccedenza 2.686,08 x 0,65 = 1.745,95
        // maternita' resta 7,44 -> totale 4.687,44
        val result = FiscalEngine.calculateInps(30_000.0, reduction35 = true)

        assertEquals(2_934.05, result.fixed, cents)
        assertEquals(1_745.95, result.excess, cents)
        assertEquals(7.44, result.maternity, cents)
        assertEquals(4_687.44, result.total, cents)
        assertEquals(0.35, result.reductionApplied, 0.0001)
    }

    @Test
    fun `i mesi accreditati non superano dodici`() {
        val result = FiscalEngine.calculateInps(200_000.0, reduction35 = false)

        assertTrue(
            "mesi accreditati stimati fuori scala: ${result.creditedMonthsEstimate}",
            result.creditedMonthsEstimate <= 12.0
        )
    }

    // -----------------------------------------------------------------------
    // Regime ordinario: IRPEF, detrazioni, addizionali
    // -----------------------------------------------------------------------

    /**
     * Ricavi puliti, nessuna spesa, nessun cliente privato: cosi' il reddito
     * d'impresa coincide con i ricavi e la catena IRPEF e' verificabile a mano.
     */
    private fun ordinaryOn(revenue: Double, settings: FiscalSettings = plainSettings()) =
        FiscalEngine.compare(
            FiscalInput(
                year = FiscalParams2026.YEAR,
                revenue = revenue,
                cashExpenses = 0.0,
                movements = 1,
                expenses = emptyList(),
                settings = settings
            )
        ).ordinary

    private fun plainSettings() = FiscalSettings(
        privateClientShare = 0.0,
        stampInvoices = 0,
        otherDeductions = 0.0,
        otherTaxCredits = 0.0
    )

    @Test
    fun `ordinario su cinquantamila segue scaglioni e addizionali FVG`() {
        // reddito d'impresa 50.000 · INPS 12.007,44 · imponibile 37.992,56
        // IRPEF: 28.000 x 23% = 6.440,00
        //      + 9.992,56 x 33% = 3.297,54   -> 9.737,54
        // detrazione lavoro autonomo: 0 (reddito a 50.000)
        // addizionale regionale FVG 1,23% (imponibile oltre 15.000) = 467,31
        // addizionale comunale 0,8% (imponibile oltre 12.500)       = 303,94
        val result = ordinaryOn(50_000.0)

        assertEquals(12_007.44, result.inps.total, cents)
        assertEquals(37_992.56, result.fiscalIncome, cents)
        assertEquals(9_737.54, result.tax, cents)
        assertEquals(467.31, result.regionalTax, cents)
        assertEquals(303.94, result.municipalTax, cents)
    }

    @Test
    fun `addizionale regionale FVG passa da 0,70 a 1,23 percento sui 15000`() {
        // Il Friuli Venezia Giulia applica lo 0,70% fino a 15.000 e l'1,23%
        // sopra, sull'intero imponibile e non solo sull'eccedenza.
        //
        // ricavi 19.700 -> imponibile 14.964,56 (sotto): x 0,70% = 104,75
        // ricavi 20.000 -> imponibile 15.192,56 (sopra): x 1,23% = 186,87
        val below = ordinaryOn(19_700.0)
        val above = ordinaryOn(20_000.0)

        assertEquals(14_964.56, below.fiscalIncome, cents)
        assertEquals(104.75, below.regionalTax, cents)

        assertEquals(15_192.56, above.fiscalIncome, cents)
        assertEquals(186.87, above.regionalTax, cents)
    }

    @Test
    fun `addizionale comunale solo oltre la soglia di esenzione`() {
        // Trieste: niente addizionale fino a 12.500, e sopra si applica
        // all'intero imponibile.
        //
        // ricavi 17.000 -> imponibile 12.478,64 (sotto soglia): 0
        // ricavi 18.000 -> imponibile 13.478,64 (sopra):  x 0,8% = 107,83
        val exempt = ordinaryOn(17_000.0)
        val taxed = ordinaryOn(18_000.0)

        assertEquals(12_478.64, exempt.fiscalIncome, cents)
        assertEquals(0.0, exempt.municipalTax, cents)

        assertEquals(13_478.64, taxed.fiscalIncome, cents)
        assertEquals(107.83, taxed.municipalTax, cents)
    }

    @Test
    fun `la detrazione lavoro autonomo include il bonus tra 11000 e 17000`() {
        // reddito 17.000: 500 + 765 x (28.000 - 17.000) / 22.500 = 874,00
        // piu' i 50 euro di ulteriore detrazione nella fascia -> 924,00
        // IRPEF lorda 12.478,64 x 23% = 2.870,09 -> netta 1.946,09
        val result = ordinaryOn(17_000.0)

        assertEquals(1_946.09, result.tax, cents)
    }

    @Test
    fun `senza imposta netta non si pagano addizionali`() {
        // Ricavi bassi: l'INPS da sola azzera l'imponibile, quindi niente IRPEF
        // e niente addizionali.
        val result = ordinaryOn(4_000.0)

        assertEquals(0.0, result.fiscalIncome, cents)
        assertEquals(0.0, result.tax, cents)
        assertEquals(0.0, result.regionalTax, cents)
        assertEquals(0.0, result.municipalTax, cents)
    }

    @Test
    fun `i crediti d'imposta non possono rendere l'IRPEF negativa`() {
        val result = ordinaryOn(
            50_000.0,
            plainSettings().copy(otherTaxCredits = 999_999.0)
        )

        assertEquals(0.0, result.tax, cents)
    }

    // -----------------------------------------------------------------------
    // Regime forfettario
    // -----------------------------------------------------------------------

    @Test
    fun `forfettario applica il coefficiente edilizia dell'86 percento`() {
        // 50.000 x 86% = 43.000 di reddito lordo
        // INPS su 43.000 = 10.327,44 · imponibile 32.672,56
        // imposta sostitutiva 15% = 4.900,88
        val result = FiscalEngine.compare(
            FiscalInput(
                year = FiscalParams2026.YEAR,
                revenue = 50_000.0,
                cashExpenses = 0.0,
                movements = 1,
                expenses = emptyList(),
                settings = plainSettings()
            )
        ).forfettario

        assertEquals(10_327.44, result.inps.total, cents)
        assertEquals(32_672.56, result.fiscalIncome, cents)
        assertEquals(4_900.88, result.tax, cents)
        assertEquals(0.0, result.regionalTax, cents)
        assertEquals(0.0, result.municipalTax, cents)
    }

    @Test
    fun `aliquota startup al 5 percento con riduzione contributiva`() {
        // INPS ridotta del 35% su 43.000 = 6.715,44 -> imponibile 36.284,56
        // 36.284,56 x 5% = 1.814,23
        val result = FiscalEngine.compare(
            FiscalInput(
                year = FiscalParams2026.YEAR,
                revenue = 50_000.0,
                cashExpenses = 0.0,
                movements = 1,
                expenses = emptyList(),
                settings = plainSettings().copy(
                    forfettarioRate = FiscalParams2026.STARTUP_RATE,
                    inpsReduction35 = true
                )
            )
        ).forfettario

        assertEquals(6_715.44, result.inps.total, cents)
        assertEquals(36_284.56, result.fiscalIncome, cents)
        assertEquals(1_814.23, result.tax, cents)
    }

    @Test
    fun `nel forfettario i costi reali non riducono il reddito tassabile`() {
        // E' la differenza che conta nella scelta del regime: i costi incidono
        // sulla cassa, non sull'imponibile calcolato col coefficiente.
        val withoutCosts = FiscalEngine.compare(
            FiscalInput(FiscalParams2026.YEAR, 50_000.0, 0.0, 1, emptyList(), plainSettings())
        ).forfettario
        val withCosts = FiscalEngine.compare(
            FiscalInput(FiscalParams2026.YEAR, 50_000.0, 20_000.0, 1, emptyList(), plainSettings())
        ).forfettario

        assertEquals(withoutCosts.fiscalIncome, withCosts.fiscalIncome, cents)
        assertEquals(withoutCosts.tax, withCosts.tax, cents)
        assertEquals(
            "i costi reali devono ridurre la cassa stimata",
            withoutCosts.netCashEstimate - 20_000.0,
            withCosts.netCashEstimate,
            cents
        )
    }

    @Test
    fun `oltre il limite di ricavi arriva l'avviso di uscita`() {
        val comparison = FiscalEngine.compare(
            FiscalInput(FiscalParams2026.YEAR, 90_000.0, 0.0, 1, emptyList(), plainSettings())
        )

        assertTrue(
            "atteso un avviso sul superamento degli 85.000: ${comparison.warnings}",
            comparison.warnings.any { it.contains("85.000") }
        )
    }

    @Test
    fun `la pressione fiscale e una percentuale sensata`() {
        val comparison = FiscalEngine.compare(
            FiscalInput(FiscalParams2026.YEAR, 50_000.0, 0.0, 1, emptyList(), plainSettings())
        )

        listOf(comparison.forfettario, comparison.ordinary).forEach { regime ->
            assertTrue(
                "${regime.regime}: pressione fuori scala ${regime.pressurePercent}",
                regime.pressurePercent in 0.0..100.0
            )
        }
    }
}
