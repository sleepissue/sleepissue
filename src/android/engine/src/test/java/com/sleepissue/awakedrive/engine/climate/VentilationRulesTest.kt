package com.sleepissue.awakedrive.engine.climate

import com.sleepissue.awakedrive.engine.config.EngineConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// 기획서 6장 통풍 규칙: 사전 동의한 경우만, 운전석만, 바꾸기 전 값을 저장해 되돌림.
class VentilationRulesTest {

    private val cap = VentilationCapability(minLevel = 0, maxLevel = 3, readable = true, writable = true)

    @Test
    fun `동의 없으면 아무것도 하지 않음`() {
        assertEquals(VentilationPlan.NoConsent, VentilationRules.planEnable(false, cap, 0))
    }

    @Test
    fun `읽을 수 없거나 지원하지 않으면 미지원`() {
        assertEquals(VentilationPlan.NotSupported, VentilationRules.planEnable(true, null, 0))
        assertEquals(VentilationPlan.NotSupported, VentilationRules.planEnable(true, cap.copy(readable = false), 0))
        assertEquals(VentilationPlan.NotSupported, VentilationRules.planEnable(true, cap, null))
        assertEquals(VentilationPlan.NotSupported, VentilationRules.planEnable(true, cap.copy(maxLevel = 0), 0))
    }

    @Test
    fun `꺼져 있으면 목표 단계로, 바꾸기 전 값 저장`() {
        assertEquals(VentilationPlan.Raise(priorLevel = 1, targetLevel = 3, writable = true), VentilationRules.planEnable(true, cap, 1))
    }

    @Test
    fun `목표가 차의 최대 단계보다 크면 최대로 줄임`() {
        val plan = VentilationRules.planEnable(true, cap.copy(maxLevel = 2), 0, EngineConfig(ventilationTargetLevel = 5))
        assertEquals(VentilationPlan.Raise(0, 2, true), plan)
    }

    @Test
    fun `이미 켜져 있으면 바꾸지 않음`() {
        assertEquals(VentilationPlan.AlreadyOn(3), VentilationRules.planEnable(true, cap, 3))
    }

    @Test
    fun `쓸 수 없으면 Raise지만 writable false로 바로 안내`() {
        assertEquals(VentilationPlan.Raise(0, 3, writable = false), VentilationRules.planEnable(true, cap.copy(writable = false), 0))
    }

    @Test
    fun `반영 확인과 수동 확인`() {
        assertTrue(VentilationRules.isApplied(3, 3))
        assertFalse(VentilationRules.isApplied(3, 0))
        assertFalse(VentilationRules.isApplied(3, null))
        assertTrue(VentilationRules.isManuallyRaised(0, 1))
        assertFalse(VentilationRules.isManuallyRaised(0, 0))
    }

    @Test
    fun `되돌리기`() {
        assertEquals(0, VentilationRules.restoreTarget(0, 3))
        assertNull(VentilationRules.restoreTarget(0, 0))
        assertNull(VentilationRules.restoreTarget(null, 3))
    }
}
