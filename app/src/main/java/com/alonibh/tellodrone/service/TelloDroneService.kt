package com.alonibh.tellodrone.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.alonibh.tellodrone.MainActivity
import com.alonibh.tellodrone.R
import com.alonibh.tellodrone.data.TelloSessionStore
import com.alonibh.tellodrone.domain.ControlAuthority
import com.alonibh.tellodrone.domain.ControllerMode
import com.alonibh.tellodrone.domain.DroneConnectionState
import com.alonibh.tellodrone.domain.DetectorBackendPreference
import com.alonibh.tellodrone.domain.FlightState
import com.alonibh.tellodrone.domain.ManualControlVector
import com.alonibh.tellodrone.domain.NetworkSelectionState
import com.alonibh.tellodrone.domain.TrackingMode
import com.alonibh.tellodrone.domain.VideoAvailability
import com.alonibh.tellodrone.domain.VideoState
import com.alonibh.tellodrone.tello.AndroidTelloVideoController
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
    private val connectionGate = ConnectionAttemptGate()
    @Volatile private var session: TelloFlightSession? = null
    @Volatile private var videoController: AndroidTelloVideoController? = null
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
    fun setTrackingMode(mode: TrackingMode) { session?.setTrackingMode(mode) }
    fun setDetectorBackendPreference(preference: DetectorBackendPreference) {
        session?.setDetectorBackendPreference(preference)
    }
    fun runDetectorBenchmark() { session?.runDetectorBenchmark() }
    fun cancelDetectorBenchmark() { session?.cancelDetectorBenchmark() }
    fun attachVideoSurface(surface: Surface) { videoController?.attachSurface(surface) }
    fun detachVideoSurface(surface: Surface) { videoController?.detachSurface(surface) }

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
        if (!connectionGate.begin()) return
        startConnectedDeviceForeground("Select the TELLO Wi-Fi network")
        TelloSessionStore.update {
            it.copy(
                controllerMode = ControllerMode.Real,
                connection = DroneConnectionState.Connecting,
                networkSelection = NetworkSelectionState.Requesting,
                flight = FlightState.Unknown,
                authority = ControlAuthority.Manual,
                tracking = TrackingMode.Off,
                video = VideoState(),
                personDetections = emptyList(),
                target = null,
                hoverActive = false,
                lastMessage = "Waiting for Tello Wi-Fi selection",
            )
        }
        networkManager.request(this)
    }

    override fun onAvailable(network: Network) {
        if (!connectionGate.claimNetwork()) return
        scope.launch {
            try {
                val transport = NetworkTelloTransport(network, scope, SystemMonotonicClock)
                val video = AndroidTelloVideoController(network, applicationContext)
                videoController = video
                TelloServiceGateway.videoPipelineAvailable(this@TelloDroneService)
                val newSession = TelloFlightSession(
                    transport = transport,
                    scope = scope,
                    clock = SystemMonotonicClock,
                    video = video,
                    initialState = TelloSessionStore.state.value.copy(
                        connection = DroneConnectionState.Connecting,
                        networkSelection = NetworkSelectionState.Available,
                        flight = FlightState.Unknown,
                        hoverActive = false,
                    ),
                    onFatalConnectionLoss = { scope.launch { finishService() } },
                )
                if (!connectionGate.activate { session = newSession }) {
                    videoController = null
                    video.close()
                    transport.close()
                    return@launch
                }
                stateCollection = scope.launch {
                    newSession.state.collect { value ->
                        TelloSessionStore.set(value)
                        updateWakeLock(value.connection, value.flight)
                        updateNotification(value.lastMessage ?: value.connection.name)
                    }
                }
                if (!newSession.connect()) {
                    TelloSessionStore.set(newSession.state.value)
                    finishService()
                }
            } catch (error: Throwable) {
                if (connectionGate.isRequested()) {
                    failAndStop("Could not open Tello UDP transport: ${error.message ?: error.javaClass.simpleName}")
                }
            }
        }
    }

    override fun onManualSelectionRequired(message: String) {
        TelloSessionStore.update {
            it.copy(
                connection = DroneConnectionState.Connecting,
                networkSelection = NetworkSelectionState.Requesting,
                flight = FlightState.Unknown,
                hoverActive = false,
                lastMessage = message,
            )
        }
        updateNotification(message)
    }

    override fun onUnavailable(message: String) {
        if (connectionGate.isRequested()) failAndStop(message)
    }

    override fun onLost(network: Network) {
        if (!connectionGate.isRequested()) return
        scope.launch {
            session?.networkLost("Tello Wi-Fi network was lost")
                ?: TelloSessionStore.update {
                    it.copy(
                        connection = DroneConnectionState.Error,
                        networkSelection = NetworkSelectionState.Lost,
                        flight = FlightState.Unknown,
                        video = VideoState(VideoAvailability.Error, errorReason = "Tello Wi-Fi network was lost"),
                        tracking = TrackingMode.Off,
                        authority = ControlAuthority.Manual,
                        personDetections = emptyList(),
                        target = null,
                        hoverActive = false,
                        lastMessage = "Tello Wi-Fi network was lost",
                    )
                }
            finishService()
        }
    }

    private fun failAndStop(message: String) {
        scope.launch {
            if (!connectionGate.isRequested()) return@launch
            TelloSessionStore.update {
                it.copy(
                    connection = DroneConnectionState.Error,
                    networkSelection = NetworkSelectionState.Error,
                    telemetry = it.telemetry.copy(isFresh = false),
                    video = VideoState(VideoAvailability.Error, errorReason = message),
                    tracking = TrackingMode.Off,
                    authority = ControlAuthority.Manual,
                    personDetections = emptyList(),
                    target = null,
                    manualVector = ManualControlVector(),
                    hoverActive = false,
                    lastMessage = message,
                )
            }
            finishService()
        }
    }

    private fun finishService() {
        var active: TelloFlightSession? = null
        var orphanVideo: AndroidTelloVideoController? = null
        connectionGate.finish {
            active = session
            session = null
            orphanVideo = videoController
            videoController = null
        }
        networkManager.cancel()
        stateCollection?.cancel()
        stateCollection = null
        active?.let { closing -> runBlocking(Dispatchers.IO) { closing.networkLost("Tello service stopping") } }
            ?: orphanVideo?.let { closing -> runBlocking(Dispatchers.IO) { closing.close() } }
        releaseWakeLock()
        if (foreground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foreground = false
        }
        stopSelf()
    }

    private fun startConnectedDeviceForeground(message: String) {
        val notification = notification(message)
        startForegroundCompat(notification)
        foreground = true
    }

    private fun updateNotification(message: String) {
        if (foreground) {
            startForegroundCompat(notification(message))
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) startConnectedDeviceForegroundApi29(notification)
        else startForeground(NOTIFICATION_ID, notification)
    }

    @androidx.annotation.RequiresApi(29)
    private fun startConnectedDeviceForegroundApi29(notification: Notification) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
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
        var active: TelloFlightSession? = null
        var orphanVideo: AndroidTelloVideoController? = null
        connectionGate.finish {
            active = session
            session = null
            orphanVideo = videoController
            videoController = null
        }
        active?.let { closing -> runBlocking(Dispatchers.IO) { closing.networkLost("Tello service stopped") } }
            ?: orphanVideo?.let { closing -> runBlocking(Dispatchers.IO) { closing.close() } }
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
    private val lock = Any()
    @Volatile private var service: TelloDroneService? = null
    private var videoSurface: Surface? = null
    fun attach(value: TelloDroneService) { synchronized(lock) { service = value } }
    fun detach(value: TelloDroneService) { synchronized(lock) { if (service === value) service = null } }
    fun videoPipelineAvailable(value: TelloDroneService) {
        val display = synchronized(lock) { videoSurface.takeIf { service === value } }
        if (display != null) value.attachVideoSurface(display)
    }
    fun attachVideoSurface(value: Surface) {
        val active = synchronized(lock) {
            videoSurface = value
            service
        }
        active?.attachVideoSurface(value)
    }
    fun detachVideoSurface(value: Surface) {
        val active = synchronized(lock) {
            if (videoSurface === value) videoSurface = null
            service
        }
        active?.detachVideoSurface(value)
    }
    fun takeOff() = service?.takeOff()
    fun land() = service?.land()
    fun stopAndHover() = service?.stopAndHover()
    fun emergencyMotorKill() = service?.emergencyMotorKill()
    fun publishManualControl(vector: ManualControlVector) = service?.publishManualControl(vector)
    fun setSpeed(percent: Int) = service?.setSpeed(percent)
    fun setTrackingMode(mode: TrackingMode) = service?.setTrackingMode(mode)
    fun setDetectorBackendPreference(preference: DetectorBackendPreference) =
        service?.setDetectorBackendPreference(preference)
    fun runDetectorBenchmark() = service?.runDetectorBenchmark()
    fun cancelDetectorBenchmark() = service?.cancelDetectorBenchmark()
    fun disconnect() = service?.disconnect()
    fun isAvailable(): Boolean = service != null
}
