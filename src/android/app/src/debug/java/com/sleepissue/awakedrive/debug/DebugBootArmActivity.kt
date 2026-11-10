package com.sleepissue.awakedrive.debug

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.sleepissue.awakedrive.pleos.PlaygroundSdkOwnership
import com.sleepissue.awakedrive.service.MonitorPreference
import java.io.File

/** instrumentation 종료의 USER_REQUESTED 이력 이후 보이는 화면에서 새 시작 의사를 확인한다. */
class DebugBootArmActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(intent.getStringExtra("role1BootRunId") == DebugBootAudit.runId(this))
        check(File(DebugBootAudit.directory(this), "expected-snapshot.json").isFile)
        check(PlaygroundSdkOwnership.currentOwner == null)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 40, 40, 40) }
        val status = TextView(this).apply { text = "격리 저장 10종 준비됨 · 서비스를 시작하지 않음 · P/속도0에서만 시험"; textSize = 22f }
        val confirm = Button(this).apply { text = "다음 부팅 재개 허용" }
        confirm.setOnClickListener {
            val preference = MonitorPreference(DebugIsolatedMonitorService.storage(this))
            preference.reconcileUserStop()
            check(!preference.requested) { "새 시험은 정상 중지 상태에서 준비하세요" }
            check(preference.request(true))
            DebugBootAudit.record(this, "ARM_CONFIRMED", mapOf("requested" to preference.requested,
                "servicesStartedByActivity" to 0, "owner" to PlaygroundSdkOwnership.currentOwner))
            status.text = "준비 완료 · 실제 adb reboot 대기 · 현재 서비스 시작 0회"
            confirm.isEnabled = false
        }
        layout.addView(status); layout.addView(confirm); setContentView(layout)
    }
}
