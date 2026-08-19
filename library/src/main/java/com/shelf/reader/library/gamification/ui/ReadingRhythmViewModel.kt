package com.shelf.reader.library.gamification.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shelf.reader.core.gamification.ReadingTrackerFacade
import com.shelf.reader.data.local.dao.ReadingRhythmDao
import com.shelf.reader.data.local.entity.DailyReadingEntity
import com.shelf.reader.data.local.entity.ReadingProfileEntity
import com.shelf.reader.data.prefs.UserPreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

sealed class RhythmTierAchievement {
    data class DailyGoalReached(val minutesToday: Long, val targetMinutes: Long) : RhythmTierAchievement()
    data class StreakMilestone(val days: Int, val targetDays: Int) : RhythmTierAchievement()
}

data class RhythmUiState(
    val activeSeconds: Long = 0L,
    val targetSeconds: Long = 900L,
    val currentStreak: Int = 0,
    val longestStreak: Int = 0,
    val totalReadingDays: Int = 0,
    val isGoalMet: Boolean = false,
    val progressFraction: Float = 0f,
    val formattedRemainingText: String = "",
    val weeklyGoalMinutes: Long = 0L,
    val weeklyActiveMinutes: Long = 0L,
    val weeklyProgressFraction: Float = 0f,
    val streakGoalDays: Int = 7,
    val streakProgressFraction: Float = 0f,
    val celebrationsEnabled: Boolean = true
)

