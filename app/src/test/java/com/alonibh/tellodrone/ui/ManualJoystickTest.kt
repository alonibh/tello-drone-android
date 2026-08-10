package com.alonibh.tellodrone.ui

import com.alonibh.tellodrone.domain.ManualControlVector
import org.junit.Assert.assertEquals
import org.junit.Test

class ManualJoystickTest {
    @Test fun center_and_dead_zone_are_neutral() {
        assertEquals(JoystickVector(), normalizedJoystickVector(0f, 0f))
        assertEquals(JoystickVector(), normalizedJoystickVector(.08f, -.08f))
    }

    @Test fun cardinal_positions_preserve_mode_2_axes() {
        assertEquals(ManualControlVector(yaw = -1f), manualVectorFromSticks(normalizedJoystickVector(-1f, 0f), JoystickVector()))
        assertEquals(ManualControlVector(vertical = 1f), manualVectorFromSticks(normalizedJoystickVector(0f, 1f), JoystickVector()))
        assertEquals(ManualControlVector(lateral = 1f), manualVectorFromSticks(JoystickVector(), normalizedJoystickVector(1f, 0f)))
        assertEquals(ManualControlVector(forward = -1f), manualVectorFromSticks(JoystickVector(), normalizedJoystickVector(0f, -1f)))
    }

    @Test fun diagonal_is_clamped_to_unit_radius() {
        val vector = normalizedJoystickVector(2f, 2f)
        assertEquals(.70710677f, vector.horizontal, .00001f)
        assertEquals(.70710677f, vector.vertical, .00001f)
    }

    @Test fun releasing_one_stick_preserves_the_other_axes() {
        val left = JoystickVector(horizontal = .4f, vertical = .6f)
        val right = JoystickVector(horizontal = -.5f, vertical = .7f)
        assertEquals(ManualControlVector(lateral = -.5f, forward = .7f), manualVectorFromSticks(JoystickVector(), right))
        assertEquals(ManualControlVector(), manualVectorFromSticks(JoystickVector(), JoystickVector()))
        assertEquals(ManualControlVector(vertical = .6f, yaw = .4f), manualVectorFromSticks(left, JoystickVector()))
    }
}
