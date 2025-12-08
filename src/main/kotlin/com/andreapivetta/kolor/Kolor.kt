package com.andreapivetta.kolor

enum class Color { YELLOW, MAGENTA, LIGHT_RED, LIGHT_BLUE, GREEN }

object Kolor { fun foreground(text: String, color: Color): String = text }

fun String.lightBlue(): String = this
