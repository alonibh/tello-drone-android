package com.alonibh.tellodrone.simulator

import kotlin.math.abs

/** Integer Tello-axis input copied field-for-field by the simulator transport. */
data class SimulatorAxes(
    val lateral: Int = 0,
    val forward: Int = 0,
    val vertical: Int = 0,
    val yaw: Int = 0,
)

data class SimulatorBoundingBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

data class SimulatorPlantSnapshot(
    val airborne: Boolean,
    val heightMeters: Float,
    val droneYawDegrees: Float,
    val personWorldBearingDegrees: Float,
    val horizontalPosition: Float,
    val horizontalError: Float,
    val personVisible: Boolean,
    val boundingBox: SimulatorBoundingBox?,
    val appliedAxes: SimulatorAxes,
    val flightTimeSeconds: Int,
)

/**
 * Small deterministic virtual world. It deliberately contains no Android, coroutine, detector,
 * association, tracking-error, or controller dependency, so its signs are independent of flight
 * control production code.
 */
class SimulatorPlant {
    private var airborne = false
    private var heightMeters = 0f
    private var droneYawDegrees = 0f
    private var personWorldBearingDegrees = 0f
    private var requestedPersonBearingDegrees = 0f
    private var droneLateralMeters = 0f
    private var droneVerticalOffsetMeters = 0f
    private var personDistanceMeters = INITIAL_DISTANCE_METERS
    private var personVisible = true
    private var axes = SimulatorAxes()
    private var flightTimeSeconds = 0f

    @Synchronized
    fun applyAxes(value: SimulatorAxes) {
        axes = value.copy(
            lateral = value.lateral.coerceIn(-100, 100),
            forward = value.forward.coerceIn(-100, 100),
            vertical = value.vertical.coerceIn(-100, 100),
            yaw = value.yaw.coerceIn(-100, 100),
        )
    }

    @Synchronized
    fun setAirborne(value: Boolean) {
        airborne = value
        heightMeters = if (value) TAKEOFF_HEIGHT_METERS else 0f
        if (!value) {
            axes = SimulatorAxes()
            droneVerticalOffsetMeters = 0f
        }
    }

    @Synchronized
    fun emergencyStop() {
        airborne = false
        heightMeters = 0f
        axes = SimulatorAxes()
    }

    @Synchronized
    fun movePersonLeft() {
        requestedPersonBearingDegrees = -PERSON_SCENARIO_BEARING_DEGREES
    }

    @Synchronized
    fun movePersonRight() {
        requestedPersonBearingDegrees = PERSON_SCENARIO_BEARING_DEGREES
    }

    /** Places the person's requested world bearing on the current virtual camera centre line. */
    @Synchronized
    fun centerPerson() {
        requestedPersonBearingDegrees = droneYawDegrees + droneLateralMeters * LATERAL_PARALLAX_DEGREES_PER_METER
    }

    @Synchronized
    fun togglePersonVisibility() {
        personVisible = !personVisible
    }

    @Synchronized
    fun reset() {
        airborne = false
        heightMeters = 0f
        droneYawDegrees = 0f
        personWorldBearingDegrees = 0f
        requestedPersonBearingDegrees = 0f
        droneLateralMeters = 0f
        droneVerticalOffsetMeters = 0f
        personDistanceMeters = INITIAL_DISTANCE_METERS
        personVisible = true
        axes = SimulatorAxes()
        flightTimeSeconds = 0f
    }

    /** Advances exactly by [dtSeconds]; no wall clock is read. */
    @Synchronized
    fun step(dtSeconds: Float): SimulatorPlantSnapshot {
        require(dtSeconds.isFinite() && dtSeconds >= 0f)
        personWorldBearingDegrees = approach(
            personWorldBearingDegrees,
            requestedPersonBearingDegrees,
            PERSON_BEARING_RATE_DEGREES_PER_SECOND * dtSeconds,
        ).coerceIn(-MAX_WORLD_BEARING_DEGREES, MAX_WORLD_BEARING_DEGREES)

        if (airborne) {
            droneYawDegrees = wrapDegrees(
                droneYawDegrees + normalized(axes.yaw) * MAX_YAW_RATE_DEGREES_PER_SECOND * dtSeconds,
            )
            droneLateralMeters = (
                droneLateralMeters + normalized(axes.lateral) * MAX_TRANSLATION_METERS_PER_SECOND * dtSeconds
                ).coerceIn(-MAX_LATERAL_METERS, MAX_LATERAL_METERS)
            droneVerticalOffsetMeters = (
                droneVerticalOffsetMeters + normalized(axes.vertical) * MAX_TRANSLATION_METERS_PER_SECOND * dtSeconds
                ).coerceIn(-MAX_VERTICAL_OFFSET_METERS, MAX_VERTICAL_OFFSET_METERS)
            heightMeters = (TAKEOFF_HEIGHT_METERS + droneVerticalOffsetMeters).coerceIn(MIN_AIRBORNE_HEIGHT_METERS, MAX_HEIGHT_METERS)
            personDistanceMeters = (
                personDistanceMeters - normalized(axes.forward) * MAX_TRANSLATION_METERS_PER_SECOND * dtSeconds
                ).coerceIn(MIN_DISTANCE_METERS, MAX_DISTANCE_METERS)
            flightTimeSeconds += dtSeconds
        }
        return snapshotLocked()
    }

