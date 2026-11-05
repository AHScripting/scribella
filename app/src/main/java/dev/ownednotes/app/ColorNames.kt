package dev.ownednotes.app

import android.graphics.Color

/** P540 (F219): a plain name for any colour, so a screen reader can say more than a hex code. A rough name, not a colour match. */
internal object ColorNames {
    fun of(color: Long): String {
        val hsv = FloatArray(3); Color.colorToHSV(color.toInt(), hsv)
        val (hue, colour, bright) = hsv
        if (bright < .12f) return "black"
        if (colour < .1f) return when { bright > .92f -> "white"; bright > .7f -> "light gray"; bright > .35f -> "gray"; else -> "dark gray" }
        val name = when {
            hue < 15f || hue >= 340f -> "red"
            hue < 42f -> if (bright < .7f) "brown" else "orange"
            hue < 68f -> "yellow"
            hue < 165f -> "green"
            hue < 195f -> "cyan"
            hue < 255f -> "blue"
            hue < 295f -> "purple"
            else -> "pink"
        }
        return when { bright < .5f -> "dark $name"; colour < .4f -> "pale $name"; else -> name }
    }
}
