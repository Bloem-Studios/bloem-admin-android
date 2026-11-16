package com.bloemstudios.admin.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bloemstudios.admin.alerts.SurfacePreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The widgets that exist are the ones the system says exist. The note the app keeps of each one is only a note: it was the reason a
 * widget placed without one was never refreshed.
 */
@RunWith(RobolectricTestRunner::class)
class WidgetInventoryTest {
    private lateinit var surfaces: SurfacePreferences

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("inventory_surfaces", Context.MODE_PRIVATE).edit().clear().commit()
        surfaces = SurfacePreferences(context.getSharedPreferences("inventory_surfaces", Context.MODE_PRIVATE))
    }

    private fun system(vararg widgets: Pair<WidgetKind, Int>) = WidgetIds { kind -> widgets.filter { it.first == kind }.map { it.second }.toSet() }

    @Test fun aWidgetTheAppHasNoNoteOfIsAdoptedAsAllServers() {
        val placed = WidgetInventory.sync(surfaces, system(WidgetKind.NowPlaying to 41))
        assertEquals(listOf(PlacedWidget(41, WidgetKind.NowPlaying, null)), placed)
        assertTrue("and the note is written, so the next read agrees", surfaces.hasWidget(41))
        assertEquals(WidgetKind.NowPlaying, surfaces.widgetKind(41))
    }

    @Test fun aWidgetWithANoteKeepsItsServer() {
        surfaces.setWidget(7, WidgetKind.Requests, "home")
        val placed = WidgetInventory.sync(surfaces, system(WidgetKind.Requests to 7))
        assertEquals(listOf(PlacedWidget(7, WidgetKind.Requests, "home")), placed)
    }

    @Test fun aNoteForAWidgetTheSystemNoLongerHasIsDropped() {
        surfaces.setWidget(7, WidgetKind.Libraries, null)
        surfaces.setWidget(8, WidgetKind.Nodes, null)
        val placed = WidgetInventory.sync(surfaces, system(WidgetKind.Nodes to 8))
        assertEquals(listOf(8), placed.map { it.id })
        assertFalse(surfaces.hasWidget(7))
    }

    @Test fun aNoteWithTheWrongKindIsCorrectedToWhatTheSystemPlaced() {
        surfaces.setWidgetTarget(5, "home") // an old note: no kind, so Server status
        val placed = WidgetInventory.sync(surfaces, system(WidgetKind.NowPlaying to 5))
        assertEquals(listOf(PlacedWidget(5, WidgetKind.NowPlaying, "home")), placed)
    }

    @Test fun whenTheLastWidgetIsRemovedNothingIsLeft() {
        surfaces.setWidget(7, WidgetKind.NowPlaying, null)
        assertTrue(WidgetInventory.sync(surfaces, system()).isEmpty())
    }

    @Test fun aFailedQuestionNeverRemovesAnyonesWidget() {
        surfaces.setWidget(7, WidgetKind.NowPlaying, "home")
        val failing = WidgetIds { throw IllegalStateException("the widget service is not there") }
        val placed = WidgetInventory.sync(surfaces, failing)
        assertEquals(listOf(PlacedWidget(7, WidgetKind.NowPlaying, "home")), placed)
        assertTrue(surfaces.hasWidget(7))
    }

    @Test fun widgetsOfEveryKindAreFoundAtOnce() {
        val placed = WidgetInventory.sync(
            surfaces,
            system(WidgetKind.ServerStatus to 1, WidgetKind.NowPlaying to 2, WidgetKind.Requests to 3, WidgetKind.Libraries to 4, WidgetKind.Nodes to 5),
        )
        assertEquals(WidgetKind.entries.toSet(), WidgetInventory.kinds(placed))
        assertNull(placed.first().target)
    }
}
