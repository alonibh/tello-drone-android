package com.alonibh.tellodrone.vision

import kotlin.math.abs
import kotlin.math.sqrt

internal data class FlightSummaryEvent(val kind: String, val timestampNanos: Long, val frameSequence: Long?)

internal data class FlightSummary(
    val durationMs: Long, val armedMs: Long, val activeMs: Long, val matchedPercent: Double?,
    val missingCount: Int, val missingMs: Long, val longestMissingMs: Long, val lostCount: Int, val requiresRearmCount: Int,
    val inferenceP50: Double?, val inferenceP95: Double?, val detectorFps: Double?, val analysisFps: Double?, val previewFps: Double?,
    val renderToPixelCopyP50: Double?, val renderToPixelCopyP95: Double?,
    val renderToDetectorStartP50: Double?, val renderToDetectorStartP95: Double?,
    val renderToDetectorCompleteP50: Double?, val renderToDetectorCompleteP95: Double?,
    val detectorCompleteToAssociationP50: Double?, val detectorCompleteToAssociationP95: Double?,
    val sourceToDecisionP50: Double?, val sourceToDecisionP95: Double?,
    val decisionToSendP50: Double?, val decisionToSendP95: Double?,
    val sourceToPhysicalSendP50: Double?, val sourceToPhysicalSendP95: Double?,
    val analysisDroppedFrames: Long?, val maximumAnalysisPendingDepth: Int?,
    val preprocessingP50: Double?, val preprocessingP95: Double?,
    val modelInferenceP50: Double?, val modelInferenceP95: Double?,
    val decodeAndNmsP50: Double?, val decodeAndNmsP95: Double?,
    val appearanceP50: Double?, val appearanceP95: Double?,
    val ageP50: Double?, val ageP95: Double?, val ageMax: Long?, val excessiveAgeRejections: Int,
    val meanAbsYaw: Double?, val p95AbsYaw: Double?, val maxAbsYaw: Int?, val maxYawStep: Int?, val normalMaxYawStep: Int?, val safetyMaxYawStep: Int?, val slewLimited: Int,
    val jumpSuppressions: Int, val crossingBrakes: Int, val stableRecoverySuppressions: Int, val physicalExpirations: Int,
    val manualOverrides: Int, val stopHoverPreemptions: Int, val emergencyEvents: Int, val lostSafetyLatches: Int,
    val nonYawAutonomousAxisViolations: Int,
    val fractionOfActiveNonZeroYaw: Double?,
    val commandHoldExpiredCount: Int, val commandHoldExpiredPercent: Double?,
    val sourceAgeExpiredCount: Int, val sourceAgeExpiredPercent: Double?,
    val longestExpirationZeroIntervalMs: Long,
    val interMeasurementMeanMs: Double?, val interMeasurementP50Ms: Double?, val interMeasurementP95Ms: Double?,
    val timeOutsideError15Ms: Long, val timeOutsideError20Ms: Long,
    val maxContinuousOutsideError15Ms: Long, val maxContinuousOutsideError20Ms: Long,
    val heightMin: Double?, val heightMax: Double?, val heightMean: Double?, val heightRange: Double?, val heightStdDev: Double?, val verticalVelocityP95: Double?,
    val maximumYawRate: Double?, val timeFromYawZeroRequestUntilSettledMs: Long?,
    val centerCrossingsCount: Int, val directionReversalsCount: Int, val reversalsWhileYawRateUnsettledCount: Int,
    val yawResponseAnomaliesCount: Int, val yawResponseMismatchSuspectCount: Int,
    val maxObservedTelemetryYawRateDps: Double?, val maxObservedRawTelemetryYawRateDps: Double?,
    val meanInterSendIntervalMs: Double?, val p95InterSendIntervalMs: Double?, val maxSendDurationMs: Double?,
    val notableEvents: List<FlightSummaryEvent>,
)

