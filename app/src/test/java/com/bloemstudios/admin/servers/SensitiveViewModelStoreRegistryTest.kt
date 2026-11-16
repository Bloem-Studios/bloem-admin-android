package com.bloemstudios.admin.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SensitiveViewModelStoreRegistryTest {
    @Test
    fun `admin gate routing reason survives replacement of sensitive feature owner`() {
        val routeStore = ViewModelStore()
        val routeOwner = object : ViewModelStoreOwner {
            override val viewModelStore = routeStore
        }
        val registry = SensitiveViewModelStoreRegistry()
        registry.owner("server-a", principalGeneration = 3)
        val original = adminGateRoutingViewModels(routeOwner)
        original.session.reportAdminUnauthorized("server-a")
        val reason = original.session.sessionExpiredReason("server-a")!!
        original.gate.forceNeedsLogin(reason)

        registry.owner("server-a", principalGeneration = 4)
        val replacement = adminGateRoutingViewModels(routeOwner)

        assertSame(original.gate, replacement.gate)
        assertSame(original.session, replacement.session)
        assertEquals(AdminGateState.NeedsLogin(reason), replacement.gate.state.value)
    }

    @Test
    fun `same principal reuses state across configuration recreation`() {
        val registry = SensitiveViewModelStoreRegistry()
        val firstOwner = registry.owner("server-a", principalGeneration = 3)
        val first = ViewModelProvider.create(firstOwner)[TrackedViewModel::class.java]

        val recreatedOwner = registry.owner("server-a", principalGeneration = 3)
        val recreated = ViewModelProvider.create(recreatedOwner)[TrackedViewModel::class.java]

        assertSame(first, recreated)
        assertEquals(0, first.clearCount)
    }

    @Test
    fun `principal change clears stale actionable state`() {
        val registry = SensitiveViewModelStoreRegistry()
        val oldOwner = registry.owner("server-a", principalGeneration = 3)
        val old = ViewModelProvider.create(oldOwner)[TrackedViewModel::class.java]

        registry.owner("server-a", principalGeneration = 4)

        assertEquals(1, old.clearCount)
    }

    @Test
    fun `scope exposes principal generation as the composition restart key`() {
        val registry = SensitiveViewModelStoreRegistry()

        val first = registry.scope("server-a", principalGeneration = 3)
        val refreshed = registry.scope("server-a", principalGeneration = 4)

        assertEquals(3L, first.principalGeneration)
        assertEquals(4L, refreshed.principalGeneration)
    }

    @Test
    fun `app lock clears every sensitive feature store exactly once`() {
        val registry = SensitiveViewModelStoreRegistry()
        val first = ViewModelProvider.create(registry.owner("server-a", 1))[TrackedViewModel::class.java]
        val second = ViewModelProvider.create(registry.owner("server-b", 2))[TrackedViewModel::class.java]

        registry.clearAll()
        registry.clearAll()

        assertEquals(1, first.clearCount)
        assertEquals(1, second.clearCount)
    }

    class TrackedViewModel : ViewModel() {
        var clearCount = 0
            private set

        override fun onCleared() {
            clearCount++
        }
    }
}
