package it.edilmilan.ge360.core.common.ui

/**
 * Scala delle spaziature, in dp.
 *
 * Prima di questo file ogni schermata scriveva numeri a caso (dp(2), dp(9),
 * dp(14), dp(18)...) e il risultato era che due moduli affiancati non avevano
 * mai lo stesso respiro. Usare solo questi valori: se una spaziatura non c'e',
 * di solito significa che serve quella piu' vicina, non una nuova.
 */
object GeSpace {
    /** 4dp - distacco minimo, es. tra una riga e la sua didascalia. */
    const val XS = 4

    /** 8dp - spazio tra elementi dentro lo stesso blocco. */
    const val SM = 8

    /** 12dp - padding interno standard di card e righe. */
    const val MD = 12

    /** 16dp - margine laterale delle schermate, spazio tra blocchi. */
    const val LG = 16

    /** 20dp - separazione tra sezioni diverse. */
    const val XL = 20

    /** 28dp - respiro grande, es. attorno a uno stato vuoto. */
    const val XXL = 28
}

/**
 * Scala tipografica, in sp.
 *
 * Sostituisce i valori passati a mano caso per caso (23f, 22f, 17f, 12.5f, 11.5f,
 * 10.2f...). Tre livelli di titolo e tre di corpo bastano per tutta l'app.
 */
object GeType {
    /** 26sp - numero grande di una dashboard, uno per schermata. */
    const val DISPLAY = 26f

    /** 22sp - titolo della schermata. */
    const val TITLE = 22f

    /** 18sp - titolo di sezione. */
    const val H2 = 18f

    /** 16sp - titolo di una riga di lista. */
    const val H3 = 16f

    /** 14sp - testo normale. */
    const val BODY = 14f

    /** 13sp - testo secondario, didascalie di riga. */
    const val BODY_SM = 13f

    /** 11.5sp - etichette, metadati. */
    const val CAPTION = 11.5f

    /** 11.5sp - etichetta maiuscoletta sopra un titolo. */
    const val EYEBROW = 11.5f

    /** 10.5sp - badge e microtesto. Non scendere sotto. */
    const val MICRO = 10.5f
}

/** Raggi degli angoli, in dp. */
object GeRadius {
    /** 10dp - elementi piccoli dentro una card. */
    const val SMALL = 10

    /** 14dp - bottoni e campi. */
    const val CONTROL = 14

    /** 18dp - card e contenitori di sezione. */
    const val CARD = 18

    /**
     * 22dp - pillole e badge.
     *
     * Volutamente non e' un numero enorme: sia GradientDrawable sia le forme
     * Material riducono il raggio quando supera meta' altezza, quindi 22dp da'
     * una capsula perfetta su tutto cio' che e' alto fino a 44dp (badge,
     * filtri) senza dipendere da quel comportamento di salvataggio.
     */
    const val PILL = 22
}

/**
 * Altezze minime dei bersagli toccabili, in dp.
 *
 * 48dp e' il minimo delle linee guida di accessibilita'. In cantiere, con i
 * guanti, [COMFORTABLE] e' la scelta migliore per le azioni principali.
 */
object GeTouch {
    const val MINIMUM = 48
    const val COMFORTABLE = 54
}
