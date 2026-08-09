package com.alonibh.tellodrone.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Network
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.alonibh.tellodrone.MainActivity
import com.alonibh.tellodrone.R
import com.alonibh.tellodrone.data.TelloSessionStore
import com.alonibh.tellodrone.domain.ControlAuthority
import com.alonibh.tellodrone.domain.ControllerMode
import com.alonibh.tellodrone.domain.DroneConnectionState
import com.alonibh.tellodrone.domain.FlightState
import com.alonibh.tellodrone.domain.ManualControlVector
import com.alonibh.tellodrone.domain.NetworkSelectionState
import com.alonibh.tellodrone.domain.TrackingMode
import com.alonibh.tellodrone.tello.NetworkTelloTransport
import com.alonibh.tellodrone.tello.SystemMonotonicClock
import com.alonibh.tellodrone.tello.TelloFlightSession
import com.alonibh.tellodrone.tello.TelloWifiNetworkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Owns the physical Tello session; it is deliberately independent of Activity/ViewModel life. */
class TelloDroneService : Service(), TelloWifiNetworkManager.Listener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var networkManager: TelloWifiNetworkManager
    private var session: TelloFlightSession? = null
    private var stateCollection: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var foreground = false

    override fun onCreate() {
        super.onCreate()
        networkManager = TelloWifiNetworkManager(this)
        TelloServiceGateway.attach(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> beginConnect()
            ACTION_DISCONNECT -> disconnect()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun takeOff() { scope.launch { session?.takeOff() } }
    fun land() { scope.launch { session?.land() } }
    fun stopAndHover() { scope.launch { session?.stopAndHover() } }
    fun emergencyMotorKill() { scope.launch { session?.emergencyMotorKill() } }
    fun publishManualControl(vector: ManualControlVector) { session?.publishManualControl(vector) }
    fun setSpeed(percent: Int) { session?.setSpeed(percent) }

    fun disconnect() {
        scope.launch {
            val active = session
            if (active == null) {
                finishService()
            } else if (active.disconnect()) {
                TelloSessionStore.set(active.state.value)
                finishService()
            }
        }
    }

    private fun beginConnect() {
        if (foreground || session != null) return
        startConnectedDeviceForeground("Select the TELLO Wi-Fi network")
        TelloSessionStore.update {
            it.copy(
                controllerMode = ControllerMode.Real,
                connection = DroneConnectionState.Connecting,
                networkSelection = NetworkSelectionState.Requesting,
                flight = FlightState.Unknown,
                authority = ControlAuthority.Manual,
                tracking = TrackingMode.Off,
                lastMessage = "Waiting for Tello Wi-Fi selection",
            )
        }
        networkManager.request(this)
    }

    override fun onAvailable(network: Network) {
        scope.launch {
            if (session != null) return@launch
            try {
                val transport = NetworkTelloTransport(network, scope, SystemMonotonicClock)
                val newSession = TelloFlightSession(
                    transport = transport,
                    scope = scope,
                    clock = SystemMonotonicClock,
                    initialState = TelloSessionStore.state.value.copy(
                        connection = DroneConnectionState.Connecting,
                        networkSelection = NetworkSelectionState.Available,
                        flight = FlightState.Unknown,
                    ),
                    onFatalConnectionLoss = { scope.launch { finishService() } },
                )
                session = newSession
                stateCollection = scope.launch {
                    newSession.state.collect { value ->
                        TelloSessionStore.set(value)
                        updateWakeLock(value.connection, value.flight)
                        updateNotification(value.lastMessage ?: value.connection.name)
                    }
                }
                if (!newSession.connect()) finishService()
            } catch (error: Throwable) {
                failAndStop("Could not open Tello UDP transport: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    override fun onUnavailable(message: String) = failAndStop(message)

    override fun onLost(network: Network) {
        scope.launch {
            session?.networkLost("Tello Wi-Fi network was lost")
                ?: TelloSessionStore.update {
                    it.copy(
                        connection = DroneConnectionState.Error,
                        networkSelection = NetworkSelectionState.Lost,
                        flight = FlightState.Unknown,
                        lastMessage = "Tello Wi-Fi network was lost",
                    )
                }
            finishService()
        }
    }

    private fun failAndStop(message: String) {
        scope.launch {
            TelloSessionStore.update {
                it.copy(
                    connection = DroneConnectionState.Error,
                    networkSelection = NetworkSelectionState.Error,
                    telemetry = it.telemetry.copy(isFresh = false),
                    manualVector = ManualControlVector(),
                    lastMessage = message,
                )
            }
            finishService()
        }
    }

    private fun finishService() {
        networkManager.cancel()
        stateCollection?.cancel()
        stateCollection = null
        session = null
        releaseWakeLock()
        if (foreground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foreground = false
        }
        stopSelf()
    }

    private fun startConnectedDeviceForeground(message: String) {
        val notification = notification(message)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        foreground = true
    }

    private fun updateNotification(message: String) {
        if (foreground) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification(message),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        }
    }

    private fun notification(message: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val disconnectIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, TelloDroneService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_drone_notification)
            .setContentTitle("Tello drone session")
            .setContentText(message)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Safe disconnect", disconnectIntent)
            .build()
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Tello connection",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Active connection to a Tello drone" },
        )
    }

    private fun updateWakeLock(connection: DroneConnectionState, flight: FlightState) {
        val shouldHold = connection == DroneConnectionState.Connected && flight in setOf(
            FlightState.TakingOff,
            FlightState.Flying,
            FlightState.Landing,
            FlightState.Unknown,
        )
        if (shouldHold && wakeLock?.isHeld != true) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:tello-flight")
                .apply { setReferenceCounted(false); acquire(MAX_WAKE_LOCK_MILLIS) }
        } else if (!shouldHold) releaseWakeLock()
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    override fun onDestroy() {
        TelloServiceGateway.detach(this)
        networkManager.cancel()
        val active = session
        session = null
        if (active != null) runBlocking(Dispatchers.IO) { active.networkLost("Tello service stopped") }
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_CONNECT = "com.alonibh.tellodrone.action.CONNECT"
        const val ACTION_DISCONNECT = "com.alonibh.tellodrone.action.DISCONNECT"
        private const val NOTIFICATION_CHANNEL_ID = "tello_connection"
        private const val NOTIFICATION_ID = 8_889
        private const val MAX_WAKE_LOCK_MILLIS = 30L * 60L * 1_000L
    }
}

object TelloServiceGateway {
    @Volatile private var service: TelloDroneService? = null
    fun attach(value: TelloDroneService) { service = value }
    fun detach(value: TelloDroneService) { if (service === value) service = null }
    fun takeOff() = service?.takeOff()
    fun land() = service?.land()
    fun stopAndHover() = service?.stopAndHover()
    fun emergencyMotorKill() = service?.emergencyMotorKill()
    fun publishManualControl(vector: ManualControlVector) = service?.publishManualControl(vector)
    fun setSpeed(percent: Int) = service?.setSpeed(percent)
    fun disconnect() = service?.disconnect()
    fun isAvailable(): Boolean = service != null
}
