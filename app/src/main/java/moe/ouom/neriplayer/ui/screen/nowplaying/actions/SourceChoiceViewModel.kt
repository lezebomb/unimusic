package moe.ouom.neriplayer.ui.screen.nowplaying.actions

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.player.resolver.netease.recordingMetadata
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.url.checkSourceAvailability
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.unified.RecordingSourceCandidate
import moe.ouom.neriplayer.data.model.unified.RecordingSourceMatch
import moe.ouom.neriplayer.data.model.unified.SourceMatchDecision
import moe.ouom.neriplayer.data.model.unified.SongSourceKey
import moe.ouom.neriplayer.data.model.unified.SourceCheckResult
import moe.ouom.neriplayer.data.model.unified.SourceCheckStatus
import moe.ouom.neriplayer.platform.youtube.config.YouTubeFeatureGate

internal enum class SourceChoiceNotice { INCOMPLETE, OFFLINE, LOAD_FAILED, SAVE_FAILED, SAVED, CLEARED, ACCOUNT_CHANGED }

internal data class SourceChoiceState(
    val song: SongItem? = null,
    val matches: List<RecordingSourceMatch> = emptyList(),
    val candidates: List<RecordingSourceCandidate> = emptyList(),
    val loading: Boolean = false,
    val saving: Boolean = false,
    val notice: SourceChoiceNotice? = null,
    val youtubeEnabled: Boolean = true,
    val failures: Map<String, SourceChoiceFailure> = emptyMap(),
    val checking: SongSourceKey? = null,
    val checks: Map<SongSourceKey, SourceCheckResult> = emptyMap()
)

/** This owner remembers metadata decisions; saving never interrupts current playback. */
internal class SourceChoiceViewModel(application: Application) : AndroidViewModel(application) {
    private val backend get() = AppContainer.sourceMatchBackend
    private var youtubeEnabled = YouTubeFeatureGate.isEnabled()
    private val _state = MutableStateFlow(SourceChoiceState(youtubeEnabled = youtubeEnabled))
    val state = _state.asStateFlow()
    private var work: Job? = null
    private var generation = 0L
    private var account = accountSnapshot()

    init {
        viewModelScope.launch {
            combine(
                AppContainer.biliCookieRepo.cookieFlow,
                AppContainer.neteaseCookieRepo.cookieFlow,
                AppContainer.youtubeAuthRepo.authFlow,
                AppContainer.settingsRepo.youtubeEnabledFlow,
                AppContainer.qqMusicAuthRepo.state
            ) { bili, netease, youtube, enabled, _ ->
                sourceChoiceSession(bili, netease, youtube, enabled).copy(qqRevision = AppContainer.qqMusicAuthRepo.generation())
            }.distinctUntilChanged().collect { current ->
                youtubeEnabled = current.youtubeEnabled
                if (account != current) {
                    account = current
                    generation++
                    work?.cancel()
                    _state.value = _state.value.copy(candidates = emptyList(), loading = false,
                        saving = false, youtubeEnabled = youtubeEnabled, failures = emptyMap(),
                        checking = null, checks = emptyMap(),
                        notice = if (_state.value.song != null) SourceChoiceNotice.ACCOUNT_CHANGED else null)
                }
            }
        }
    }

    fun bind(song: SongItem) {
        close()
        _state.value = SourceChoiceState(song = song, youtubeEnabled = youtubeEnabled)
        refresh()
    }

    fun close() {
        generation++
        work?.cancel()
        work = null
        _state.value = SourceChoiceState(youtubeEnabled = youtubeEnabled)
    }

