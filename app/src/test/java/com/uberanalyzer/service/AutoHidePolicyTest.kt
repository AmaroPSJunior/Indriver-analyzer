package com.uberanalyzer.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoHidePolicyTest {
    @Test fun checksSecondAndThirdEvenWhenFirstMeetsMinimum() {
        assertEquals(1, AutoHidePolicy.firstBelowMinimum(listOf(3.0, 1.0, 1.5), 2.0))
        assertEquals(2, AutoHidePolicy.firstBelowMinimum(listOf(3.0, 2.0, 1.5), 2.0))
    }

    @Test fun ignoresFourthRideAndAcceptsEquality() {
        assertNull(AutoHidePolicy.firstBelowMinimum(listOf(2.0, 3.0, 4.0, 1.0), 2.0))
    }

    @Test fun reevaluatesChangedValuesAndNewOrderAfterRemoval() {
        assertNull(AutoHidePolicy.firstBelowMinimum(listOf(3.0, 3.0, 3.0), 2.0))
        assertEquals(0, AutoHidePolicy.firstBelowMinimum(listOf(1.0, 3.0, 1.5), 2.0))
        assertEquals(1, AutoHidePolicy.firstBelowMinimum(listOf(3.0, 1.5, 4.0), 2.0))
        assertEquals(0, AutoHidePolicy.firstBelowMinimum(listOf(3.0, 3.0, 3.0), 4.0))
    }

    @Test fun incompleteReadingsNeverTriggerRemoval() {
        assertNull(AutoHidePolicy.firstBelowMinimum(listOf(0.0, Double.NaN, Double.POSITIVE_INFINITY), 2.0))
        assertNull(AutoHidePolicy.firstBelowMinimum(emptyList(), 2.0))
    }
}
