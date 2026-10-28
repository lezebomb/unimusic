package moe.ouom.neriplayer.ui.viewmodel.tab.search

import kotlinx.coroutines.Job

/** UI-owner confined. Metadata jobs are independent; only speculative audio checks are owned here. */
internal class ExploreNativeProbeOwner {
    private val claimed = mutableSetOf<String>()
    private val jobs = mutableListOf<Job>()
    private var playbackStarted = false

    fun isClaimed(provider: String): Boolean = playbackStarted || provider in claimed
    fun claim(provider: String): Boolean = !playbackStarted && claimed.add(provider)
    fun track(job: Job) { if (playbackStarted) job.cancel() else jobs += job }

    fun yieldToPlayback() {
        playbackStarted = true
        cancelJobs()
    }

    fun reset() {
        cancelJobs()
        claimed.clear()
        playbackStarted = false
    }

    private fun cancelJobs() {
        jobs.forEach { it.cancel() }
        jobs.clear()
    }
}
