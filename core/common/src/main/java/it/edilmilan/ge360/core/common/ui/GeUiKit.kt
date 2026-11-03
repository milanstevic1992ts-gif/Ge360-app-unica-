package it.edilmilan.ge360.core.common.ui

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import it.edilmilan.ge360.core.common.R

/** Converte dp in pixel usando la densità del device corrente. */
fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

/** Un'azione mostrata come icona nell'header di una schermata (es. "+", "⋮"). */
data class HeaderAction(
    val icon: Int,
    val description: String,
    val onClick: () -> Unit
)

/** Header condiviso con identità EDIL MILAN STEVIC. */
fun buildScreenHeader(
    context: Context,
    title: String,
    onBack: (() -> Unit)? = null,
    actions: List<HeaderAction> = emptyList()
): LinearLayout {
    val header = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, 0, 0, context.dp(GeSpace.SM))
    }

    if (onBack != null) {
        header.addView(
            headerIconButton(context, R.drawable.ic_ge360_back, "Indietro", onBack),
            LinearLayout.LayoutParams(context.dp(GeTouch.MINIMUM), context.dp(GeTouch.MINIMUM))
        )
    }

    header.addView(
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(context.ge360Eyebrow("EDIL MILAN STEVIC"))
            addView(TextView(context).apply {
                text = title
                applyGe360Title(GeType.TITLE)
                gravity = Gravity.START
                setPadding(0, context.dp(2), 0, 0)
            })
        },
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = if (onBack != null) context.dp(2) else 0
        }
    )

    actions.forEach { action ->
        header.addView(
            headerIconButton(context, action.icon, action.description, action.onClick),
            LinearLayout.LayoutParams(context.dp(GeTouch.MINIMUM), context.dp(GeTouch.MINIMUM))
        )
    }

    return header
}

private fun headerIconButton(context: Context, icon: Int, description: String, onClick: () -> Unit) =
    ImageButton(context).apply {
        setImageResource(icon)
        setColorFilter(Ge360Brand.Forest)
        contentDescription = description
        background = null
        val outValue = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
        setBackgroundResource(outValue.resourceId)
        setOnClickListener { onClick() }
    }

/** Header a due righe usato dai moduli operativi. */
fun buildScreenHeaderWithSubtitle(
    context: Context,
    title: String,
    subtitle: String? = null,
    onBack: () -> Unit,
    trailingLabel: String? = null,
    onTrailingClick: (() -> Unit)? = null
): LinearLayout {
    val header = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, 0, 0, context.dp(GeSpace.SM))
    }

    header.addView(
        headerIconButton(context, R.drawable.ic_ge360_back, "Indietro", onBack),
        LinearLayout.LayoutParams(context.dp(GeTouch.MINIMUM), context.dp(GeTouch.MINIMUM))
    )

    header.addView(
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(context.ge360Eyebrow("EDIL MILAN STEVIC"))
            addView(TextView(context).apply {
                text = title
                applyGe360Title(GeType.TITLE)
                setPadding(0, context.dp(1), 0, 0)
            })
            if (!subtitle.isNullOrBlank()) {
                addView(TextView(context).apply {
                    text = subtitle
                    applyGe360Subtitle(GeType.CAPTION)
                    setPadding(0, context.dp(2), 0, 0)
                })
            }
        },
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = context.dp(2)
        }
    )

    if (trailingLabel != null && onTrailingClick != null) {
        header.addView(MaterialButton(context).apply {
            text = trailingLabel
            minHeight = context.dp(GeTouch.MINIMUM - 6)
            applyGe360Secondary()
            setOnClickListener { onTrailingClick() }
        })
    }

    return header
}
