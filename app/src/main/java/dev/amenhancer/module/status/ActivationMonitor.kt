package dev.amenhancer.module.status

import android.os.Handler
import android.os.Looper
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** All state and subscriptions are confined to the main thread; Binder reads never block it. */
internal class ActivationMonitor(
    private val execute: (() -> Unit) -> Unit,
    private val post: (() -> Unit) -> Unit,
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
) {
    private var identity: Any? = null
    private var read: (() -> ActivationEvidence)? = null
    private var revision = 0L
    private var reading = false
    private var cancelDeadline: (() -> Unit)? = null
    private val observers = linkedSetOf<(ActivationEvidence) -> Unit>()
    var latest = ActivationEvidence()
        private set
    fun subscribe(observer: (ActivationEvidence) -> Unit): () -> Unit {
        observers += observer; observer(latest)
        return { observers -= observer }
    }
    fun connected(owner: Any, query: () -> ActivationEvidence) {
        cancelDeadline?.invoke(); cancelDeadline = null
        revision++; reading = false; identity = owner; read = query
        latest = ActivationEvidence(connected = true)
        refresh()
    }
    fun disconnected(owner: Any) {
        if (identity !== owner) return
        cancelDeadline?.invoke(); cancelDeadline = null
        revision++; reading = false; identity = null; read = null
        publish(ActivationEvidence())
    }
    fun refresh(silent: Boolean = false) {
        if (reading) return
        val query = read ?: run { publish(ActivationEvidence()); return }
        val generation = ++revision
        reading = true
        if (!silent) publish(latest.copy(connected = true, checking = true))
        cancelDeadline = schedule(5000) {
            if (revision == generation) {
                revision++; reading = false; cancelDeadline = null
                publish(latest.copy(connected = true, checking = false, targets = null, failed = true))
            }
        }
        try {
            execute {
                val result = runCatching(query).getOrElse { ActivationEvidence(connected = true, failed = true) }
                post {
                    if (revision == generation) {
                        cancelDeadline?.invoke(); cancelDeadline = null
                        reading = false; publish(result.copy(checking = false))
                    }
                }
            }
        } catch (_: Exception) {
            cancelDeadline?.invoke(); cancelDeadline = null; reading = false
            publish(ActivationEvidence(connected = true, failed = true))
        }
    }
    private fun publish(value: ActivationEvidence) { latest = value; observers.toList().forEach { it(value) } }
}

/** One process-wide framework listener; Activities only hold removable subscriptions. */
internal object ModuleActivation {
    private val main = Handler(Looper.getMainLooper())
    private val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1),
        { task -> Thread(task, "LyricWave-activation").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    val monitor = ActivationMonitor({ task -> executor.execute(task) }, { task -> main.post(task) }, { delay, task ->
        val timeout = Runnable { task() }
        main.postDelayed(timeout, delay)
        val cancel: () -> Unit = { main.removeCallbacks(timeout) }
        cancel
    })
    private var initialized = false
    fun initialize() {
        if (initialized) return
        initialized = true
        runCatching {
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(service: XposedService) {
                    main.post {
                        monitor.connected(service) {
                            val api = service.apiVersion
                            val framework = "${service.frameworkName} ${service.frameworkVersion}"
                            if (api < 102) ActivationEvidence(connected = true, api = api, framework = framework)
                            else {
                                val scope = runCatching { service.scope.toSet() }.getOrNull()
                                val targets = runCatching { service.runningTargets.map { target ->
                                    LoadedHost(target.uid, target.pid, target.processName, target.state.name, target.loadedVersionCode)
                                } }.getOrNull()
                                ActivationEvidence(connected = true, api = api, framework = framework, scope = scope, targets = targets)
                            }
                        }
                    }
                }
                override fun onServiceDied(service: XposedService) { main.post { monitor.disconnected(service) } }
            })
        }.onFailure { monitor.refresh() }
    }
}
