package moe.ouom.neriplayer.core.player.policy.source

typealias RecordingMetadata = moe.ouom.neriplayer.data.model.unified.RecordingMetadata
typealias RecordingMismatch = moe.ouom.neriplayer.data.model.unified.RecordingMismatch
typealias RecordingMatch = moe.ouom.neriplayer.data.model.unified.RecordingMatch

object RecordingMatchPolicy {
    private val gate = moe.ouom.neriplayer.data.model.unified.RecordingMatchPolicy
    fun versions(metadata: RecordingMetadata) = gate.versions(metadata)
    fun hasVersionConflict(expected: RecordingMetadata, candidate: RecordingMetadata) = gate.hasVersionConflict(expected, candidate)
    fun assess(expected: RecordingMetadata, candidate: RecordingMetadata, toleranceMs: Long = 4_000) = gate.assess(expected, candidate, toleranceMs)
    fun canonicalTitle(title: String) = gate.canonicalTitle(title)
    fun canonicalText(value: String) = gate.canonicalText(value)
}