/** Pure, export-time analysis of recorded trace files. It is deliberately outside the control path. */
internal object FlightSummaryBuilder {
    fun build(traceLines: List<String>, controlLines: List<String>): FlightSummary {
        val traces = traceLines.mapNotNull(::record).sortedBy { it.long("sourceTimestampNanos") ?: Long.MAX_VALUE }
        val controls = controlLines.mapNotNull(::record).sortedBy { it.long("commandTimestampNanos") ?: it.long("receivedAtMonotonicMillis")?.times(MS_NANOS) ?: Long.MAX_VALUE }
        val allTimes = traces.mapNotNull { it.long("sourceTimestampNanos") } + controls.mapNotNull { it.long("commandTimestampNanos") ?: it.long("receivedAtMonotonicMillis")?.times(MS_NANOS) }
        val associations = intervals(traces, "sourceTimestampNanos", "associationState")
        val yawStates = intervals(controls.filter { it.string("yawFollowState") != null }, "commandTimestampNanos", "yawFollowState")
        val missing = associations.filter { it.value == "TemporarilyMissing" }
        val selectedMs = associations.filter { it.value in SELECTED_STATES }.sumOf { it.durationMs }
        val matchedMs = associations.filter { it.value == "Matched" }.sumOf { it.durationMs }
        val inference = traces.mapNotNull { it.double("detector", "inferenceMillis") }
        val publications = controls.filter { it.string("eventType") == "rcPublication" }
        fun elapsedMillis(records: List<Record>, start: String, end: String): List<Double> = records.mapNotNull { record ->
            val startNanos = record.long(start) ?: return@mapNotNull null
            val endNanos = record.long(end) ?: return@mapNotNull null
            (endNanos - startNanos).takeIf { it >= 0L }?.div(1_000_000.0)
        }
        fun stageMillis(key: String) = traces.mapNotNull { it.long(key)?.div(1_000_000.0) }
        val renderToPixelCopy = elapsedMillis(traces, "renderedFrameTimestampNanos", "pixelCopyCompletedTimestampNanos")
        val renderToDetectorStart = elapsedMillis(traces, "renderedFrameTimestampNanos", "detectorInferenceStartedTimestampNanos")
        val renderToDetectorComplete = elapsedMillis(traces, "renderedFrameTimestampNanos", "detectorInferenceCompletedTimestampNanos")
        val detectorToAssociation = elapsedMillis(traces, "detectorInferenceCompletedTimestampNanos", "associationCompletedTimestampNanos")
        val controlMeasurements = controls.filter { it.string("eventType") == "controlMeasurement" }
        val uniqueControlMeasurements = if (controlMeasurements.isNotEmpty()) {
            controlMeasurements.distinctBy {
                it.long("frameSequence") to it.long("sourceTimestampNanos")
            }
        } else {
            controls.filter { it.string("eventType") != "telemetrySample" && it.string("eventType") != "yawResponseAnomalyEvent" }
        }
        val publicationRecords = if (publications.isNotEmpty()) publications else controls
        val sourceToDecision = elapsedMillis(uniqueControlMeasurements, "sourceTimestampNanos", "yawDecisionTimestampNanos")
        val sentAutonomous = publicationRecords.filter { it.string("inputKind") == "AUTONOMOUS_YAW" && it.string("sendSuppressionReason") == "NONE" }
        val firstSendPerDecision = (if (sentAutonomous.isNotEmpty()) sentAutonomous else publicationRecords.filter { it.string("inputKind") == "AUTONOMOUS_YAW" })
            .groupBy { it.long("yawDecisionTimestampNanos") ?: it.long("frameSequence") ?: it.long("commandTimestampNanos") }
            .mapNotNull { (_, group) -> group.minByOrNull { it.long("actualSentAtNanos") ?: Long.MAX_VALUE } }
        val decisionToSend = elapsedMillis(firstSendPerDecision, "yawDecisionTimestampNanos", "actualSentAtNanos")
        val sourceToSend = elapsedMillis(firstSendPerDecision, "sourceTimestampNanos", "actualSentAtNanos")
        val preprocessing = stageMillis("detectorPreprocessingNanos")
        val modelInference = stageMillis("detectorModelInferenceNanos")
        val decodeAndNms = stageMillis("detectorDecodeAndNmsNanos")
        val appearance = stageMillis("detectorAppearanceNanos")
        val ages = uniqueControlMeasurements.mapNotNull { measurement ->
            val source = measurement.long("sourceTimestampNanos") ?: return@mapNotNull null
            val decision = measurement.long("yawDecisionTimestampNanos") ?: return@mapNotNull null
            (decision - source).takeIf { it >= 0L }?.div(MS_NANOS)
        }
        val sentYaw = sentAutonomous.mapNotNull { it.int("actualSentVector", "yaw") }
        val absYaw = sentYaw.map(::abs)
        val heights = controls.mapNotNull { it.double("telemetryHeightMeters") ?: it.double("heightMeters") }
        val telemetrySamples = controls.filter { it.string("eventType") == "telemetrySample" }
        val events = mutableListOf<FlightSummaryEvent>()
        fun countEpisodes(records: List<Record>, kind: String, predicate: (Record) -> Boolean): Int {
            val values = episodes(records, predicate)
            values.take(3).forEach { events += FlightSummaryEvent(kind, it.long("commandTimestampNanos") ?: (it.long("receivedAtMonotonicMillis")?.times(MS_NANOS)) ?: 0L, it.long("frameSequence")) }
            return values.size
        }
        val stale = countEpisodes(uniqueControlMeasurements, "stale perception") { it.suppression() == "STALE_PERCEPTION" || it.string("sendSuppressionReason") == "PERCEPTION_AGE_EXPIRED" }
        val jump = countEpisodes(uniqueControlMeasurements, "jump rejection") { it.suppression() == "TARGET_JUMP_REJECTED" }
        val crossing = countEpisodes(uniqueControlMeasurements, "center-crossing brake") { it.suppression() == "CENTER_CROSSING_BRAKE" || it.string("controllerPhase") == "SETTLING" }
        val stableRecovery = countEpisodes(uniqueControlMeasurements, "stable recovery") { it.suppression() == "STABLE_RESUME" }
        val rearm = countEpisodes(publicationRecords, "Lost/re-arm") { it.string("yawFollowState") == "REQUIRES_REARM" }
        val manual = countEpisodes(publicationRecords, "manual override") { it.string("yawFollowReason") == "MANUAL_OVERRIDE" }
        val hover = countEpisodes(publicationRecords, "STOP/HOVER") { it.string("yawFollowReason") == "HOVER_INTERVENTION" }
        val emergency = countEpisodes(publicationRecords, "Emergency") { it.string("yawFollowReason") == "EMERGENCY" }
        val lostLatches = countEpisodes(publicationRecords, "Lost/re-arm") { it.string("yawFollowReason") == "TARGET_LOST" }

        val activePublications = publicationRecords.filter {
            it.string("inputKind") == "AUTONOMOUS_YAW" && it.string("yawFollowState") != "DISARMED"
        }
        val activeNonZeroCount = activePublications.count { (it.int("actualSentVector", "yaw") ?: 0) != 0 }
        val fractionOfActiveNonZeroYaw = if (activePublications.isEmpty()) null else activeNonZeroCount.toDouble() / activePublications.size
        val sourceAgeExpiredPublications = activePublications.filter {
            it.string("sendSuppressionReason") == "PERCEPTION_AGE_EXPIRED"
        }
        val commandHoldExpiredPublications = activePublications.filter {
            it.string("sendSuppressionReason") == "AUTONOMOUS_COMMAND_HOLD_EXPIRED"
        }
        val sourceAgeExpiredPercent = if (activePublications.isEmpty()) null else
            sourceAgeExpiredPublications.size * 100.0 / activePublications.size
        val commandHoldExpiredPercent = if (activePublications.isEmpty()) null else
            commandHoldExpiredPublications.size * 100.0 / activePublications.size

        var maxExpirationZeroSpanMs = 0L
        var curZeroStart: Long? = null
        for (pub in activePublications) {
            val isExpired = pub.string("sendSuppressionReason") in setOf(
                "PERCEPTION_AGE_EXPIRED",
                "AUTONOMOUS_COMMAND_HOLD_EXPIRED",
            )
            val ts = pub.long("commandTimestampNanos") ?: 0L
            if (isExpired) {
                if (curZeroStart == null) curZeroStart = ts
                val span = (ts - curZeroStart) / MS_NANOS
                if (span > maxExpirationZeroSpanMs) maxExpirationZeroSpanMs = span
            } else {
                curZeroStart?.let { start ->
                    val span = (ts - start).coerceAtLeast(0L) / MS_NANOS
                    if (span > maxExpirationZeroSpanMs) maxExpirationZeroSpanMs = span
                }
                curZeroStart = null
            }
        }

        val measurementTimes = (controlMeasurements.mapNotNull { it.long("sourceTimestampNanos") }.ifEmpty { controls.mapNotNull { it.long("sourceTimestampNanos") } }).distinct().sorted()
        val interMeasurementIntervalsMs = measurementTimes.zipWithNext().map { (a, b) -> (b - a).coerceAtLeast(0L) / 1_000_000.0 }
        val interMeasurementMeanMs = interMeasurementIntervalsMs.takeIf { it.isNotEmpty() }?.average()
        val interMeasurementP50Ms = percentile(interMeasurementIntervalsMs, 0.50)
        val interMeasurementP95Ms = percentile(interMeasurementIntervalsMs, 0.95)

        val errorRecords = controls.mapNotNull { rec ->
            (rec.double("rawYawError") ?: rec.double("filteredYawError"))?.let { err ->
                (rec.long("commandTimestampNanos") ?: 0L) to abs(err)
            }
        }.sortedBy { it.first }

        var timeOutside15Ms = 0L
        var timeOutside20Ms = 0L
        var maxCont15Ms = 0L
        var maxCont20Ms = 0L
        var curStart15: Long? = null
        var curStart20: Long? = null
        for (i in 0 until errorRecords.size - 1) {
            val (t1, err1) = errorRecords[i]
            val (t2, _) = errorRecords[i + 1]
            val dtMs = ((t2 - t1).coerceAtLeast(0L)) / MS_NANOS
            if (err1 > 0.15) {
                timeOutside15Ms += dtMs
                if (curStart15 == null) curStart15 = t1
                val span = (t2 - curStart15) / MS_NANOS
                if (span > maxCont15Ms) maxCont15Ms = span
            } else {
                curStart15 = null
            }
            if (err1 > 0.20) {
                timeOutside20Ms += dtMs
                if (curStart20 == null) curStart20 = t1
                val span = (t2 - curStart20) / MS_NANOS
                if (span > maxCont20Ms) maxCont20Ms = span
            } else {
                curStart20 = null
            }
        }

        val yawRates = controls.mapNotNull { it.double("telloYawRateDegreesPerSecond")?.let(::abs) ?: it.double("filteredYawRateDegreesPerSecond")?.let(::abs) }
        val rawYawRates = controls.mapNotNull { it.double("rawYawRateDegreesPerSecond")?.let(::abs) }
        val maxYawRate = yawRates.maxOrNull()
        val maxRawYawRate = rawYawRates.maxOrNull()
        val nonZeroYawPubs = sentAutonomous.mapNotNull { it.int("actualSentVector", "yaw")?.takeIf { y -> y != 0 } }
        val directionReversalsCount = nonZeroYawPubs.zipWithNext().count { (a, b) -> (a > 0 && b < 0) || (a < 0 && b > 0) }

        val nonZeroYawEvents = controls.filter {
            val y = it.int("requestedYawRc") ?: it.int("actualSentVector", "yaw") ?: 0
            y != 0
        }
        val reversalsWhileYawRateUnsettledCount = nonZeroYawEvents.zipWithNext().count { (a, b) ->
            val yawA = a.int("requestedYawRc") ?: a.int("actualSentVector", "yaw") ?: 0
            val yawB = b.int("requestedYawRc") ?: b.int("actualSentVector", "yaw") ?: 0
            val reversed = (yawA > 0 && yawB < 0) || (yawA < 0 && yawB > 0)
            val yawRate = abs(b.double("telloYawRateDegreesPerSecond") ?: a.double("telloYawRateDegreesPerSecond") ?: 0.0)
            reversed && yawRate > 8.0
        }

        // True settling duration per episode
        val settlingEpisodes = episodes(uniqueControlMeasurements) { it.string("controllerPhase") == "SETTLING" || it.suppression() == "CENTER_CROSSING_BRAKE" }
        val settlingDurationsMs = settlingEpisodes.mapNotNull { ep ->
            val startNanos = ep.long("commandTimestampNanos") ?: return@mapNotNull null
            val subsequent = telemetrySamples.filter {
                val t = it.long("receivedAtNanos") ?: (it.long("receivedAtMonotonicMillis")?.times(MS_NANOS)) ?: 0L
                t >= startNanos
            }
            var consecutiveSettled = 0
            var settledNanos: Long? = null
            for (sample in subsequent) {
                val rate = sample.double("filteredYawRateDegreesPerSecond") ?: sample.double("rawYawRateDegreesPerSecond")
                if (rate != null && abs(rate) <= 8.0) {
                    consecutiveSettled++
                    if (consecutiveSettled >= 2) {
                        settledNanos = sample.long("receivedAtNanos") ?: (sample.long("receivedAtMonotonicMillis")?.times(MS_NANOS))
                        break
                    }
                } else {
                    consecutiveSettled = 0
                }
            }
            settledNanos?.let { (it - startNanos).coerceAtLeast(0L) / MS_NANOS }
        }
        val timeFromYawZeroRequestUntilSettledMs = settlingDurationsMs.takeIf { it.isNotEmpty() }?.maxOrNull()

        // Distinguish normal vs safety yaw step
        val allSentYawPublications = publications.filter { it.string("inputKind") in setOf("AUTONOMOUS_YAW", "SAFETY_ZERO") }
        val normalYawSteps = mutableListOf<Int>()
        var currentRun = mutableListOf<Int>()
        var lastEpoch: Long? = null
        var lastGen: Long? = null
        var lastTimestampNanos: Long? = null

        for (pub in publications) {
            val inputKind = pub.string("inputKind")
            val yawFollowState = pub.string("yawFollowState")
            val suppression = pub.string("sendSuppressionReason")
            val epoch = pub.long("flightControlEpoch")
            val gen = pub.long("yawFollowGeneration")
            val timestamp = pub.long("actualSentAtNanos") ?: pub.long("commandTimestampNanos") ?: 0L

            val isContinuousActive = inputKind == "AUTONOMOUS_YAW" &&
                yawFollowState == "ACTIVE" &&
                (suppression == "NONE" || suppression == null) &&
                (lastEpoch == null || epoch == lastEpoch) &&
                (lastGen == null || gen == lastGen) &&
                (lastTimestampNanos == null || (timestamp - lastTimestampNanos).coerceAtLeast(0L) <= 1_500_000_000L)

            val yaw = pub.int("actualSentVector", "yaw")

            if (isContinuousActive && yaw != null) {
                currentRun.add(yaw)
                lastEpoch = epoch
                lastGen = gen
                lastTimestampNanos = timestamp
            } else {
                if (currentRun.size >= 2) {
                    normalYawSteps.addAll(currentRun.zipWithNext { a, b -> abs(b - a) })
                }
                currentRun.clear()
                if (inputKind == "AUTONOMOUS_YAW" && yawFollowState == "ACTIVE" && (suppression == "NONE" || suppression == null) && yaw != null) {
                    currentRun.add(yaw)
                    lastEpoch = epoch
                    lastGen = gen
                    lastTimestampNanos = timestamp
                } else {
                    lastEpoch = null
                    lastGen = null
                    lastTimestampNanos = null
                }
            }
        }
        if (currentRun.size >= 2) {
            normalYawSteps.addAll(currentRun.zipWithNext { a, b -> abs(b - a) })
        }
        val normalMaxYawStep = normalYawSteps.maxOrNull()
        val allYawSteps = allSentYawPublications.mapNotNull { it.int("actualSentVector", "yaw") }.zipWithNext { a, b -> abs(b - a) }
        val safetyMaxYawStep = allYawSteps.maxOrNull()
        val maxYawStep = normalMaxYawStep ?: safetyMaxYawStep

        // Transport metrics
        val interSendIntervals = publications.mapNotNull { it.double("interSendIntervalMillis") }
        val sendDurations = publications.mapNotNull { it.long("sendDurationNanos")?.div(1_000_000.0) }
        val meanInterSend = interSendIntervals.takeIf { it.isNotEmpty() }?.average()
        val p95InterSend = percentile(interSendIntervals, 0.95)
        val maxSendDuration = sendDurations.maxOrNull()

        // Anomaly events
        val anomalyLatchedCount = controls.count {
            it.string("eventType") == "yawResponseAnomalyEvent" && it.string("subType") == "yaw_response_anomaly_latched"
        }
        val anomalySuspectCount = controls.count {
            it.string("eventType") == "yawResponseAnomalyEvent" && it.string("subType") == "yaw_response_mismatch_suspect"
        }

        // Filter physical expirations over publications to avoid false fragmentation by interleaved measurements
        val physicalExpirationsCount = episodes(publications) { it.string("sendSuppressionReason") in EXPIRATION_REASONS }.size

        return FlightSummary(
            durationMs = allTimes.maxOrNull()?.minus(allTimes.minOrNull() ?: 0L)?.div(MS_NANOS)?.coerceAtLeast(0L) ?: 0L,
            armedMs = yawStates.filter { it.value in setOf("ARMED_WAITING", "ACTIVE") }.sumOf { it.durationMs }, activeMs = yawStates.filter { it.value == "ACTIVE" }.sumOf { it.durationMs },
            matchedPercent = if (selectedMs == 0L) null else matchedMs * 100.0 / selectedMs,
            missingCount = missing.size, missingMs = missing.sumOf { it.durationMs }, longestMissingMs = missing.maxOfOrNull { it.durationMs } ?: 0L,
            lostCount = episodes(traces) { it.string("associationState") == "Lost" }.size, requiresRearmCount = rearm,
            inferenceP50 = percentile(inference, .50), inferenceP95 = percentile(inference, .95),
            detectorFps = percentile(traces.mapNotNull { it.double("detectorMeasuredFps") }, .50) ?: fps(traces.filter { it.double("detector", "inferenceMillis") != null }),
            analysisFps = percentile(traces.mapNotNull { it.double("analysisMeasuredFps") }, .50), previewFps = null,
            renderToPixelCopyP50 = percentile(renderToPixelCopy, .50), renderToPixelCopyP95 = percentile(renderToPixelCopy, .95),
            renderToDetectorStartP50 = percentile(renderToDetectorStart, .50), renderToDetectorStartP95 = percentile(renderToDetectorStart, .95),
            renderToDetectorCompleteP50 = percentile(renderToDetectorComplete, .50), renderToDetectorCompleteP95 = percentile(renderToDetectorComplete, .95),
            detectorCompleteToAssociationP50 = percentile(detectorToAssociation, .50), detectorCompleteToAssociationP95 = percentile(detectorToAssociation, .95),
            sourceToDecisionP50 = percentile(sourceToDecision, .50), sourceToDecisionP95 = percentile(sourceToDecision, .95),
            decisionToSendP50 = percentile(decisionToSend, .50), decisionToSendP95 = percentile(decisionToSend, .95),
            sourceToPhysicalSendP50 = percentile(sourceToSend, .50), sourceToPhysicalSendP95 = percentile(sourceToSend, .95),
            analysisDroppedFrames = traces.mapNotNull { it.long("analysisDroppedFrames") }.maxOrNull(),
            maximumAnalysisPendingDepth = traces.mapNotNull { it.int("analysisPendingFrameDepth") }.maxOrNull(),
            preprocessingP50 = percentile(preprocessing, .50), preprocessingP95 = percentile(preprocessing, .95),
            modelInferenceP50 = percentile(modelInference, .50), modelInferenceP95 = percentile(modelInference, .95),
            decodeAndNmsP50 = percentile(decodeAndNms, .50), decodeAndNmsP95 = percentile(decodeAndNms, .95),
            appearanceP50 = percentile(appearance, .50), appearanceP95 = percentile(appearance, .95),
            ageP50 = percentile(ages.map { it.toDouble() }, .50), ageP95 = percentile(ages.map { it.toDouble() }, .95), ageMax = ages.maxOrNull(), excessiveAgeRejections = stale,
            meanAbsYaw = absYaw.takeIf { it.isNotEmpty() }?.average(), p95AbsYaw = percentile(absYaw.map { it.toDouble() }, .95), maxAbsYaw = absYaw.maxOrNull(),
            maxYawStep = maxYawStep, normalMaxYawStep = normalMaxYawStep, safetyMaxYawStep = safetyMaxYawStep,
            slewLimited = controls.count { it.string("eventType") == "controlMeasurement" && it.string("suppressionReason") == "NONE" && it.int("requestedYawRc") != it.int("safetyFilteredYawRc") },
            jumpSuppressions = jump, crossingBrakes = crossing, stableRecoverySuppressions = stableRecovery,
            physicalExpirations = physicalExpirationsCount, manualOverrides = manual, stopHoverPreemptions = hover, emergencyEvents = emergency,
            lostSafetyLatches = lostLatches,
            nonYawAutonomousAxisViolations = sentAutonomous.count { it.int("actualSentVector", "lateral") != 0 || it.int("actualSentVector", "forward") != 0 || it.int("actualSentVector", "vertical") != 0 },
            fractionOfActiveNonZeroYaw = fractionOfActiveNonZeroYaw,
            commandHoldExpiredCount = commandHoldExpiredPublications.size,
            commandHoldExpiredPercent = commandHoldExpiredPercent,
            sourceAgeExpiredCount = sourceAgeExpiredPublications.size,
            sourceAgeExpiredPercent = sourceAgeExpiredPercent,
            longestExpirationZeroIntervalMs = maxExpirationZeroSpanMs,
            interMeasurementMeanMs = interMeasurementMeanMs,
            interMeasurementP50Ms = interMeasurementP50Ms,
            interMeasurementP95Ms = interMeasurementP95Ms,
            timeOutsideError15Ms = timeOutside15Ms,
            timeOutsideError20Ms = timeOutside20Ms,
            maxContinuousOutsideError15Ms = maxCont15Ms,
            maxContinuousOutsideError20Ms = maxCont20Ms,
            heightMin = heights.minOrNull(), heightMax = heights.maxOrNull(), heightMean = heights.takeIf { it.isNotEmpty() }?.average(), heightRange = heights.takeIf { it.isNotEmpty() }?.let { it.max() - it.min() }, heightStdDev = standardDeviation(heights), verticalVelocityP95 = null,
            maximumYawRate = maxYawRate,
            timeFromYawZeroRequestUntilSettledMs = timeFromYawZeroRequestUntilSettledMs,
            centerCrossingsCount = crossing,
            directionReversalsCount = directionReversalsCount,
            reversalsWhileYawRateUnsettledCount = reversalsWhileYawRateUnsettledCount,
            yawResponseAnomaliesCount = anomalyLatchedCount,
            yawResponseMismatchSuspectCount = anomalySuspectCount,
            maxObservedTelemetryYawRateDps = maxYawRate,
            maxObservedRawTelemetryYawRateDps = maxRawYawRate,
            meanInterSendIntervalMs = meanInterSend,
            p95InterSendIntervalMs = p95InterSend,
            maxSendDurationMs = maxSendDuration,
            notableEvents = events.distinct().sortedBy { it.timestampNanos }.take(12),
        )
    }

