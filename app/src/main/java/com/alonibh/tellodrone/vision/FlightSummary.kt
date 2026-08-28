package com.alonibh.tellodrone.vision

import kotlin.math.abs
import kotlin.math.sqrt

internal data class FlightSummaryEvent(val kind: String, val timestampNanos: Long, val frameSequence: Long?)

internal data class FlightSummary(
    val durationMs: Long, val armedMs: Long, val activeMs: Long, val matchedPercent: Double?,
    val missingCount: Int, val missingMs: Long, val longestMissingMs: Long, val lostCount: Int, val requiresRearmCount: Int,
    val inferenceP50: Double?, val inferenceP95: Double?, val detectorFps: Double?, val analysisFps: Double?, val previewFps: Double?,
    val ageP50: Double?, val ageP95: Double?, val ageMax: Long?, val excessiveAgeRejections: Int,
    val meanAbsYaw: Double?, val p95AbsYaw: Double?, val maxAbsYaw: Int?, val maxYawStep: Int?, val slewLimited: Int,
    val jumpSuppressions: Int, val crossingBrakes: Int, val stableRecoverySuppressions: Int, val physicalExpirations: Int,
    val manualOverrides: Int, val stopHoverPreemptions: Int, val emergencyEvents: Int, val lostSafetyLatches: Int,
    val nonYawAutonomousAxisViolations: Int,
    val heightMin: Double?, val heightMax: Double?, val heightMean: Double?, val heightRange: Double?, val heightStdDev: Double?, val verticalVelocityP95: Double?,
    val notableEvents: List<FlightSummaryEvent>,
)

/** Pure, export-time analysis of recorded trace files. It is deliberately outside the control path. */
internal object FlightSummaryBuilder {
    fun build(traceLines: List<String>, controlLines: List<String>): FlightSummary {
        val traces = traceLines.mapNotNull(::record).sortedBy { it.long("sourceTimestampNanos") ?: Long.MAX_VALUE }
        val controls = controlLines.mapNotNull(::record).sortedBy { it.long("commandTimestampNanos") ?: Long.MAX_VALUE }
        val allTimes = traces.mapNotNull { it.long("sourceTimestampNanos") } + controls.mapNotNull { it.long("commandTimestampNanos") }
        val associations = intervals(traces, "sourceTimestampNanos", "associationState")
        val yawStates = intervals(controls, "commandTimestampNanos", "yawFollowState")
        val missing = associations.filter { it.value == "TemporarilyMissing" }
        val selectedMs = associations.filter { it.value in SELECTED_STATES }.sumOf { it.durationMs }
        val matchedMs = associations.filter { it.value == "Matched" }.sumOf { it.durationMs }
        val inference = traces.mapNotNull { it.double("detector", "inferenceMillis") }
        val publications = controls.filter { it.string("eventType") == "rcPublication" }
        val ages = publications.mapNotNull { it.long("perceptionAgeMillis") }.ifEmpty { controls.mapNotNull { it.long("perceptionAgeMillis") } }
        val sentAutonomous = publications.filter { it.string("inputKind") == "AUTONOMOUS_YAW" && it.string("sendSuppressionReason") == "NONE" }
        val sentYaw = sentAutonomous.mapNotNull { it.int("actualSentVector", "yaw") }
        val absYaw = sentYaw.map(::abs)
        val heights = controls.mapNotNull { it.double("telemetryHeightMeters") }
        val events = mutableListOf<FlightSummaryEvent>()
        fun count(kind: String, predicate: (Record) -> Boolean): Int {
            val values = episodes(controls, predicate)
            values.take(3).forEach { events += FlightSummaryEvent(kind, it.long("commandTimestampNanos") ?: 0L, it.long("frameSequence")) }
            return values.size
        }
        val stale = count("stale perception") { it.string("suppressionReason") == "STALE_PERCEPTION" || it.string("sendSuppressionReason") == "PERCEPTION_AGE_EXPIRED" }
        val jump = count("jump rejection") { it.suppression() == "TARGET_JUMP_REJECTED" }
        val crossing = count("center-crossing brake") { it.suppression() == "CENTER_CROSSING_BRAKE" }
        val rearm = count("Lost/re-arm") { it.string("yawFollowState") == "REQUIRES_REARM" }
        val manual = count("manual override") { it.string("yawFollowReason") == "MANUAL_OVERRIDE" }
        val hover = count("STOP/HOVER") { it.string("yawFollowReason") == "HOVER_INTERVENTION" }
        val emergency = count("Emergency") { it.string("yawFollowReason") == "EMERGENCY" }
        return FlightSummary(
            durationMs = allTimes.maxOrNull()?.minus(allTimes.minOrNull() ?: 0L)?.div(MS_NANOS)?.coerceAtLeast(0L) ?: 0L,
            armedMs = yawStates.filter { it.value in setOf("ARMED_WAITING", "ACTIVE") }.sumOf { it.durationMs }, activeMs = yawStates.filter { it.value == "ACTIVE" }.sumOf { it.durationMs },
            matchedPercent = if (selectedMs == 0L) null else matchedMs * 100.0 / selectedMs,
            missingCount = missing.size, missingMs = missing.sumOf { it.durationMs }, longestMissingMs = missing.maxOfOrNull { it.durationMs } ?: 0L,
            lostCount = episodes(traces) { it.string("associationState") == "Lost" }.size, requiresRearmCount = rearm,
            inferenceP50 = percentile(inference, .50), inferenceP95 = percentile(inference, .95), detectorFps = fps(traces.filter { it.double("detector", "inferenceMillis") != null }), analysisFps = fps(traces), previewFps = null,
            ageP50 = percentile(ages.map { it.toDouble() }, .50), ageP95 = percentile(ages.map { it.toDouble() }, .95), ageMax = ages.maxOrNull(), excessiveAgeRejections = stale,
            meanAbsYaw = absYaw.takeIf { it.isNotEmpty() }?.average(), p95AbsYaw = percentile(absYaw.map { it.toDouble() }, .95), maxAbsYaw = absYaw.maxOrNull(), maxYawStep = sentYaw.zipWithNext { a, b -> abs(b - a) }.maxOrNull(),
            slewLimited = controls.count { it.string("eventType") == "controlMeasurement" && it.string("suppressionReason") == "NONE" && it.int("requestedYawRc") != it.int("safetyFilteredYawRc") },
            jumpSuppressions = jump, crossingBrakes = crossing, stableRecoverySuppressions = count("stable recovery") { it.suppression() == "STABLE_RESUME" },
            physicalExpirations = count("physical command expiration") { it.string("sendSuppressionReason") in EXPIRATION_REASONS }, manualOverrides = manual, stopHoverPreemptions = hover, emergencyEvents = emergency,
            lostSafetyLatches = count("Lost/re-arm") { it.string("yawFollowReason") == "TARGET_LOST" },
            nonYawAutonomousAxisViolations = sentAutonomous.count { it.int("actualSentVector", "lateral") != 0 || it.int("actualSentVector", "forward") != 0 || it.int("actualSentVector", "vertical") != 0 },
            heightMin = heights.minOrNull(), heightMax = heights.maxOrNull(), heightMean = heights.takeIf { it.isNotEmpty() }?.average(), heightRange = heights.takeIf { it.isNotEmpty() }?.let { it.max() - it.min() }, heightStdDev = standardDeviation(heights), verticalVelocityP95 = null,
            notableEvents = events.distinct().sortedBy { it.timestampNanos }.take(12),
        )
    }

