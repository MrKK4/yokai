package eu.kanade.tachiyomi.ui.suggestions

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.tachiyomi.util.system.getResourceColor

class SuggestionsVersionActionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatTextView(context, attrs) {

    init {
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        includeFontPadding = false
        typeface = Typeface.DEFAULT_BOLD
        textSize = 12f
        letterSpacing = 0.02f
        minWidth = VERSION_BUTTON_SIZE
        minHeight = VERSION_BUTTON_SIZE
        layoutParams = ViewGroup.LayoutParams(VERSION_BUTTON_SIZE, VERSION_BUTTON_SIZE)
        ViewCompat.setTooltipText(this, "Switch between For you and Surprise me suggestions")
    }

    fun setVersion(isV2Enabled: Boolean) {
        text = if (isV2Enabled) "4U" else "!"
        textSize = 10f
        contentDescription = if (isV2Enabled) {
            "For you suggestions selected. Tap to switch to surprise me."
        } else {
            "Surprise me suggestions selected. Tap to switch to for you."
        }

        val actionTint = context.getResourceColor(R.attr.actionBarTintColor)
        setTextColor(
            if (isV2Enabled) {
                actionTint
            } else {
                ColorUtils.setAlphaComponent(actionTint, DISABLED_TEXT_ALPHA)
            },
        )
        background = RippleDrawable(
            ColorStateList.valueOf(context.getResourceColor(R.attr.colorControlHighlight)),
            badgeDrawable(isV2Enabled),
            ovalDrawable(fillColor = Color.WHITE),
        )
    }

    private fun badgeDrawable(isV2Enabled: Boolean): Drawable {
        val actionTint = context.getResourceColor(R.attr.actionBarTintColor)
        val accent = context.getResourceColor(R.attr.colorSecondary)
        val ringColor = if (isV2Enabled) {
            accent
        } else {
            ColorUtils.setAlphaComponent(actionTint, DISABLED_RING_ALPHA)
        }
        val fillColor = if (isV2Enabled) {
            ColorUtils.setAlphaComponent(accent, ENABLED_FILL_ALPHA)
        } else {
            ColorUtils.setAlphaComponent(actionTint, DISABLED_FILL_ALPHA)
        }
        val innerRingColor = ColorUtils.setAlphaComponent(actionTint, INNER_RING_ALPHA)

        return LayerDrawable(
            arrayOf(
                ovalDrawable(fillColor = fillColor, strokeColor = ringColor, strokeWidth = OUTER_STROKE),
                ovalDrawable(fillColor = Color.TRANSPARENT, strokeColor = innerRingColor, strokeWidth = INNER_STROKE),
            ),
        ).apply {
            setLayerInset(1, INNER_RING_INSET, INNER_RING_INSET, INNER_RING_INSET, INNER_RING_INSET)
        }
    }

    private fun ovalDrawable(
        fillColor: Int,
        strokeColor: Int = Color.TRANSPARENT,
        strokeWidth: Int = 0,
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fillColor)
            if (strokeWidth > 0) {
                setStroke(strokeWidth, strokeColor)
            }
        }
    }

    private companion object {
        val VERSION_BUTTON_SIZE = 40.dpToPx
        val OUTER_STROKE = 2.dpToPx
        val INNER_STROKE = 1.dpToPx
        val INNER_RING_INSET = 5.dpToPx
        const val ENABLED_FILL_ALPHA = 36
        const val DISABLED_FILL_ALPHA = 18
        const val DISABLED_RING_ALPHA = 150
        const val DISABLED_TEXT_ALPHA = 210
        const val INNER_RING_ALPHA = 78
    }
}
