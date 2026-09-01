package com.cyberpulse.studylock.shared

import java.security.SecureRandom

data class PairingCode(
    val value: String,
    val createdAtMillis: Long,
    val expiresAtMillis: Long
) {
    fun isValid(nowMillis: Long = System.currentTimeMillis()): Boolean =
        value.length == 6 && value.all(Char::isDigit) && nowMillis < expiresAtMillis
}

data class StudentMetrics(
    val totalStudyMinutes: Int = 0,
    val completedSessions: Int = 0,
    val tutorQuestions: Int = 0,
    val blockedApps: List<String> = emptyList(),
    val updatedAtMillis: Long = 0
)

sealed interface FamilyCommand {
    data class StartFocus(val minutes: Int) : FamilyCommand
    data object EndFocus : FamilyCommand
    data class SetBlockedApps(val packageNames: List<String>) : FamilyCommand
    data class SetDailySchedule(val hour: Int, val minute: Int, val durationMinutes: Int) : FamilyCommand
}

object FamilyRules {
    const val MIN_SESSION_MINUTES = 25
    const val MAX_SESSION_MINUTES = 300
    const val PAIRING_CODE_LIFETIME_MILLIS = 10 * 60 * 1000L

    fun validDuration(minutes: Int): Boolean = minutes in MIN_SESSION_MINUTES..MAX_SESSION_MINUTES

    fun newPairingCode(
        nowMillis: Long = System.currentTimeMillis(),
        random: SecureRandom = SecureRandom()
    ): PairingCode {
        val value = random.nextInt(1_000_000).toString().padStart(6, '0')
        return PairingCode(value, nowMillis, nowMillis + PAIRING_CODE_LIFETIME_MILLIS)
    }
}
