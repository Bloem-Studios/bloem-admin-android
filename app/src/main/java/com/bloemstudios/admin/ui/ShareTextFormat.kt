package com.bloemstudios.admin.ui

/**
 * The plain text Copy and Share send for a record: its title, then "Label: value" lines. Fields with no value are left
 * out; a value of several lines goes under its label.
 */
fun shareText(title: String, fields: List<Pair<String, String?>>): String = buildString {
    append(title)
    for ((label, value) in fields) {
        if (value.isNullOrBlank()) continue
        append('\n')
        if (value.contains('\n')) append(label).append(":\n").append(value) else append(label).append(": ").append(value)
    }
}
