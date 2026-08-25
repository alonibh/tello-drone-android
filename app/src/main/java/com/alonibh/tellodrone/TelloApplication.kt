package com.alonibh.tellodrone

import android.app.Application
import com.alonibh.tellodrone.data.RealDroneController
import com.alonibh.tellodrone.domain.DroneController

class TelloApplication : Application() {
    val droneController: DroneController by lazy {
        RealDroneController(this)
    }
}
// SPDX-License-Identifier: AGPL-3.0-only
