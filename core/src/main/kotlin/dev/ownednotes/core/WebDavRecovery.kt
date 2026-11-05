package dev.ownednotes.core

/** A current observation, not proof of which actor performed an earlier mutation. No payloads. */
data class WebDavRecovery(
    val attempt: WebDavTransferJournal.Pending,
    val target: Resource?,
    val stage: Resource?,
    val outcome: Outcome,
    val stageState: StageState,
) {
    data class Resource(val etag: String, val bytes: Long, val sha256: String)
    enum class Outcome { UPLOAD_PRESENT, ORIGINAL_PRESENT, TARGET_ABSENT, CONFLICT }
    enum class StageState { ABSENT, VERIFIED, CHANGED }

    internal companion object {
        fun observed(attempt: WebDavTransferJournal.Pending, target: Resource?, stage: Resource?): WebDavRecovery {
            val upload = attempt.kind == WebDavTransferJournal.Kind.UPLOAD
            val outcome = when {
                upload && target != null && target.bytes == attempt.bytes && target.sha256 == attempt.sha256 -> Outcome.UPLOAD_PRESENT
                target == null && (!upload || attempt.previousEtag == null) -> Outcome.TARGET_ABSENT
                target != null && target.etag == attempt.previousEtag -> Outcome.ORIGINAL_PRESENT
                else -> Outcome.CONFLICT
            }
            val stageState = when {
                stage == null -> StageState.ABSENT
                upload && stage.bytes == attempt.bytes && stage.sha256 == attempt.sha256 &&
                    (attempt.stageEtag == null || stage.etag == attempt.stageEtag) -> StageState.VERIFIED
                else -> StageState.CHANGED
            }
            return WebDavRecovery(attempt, target, stage, outcome, stageState)
        }
    }
}
