package com.sleepissue.awakedrive.service

import com.sleepissue.awakedrive.debug.DebugIsolatedMonitorService
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** 서비스가 같은 앱 안에 있어도 시험 알림·채널은 사용자 감시 알림과 독립적이어야 한다. */
class MonitorNotificationIsolationTest {
    @Test fun debugFixtureDoesNotShareTheUserNotificationIdentityOrSettingsChannel() {
        assertNotEquals(RealTripMonitorService.NOTIFICATION_ID, DebugIsolatedMonitorService.ISOLATED_NOTIFICATION_ID)
        assertNotEquals(RealTripMonitorService.CHANNEL, DebugIsolatedMonitorService.ISOLATED_NOTIFICATION_CHANNEL)
    }
}
