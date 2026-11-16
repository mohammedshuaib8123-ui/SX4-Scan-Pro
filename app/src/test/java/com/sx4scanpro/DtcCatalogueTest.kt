package com.sx4scanpro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DtcCatalogueTest {
    @Test fun knownCodeHasDescriptionCausesAndChecks() {
        val p = DtcPresentation.of("P0118", "STORED")
        assertTrue(p.descriptionAvailable)
        assertTrue(p.title!!.lowercase().contains("coolant"))
        assertTrue(p.possibleCauses.isNotEmpty())
        assertTrue(p.nextChecks.isNotEmpty())
        assertEquals(DtcCatalogue.SOURCE, p.titleSource)
    }

    @Test fun lookupIsCaseAndSpaceTolerant() {
        assertNotNull(DtcCatalogue.lookup(" p0118 "))
    }

    @Test fun unknownCodeIsStillShownWithItsExactIdentifier() {
        val p = DtcPresentation.of("P1234", "PENDING")
        assertFalse(p.descriptionAvailable)
        assertNull(p.title)
        val text = p.lines().joinToString("\n")
        assertTrue(text.contains("P1234"))
        assertTrue(text.contains("not available in this app's catalogue"))
        assertTrue(p.possibleCauses.isEmpty())
        assertTrue(p.nextChecks.isEmpty())
    }

    @Test fun manufacturerSpecificCodesAreNeverDescribed() {
        assertNull(DtcCatalogue.lookup("P1000"))
        assertNull(DtcCatalogue.lookup("P1A00"))
    }

    @Test fun catalogueIsWellFormedUniqueAndStandardised() {
        val all = DtcCatalogue.all()
        assertEquals(all.size, all.map { it.code }.toSet().size)
        all.forEach {
            assertNotNull(it.code, DtcExplainer.explain(it.code))
            assertTrue(it.code, it.code[0] in "PU")
            assertEquals(it.code, '0', it.code[1])
            assertTrue(it.code, it.title.isNotBlank())
        }
    }

    @Test fun categoryIsKeptApartFromInterpretation() {
        val p = DtcPresentation.of("P0118", "STORED")
        assertEquals("STORED", p.ecuCategory)
        assertTrue(p.categoryMeaning.contains("not proof"))
        val text = p.lines().joinToString("\n")
        assertTrue(text.contains("possibilities, not confirmed findings"))
        assertTrue(text.contains("reported by engine ECU"))
    }

    @Test fun nothingIsCalledActiveOrGivenASeverity() {
        DtcCatalogue.all().forEach {
            val text = DtcPresentation.of(it.code, "STORED").lines().joinToString("\n").lowercase()
            assertFalse(it.code, text.contains("currently active"))
            assertFalse(it.code, text.contains("severity"))
            assertFalse(it.code, text.contains("critical"))
        }
    }

    @Test fun pendingAndPermanentMeaningsDiffer() {
        assertTrue(DtcPresentation.categoryMeaning("PENDING").contains("not yet confirmed"))
        assertTrue(DtcPresentation.categoryMeaning("PERMANENT").contains("until the ECU itself confirms"))
    }

    @Test fun everyFamilyHasCausesAndChecks() {
        DtcFamily.values().forEach {
            assertTrue(it.name, it.possibleCauses.isNotEmpty())
            assertTrue(it.name, it.nextChecks.isNotEmpty())
        }
    }
}
