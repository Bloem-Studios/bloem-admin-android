package com.bloemstudios.admin.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlanWordsTest {
    private val words = PlanWords(ApplicationProvider.getApplicationContext<Context>().resources)
    private val templates = listOf(
        EntitlementTemplate(key = "basic", name = "Basic", revision = 3),
        EntitlementTemplate(key = "premium", name = "Premium", revision = 2),
    )

    private fun plan(key: String, revision: Long) = buildJsonObject { put("template_key", key); put("template_revision", revision) }

    private fun line(field: String, before: kotlinx.serialization.json.JsonElement?, after: kotlinx.serialization.json.JsonElement?) =
        words.changeLines(listOf(EntitlementChange(field, before, after)), templates).single()

    @Test
    fun `a plan change names both plans and their revisions`() {
        assertEquals("Plan: Basic, revision 3 → Premium, revision 2", line("template_revision", plan("basic", 3), plan("premium", 2)))
    }

    @Test
    fun `an organization with no plan yet reads as no plan`() {
        assertEquals("Plan: No plan → Premium, revision 2", line("template_revision", plan("", 0), plan("premium", 2)))
    }

    @Test
    fun `a plan that is no longer offered is named by its key`() {
        assertEquals("Plan: retired, revision 1 → Basic, revision 3", line("template_revision", plan("retired", 1), plan("basic", 3)))
    }

    @Test
    fun `limits, switches and quality read the way the web writes them`() {
        assertEquals("Screens at once: 2 → 4", line("max_streams", JsonPrimitive(2), JsonPrimitive(4)))
        assertEquals("Screens at once: 4 → No limit", line("max_streams", JsonPrimitive(4), JsonPrimitive(0)))
        assertEquals("Video conversion: No → Yes", line("transcode_allowed", JsonPrimitive(false), JsonPrimitive(true)))
        assertEquals("Best picture quality: Up to 1080p → Up to 4K", line("max_playback_quality", JsonPrimitive("1080p"), JsonPrimitive("2160p")))
        assertEquals("Best picture quality: Any quality → Original quality", line("max_playback_quality", JsonPrimitive(""), JsonPrimitive("original")))
    }

    @Test
    fun `libraries and permissions list their values, and no list means all`() {
        assertEquals("Libraries: All → 1, 2", line("library_ids", JsonNull, buildJsonArray { add(JsonPrimitive(1)); add(JsonPrimitive(2)) }))
        assertEquals("Libraries: 1, 2 → None", line("library_ids", buildJsonArray { add(JsonPrimitive(1)); add(JsonPrimitive(2)) }, buildJsonArray { }))
        assertEquals(
            "Extra permissions: All permissions → Edit metadata, Watch Live TV",
            line("allowed_permissions", JsonNull, buildJsonArray { add(JsonPrimitive("metadata_curation")); add(JsonPrimitive("watch_live_tv")) }),
        )
    }

    @Test
    fun `a field the app has no words for is made readable, never shown as JSON`() {
        assertEquals("Foo bar: a → b", line("foo_bar", JsonPrimitive("a"), JsonPrimitive("b")))
        val shown = line("something", Json.parseToJsonElement("""{"k":1}"""), JsonPrimitive("x"))
        assertEquals("Something: k: 1 → x", shown)
    }

    @Test
    fun `the current default settings are listed with the web's names`() {
        val rows = words.policyRows(
            EntitlementPolicy(
                library_ids = null, playback_allowed = true, max_streams = 2, max_profiles = 0, transcode_allowed = true,
                max_transcodes = 1, download_allowed = false, download_transcode_allowed = false,
                max_playback_quality = "1080p", allowed_permissions = emptyList(), requests_allowed = true,
            ),
        ).toMap()

        assertEquals("All libraries", rows["Libraries"])
        assertEquals("Yes", rows["Plays video"])
        assertEquals("2 screens", rows["Screens at once"])
        assertEquals("No limit", rows["Profiles"])
        assertEquals("1 conversion", rows["Conversions at once"])
        assertEquals("No", rows["Download original files"])
        assertEquals("Up to 1080p", rows["Best picture quality"])
        assertEquals("None", rows["Extra permissions"])
    }

    @Test
    fun `a chosen set of libraries is counted`() {
        val rows = words.policyRows(EntitlementPolicy(library_ids = listOf(1, 2, 3))).toMap()
        assertEquals("3 libraries", rows["Libraries"])
    }
}
