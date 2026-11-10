package dev.amenhancer.module.status

import dev.amenhancer.module.ModuleConstants

internal enum class ActivationState { CHECKING, DISCONNECTED, UNVERIFIED, FRAMEWORK_OLD, SCOPE_MISSING, WAITING_HOST, RESTART_REQUIRED, RELOADING, LOAD_FAILED, ACTIVE }
internal data class LoadedHost(val uid: Int, val pid: Int, val process: String, val state: String, val moduleVersion: Long)
internal data class ActivationEvidence(
    val connected: Boolean = false,
    val checking: Boolean = false,
    val api: Int? = null,
    val framework: String? = null,
    val scope: Set<String>? = null,
    val targets: List<LoadedHost>? = null,
    val failed: Boolean = false,
)

/** Neither root binaries, installed packages nor a selected scope prove successful injection. */
internal object ActivationPolicy {
    fun evaluate(evidence: ActivationEvidence, installedModuleCode: Long, hostUid: Int?): ActivationState {
        if (evidence.checking) return ActivationState.CHECKING
        if (!evidence.connected) return ActivationState.DISCONNECTED
        if (evidence.api?.let { it < 102 } == true) return ActivationState.FRAMEWORK_OLD
        if (evidence.failed || evidence.api == null || evidence.scope == null) return ActivationState.UNVERIFIED
        if (ModuleConstants.TARGET_PACKAGE !in evidence.scope) return ActivationState.SCOPE_MISSING
        val targets = evidence.targets ?: return ActivationState.UNVERIFIED
        val host = targets.filter { it.process == ModuleConstants.TARGET_PACKAGE && it.uid == hostUid && it.pid > 0 }
        if (host.isEmpty()) return ActivationState.WAITING_HOST
        if (host.any { it.state == "FAILED" }) return ActivationState.LOAD_FAILED
        if (host.any { it.state == "RELOADING" }) return ActivationState.RELOADING
        if (host.any { it.state == "STALE" }) return ActivationState.RESTART_REQUIRED
        if (host.any { it.moduleVersion <= 0 }) return ActivationState.UNVERIFIED
        if (host.any { it.moduleVersion != installedModuleCode }) return ActivationState.RESTART_REQUIRED
        return if (host.all { it.state == "UP_TO_DATE" }) ActivationState.ACTIVE else ActivationState.UNVERIFIED
    }
}