    fun json(s: FlightSummary): String = listOf(
        "session_duration_ms" to s.durationMs, "yaw_follow_armed_duration_ms" to s.armedMs, "yaw_follow_active_duration_ms" to s.activeMs,
        "matched_percent_of_selected_target_time" to s.matchedPercent, "temporarily_missing_count" to s.missingCount, "temporarily_missing_total_ms" to s.missingMs, "longest_temporarily_missing_ms" to s.longestMissingMs, "lost_count" to s.lostCount, "requires_rearm_count" to s.requiresRearmCount,
        "detector_inference_p50_ms" to s.inferenceP50, "detector_inference_p95_ms" to s.inferenceP95, "detector_fps" to s.detectorFps, "analysis_fps" to s.analysisFps, "preview_fps" to s.previewFps,
        "render_to_pixelcopy_p50_ms" to s.renderToPixelCopyP50, "render_to_pixelcopy_p95_ms" to s.renderToPixelCopyP95,
        "render_to_detector_start_p50_ms" to s.renderToDetectorStartP50, "render_to_detector_start_p95_ms" to s.renderToDetectorStartP95,
        "render_to_detector_complete_p50_ms" to s.renderToDetectorCompleteP50, "render_to_detector_complete_p95_ms" to s.renderToDetectorCompleteP95,
        "detector_complete_to_association_p50_ms" to s.detectorCompleteToAssociationP50, "detector_complete_to_association_p95_ms" to s.detectorCompleteToAssociationP95,
        "source_to_yaw_decision_p50_ms" to s.sourceToDecisionP50, "source_to_yaw_decision_p95_ms" to s.sourceToDecisionP95,
        "yaw_decision_to_actual_rc_send_p50_ms" to s.decisionToSendP50, "yaw_decision_to_actual_rc_send_p95_ms" to s.decisionToSendP95,
        "source_to_actual_rc_send_p50_ms" to s.sourceToPhysicalSendP50, "source_to_actual_rc_send_p95_ms" to s.sourceToPhysicalSendP95,
        "analysis_dropped_frames" to s.analysisDroppedFrames, "maximum_analysis_pending_depth" to s.maximumAnalysisPendingDepth,
        "detector_preprocessing_p50_ms" to s.preprocessingP50, "detector_preprocessing_p95_ms" to s.preprocessingP95,
        "detector_model_p50_ms" to s.modelInferenceP50, "detector_model_p95_ms" to s.modelInferenceP95,
        "detector_decode_nms_p50_ms" to s.decodeAndNmsP50, "detector_decode_nms_p95_ms" to s.decodeAndNmsP95,
        "detector_appearance_p50_ms" to s.appearanceP50, "detector_appearance_p95_ms" to s.appearanceP95,
        "perception_age_p50_ms" to s.ageP50, "perception_age_p95_ms" to s.ageP95, "perception_age_max_ms" to s.ageMax, "measurements_rejected_for_excessive_age" to s.excessiveAgeRejections,
        "mean_absolute_physical_yaw_rc" to s.meanAbsYaw, "p95_absolute_yaw_rc" to s.p95AbsYaw, "maximum_absolute_yaw_rc" to s.maxAbsYaw, "maximum_yaw_step" to s.maxYawStep, "normal_maximum_yaw_step" to s.normalMaxYawStep, "safety_maximum_yaw_step" to s.safetyMaxYawStep, "slew_limited_commands" to s.slewLimited,
        "target_error_jump_suppressions" to s.jumpSuppressions, "center_crossing_brake_events" to s.crossingBrakes, "stable_recovery_consistency_suppressions" to s.stableRecoverySuppressions, "physical_command_expirations" to s.physicalExpirations,
        "manual_override_preemptions" to s.manualOverrides, "stop_hover_preemptions" to s.stopHoverPreemptions, "emergency_events" to s.emergencyEvents, "lost_safety_latches" to s.lostSafetyLatches, "non_yaw_autonomous_axis_violations" to s.nonYawAutonomousAxisViolations,
        "fraction_of_active_non_zero_yaw" to s.fractionOfActiveNonZeroYaw,
        "command_hold_expiration_count" to s.commandHoldExpiredCount,
        "command_hold_expiration_percent" to s.commandHoldExpiredPercent,
        "source_age_expiration_count" to s.sourceAgeExpiredCount,
        "source_age_expiration_percent" to s.sourceAgeExpiredPercent,
        "longest_zero_interval_caused_by_expiration_ms" to s.longestExpirationZeroIntervalMs,
        "inter_measurement_mean_ms" to s.interMeasurementMeanMs,
        "inter_measurement_p50_ms" to s.interMeasurementP50Ms,
        "inter_measurement_p95_ms" to s.interMeasurementP95Ms,
        "time_outside_error_15_ms" to s.timeOutsideError15Ms,
        "time_outside_error_20_ms" to s.timeOutsideError20Ms,
        "max_continuous_outside_error_15_ms" to s.maxContinuousOutsideError15Ms,
        "max_continuous_outside_error_20_ms" to s.maxContinuousOutsideError20Ms,
        "height_min_m" to s.heightMin, "height_max_m" to s.heightMax, "height_mean_m" to s.heightMean, "height_range_m" to s.heightRange, "height_standard_deviation_m" to s.heightStdDev, "vertical_velocity_p95_mps" to s.verticalVelocityP95,
        "maximum_yaw_rate_dps" to s.maximumYawRate,
        "time_from_yaw_zero_request_until_settled_ms" to s.timeFromYawZeroRequestUntilSettledMs,
        "center_crossings_count" to s.centerCrossingsCount,
        "direction_reversals_count" to s.directionReversalsCount,
        "reversals_while_yaw_rate_unsettled_count" to s.reversalsWhileYawRateUnsettledCount,
        "yaw_response_anomalies_count" to s.yawResponseAnomaliesCount,
        "yaw_response_mismatch_suspect_count" to s.yawResponseMismatchSuspectCount,
        "max_observed_telemetry_yaw_rate_dps" to s.maxObservedTelemetryYawRateDps,
        "max_observed_raw_telemetry_yaw_rate_dps" to s.maxObservedRawTelemetryYawRateDps,
        "mean_inter_send_interval_ms" to s.meanInterSendIntervalMs,
        "p95_inter_send_interval_ms" to s.p95InterSendIntervalMs,
        "max_send_duration_ms" to s.maxSendDurationMs,
    ).joinToString(prefix = "{\n", postfix = ",\n  \"notable_events\": [${s.notableEvents.joinToString { "{\"kind\":\"${it.kind}\",\"timestamp_nanos\":${it.timestampNanos},\"frame_sequence\":${it.frameSequence ?: "null"}}" }}]\n}", separator = ",\n") { "  \"${it.first}\": ${jsonValue(it.second)}" }

    fun text(s: FlightSummary): String = buildString {
        appendLine("FLIGHT / YAW FOLLOW SUMMARY"); appendLine("Duration: ${duration(s.durationMs)}"); appendLine("Yaw armed / active: ${duration(s.armedMs)} / ${duration(s.activeMs)}")
        appendLine("Tracking matched: ${metric(s.matchedPercent, "%")}"); appendLine("Missing: ${s.missingCount} events / longest ${duration(s.longestMissingMs)}"); appendLine("Lost: ${s.lostCount}"); appendLine()
        appendLine("Perception age p50/p95/max: ${metric(s.ageP50, " ms")} / ${metric(s.ageP95, " ms")} / ${s.ageMax?.let { "$it ms" } ?: "unavailable"}")
        appendLine("Source -> yaw decision p50/p95: ${metric(s.sourceToDecisionP50, " ms")} / ${metric(s.sourceToDecisionP95, " ms")}")
        appendLine("Yaw decision -> physical send p50/p95: ${metric(s.decisionToSendP50, " ms")} / ${metric(s.decisionToSendP95, " ms")}")
        appendLine("Source -> physical send p50/p95: ${metric(s.sourceToPhysicalSendP50, " ms")} / ${metric(s.sourceToPhysicalSendP95, " ms")}")
        appendLine("Analysis/detector FPS: ${metric(s.analysisFps)} / ${metric(s.detectorFps)}; dropped ${s.analysisDroppedFrames ?: "unavailable"}, max pending ${s.maximumAnalysisPendingDepth ?: "unavailable"}")
        appendLine("Yaw RC p95/max: ${metric(s.p95AbsYaw)} / ${s.maxAbsYaw ?: "unavailable"} (normal max step: ${s.normalMaxYawStep ?: "—"}, safety max step: ${s.safetyMaxYawStep ?: "—"})")
        appendLine("Safety suppressions: stale ${s.excessiveAgeRejections}, jump ${s.jumpSuppressions}, crossing ${s.crossingBrakes}, anomalies ${s.yawResponseAnomaliesCount}")
        appendLine("Autonomous yaw activity: ${metric(s.fractionOfActiveNonZeroYaw?.times(100.0), "% non-zero")}")
        appendLine("Command-hold / source-age expirations: ${s.commandHoldExpiredCount} / ${s.sourceAgeExpiredCount}; longest expiration zero interval: ${s.longestExpirationZeroIntervalMs}ms")
        appendLine("Inter-measurement p50/p95: ${metric(s.interMeasurementP50Ms, " ms")} / ${metric(s.interMeasurementP95Ms, " ms")}")
        appendLine("Inter-send interval mean/p95: ${metric(s.meanInterSendIntervalMs, " ms")} / ${metric(s.p95InterSendIntervalMs, " ms")}; max send duration: ${metric(s.maxSendDurationMs, " ms")}")
        appendLine("Time outside |error|>0.15 / >0.20: ${s.timeOutsideError15Ms}ms (max continuous ${s.maxContinuousOutsideError15Ms}ms) / ${s.timeOutsideError20Ms}ms (max continuous ${s.maxContinuousOutsideError20Ms}ms)")
        appendLine("Oscillation & Settling: max yaw rate ${metric(s.maximumYawRate, " deg/s")} (raw ${metric(s.maxObservedRawTelemetryYawRateDps, " deg/s")}), reversals ${s.directionReversalsCount}, unsettled reversals ${s.reversalsWhileYawRateUnsettledCount}, settling time ${s.timeFromYawZeroRequestUntilSettledMs?.let { "${it}ms" } ?: "unavailable"}")
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
    private val EXPIRATION_REASONS = setOf(
        "RC_TTL_EXPIRED",
        "PERCEPTION_AGE_EXPIRED",
        "AUTONOMOUS_COMMAND_HOLD_EXPIRED",
    )
}

private class Record(private val values: Map<String, Any?>) {
    fun string(key: String) = values[key] as? String
    fun long(key: String) = (values[key] as? Number)?.toLong()
    fun double(key: String) = (values[key] as? Number)?.toDouble()
    fun int(key: String) = (values[key] as? Number)?.toInt()
    @Suppress("UNCHECKED_CAST") private fun nested(parent: String) = values[parent] as? Map<String, Any?>
    fun double(parent: String, key: String) = (nested(parent)?.get(key) as? Number)?.toDouble()
    fun int(parent: String, key: String) = (nested(parent)?.get(key) as? Number)?.toInt()
    fun suppression() = string("suppressionReason") ?: string("yawSuppressionReason")
}

// SPDX-License-Identifier: AGPL-3.0-only