    @Synchronized
    fun snapshot(): SimulatorPlantSnapshot = snapshotLocked()

    private fun snapshotLocked(): SimulatorPlantSnapshot {
        val relativeBearing = personWorldBearingDegrees - droneYawDegrees -
            droneLateralMeters * LATERAL_PARALLAX_DEGREES_PER_METER
        val horizontal = .5f + relativeBearing / HORIZONTAL_FIELD_OF_VIEW_DEGREES
        val verticalCenter = .52f + droneVerticalOffsetMeters * VERTICAL_POSITION_PER_METER
        val boxHeight = (BASE_PERSON_BOX_HEIGHT * INITIAL_DISTANCE_METERS / personDistanceMeters)
            .coerceIn(MIN_BOX_HEIGHT, MAX_BOX_HEIGHT)
        val boxWidth = boxHeight * PERSON_ASPECT_RATIO
        val projected = if (!personVisible || horizontal + boxWidth / 2f <= 0f || horizontal - boxWidth / 2f >= 1f) {
            null
        } else {
            val left = (horizontal - boxWidth / 2f).coerceIn(0f, 1f)
            val right = (horizontal + boxWidth / 2f).coerceIn(0f, 1f)
            val top = (verticalCenter - boxHeight / 2f).coerceIn(0f, 1f)
            val bottom = (verticalCenter + boxHeight / 2f).coerceIn(0f, 1f)
            if (right - left < MIN_VALID_BOX_EDGE || bottom - top < MIN_VALID_BOX_EDGE) null
            else SimulatorBoundingBox(left, top, right, bottom)
        }
        return SimulatorPlantSnapshot(
            airborne = airborne,
            heightMeters = heightMeters,
            droneYawDegrees = droneYawDegrees,
            personWorldBearingDegrees = personWorldBearingDegrees,
            horizontalPosition = horizontal,
            horizontalError = horizontal - .5f,
            personVisible = personVisible,
            boundingBox = projected,
            appliedAxes = axes,
            flightTimeSeconds = flightTimeSeconds.toInt(),
        )
    }

    private fun normalized(value: Int) = value.coerceIn(-100, 100) / 100f

    private fun approach(current: Float, target: Float, maximumDelta: Float): Float = when {
        abs(target - current) <= maximumDelta -> target
        target > current -> current + maximumDelta
        else -> current - maximumDelta
    }

    private fun wrapDegrees(value: Float): Float {
        var wrapped = value
        while (wrapped > 180f) wrapped -= 360f
        while (wrapped < -180f) wrapped += 360f
        return wrapped
    }

    companion object {
        const val RUNTIME_STEP_SECONDS = .05f
        const val TAKEOFF_HEIGHT_METERS = 1.2f
        const val PERSON_SCENARIO_BEARING_DEGREES = 15f
        private const val PERSON_BEARING_RATE_DEGREES_PER_SECOND = 8f
        private const val HORIZONTAL_FIELD_OF_VIEW_DEGREES = 60f
        private const val LATERAL_PARALLAX_DEGREES_PER_METER = 12f
        private const val VERTICAL_POSITION_PER_METER = .24f
        private const val MAX_YAW_RATE_DEGREES_PER_SECOND = 90f
        private const val MAX_TRANSLATION_METERS_PER_SECOND = 2f
        private const val INITIAL_DISTANCE_METERS = 3f
        private const val MIN_DISTANCE_METERS = 1.1f
        private const val MAX_DISTANCE_METERS = 8f
        private const val MAX_LATERAL_METERS = 2.5f
        private const val MAX_VERTICAL_OFFSET_METERS = 1.5f
        private const val MIN_AIRBORNE_HEIGHT_METERS = .3f
        private const val MAX_HEIGHT_METERS = 3f
        private const val MAX_WORLD_BEARING_DEGREES = 45f
        private const val BASE_PERSON_BOX_HEIGHT = .46f
        private const val MIN_BOX_HEIGHT = .18f
        private const val MAX_BOX_HEIGHT = .72f
        private const val PERSON_ASPECT_RATIO = .38f
        private const val MIN_VALID_BOX_EDGE = .01f
    }
}
