package io.github.proify.lyricon.amprovider.xposed

import dev.amenhancer.module.hook.ProfileMethodContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class NativeMetadataLifecycleContractTest {
    class LiveData1622
    class ComposeFixture { fun observe(source: LiveData1622, composer: Any) = source }

    @Test fun `verified 1622 LiveData alias passes the library observe contract`() {
        val point = AppleMusicHookPoint.COMPOSE_OBSERVE_AS_STATE
        val target = AppleMusicHookProfiles.exactTargets(AppleMusicVersion("7.0.1", 1622L), point).single()
        assertEquals("androidx.lifecycle.I", target.parameterTypeNames?.first())
        val method = ComposeFixture::class.java.getDeclaredMethod("observe", LiveData1622::class.java, Any::class.java)
        val context = HookContractContext(point, target, ComposeFixture::class.java, method,
            classLookup = { name -> if (name == "androidx.lifecycle.I") LiveData1622::class.java else throw ClassNotFoundException(name) })
        assertEquals(ContractResult.Passed, AppleMusicHookContracts.validate(context))
        assertEquals(true, AppleMusicHookContracts.validate(context.copy(classLookup = null)) is ContractResult.Rejected)
    }

    class Fragment {
        var resumed = false
        fun onResume() { resumed = true }
        fun onPause() { resumed = false }
    }
    class WrongReturn { fun onResume(): Int = 1; fun onPause() = Unit }
    private fun contracts(owner: Class<*>): Map<String, ProfileMethodContract> = mapOf(
        "metadata-fragment-resume-method" to ProfileMethodContract(owner.name, "onResume", emptyList(), "void", false),
        "metadata-fragment-pause-method" to ProfileMethodContract(owner.name, "onPause", emptyList(), "void", false),
    )

    @Test fun `legacy descriptors stay absent without class probing`() {
        assertNull(NativeMetadataLifecycleContract.resolve(emptyMap()) { error("Unexpected class probe") })
    }

    @Test fun `native fragment callbacks clear page priority without dropping playback priority`() {
        val (resume, pause) = requireNotNull(NativeMetadataLifecycleContract.resolve(contracts(Fragment::class.java)) { Class.forName(it) })
        val fragment = Fragment()
        val coordinator = AppleMetadataSurfaceCoordinator(clock = { 0L })
        resume.invoke(fragment)
        coordinator.onSurfaceResumed(fragment)
        coordinator.markCurrentPage(listOf("100"))
        coordinator.markVisible(listOf("100"))
        coordinator.setPlaybackMediaId("200")
        val generation = coordinator.snapshot().generation
        assertEquals(true, fragment.resumed)
        assertEquals(AppleInternalCatalogResolver.RequestPriority.VISIBLE, coordinator.requestContext("100").priority)
        pause.invoke(fragment)
        coordinator.onSurfacePaused(fragment)
        assertEquals(false, fragment.resumed)
        assertEquals(AppleInternalCatalogResolver.RequestPriority.BACKGROUND, coordinator.requestContext("100").priority)
        assertEquals(AppleInternalCatalogResolver.RequestPriority.VISIBLE, coordinator.requestContext("200").priority)
        assertEquals(false, coordinator.allowsRefresh(generation, "100"))
        assertEquals(true, coordinator.allowsRefresh(generation, "200"))
    }

    @Test fun `partial stale or foreign lifecycle descriptors fail closed`() {
        assertThrows(IllegalStateException::class.java) {
            NativeMetadataLifecycleContract.resolve(contracts(Fragment::class.java).filterKeys { it.endsWith("resume-method") }) { Class.forName(it) }
        }
        assertThrows(IllegalStateException::class.java) {
            NativeMetadataLifecycleContract.resolve(contracts(WrongReturn::class.java)) { Class.forName(it) }
        }
        assertThrows(IllegalStateException::class.java) {
            NativeMetadataLifecycleContract.resolve(contracts(Fragment::class.java)) { WrongReturn::class.java }
        }
    }
}
