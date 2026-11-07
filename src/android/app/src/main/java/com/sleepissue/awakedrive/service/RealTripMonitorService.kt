package com.sleepissue.awakedrive.service

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import com.google.gson.GsonBuilder
import com.sleepissue.awakedrive.data.*
import com.sleepissue.awakedrive.engine.time.Clock
import com.sleepissue.awakedrive.pleos.PlaygroundSdkOwnership
import com.sleepissue.awakedrive.signal.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.io.FileDescriptor
import java.io.PrintWriter

/** 마이크·개입 없이 실제 Playground 신호와 운행 저장만 소유한다. */
class RealTripMonitorService : Service() {
    inner class LocalBinder : Binder() { val service get() = this@RealTripMonitorService }
    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val clock = AnchoredTripClock(object : Clock {
        override fun scenarioTimeMs() = SystemClock.elapsedRealtime()
        override fun elapsedRealtimeMs() = SystemClock.elapsedRealtime()
        override fun wallTimeMs() = java.time.Clock.systemUTC().millis()
    })
    private lateinit var database: TripDatabase
    lateinit var runtime: RealTripMonitorRuntime
        private set
    private lateinit var initialization: Job
    private lateinit var preference: MonitorPreference
    var input: PlaygroundMonitorInput? = null
        private set
    var foreground = false
        private set
    var diagnostic = "진단 저장 대기 · cache/sdk-trip-probe.json"
        private set
    private var diagnosticJob: Job? = null
    private var commandJob: Job? = null
    var stopping = false
        private set
    private var ownsInstance = false
    private val instanceId = java.util.UUID.randomUUID().toString()
    private var lastCommand: String? = null

    override fun onCreate() {
        super.onCreate()
        preference = MonitorPreference(this)
        preference.reconcileUserStop()
        database = TripDatabase.open(this, RealTripPersistence.DATABASE_NAME)
        runtime = RealTripMonitorRuntime(PlaygroundTripSession(RoomTripRepository(database.checkpointDao()), clock), scope, {
            PlaygroundMonitorInput(this, clock).also { input = it }
        })
        initialization = scope.launch {
            // 이전 인스턴스 최종 저장/DB close가 끝난 뒤에만 같은 checkpoint를 복원한다.
            instanceOwner.lock(); ownsInstance = true
            try { runtime.initialize() }
            catch (e: Exception) {
                diagnostic = "초기화 실패 ${e.javaClass.simpleName} · DB 보존 · 화면에서 재개하세요"
                preference.request(false)
                stopForeground(STOP_FOREGROUND_REMOVE); foreground = false; stopSelf()
            }
        }
        scope.launch { runtime.failure.collect { if (it != null) stopMonitoring() } }
    }

