package com.example.crtui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

class KeyboardEngine(private val onKeyAction: (String) -> Unit) {
    var isShifted = false
    var isSymbols = false

    private var keyHitboxes = mutableMapOf<String, RectF>()

    data class KeyDef(val id: String, val display: String, val weight: Float)

    // Standard Alpha Layout
    private val rowsAlpha = listOf(
        listOf(KeyDef("Tab", "TAB", 1.5f), KeyDef("CTRL+C", "BRK", 1.5f), KeyDef("<", "<", 1f), KeyDef("^", "^", 1f), KeyDef("v", "v", 1f), KeyDef(">", ">", 1f), KeyDef("/", "/", 1f), KeyDef("-", "-", 1f), KeyDef("=", "=", 1f)),
        listOf(KeyDef("q", "Q", 1f), KeyDef("w", "W", 1f), KeyDef("e", "E", 1f), KeyDef("r", "R", 1f), KeyDef("t", "T", 1f), KeyDef("y", "Y", 1f), KeyDef("u", "U", 1f), KeyDef("i", "I", 1f), KeyDef("o", "O", 1f), KeyDef("p", "P", 1f)),
        listOf(KeyDef("a", "A", 1f), KeyDef("s", "S", 1f), KeyDef("d", "D", 1f), KeyDef("f", "F", 1f), KeyDef("g", "G", 1f), KeyDef("h", "H", 1f), KeyDef("j", "J", 1f), KeyDef("k", "K", 1f), KeyDef("l", "L", 1f)),
        listOf(KeyDef("SHFT", "SHT", 1.5f), KeyDef("z", "Z", 1f), KeyDef("x", "X", 1f), KeyDef("c", "C", 1f), KeyDef("v", "V", 1f), KeyDef("b", "B", 1f), KeyDef("n", "N", 1f), KeyDef("m", "M", 1f), KeyDef("DEL", "DEL", 1.5f)),
        listOf(KeyDef("?123", "?12", 1.5f), KeyDef(",", ",", 1f), KeyDef("SPACE", "_____", 4.5f), KeyDef(".", ".", 1f), KeyDef("ENTR", "ENT", 1.5f))
    )

    // Symbol/Number Layout
    private val rowsSym = listOf(
        listOf(KeyDef("Tab", "TAB", 1.5f), KeyDef("CTRL+C", "BRK", 1.5f), KeyDef("<", "<", 1f), KeyDef("^", "^", 1f), KeyDef("v", "v", 1f), KeyDef(">", ">", 1f), KeyDef("/", "/", 1f), KeyDef("-", "-", 1f), KeyDef("=", "=", 1f)),
        listOf(KeyDef("1", "1", 1f), KeyDef("2", "2", 1f), KeyDef("3", "3", 1f), KeyDef("4", "4", 1f), KeyDef("5", "5", 1f), KeyDef("6", "6", 1f), KeyDef("7", "7", 1f), KeyDef("8", "8", 1f), KeyDef("9", "9", 1f), KeyDef("0", "0", 1f)),
        listOf(KeyDef("!", "!", 1f), KeyDef("@", "@", 1f), KeyDef("#", "#", 1f), KeyDef("$", "$", 1f), KeyDef("%", "%", 1f), KeyDef("&", "&", 1f), KeyDef("*", "*", 1f), KeyDef("(", "(", 1f), KeyDef(")", ")", 1f)),
        listOf(KeyDef("SHFT", "SYM", 1.5f), KeyDef("\"", "\"", 1f), KeyDef("'", "'", 1f), KeyDef(":", ":", 1f), KeyDef(";", ";", 1f), KeyDef("_", "_", 1f), KeyDef("+", "+", 1f), KeyDef("?", "?", 1f), KeyDef("DEL", "DEL", 1.5f)),
        listOf(KeyDef("?123", "ABC", 1.5f), KeyDef(",", ",", 1f), KeyDef("SPACE", "_____", 4.5f), KeyDef(".", ".", 1f), KeyDef("ENTR", "ENT", 1.5f))
    )

    fun draw(canvas: Canvas, leftX: Float, rightX: Float, bottomY: Float, paint: Paint, boldPaint: Paint, framePaint: Paint): Float {
        keyHitboxes.clear()
        val rows = if (isSymbols) rowsSym else rowsAlpha

        val fontMetrics = paint.fontMetrics
        val textHeight = fontMetrics.descent - fontMetrics.ascent
        val rowHeight = textHeight * 1.8f
        val paddingY = 12f

        val keyboardHeight = (rowHeight * rows.size) + (paddingY * (rows.size - 1))
        val topY = bottomY - keyboardHeight
        var currentY = topY

        for (row in rows) {
            val totalWeight = row.sumOf { it.weight.toDouble() }.toFloat()
            val widthPerWeight = (rightX - leftX) / totalWeight
            var currentX = leftX

            for (key in row) {
                val keyWidth = widthPerWeight * key.weight
                val rect = RectF(currentX + 4f, currentY, currentX + keyWidth - 4f, currentY + rowHeight)

                canvas.drawRect(rect, framePaint)

                var displayText = key.display
                if (!isSymbols && key.id.length == 1) {
                    displayText = if (isShifted) key.id.uppercase(java.util.Locale.US) else key.id.lowercase(java.util.Locale.US)
                }
                if (key.id == "SHFT" && isShifted) displayText = "[S]"

                val textWidth = paint.measureText(displayText)
                val textX = currentX + (keyWidth / 2f) - (textWidth / 2f)
                val textY = currentY + (rowHeight / 2f) + (textHeight / 3f)

                val useBold = key.id == "ENTR" || key.id == "DEL" || key.id == "Tab" || key.id == "CTRL+C"
                canvas.drawText(displayText, textX, textY, if (useBold) boldPaint else paint)

                keyHitboxes[key.id] = rect
                currentX += keyWidth
            }
            currentY += rowHeight + paddingY
        }

        return topY - 30f // Returns the exact pixel coordinate where the command bar should be drawn
    }

    fun handleTouch(x: Float, y: Float): Boolean {
        for ((id, rect) in keyHitboxes) {
            if (rect.contains(x, y)) {
                when (id) {
                    "SHFT" -> isShifted = !isShifted
                    "?123" -> isSymbols = !isSymbols
                    else -> {
                        val payload = if (!isSymbols && id.length == 1 && isShifted) id.uppercase(java.util.Locale.US) else id
                        onKeyAction(payload)
                        if (isShifted && id.length == 1) isShifted = false // Auto unshift after 1 char
                    }
                }
                return true
            }
        }
        return false
    }
}