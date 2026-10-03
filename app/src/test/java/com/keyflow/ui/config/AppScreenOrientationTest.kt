package com.keyflow.ui.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppScreenOrientationTest {

    @Test
    fun nextRotatesContinuouslyInSingleDirection() {
        val o0 = AppScreenOrientation.PORTRAIT
        val o1 = o0.next()
        val o2 = o1.next()
        val o3 = o2.next()
        val o4 = o3.next()

        assertEquals(AppScreenOrientation.LANDSCAPE, o1)
        assertEquals(AppScreenOrientation.REVERSE_PORTRAIT, o2)
        assertEquals(AppScreenOrientation.REVERSE_LANDSCAPE, o3)
        assertEquals(AppScreenOrientation.PORTRAIT, o4)
    }

    @Test
    fun fromIdResolvesCorrectlyWithWrapAround() {
        assertEquals(AppScreenOrientation.PORTRAIT, AppScreenOrientation.fromId(0))
        assertEquals(AppScreenOrientation.LANDSCAPE, AppScreenOrientation.fromId(1))
        assertEquals(AppScreenOrientation.REVERSE_PORTRAIT, AppScreenOrientation.fromId(2))
        assertEquals(AppScreenOrientation.REVERSE_LANDSCAPE, AppScreenOrientation.fromId(3))
        assertEquals(AppScreenOrientation.PORTRAIT, AppScreenOrientation.fromId(4))
        assertEquals(AppScreenOrientation.LANDSCAPE, AppScreenOrientation.fromId(5))
        assertEquals(AppScreenOrientation.REVERSE_LANDSCAPE, AppScreenOrientation.fromId(-1))
    }

    @Test
    fun isLandscapeReportsCorrectly() {
        assertFalse(AppScreenOrientation.PORTRAIT.isLandscape)
        assertTrue(AppScreenOrientation.LANDSCAPE.isLandscape)
        assertFalse(AppScreenOrientation.REVERSE_PORTRAIT.isLandscape)
        assertTrue(AppScreenOrientation.REVERSE_LANDSCAPE.isLandscape)
    }
}
