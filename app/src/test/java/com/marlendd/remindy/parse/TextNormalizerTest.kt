package com.marlendd.remindy.parse

import org.junit.Assert.assertEquals
import org.junit.Test

class TextNormalizerTest {

    private fun norm(s: String) = TextNormalizer.normalize(s)

    @Test fun lowercases() {
        assertEquals("птс", norm("ПТС"))
    }

    @Test fun replacesYo() {
        assertEquals("елка", norm("Ёлка"))
    }

    @Test fun stripsPunctuationAndCollapsesSpaces() {
        assertEquals("документы на машину", norm("Документы,  на   машину!"))
    }

    @Test fun trims() {
        assertEquals("ключи", norm("  ключи  "))
    }

    @Test fun keepsDigits() {
        assertEquals("айфон 12", norm("айфон 12"))
    }
}
