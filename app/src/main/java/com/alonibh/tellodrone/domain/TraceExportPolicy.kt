package com.alonibh.tellodrone.domain

/** File-picking can disturb the preview surface, so trace export is grounded-only. */
fun isTraceExportAllowed(flight: FlightState): Boolean = flight == FlightState.Grounded

// SPDX-License-Identifier: AGPL-3.0-only
