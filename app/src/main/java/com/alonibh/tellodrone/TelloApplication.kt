package com.alonibh.tellodrone

import android.app.Application
import com.alonibh.tellodrone.data.AppDroneController
import com.alonibh.tellodrone.data.MockDroneController
import com.alonibh.tellodrone.data.RealDroneController
import com.alonibh.tellodrone.domain.DroneController

class TelloApplication : Application() {
    val droneController: DroneController by lazy {
        AppDroneController(RealDroneController(this), MockDroneController())
    }
}
