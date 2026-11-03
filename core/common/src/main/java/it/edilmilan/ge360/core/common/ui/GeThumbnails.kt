package it.edilmilan.ge360.core.common.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import android.widget.ImageView
import it.edilmilan.ge360.core.common.R

/**
 * Caricatore di miniature con cache in memoria.
 *
 * Prima la Galleria decodificava l'immagine dentro `getView` dell'adapter:
 * apertura del ContentResolver e `BitmapFactory.decodeStream` completo sul
 * thread della UI, per ogni riga, a ogni riciclo durante lo scorrimento, senza
 * cache e con `inSampleSize` fisso a 8 (quindi una miniatura da 74dp pesava
 * come mezza foto).
 *
 * Qui:
 * - la decodifica avviene in background;
 * - `inSampleSize` viene calcolato sulle dimensioni reali del file, cosi' una
 *   miniatura occupa la memoria di una miniatura;
 * - i risultati stanno in una [LruCache] dimensionata su un ottavo della heap;
 * - ogni ImageView viene marcata con la chiave richiesta, quindi una vista
 *   riciclata non si ritrova la foto sbagliata (bug classico delle liste).
 */
object GeThumbnails {

    private val cache: LruCache<String, Bitmap> = object : LruCache<String, Bitmap>(
        ((Runtime.getRuntime().maxMemory() / 1024) / 8).toInt().coerceAtLeast(4 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    private val tagKey = R.id.ge360_thumb_tag

    /**
     * Mostra la miniatura di [uriText] dentro [target].
     *
     * Se e' gia' in cache la applica subito, senza passare dal background: la
     * lista scorre senza sfarfallio. [placeholder] viene mostrato nel frattempo.
     */
    fun load(
        target: ImageView,
        uriText: String,
        sizePx: Int,
        placeholder: Int = Ge360Brand.ForestSoft
    ) {
        val key = "$uriText@$sizePx"
        target.setTag(tagKey, key)

        cache.get(key)?.let {
            target.setImageBitmap(it)
            return
        }

        target.setImageBitmap(null)
        target.setBackgroundColor(placeholder)

        val context = target.context.applicationContext
        GeAsync.execute {
            val bitmap = decode(context, uriText, sizePx)
            if (bitmap != null) cache.put(key, bitmap)
            GeAsync.onMain {
                // La vista potrebbe essere stata riciclata per un'altra foto.
                if (target.getTag(tagKey) == key && bitmap != null) {
                    target.setImageBitmap(bitmap)
                }
            }
        }
    }

    /** Svuota la cache, es. dopo un import massivo o la cancellazione di foto. */
    fun clear() = cache.evictAll()

    /** Rimuove dalla cache tutte le dimensioni di una singola foto. */
    fun invalidate(uriText: String) {
        cache.snapshot().keys
            .filter { it.substringBeforeLast('@') == uriText }
            .forEach { cache.remove(it) }
    }

    private fun decode(context: Context, uriText: String, sizePx: Int): Bitmap? = runCatching {
        val uri = Uri.parse(uriText)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, sizePx)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }.getOrNull()

    /** La potenza di due piu' grande che tiene la miniatura sopra [targetPx]. */
    internal fun sampleSize(width: Int, height: Int, targetPx: Int): Int {
        if (targetPx <= 0) return 1
        var sample = 1
        var shortest = minOf(width, height)
        while (shortest / 2 >= targetPx) {
            shortest /= 2
            sample *= 2
        }
        return sample
    }
}
