# GE360 Preventivi — standard flessibile 2026.09

## Principio

Lo standard serve a velocizzare il preventivo, non a imporre una struttura rigida.

Ogni ambiente può usare una checklist proposta, modificabile liberamente, lavorazioni manuali illimitate, prezzo a corpo o a misura e quantità automatiche o manuali.

## Misure

Calcolo geometrico standard:

- pavimento = lunghezza × larghezza;
- soffitto = superficie pavimento;
- perimetro = 2 × (lunghezza + larghezza);
- pareti = perimetro × altezza;
- pareti + soffitto e pareti + pavimento sono calcolati per somma.

**Porte e finestre restano comprese nei m². Non vengono sottratte.**

Per corridoi, stanze a L e forme irregolari è disponibile, ambiente per ambiente, la modalità `Misure manuali / forma irregolare` con inserimento diretto di:

- pavimento m²;
- soffitto m²;
- pareti lorde m²;
- perimetro ml.

Un valore manuale lasciato a zero usa come fallback il calcolo geometrico standard.

## Checklist

- ogni voce è attivabile/disattivabile;
- nessuna scelta deve bloccare il preventivo;
- una checklist modificata può essere salvata come predefinita per quel tipo di ambiente;
- le checklist personali non modificano il catalogo tecnico originale.

## Lavorazioni manuali e libreria personale

Ogni ambiente consente lavorazioni completamente manuali con:

- nome;
- categoria;
- descrizione;
- quantità;
- unità;
- prezzo;
- inclusa/opzionale;
- riferimenti normativi e tecnici modificabili.

Una lavorazione manuale può essere salvata in `Le mie lavorazioni` e riutilizzata successivamente in qualsiasi ambiente.

## Prezzi

Ogni ambiente può essere:

- `A corpo`: una voce economica unica; le voci tecniche descrivono ciò che è compreso;
- `A misura`: quantità × prezzo unitario.

Una lavorazione manuale può comunque avere un prezzo separato anche dentro un ambiente a corpo, per gestire gli extra.

## Duplicazione ambiente

La duplicazione crea nuovi UUID e copia:

- tipo ambiente;
- misure;
- misure manuali;
- checklist;
- modalità prezzo;
- prezzi delle voci;
- lavorazioni manuali;
- riferimenti tecnici;
- costi interni.

La copia è indipendente dall'ambiente originale.

## Costi interni e margine

Ogni voce può conservare, nelle note interne strutturate:

- costo materiali;
- manodopera interna;
- collaboratori;
- altri costi;
- ore previste.

Questi dati non vengono mostrati al cliente e seguono backup/duplicazione/revisione del preventivo.

## Documento cliente

Sono previste tre modalità:

- `Dettagliato`;
- `Sintetico`;
- `A corpo`.

La stessa logica di presentazione dovrà essere usata dal futuro generatore PDF, evitando di duplicare le regole di composizione.

## Revisioni

`Crea revisione` genera un nuovo preventivo senza sovrascrivere l'originale. Gli ID degli ambienti vengono rigenerati per evitare collegamenti incrociati fra preventivi.

## Preventivo → cantiere

Il comando crea o collega un cantiere GE360 e trasferisce le lavorazioni incluse come fasi operative. Se il cantiere ha già avanzamenti, tali avanzamenti non vengono sovrascritti.

## Backup personalizzazioni

Schema:

`ge360.preventivi.customizations.v1`

Contiene esclusivamente dati riutilizzabili:

- `savedWorks`;
- `checklistDefaults`.

Non contiene dati di clienti/cantieri o misure specifiche di un singolo preventivo.

Il backup può essere copiato o condiviso come JSON.

## Feedback loop repo → app

Asset standard:

`feature/preventivi/src/main/assets/preventivi_customizations.default.json`

Quando l'uso reale dell'app ha prodotto una libreria sufficientemente matura:

1. esportare il backup personalizzazioni;
2. revisionarlo;
3. inserirlo nell'asset della repository;
4. mantenere `schema = ge360.preventivi.customizations.v1`;
5. usare un `exportedAt`/`bundleVersion` maggiore del bundle precedente.

`PreventiviDefaultsSeeder` rileva una versione nuova e la unisce una sola volta alle personalizzazioni locali senza cancellare quelle create successivamente sul dispositivo.

Questo permette allo standard GE360 di evolvere sulla base dei lavori realmente eseguiti.
