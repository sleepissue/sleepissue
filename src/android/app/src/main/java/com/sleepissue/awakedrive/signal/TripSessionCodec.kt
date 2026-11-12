package com.sleepissue.awakedrive.signal

import com.sleepissue.awakedrive.engine.intervention.InterventionPolicyState
import com.sleepissue.awakedrive.engine.model.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Base64

enum class SaveTimerBasis { SCENARIO, ELAPSED_REALTIME }

data class TripSessionOwnerState(
    val gear: Gear,
    val power: PowerState,
    val saveTimerBasis: SaveTimerBasis,
    val intervention: InterventionCheckpoint,
)

/** 버전 있는 자체 자료형만 읽는다. Java 객체 역직렬화·음성 원문은 사용하지 않는다. */
object TripSessionCodec {
    fun encode(value: TripSessionOwnerState): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(2)
            out.writeUTF(value.gear.name); out.writeUTF(value.power.name); out.writeUTF(value.saveTimerBasis.name)
            val c = value.intervention; val p = c.policy; val s = p.publicState
            out.writeUTF(s.stage.name); out.writeUTF(s.evaluationStatus.name); out.text(s.reason?.name)
            listOf(p.lastEvaluatedMs, p.firstEvaluatedMs, p.lastTransitionMs, p.lowScoreSinceMs).forEach { out.number(it) }
            out.writeBoolean(p.normalCheckReceived); out.writeInt(p.consecutiveSlow)
            out.writeBoolean(p.cautionHeldUntilRest); out.writeBoolean(p.pendingCheckEscalation)
            val a = c.assessment
            out.writeLong(a.tMs); out.writeUTF(a.evaluationStatus.name)
            out.writeBoolean(a.riskScore != null); a.riskScore?.let(out::writeFloat)
            out.text(a.pattern?.name); out.text(a.assistedDriving?.toString()); out.number(a.operationGapSec)
            out.writeBoolean(c.tripContextAvailable)
            out.writeBoolean(c.forecast != null)
            c.forecast?.let { out.writeLong(it.tMs); out.writeUTF(it.evaluationStatus.name); out.number(it.dueAtMs) }
            out.writeBoolean(c.sensitive); out.writeBoolean(c.vulnerable); out.writeBoolean(c.ventilationConsent)
            out.writeUTF(c.alertnessContext.name); out.text(c.restSessionId)
            out.writeInt(c.seenRestSessions.size); c.seenRestSessions.sorted().forEach(out::writeUTF)
            out.number(c.latestRestCertificationMs); out.number(c.recoveryCompletedAtMs); out.writeBoolean(c.routeAdded)
            out.writeInt(c.interruptedChecks.size); c.interruptedChecks.forEach { (context, due) -> out.writeUTF(context.name); out.writeLong(due) }
            out.writeInt(c.enteredAt.size); c.enteredAt.toSortedMap(compareBy { it.level }).forEach { (stage, time) ->
                out.writeUTF(stage.name); out.writeLong(time)
            }
            out.number(c.assistedAnchor)
            out.number(c.firstCheckDueMs)
            out.writeBoolean(c.firstCheckRequested); out.writeBoolean(c.firstCheckInterrupted)
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray())
    }

    fun decode(payload: String): TripSessionOwnerState {
        // DB 손상 자료의 과도한 메모리 사용을 막는 형식 크기 제한이다.
        require(payload.length <= 1_048_576) { "소유자 복원 자료가 너무 큽니다." }
        DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(payload))).use { input ->
            val version = input.readInt()
            require(version in 1..2) { "지원하지 않는 소유자 자료 버전입니다." }
            val gear = input.enum<Gear>(); val power = input.enum<PowerState>(); val basis = input.enum<SaveTimerBasis>()
            val public = InterventionState(input.enum(), input.enum(), input.text()?.let(TransitionReason::valueOf))
            val policy = InterventionPolicyState(public, input.number(), input.number(), input.number(), input.number(),
                input.readBoolean(), input.readInt(), input.readBoolean(), input.readBoolean())
            val assessment = DrivingAssessment(input.readLong(), input.enum(),
                if (input.readBoolean()) input.readFloat() else null, input.text()?.let(DrivingPattern::valueOf),
                input.text()?.let { require(it == "true" || it == "false"); it.toBooleanStrict() }, input.number())
            val tripAvailable = input.readBoolean()
            val forecast = if (input.readBoolean()) ForecastAssessment(input.readLong(), input.enum(), input.number()) else null
            val sensitive = input.readBoolean(); val vulnerable = input.readBoolean(); val consent = input.readBoolean()
            val alertness = input.enum<CheckContext>(); val rest = input.text()
            val seen = List(input.count()) { input.readUTF() }.also { require(it.distinct().size == it.size) }.toSet()
            val certified = input.number(); val recovered = input.number(); val routeAdded = input.readBoolean()
            val checks = List(input.count()) { input.enum<CheckContext>() to input.readLong() }
                .also { require(it.map { pair -> pair.first }.distinct().size == it.size) }.toMap()
            val entered = List(input.count()) { input.enum<InterventionStage>() to input.readLong() }
                .also { require(it.map { pair -> pair.first }.distinct().size == it.size) }.toMap()
            val anchor = input.number()
            val firstDue = if (version >= 2) input.number() else null
            val firstRequested = version >= 2 && input.readBoolean()
            val firstInterrupted = version >= 2 && input.readBoolean()
            require(input.available() == 0) { "소유자 복원 자료 뒤에 해석하지 못한 값이 있습니다." }
            return TripSessionOwnerState(gear, power, basis, InterventionCheckpoint(policy, assessment, tripAvailable,
                forecast, sensitive, vulnerable, consent, alertness, rest, seen, certified, recovered, routeAdded, checks, entered, anchor,
                firstDue, firstRequested, firstInterrupted))
        }
    }

    private fun DataOutputStream.number(value: Long?) { writeBoolean(value != null); value?.let(::writeLong) }
    private fun DataInputStream.number(): Long? = if (readBoolean()) readLong() else null
    private fun DataOutputStream.text(value: String?) { writeBoolean(value != null); value?.let(::writeUTF) }
    private fun DataInputStream.text(): String? = if (readBoolean()) readUTF() else null
    private inline fun <reified T : Enum<T>> DataInputStream.enum(): T = enumValueOf(readUTF())
    private fun DataInputStream.count(): Int = readInt().also { require(it in 0..100_000) { "복원 항목 개수가 유효하지 않습니다." } }
}
