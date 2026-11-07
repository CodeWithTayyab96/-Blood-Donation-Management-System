package com.tayyab.blooddonation.util

import org.junit.Assert.*
import org.junit.Test

class CsvCellsTest {

    @Test
    fun `formula starters are neutralised`() {
        assertEquals("'=SUM(A1:A9)", CsvCells.sanitize("=SUM(A1:A9)"))
        assertEquals("'+1+1", CsvCells.sanitize("+1+1"))
        assertEquals("'-1", CsvCells.sanitize("-1"))
        assertEquals("'@import", CsvCells.sanitize("@import"))
    }

    @Test
    fun `leading whitespace cannot hide a formula`() {
        assertEquals("'=2+2", CsvCells.sanitize("   =2+2"))
        assertEquals("'=2+2", CsvCells.sanitize("\t=2+2"))
        assertEquals("'=2+2", CsvCells.sanitize("\n=2+2"))
    }

    @Test
    fun `ordinary text is left alone`() {
        assertEquals("Ali Raza", CsvCells.sanitize("Ali Raza"))
        assertEquals("O-", CsvCells.sanitize("O-"))
        assertEquals("", CsvCells.sanitize(""))
    }

    @Test
    fun `commas quotes and newlines are escaped`() {
        assertEquals("\"Faisalabad, Punjab\"", CsvCells.escape("Faisalabad, Punjab"))
        assertEquals("\"say \"\"hi\"\"\"", CsvCells.escape("say \"hi\""))
        assertEquals("\"a\nb\"", CsvCells.escape("a\nb"))
    }

    @Test
    fun `a row combines sanitising and escaping`() {
        assertEquals("Ali,'=SUM(1+1)", CsvCells.row(listOf("Ali", "=SUM(1+1)")))
    }

    @Test
    fun `a document has a header and no trailing newline`() {
        val csv = CsvCells.document(
            listOf("Group", "Units"),
            listOf(listOf("O-", "3"), listOf("A+", "=2"))
        )
        assertEquals("Group,Units\r\nO-,3\r\nA+,'=2", csv)
        assertFalse(csv.endsWith("\n"))
    }
}