    fun json(s: FlightSummary): String = listOf(
        "session_duration_ms" to s.durationMs, "yaw_follow_armed_duration_ms" to s.armedMs, "yaw_follow_active_duration_ms" to s.activeMs,
        "matched_percent_of_selected_target_time" to s.matchedPercent, "temporarily_missing_count" to s.missingCount, "temporarily_missing_total_ms" to s.missingMs, "longest_temporarily_missing_ms" to s.longestMissingMs, "lost_count" to s.lostCount, "requires_rearm_count" to s.requiresRearmCount,
        "detector_inference_p50_ms" to s.inferenceP50, "detector_inference_p95_ms" to s.inferenceP95, "detector_fps" to s.detectorFps, "analysis_fps" to s.analysisFps, "preview_fps" to s.previewFps,
        "perception_age_p50_ms" to s.ageP50, "perception_age_p95_ms" to s.ageP95, "perception_age_max_ms" to s.ageMax, "measurements_rejected_for_excessive_age" to s.excessiveAgeRejections,
        "mean_absolute_physical_yaw_rc" to s.meanAbsYaw, "p95_absolute_yaw_rc" to s.p95AbsYaw, "maximum_absolute_yaw_rc" to s.maxAbsYaw, "maximum_yaw_step" to s.maxYawStep, "slew_limited_commands" to s.slewLimited,
        "target_error_jump_suppressions" to s.jumpSuppressions, "center_crossing_brake_events" to s.crossingBrakes, "stable_recovery_consistency_suppressions" to s.stableRecoverySuppressions, "physical_command_expirations" to s.physicalExpirations,
        "manual_override_preemptions" to s.manualOverrides, "stop_hover_preemptions" to s.stopHoverPreemptions, "emergency_events" to s.emergencyEvents, "lost_safety_latches" to s.lostSafetyLatches, "non_yaw_autonomous_axis_violations" to s.nonYawAutonomousAxisViolations,
        "height_min_m" to s.heightMin, "height_max_m" to s.heightMax, "height_mean_m" to s.heightMean, "height_range_m" to s.heightRange, "height_standard_deviation_m" to s.heightStdDev, "vertical_velocity_p95_mps" to s.verticalVelocityP95,
    ).joinToString(prefix = "{\n", postfix = ",\n  \"notable_events\": [${s.notableEvents.joinToString { "{\"kind\":\"${it.kind}\",\"timestamp_nanos\":${it.timestampNanos},\"frame_sequence\":${it.frameSequence ?: "null"}}" }}]\n}", separator = ",\n") { "  \"${it.first}\": ${jsonValue(it.second)}" }

    fun text(s: FlightSummary): String = buildString {
        appendLine("FLIGHT / YAW FOLLOW SUMMARY"); appendLine("Duration: ${duration(s.durationMs)}"); appendLine("Yaw armed / active: ${duration(s.armedMs)} / ${duration(s.activeMs)}")
        appendLine("Tracking matched: ${metric(s.matchedPercent, "%")}"); appendLine("Missing: ${s.missingCount} events / longest ${duration(s.longestMissingMs)}"); appendLine("Lost: ${s.lostCount}"); appendLine()
        appendLine("Perception age p50/p95/max: ${metric(s.ageP50, " ms")} / ${metric(s.ageP95, " ms")} / ${s.ageMax?.let { "$it ms" } ?: "unavailable"}")
        appendLine("Yaw RC p95/max: ${metric(s.p95AbsYaw)} / ${s.maxAbsYaw ?: "unavailable"}"); appendLine("Safety suppressions: stale ${s.excessiveAgeRejections}, jump ${s.jumpSuppressions}, crossing ${s.crossingBrakes}")
        appendLine("Height min/max/range: ${metric(s.heightMin, " m")} / ${metric(s.heightMax, " m")} / ${metric(s.heightRange, " m")}"); appendLine(); appendLine("NON-YAW AUTONOMOUS AXIS VIOLATIONS: ${s.nonYawAutonomousAxisViolations}")
        if (s.notableEvents.isNotEmpty()) { appendLine(); appendLine("Notable events:"); s.notableEvents.forEach { appendLine("- ${it.kind}: t=${it.timestampNanos} frame=${it.frameSequence ?: "—"}") } }
    }

    private data class Interval(val value: String?, val durationMs: Long)
    private fun intervals(records: List<Record>, timestamp: String, value: String) = records.zipWithNext().map { (a, b) -> Interval(a.string(value), ((b.long(timestamp) ?: 0) - (a.long(timestamp) ?: 0)).coerceAtLeast(0) / MS_NANOS) }
    private fun episodes(records: List<Record>, predicate: (Record) -> Boolean) = records.filterIndexed { index, record -> predicate(record) && (index == 0 || !predicate(records[index - 1])) }
    private fun fps(records: List<Record>): Double? { val ts = records.mapNotNull { it.long("sourceTimestampNanos") }; val span = (ts.maxOrNull() ?: return null) - (ts.minOrNull() ?: return null); return if (span <= 0) null else (ts.size - 1) * 1_000_000_000.0 / span }
    private fun percentile(values: List<Double>, p: Double): Double? = values.sorted().takeIf { it.isNotEmpty() }?.let { it[((it.size - 1) * p).toInt()] }
    private fun standardDeviation(values: List<Double>): Double? = values.takeIf { it.isNotEmpty() }?.let { sqrt(it.map { v -> (v - it.average()) * (v - it.average()) }.average()) }
    private fun duration(ms: Long) = "${ms / 60_000}:${"%02d".format((ms / 1_000) % 60)}"
    private fun metric(value: Double?, suffix: String = "") = value?.let { "%.1f%s".format(it, suffix) } ?: "unavailable"
    private fun jsonValue(value: Any?): String = when (value) { null -> "null"; is String -> "\"${value.replace("\"", "\\\"")}\""; else -> value.toString() }
    private fun record(line: String): Record? = runCatching { Record(CompactJson.parseObject(line)) }.getOrNull()
    private const val MS_NANOS = 1_000_000L
    private val SELECTED_STATES = setOf("Selected", "Matched", "TemporarilyMissing", "Ambiguous")
    private val EXPIRATION_REASONS = setOf("RC_TTL_EXPIRED", "PERCEPTION_AGE_EXPIRED")
}

private class Record(private val values: Map<String, Any?>) {
    fun string(key: String) = values[key] as? String
    fun long(key: String) = (values[key] as? Number)?.toLong()
    fun double(key: String) = (values[key] as? Number)?.toDouble()
    fun int(key: String) = (values[key] as? Number)?.toInt()
    @Suppress("UNCHECKED_CAST") private fun nested(key: String) = values[key] as? Map<String, Any?>
    fun double(parent: String, key: String) = (nested(parent)?.get(key) as? Number)?.toDouble()
    fun int(parent: String, key: String) = (nested(parent)?.get(key) as? Number)?.toInt()
    fun suppression() = string("suppressionReason") ?: string("yawSuppressionReason")
}
