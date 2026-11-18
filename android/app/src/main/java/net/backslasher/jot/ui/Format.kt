package net.backslasher.jot.ui

import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val entryDateFormat = DateTimeFormatter.ofPattern("EEE, dd/MM/yyyy")

fun formatEntryDate(date: LocalDate): String = date.format(entryDateFormat)
