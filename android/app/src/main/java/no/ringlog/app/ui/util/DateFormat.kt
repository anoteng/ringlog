package no.ringlog.app.ui.util

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private val localeDateFormatter
    get() = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault())

private val localeDateTimeFormatter
    get() = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(Locale.getDefault())

fun formatIsoDate(dt: String): String = try {
    LocalDate.parse(dt.take(10)).format(localeDateFormatter)
} catch (_: Exception) { dt.take(10) }

fun formatIsoDateTime(dt: String): String = try {
    LocalDateTime.parse(dt.replace(" ", "T").take(19)).format(localeDateTimeFormatter)
} catch (_: Exception) { dt.take(16).replace("T", " ") }

fun birdAge(birthDate: String?): String? {
    if (birthDate.isNullOrBlank()) return null
    return try {
        val bd = LocalDate.parse(birthDate.take(10))
        val months = java.time.Period.between(bd, LocalDate.now()).toTotalMonths().toInt()
        when {
            months < 1  -> "< 1 mo"
            months < 24 -> "$months mo"
            else        -> "${months / 12} yr"
        }
    } catch (_: Exception) { null }
}