    fun refresh() {
        val song = _state.value.song ?: return
        if (_state.value.saving) return
        work?.cancel()
        val token = ++generation
        val session = accountSnapshot()
        _state.value = _state.value.copy(loading = true, notice = null, candidates = emptyList(), failures = emptyMap(),
            checking = null, checks = emptyMap())
        work = viewModelScope.launch {
            try {
                val matches = backend.matches(song).filter {
                    moe.ouom.neriplayer.data.model.unified.MusicSourceRegistry.isEnabled(it.candidate.key.provider)
                }
                if (!isCurrent(token, session)) return@launch
                _state.value = _state.value.copy(matches = matches)
                val result = discoverSourceChoices(getApplication(), song, session.youtubeEnabled)
                if (!isCurrent(token, session)) return@launch
                _state.value = _state.value.copy(candidates = result.candidates, loading = false, failures = result.failures,
                    notice = when {
                        result.incomplete -> SourceChoiceNotice.INCOMPLETE
                        result.offline -> SourceChoiceNotice.OFFLINE
                        else -> null
                    })
                // Probe at most one unambiguous native candidate per provider, never the whole list.
                for (provider in listOf("qqmusic", "kuwo")) {
                    val candidate = moe.ouom.neriplayer.core.player.policy.source.automaticCatalogChoice(
                        song.recordingMetadata(), result.candidates.filter { it.key.provider == provider }.map {
                            moe.ouom.neriplayer.data.model.unified.CatalogTrack(it.key, it.metadata, null) }, null) ?: continue
                    if (!isCurrent(token, session)) return@launch
                    val checked = kotlinx.coroutines.withTimeoutOrNull(2_600) {
                        PlayerManager.checkSourceAvailability(song, candidate)
                    } ?: SourceCheckResult(SourceCheckStatus.TIMED_OUT, System.currentTimeMillis())
                    if (!isCurrent(token, session)) return@launch
                    _state.value = _state.value.copy(checks = _state.value.checks + (candidate.key to checked))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (isCurrent(token, session)) _state.value = _state.value.copy(
                    loading = false, notice = SourceChoiceNotice.LOAD_FAILED)
            }
        }
    }

    fun decide(candidate: RecordingSourceCandidate, decision: SourceMatchDecision) {
        mutate(candidate, decision)
    }

    fun clear(candidate: RecordingSourceCandidate) {
        mutate(candidate, null)
    }

    fun check(candidate: RecordingSourceCandidate) {
        val snapshot = _state.value
        val song = snapshot.song ?: return
        if (snapshot.loading || snapshot.saving || snapshot.checking != null || account != accountSnapshot()) return
        if ((snapshot.candidates + snapshot.matches.map { it.candidate }).none { it == candidate }) return
        val token = ++generation
        val session = accountSnapshot()
        _state.value = snapshot.copy(checking = candidate.key, checks = snapshot.checks - candidate.key, notice = null)
        work = viewModelScope.launch {
            val result = try { PlayerManager.checkSourceAvailability(song, candidate) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { SourceCheckResult(SourceCheckStatus.UNAVAILABLE, System.currentTimeMillis()) }
            if (isCurrent(token, session)) _state.value = _state.value.copy(checking = null,
                checks = _state.value.checks + (candidate.key to result))
        }
    }

    private fun mutate(candidate: RecordingSourceCandidate, decision: SourceMatchDecision?) {
        val snapshot = _state.value
        val song = snapshot.song ?: return
        if (snapshot.loading || snapshot.saving || snapshot.checking != null) return
        if (account != accountSnapshot()) return
        if ((snapshot.candidates + snapshot.matches.map { it.candidate }).none { it == candidate }) return
        if (decision == SourceMatchDecision.CONFIRMED && candidate.key.provider == "youtube_music" &&
            (!youtubeEnabled || !YouTubeFeatureGate.isEnabled())) return
        if (decision == SourceMatchDecision.CONFIRMED && !assessSourceChoice(song, candidate).accepted) return
        val token = ++generation
        val session = accountSnapshot()
        _state.value = snapshot.copy(saving = true, notice = null, checks = snapshot.checks - candidate.key)
        work = viewModelScope.launch {
            try {
                if (!isCurrent(token, session)) return@launch
                if (decision == null) backend.clear(song, candidate.key)
                else backend.decide(song, candidate, decision)
                val matches = backend.matches(song).filter {
                    moe.ouom.neriplayer.data.model.unified.MusicSourceRegistry.isEnabled(it.candidate.key.provider)
                }
                if (isCurrent(token, session)) _state.value = _state.value.copy(matches = matches,
                    saving = false, notice = if (decision == null) SourceChoiceNotice.CLEARED else SourceChoiceNotice.SAVED)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (isCurrent(token, session)) _state.value = _state.value.copy(
                    saving = false, notice = SourceChoiceNotice.SAVE_FAILED)
            }
        }
    }

    private fun isCurrent(token: Long, session: SourceChoiceSession): Boolean =
        generation == token && accountSnapshot() == session

    private fun accountSnapshot(): SourceChoiceSession = sourceChoiceSession(
        AppContainer.biliCookieRepo.cookieFlow.value,
        AppContainer.neteaseCookieRepo.cookieFlow.value,
        AppContainer.youtubeAuthRepo.authFlow.value,
        youtubeEnabled
    ).copy(qqRevision = AppContainer.qqMusicAuthRepo.generation())
}
