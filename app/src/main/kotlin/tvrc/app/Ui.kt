package tvrc.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 视觉层（对应 ui参考/三代App-可交互原型.html 的深色主题）。
 *
 * 不引 Compose / AppCompat：本机 dl.google.com 不可达，Compose 依赖链长得可怕；
 * 首版用系统控件 + 自绘背景，把「装得上、点得动」排在「动画好看」前面。
 * 所有 token 集中在这里，后面换 Compose 或换皮只改这一处。
 */
object T {
    const val BG = 0xFF0F1115.toInt()
    const val CARD = 0xFF1A1D24.toInt()
    const val CARD_HI = 0xFF232732.toInt()
    const val LINE = 0xFF2C313B.toInt()
    const val TEXT = 0xFFE8EAED.toInt()
    const val DIM = 0xFF9AA0A6.toInt()
    const val ACCENT = 0xFFFB7299.toInt()
    const val OK = 0xFF37C871.toInt()
    const val WARN = 0xFFFFB020.toInt()
    const val ERR = 0xFFFF5D5D.toInt()
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun Context.rounded(color: Int, radiusDp: Int = 14, strokeDp: Int = 0, strokeColor: Int = T.LINE): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        if (strokeDp > 0) setStroke(dp(strokeDp), strokeColor)
    }

fun Context.label(
    text: String,
    sizeSp: Float = 14f,
    color: Int = T.TEXT,
    bold: Boolean = false
): TextView = TextView(this).apply {
    this.text = text
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    setTextColor(color)
    if (bold) setTypeface(typeface, Typeface.BOLD)
    includeFontPadding = false
}

fun Context.vpad(spaceDp: Int): View = View(this).apply {
    layoutParams = LinearLayout.LayoutParams(1, dp(spaceDp))
}

fun Context.hpad(spaceDp: Int): View = View(this).apply {
    layoutParams = LinearLayout.LayoutParams(dp(spaceDp), 1)
}

fun Context.card(inner: Int = 14, bg: Int = T.CARD): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(inner), dp(inner), dp(inner), dp(inner))
    background = rounded(bg, 16)
    layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(10) }
}

fun Context.row(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

fun Context.button(
    text: String,
    primary: Boolean = false,
    onTap: () -> Unit
): TextView = TextView(this).apply {
    this.text = text
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
    setTextColor(if (primary) 0xFF1A1A1A.toInt() else T.TEXT)
    gravity = Gravity.CENTER
    setPadding(dp(14), dp(8), dp(14), dp(8))
    background = if (primary) rounded(T.ACCENT, 12) else rounded(T.CARD_HI, 12, 1, T.LINE)
    isClickable = true
    minHeight = dp(48)
    setOnClickListener { onTap() }
}

fun Context.chip(text: String, selected: Boolean, onTap: () -> Unit): TextView = TextView(this).apply {
    this.text = text
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
    setTextColor(if (selected) 0xFF141414.toInt() else T.DIM)
    gravity = Gravity.CENTER
    setPadding(dp(12), dp(6), dp(12), dp(6))
    background = if (selected) rounded(T.ACCENT, 20) else rounded(T.CARD_HI, 20, 1, T.LINE)
    isClickable = true
    setOnClickListener { onTap() }
}

fun Context.badge(text: String, color: Int): TextView = TextView(this).apply {
    this.text = text
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
    setTextColor(color)
    setPadding(dp(8), dp(2), dp(8), dp(2))
    background = rounded(withAlpha(color, 0.16f), 8)
}

fun withAlpha(color: Int, alpha: Float): Int = Color.argb(
    (alpha * 255).toInt().coerceIn(0, 255),
    Color.red(color), Color.green(color), Color.blue(color)
)

fun Context.input(hint: String, value: String = "", numeric: Boolean = false): android.widget.EditText =
    android.widget.EditText(this).apply {
        this.hint = hint
        setText(value)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(T.TEXT)
        setHintTextColor(T.DIM)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = rounded(T.CARD_HI, 12, 1, T.LINE)
        if (numeric) inputType = InputType.TYPE_CLASS_NUMBER else inputType =
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }

fun Context.divider(): View = View(this).apply {
    layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, 1
    ).apply { topMargin = dp(8); bottomMargin = dp(8) }
    setBackgroundColor(T.LINE)
}

fun Context.statusDot(color: Int): TextView = TextView(this).apply {
    text = "●"
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
    setTextColor(color)
}
