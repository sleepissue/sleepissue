package com.sleepissue.awakedrive.debug

import android.content.Context
import android.content.ContextWrapper
import android.os.Process
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import com.google.gson.GsonBuilder
import java.io.File

/** 부팅 프로세스에서도 읽는 명시적 arm 설정이다. 기존 사용자 저장소를 열지 않는다. */
object DebugBootAudit {
    private const val PREFERENCES = "role1_boot_fixture"
    fun settings(context: Context) = DebugIsolatedMonitorService.storage(context).getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    fun runId(context: Context): String? = settings(context).let {
        if (it.getBoolean("armed", false)) it.getString("run_id", null)?.takeIf { id -> id.matches(Regex("[a-zA-Z0-9-]+")) } else null
    }
    fun storage(context: Context): Context = object : ContextWrapper(DebugIsolatedMonitorService.storage(context)) {
        override fun getCacheDir() = directory(context)
    }
    fun directory(context: Context): File = File(DebugIsolatedMonitorService.storage(context).cacheDir,
        "trp003-${requireNotNull(runId(context))}").also { check(it.mkdirs() || it.isDirectory) }
    @Synchronized fun record(context: Context, stage: String, data: Any? = null) {
        if (runId(context) == null) return
        val event = mapOf("stage" to stage, "pid" to Process.myPid(), "elapsedMs" to SystemClock.elapsedRealtime(),
            "wallMs" to java.time.Clock.systemUTC().millis(),
            "bootCount" to Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1),
            "userUnlocked" to context.getSystemService(UserManager::class.java).isUserUnlocked, "data" to data)
        android.util.Log.i("Role1BootAudit", "$stage pid=${Process.myPid()} boot=${event["bootCount"]}")
        File(directory(context), "events.jsonl").appendText(GsonBuilder().serializeNulls().create().toJson(event) + "\n")
    }
}
