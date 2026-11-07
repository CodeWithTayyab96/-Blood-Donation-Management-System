package com.tayyab.blooddonation.util

/**
 * CSV cell handling for the manager's monthly export.
 *
 * Two separate jobs, both pure and unit tested:
 *  - [sanitize] stops **formula injection**. Excel / Sheets / LibreOffice
 *    execute a cell that starts with `=`, `+`, `-` or `@`. Donor names and
 *    hospital names are user-supplied, so exporting them raw would let a
 *    crafted name run a formula when a manager opens the report. Prefixing a
 *    single quote makes the cell text, and the leading whitespace/control
 *    characters that some spreadsheets skip are stripped first.
 *  - [escape] quotes cells containing a comma, quote or newline, doubling
 *    embedded quotes so the CSV stays parseable.
 */
object CsvCells {

    private val FORMULA_STARTERS = charArrayOf('=', '+', '-', '@')

    fun sanitize(raw: String): String {
        // Tabs, CR/LF and other leading controls can hide the formula starter
        // from a spreadsheet parser, so trim them before looking at char 0.
        val text = raw.trimStart { it <= ' ' }
        return if (text.isNotEmpty() && text[0] in FORMULA_STARTERS) "'$text" else text
    }

    fun escape(raw: String): String {
        val safe = sanitize(raw)
        val needsQuotes = safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        if (!needsQuotes) return safe
        return "\"${safe.replace("\"", "\"\"")}\""
    }

    fun row(cells: List<String>): String = cells.joinToString(",") { escape(it) }

    /** A whole report with a header row. No trailing blank line. */
    fun document(header: List<String>, rows: List<List<String>>): String =
        (listOf(row(header)) + rows.map { row(it) }).joinToString("\r\n")
}
