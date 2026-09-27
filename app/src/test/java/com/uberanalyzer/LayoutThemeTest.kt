package com.uberanalyzer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutThemeTest {
    @Test fun oldLayoutIdsMigrateToNewIds() {
        assertEquals(LayoutId.AURORA, LayoutId.normalize("classic"))
        assertEquals(LayoutId.COCKPIT, LayoutId.normalize("driver"))
        assertEquals(LayoutId.FLOW, LayoutId.normalize("queue"))
    }

    @Test fun newLayoutIdsRemainStableAndUnknownDefaultsToAurora() {
        assertEquals(LayoutId.AURORA, LayoutId.normalize("aurora"))
        assertEquals(LayoutId.COCKPIT, LayoutId.normalize("cockpit"))
        assertEquals(LayoutId.FLOW, LayoutId.normalize("flow"))
        assertEquals(LayoutId.AURORA, LayoutId.normalize("unknown"))
    }

    @Test fun explicitThemesOverrideSystemAndSystemFollowsDevice() {
        assertFalse(ThemeMode.isDark(1, true))
        assertTrue(ThemeMode.isDark(2, false))
        assertTrue(ThemeMode.isDark(-1, true))
        assertFalse(ThemeMode.isDark(-1, false))
    }
}