    override fun onBind(intent: Intent) = binder
    override fun onUnbind(intent: Intent): Boolean {
        // sticky 재생성 중 재바인딩이 먼저 끝나도 대기 중인 시작 명령을 취소하지 않는다.
        if (!foreground && !preference.requested) stopSelf()
        return false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastCommand = "${intent?.action ?: "STICKY"} flags=$flags id=$startId"
        if (intent?.action == ACTION_STOP) {
            // 중지 의사를 먼저 디스크에 남겨 중간 프로세스 종료에도 재시작하지 않는다.
            preference.request(false)
            stopMonitoring()
            return START_NOT_STICKY
        }
        val resuming = intent?.action == null || intent.action == ACTION_BOOT
        if (intent?.action != ACTION_START && (!resuming || !preference.requested)) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (stopping) return START_NOT_STICKY
        try {
            enterForeground()
            if (!preference.request(true)) error("감시 시작 의사 저장 실패")
        } catch (e: Exception) {
            diagnostic = "서비스 시작 불가 ${e.javaClass.simpleName} · 화면에서 다시 시작하세요"
            if (intent?.action == ACTION_BOOT) preference.bootDeferred() else preference.request(false)
            stopForeground(STOP_FOREGROUND_REMOVE); foreground = false; stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (commandJob?.isActive != true) commandJob = scope.launch {
            initialization.join()
            if (preference.requested && !stopping && runtime.session.initialized && !runtime.start()) {
                if (intent?.action == ACTION_BOOT) preference.bootDeferred() else preference.request(false)
                stopForeground(STOP_FOREGROUND_REMOVE); foreground = false; stopSelf()
            }
        }
        return START_STICKY
    }

    private fun enterForeground() {
        if (foreground) return
        val manager = getSystemService(NotificationManager::class.java)
        check(manager.areNotificationsEnabled()) { "알림을 허용한 뒤 화면에서 다시 시작하세요" }
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "실제 차량 신호 감시", NotificationManager.IMPORTANCE_LOW))
        val screen = PendingIntent.getActivity(this, 11, Intent(this, RealTripMonitorActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 12, Intent(this, RealTripMonitorService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("AwakeDrive 운행 감시 중").setContentText("Playground 신호·운행 저장 · 전원 UNKNOWN")
            .setContentIntent(screen).setOngoing(true).setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, "감시 중지", stop).build()).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION_ID, notification)
        foreground = true
    }

    fun stopMonitoring() {
        if (stopping) return
        preference.request(false)
        stopping = true
        scope.launch {
            initialization.join(); commandJob?.join()
            runtime.stop(); input = null
            stopForeground(STOP_FOREGROUND_REMOVE); foreground = false
            diagnosticJob?.join(); captureDiagnostics()
            stopSelf()
            stopping = false
        }
    }

    fun saveNow() { scope.launch { initialization.join(); runtime.session.saveNow() } }

    fun captureDiagnostics() {
        if (diagnosticJob?.isActive == true) return
        val c = runtime.session
        val payload = linkedMapOf<String, Any?>("scope" to "REAL_PLAYGROUND_TRIP_ONLY", "serviceInstance" to instanceId,
            "pid" to Process.myPid(), "lastCommand" to lastCommand, "capturedAtElapsedMs" to clock.elapsedRealtimeMs(), "logicalMs" to clock.scenarioTimeMs(),
            "status" to runtime.status.value, "foreground" to foreground, "requested" to preference.requested,
            "bootPending" to preference.bootPending, "running" to runtime.running, "starts" to runtime.starts,
            "activeTimers" to runtime.activeTimers, "sdkOwner" to PlaygroundSdkOwnership.currentOwner,
            "connected" to c.connected, "generation" to c.generation, "power" to "UNSUPPORTED_IGNITION_STATE_UNKNOWN",
            "trip" to c.trip.value, "precise" to c.preciseState(), "restoredSnapshot" to c.restoredSnapshot,
            "persistence" to c.persistence.state.value, "events" to c.events.value,
            "readings" to input?.adapter?.store?.readings(), "sdkHistory" to input?.adapter?.store?.history(),
            "frame" to input?.hub?.latestFrame?.value, "hub" to input?.hub?.diagnostics?.value)
        diagnosticJob = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val file = File(cacheDir, "sdk-trip-probe.json")
                    val temporary = File.createTempFile(".sdk-trip-", ".json", cacheDir)
                    try { temporary.writeText(GsonBuilder().serializeNulls().create().toJson(payload)); check(temporary.renameTo(file)) }
                    finally { temporary.delete() }
                }
                diagnostic = "진단 저장 성공 · cache/sdk-trip-probe.json"
            } catch (e: Exception) { diagnostic = "진단 저장 실패 ${e.javaClass.simpleName}" }
        }
    }

    override fun onDestroy() {
        // 프로세스 kill/강제 종료에는 이 콜백이 보장되지 않는다. 10초 checkpoint가 복원 근거다.
        // 시스템 파괴는 사용자의 중지가 아니므로 requested를 변경하지 않는다.
        stopping = true
        scope.launch {
            try {
                initialization.join(); commandJob?.join()
                runtime.stop("서비스 종료"); input = null
                diagnosticJob?.join(); database.close()
            } finally {
                if (ownsInstance) { ownsInstance = false; instanceOwner.unlock() }
                scope.cancel()
            }
        }
        super.onDestroy()
    }

    /** 화면을 열지 않고도 시스템 dumpsys로 단일 소유자·실행 상태를 읽을 수 있다. */
    override fun dump(fd: FileDescriptor, writer: PrintWriter, args: Array<out String>) {
        writer.println("instance=$instanceId pid=${Process.myPid()} command=$lastCommand")
        writer.println("foreground=$foreground requested=${preference.requested} stopping=$stopping bootPending=${preference.bootPending}")
        writer.println("running=${runtime.running} starts=${runtime.starts} timers=${runtime.activeTimers} generation=${runtime.session.generation}")
        writer.println("sdkOwner=${PlaygroundSdkOwnership.currentOwner} status=${runtime.status.value}")
        writer.println("trip=${runtime.session.trip.value.tripId} state=${runtime.session.trip.value.driveState} continuousMs=${runtime.session.preciseState().continuousDriveMs}")
        writer.println("persistence=${runtime.session.persistence.state.value}")
    }

    companion object {
        const val ACTION_START = "com.sleepissue.awakedrive.monitor.START"
        const val ACTION_STOP = "com.sleepissue.awakedrive.monitor.STOP"
        const val ACTION_BOOT = "com.sleepissue.awakedrive.monitor.BOOT"
        private const val CHANNEL = "real_trip_monitor"
        private const val NOTIFICATION_ID = 4101
        private val instanceOwner = Mutex()
    }
}
