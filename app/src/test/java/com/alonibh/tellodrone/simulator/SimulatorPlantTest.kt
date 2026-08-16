package com.alonibh.tellodrone.simulator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulatorPlantTest {
    @Test fun `positive and negative yaw move a stationary person across the frame with Tello signs`() {
        val rightTurn = airbornePlant()
        rightTurn.applyAxes(SimulatorAxes(yaw = 50))
        assertTrue(rightTurn.step(1f).horizontalPosition < .5f)

        val leftTurn = airbornePlant()
        leftTurn.applyAxes(SimulatorAxes(yaw = -50))
        assertTrue(leftTurn.step(1f).horizontalPosition > .5f)
    }

    @Test fun `positive lateral moves person left`() {
        val plant = airbornePlant()
        plant.applyAxes(SimulatorAxes(lateral = 50))
        assertTrue(plant.step(.5f).horizontalPosition < .5f)
    }

    @Test fun `positive vertical moves person down`() {
        val plant = airbornePlant()
        val before = requireNotNull(plant.snapshot().boundingBox)
        plant.applyAxes(SimulatorAxes(vertical = 50))
        val after = requireNotNull(plant.step(.5f).boundingBox)
        assertTrue(centerY(after) > centerY(before))
    }

    @Test fun `positive forward makes person box larger`() {
        val plant = airbornePlant()
        val before = requireNotNull(plant.snapshot().boundingBox)
        plant.applyAxes(SimulatorAxes(forward = 50))
        val after = requireNotNull(plant.step(.5f).boundingBox)
        assertTrue(area(after) > area(before))
    }

    @Test fun `grounded plant ignores every RC axis`() {
        val plant = SimulatorPlant()
        val before = plant.snapshot()
        plant.applyAxes(SimulatorAxes(100, 100, 100, 100))
        val after = plant.step(10f)

        assertEquals(before.droneYawDegrees, after.droneYawDegrees, 0f)
        assertEquals(before.heightMeters, after.heightMeters, 0f)
        assertEquals(before.horizontalPosition, after.horizontalPosition, 0f)
        assertEquals(before.boundingBox, after.boundingBox)
    }

    @Test fun `scenario person motion is gradual across multiple frames`() {
        val plant = SimulatorPlant()
        plant.movePersonRight()
        val first = plant.step(.1f).horizontalPosition
        val second = plant.step(.1f).horizontalPosition

        assertTrue(first > .5f && first < .75f)
        assertTrue(second > first && second < .75f)
    }

    @Test fun `world clamping never creates malformed boxes`() {
        val plant = airbornePlant()
        plant.applyAxes(SimulatorAxes(lateral = 1_000, forward = 1_000, vertical = 1_000, yaw = 1_000))
        repeat(100) { plant.step(.25f) }
        val snapshot = plant.snapshot()

        assertTrue(snapshot.heightMeters in .3f..3f)
        assertTrue(snapshot.droneYawDegrees in -180f..180f)
        snapshot.boundingBox?.let(::assertValid)
        plant.reset()
        val box = plant.snapshot().boundingBox
        assertNotNull(box)
        assertValid(requireNotNull(box))
    }

    private fun assertValid(box: SimulatorBoundingBox) {
        assertTrue(box.left in 0f..1f && box.right in 0f..1f)
        assertTrue(box.top in 0f..1f && box.bottom in 0f..1f)
        assertTrue(box.left < box.right && box.top < box.bottom)
    }

    private fun airbornePlant() = SimulatorPlant().also { it.setAirborne(true) }
    private fun centerY(box: SimulatorBoundingBox) = (box.top + box.bottom) / 2f
    private fun area(box: SimulatorBoundingBox) = (box.right - box.left) * (box.bottom - box.top)
}