class ReadingRhythmViewModel(
    private val rhythmDao: ReadingRhythmDao,
    val engine: ReadingTrackerFacade,
    private val preferences: UserPreferencesRepository? = null
) : ViewModel() {

    private val todayStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)

    private val _tierEvents = MutableSharedFlow<SaluteTier>(extraBufferCapacity = 4)
    val tierEvents: SharedFlow<SaluteTier> = _tierEvents.asSharedFlow()

    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    private val streakGoalFlow: Flow<Int> = preferences?.rhythmStreakGoalDays ?: flowOf(7)
    private val celebrationsFlow: Flow<Boolean> = preferences?.rhythmCelebrationsEnabled ?: flowOf(true)

    val uiState: StateFlow<RhythmUiState> = combine(
        rhythmDao.observeDailyReading(todayStr),
        rhythmDao.observeProfile(),
        rhythmDao.observeRecentHistory(
            LocalDate.now().minusDays(6).format(dateFormatter)
        ),
        streakGoalFlow,
        celebrationsFlow
    ) { array ->
        val daily = array[0] as? DailyReadingEntity?
        val profile = array[1] as? ReadingProfileEntity?
        val recent = array[2] as? List<DailyReadingEntity>?
        val streakGoalPref = (array[3] as? Int) ?: 7
        val celebrateEnabled = (array[4] as? Boolean) ?: true

        val active = daily?.activeSeconds ?: 0L
        val targetMin = profile?.dailyTargetMinutes ?: 15
        val targetSec = daily?.targetSeconds ?: (targetMin * 60L)
        val streak = profile?.currentStreak ?: 0
        val longest = profile?.longestStreak ?: 0
        val totalDays = profile?.totalReadingDays ?: 0
        val isGoalMet = active >= targetSec && targetSec > 0

        val fraction = if (targetSec > 0) (active.toFloat() / targetSec.toFloat()).coerceIn(0f, 1f) else 0f
        val remainingSec = (targetSec - active).coerceAtLeast(0L)
        val remainingMin = (remainingSec + 59L) / 60L

        val text = if (isGoalMet) {
            "Dagens mål fullført ✓"
        } else {
            "Les $remainingMin min til for å fullføre målet"
        }

        val weeklyGoal = (targetMin * 7L)
        val weekSeconds = recent?.sumOf { it.activeSeconds } ?: 0L
        val weekMinutes = weekSeconds / 60L
        val weekFrac = if (weeklyGoal > 0) (weekMinutes.toFloat() / weeklyGoal.toFloat()).coerceIn(0f, 1f) else 0f

        val streakGoal = streakGoalPref.coerceIn(1, 365)
        val streakFrac = if (streakGoal > 0) (streak.toFloat() / streakGoal.toFloat()).coerceIn(0f, 1f) else 0f

        RhythmUiState(
            activeSeconds = active,
            targetSeconds = targetSec,
            currentStreak = streak,
            longestStreak = longest,
            totalReadingDays = totalDays,
            isGoalMet = isGoalMet,
            progressFraction = fraction,
            formattedRemainingText = text,
            weeklyGoalMinutes = weeklyGoal,
            weeklyActiveMinutes = weekMinutes,
            weeklyProgressFraction = weekFrac,
            streakGoalDays = streakGoal,
            streakProgressFraction = streakFrac,
            celebrationsEnabled = celebrateEnabled
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = RhythmUiState()
    )

    private var lastEmittedStreakForMilestone: Int = -1
    private var lastEmittedDailyForDate: String = ""

    private fun computeTierForDaily(streak: Int): SaluteTier {
        return when {
            streak >= 30 -> SaluteTier.GOLD
            streak >= 7 -> SaluteTier.SILVER
            else -> SaluteTier.BRONZE
        }
    }

    private fun computeTierForStreakMilestone(streakDays: Int, streakGoal: Int): SaluteTier {
        if (streakDays >= streakGoal && streakGoal >= 30) return SaluteTier.GOLD
        if (streakDays >= streakGoal && streakGoal >= 14) return SaluteTier.GOLD
        if (streakDays >= streakGoal) return SaluteTier.SILVER
        if (streakDays >= 100) return SaluteTier.GOLD
        if (streakDays >= 30) return SaluteTier.GOLD
        if (streakDays >= 7) return SaluteTier.SILVER
        if (streakDays >= 3) return SaluteTier.BRONZE
        return SaluteTier.BRONZE
    }

    init {
        viewModelScope.launch {
            engine.goalMetEvents.collect {
                val s = uiState.value
                if (!s.celebrationsEnabled) return@collect
                if (lastEmittedDailyForDate != todayStr) {
                    lastEmittedDailyForDate = todayStr
                    val tier = computeTierForDaily(s.currentStreak)
                    _tierEvents.emit(tier)
                }
            }
        }

        viewModelScope.launch {
            uiState.collect { s ->
                if (!s.celebrationsEnabled) return@collect
                if (s.currentStreak > 0 && s.currentStreak != lastEmittedStreakForMilestone) {
                    val shouldEmitForGoal = s.currentStreak == s.streakGoalDays
                    val shouldEmitForRound = s.currentStreak in listOf(3, 7, 14, 21, 30, 60, 90, 100, 180, 365)
                            && s.currentStreak > (lastEmittedStreakForMilestone.coerceAtLeast(0))
                    if (shouldEmitForGoal || shouldEmitForRound) {
                        lastEmittedStreakForMilestone = s.currentStreak
                        val tier = computeTierForStreakMilestone(s.currentStreak, s.streakGoalDays)
                        _tierEvents.emit(tier)
                    }
                }
                if (s.isGoalMet && lastEmittedDailyForDate != todayStr) {
                    if (s.progressFraction >= 1f) {
                        lastEmittedDailyForDate = todayStr
                        val tier = computeTierForDaily(s.currentStreak)
                        _tierEvents.emit(tier)
                    }
                }
            }
        }
    }

    fun updateDailyTarget(minutes: Int) {
        viewModelScope.launch {
            val current = rhythmDao.getProfile() ?: ReadingProfileEntity()
            rhythmDao.upsertProfile(current.copy(dailyTargetMinutes = minutes.coerceIn(1, 600)))
        }
    }

    fun updateStreakGoal(days: Int) {
        viewModelScope.launch {
            preferences?.setRhythmStreakGoalDays(days.coerceIn(1, 365))
        }
    }

    fun setCelebrationsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferences?.setRhythmCelebrationsEnabled(enabled)
        }
    }

    fun debugAddActiveSeconds(seconds: Long) {
        viewModelScope.launch {
            val profile = rhythmDao.getProfile()
            val targetMinutes = profile?.dailyTargetMinutes ?: 15
            val epochNow = System.currentTimeMillis()
            val reached = rhythmDao.recordActiveTime(
                date = todayStr,
                additionalSeconds = seconds,
                targetMinutes = targetMinutes,
                epochNow = epochNow
            )
            val s = uiState.value
            if (!s.celebrationsEnabled) return@launch
            if (reached || s.progressFraction >= 1f) {
                if (lastEmittedDailyForDate != todayStr) {
                    lastEmittedDailyForDate = todayStr
                    val tier = computeTierForDaily(profile?.currentStreak ?: 0)
                    _tierEvents.emit(tier)
                }
            }
        }
    }

    fun debugSimulateGoalReached() {
        viewModelScope.launch {
            val s = uiState.value
            if (!s.celebrationsEnabled) return@launch
            val tier = computeTierForDaily(s.currentStreak)
            _tierEvents.emit(tier)
        }
    }

    fun debugTriggerTier(tier: SaluteTier) {
        viewModelScope.launch {
            _tierEvents.emit(tier)
        }
    }

    fun debugResetStreak() {
        viewModelScope.launch {
            val current = rhythmDao.getProfile() ?: ReadingProfileEntity()
            rhythmDao.upsertProfile(
                current.copy(
                    currentStreak = 0,
                    lastCompletedDate = null
                )
            )
            lastEmittedStreakForMilestone = -1
        }
    }

    fun debugResetAll() {
        viewModelScope.launch {
            rhythmDao.upsertProfile(ReadingProfileEntity())
            lastEmittedStreakForMilestone = -1
            lastEmittedDailyForDate = ""
        }
    }
}
