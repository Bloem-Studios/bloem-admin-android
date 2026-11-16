package com.bloemstudios.admin.audit

import org.junit.Assert.assertEquals
import org.junit.Test

class AuditChangesTest {
    private val words = AuditWords(
        absent = "absent", allAssignable = "all assignable", allLibraries = "all libraries", changed = "changed",
        fullAccess = "full access", inherit = "inherit", none = "none", pinCleared = "cleared", pinNotSet = "not set",
        pinSet = "set", unset = "unset",
    )

    private fun line(change: AuditChange, target: String? = null) = auditChangeLine(change, target, words)

    @Test fun `a plain value shows as before to after`() {
        assertEquals("admin → user", line(AuditChange("role", before = "\"admin\"", after = "\"user\"")))
    }

    @Test fun `numbers and booleans show as written`() {
        assertEquals("2 → 5", line(AuditChange("max_streams", before = "2", after = "5")))
        assertEquals("false → true", line(AuditChange("enabled", before = "false", after = "true")))
    }

    @Test fun `a null value reads as inherit, or as unset for an access group`() {
        assertEquals("2 → inherit", line(AuditChange("max_streams", before = "2", after = "null")))
        assertEquals("all libraries → 3, 4", line(AuditChange("library_ids", before = "null", after = "[3,4]"), target = "access_group"))
        assertEquals("all assignable → none", line(AuditChange("allowed_permissions", before = "null", after = "[]"), target = "access_group"))
        assertEquals("unset → 1", line(AuditChange("quota", before = "null", after = "1"), target = "access_group"))
        assertEquals("none → 4", line(AuditChange("access_group_id", before = "null", after = "4")))
    }

    @Test fun `a missing side reads as absent`() {
        assertEquals("absent → 7", line(AuditChange("max_profiles", before = null, after = "7")))
        assertEquals("7 → absent", line(AuditChange("max_profiles", before = "7", after = null)))
    }

    @Test fun `a change with neither side reads as changed`() {
        assertEquals("changed", line(AuditChange("password")))
    }

    @Test fun `an empty api key scope list reads as full access`() {
        assertEquals("inherit → full access", line(AuditChange("scopes", before = "null", after = "[]")))
        assertEquals("full access → library read", line(AuditChange("scopes", before = "[]", after = "[\"library read\"]")))
    }

    @Test fun `a list shows its items joined and an empty list reads as none`() {
        assertEquals("none → a, b", line(AuditChange("tags", before = "[]", after = "[\"a\",\"b\"]")))
    }

    @Test fun `a value that is not json shows as sent`() {
        assertEquals("plain → text", line(AuditChange("note", before = "plain", after = "text")))
    }

    @Test fun `a profile pin shows set and cleared, never the pin`() {
        assertEquals("set", line(AuditChange("pin", before = "false", after = "true"), target = "profile"))
        assertEquals("cleared", line(AuditChange("pin", before = "true", after = "false"), target = "profile"))
        assertEquals("changed", line(AuditChange("pin", before = "true", after = "true"), target = "profile"))
        assertEquals("absent → set", line(AuditChange("pin", before = null, after = "true"), target = "profile"))
    }

    @Test fun `a field name reads with spaces`() {
        assertEquals("max streams", auditFieldLabel("max_streams"))
    }

    @Test fun `a target reads as its type and id, with the api key spelled out`() {
        assertEquals("user #7", auditTargetText("user", "7", apiKeyLabel = "API key"))
        assertEquals("API key #9", auditTargetText("api_key", "9", apiKeyLabel = "API key"))
        assertEquals("access group #2", auditTargetText("access_group", "2", apiKeyLabel = "API key"))
        assertEquals(null, auditTargetText(null, "2", apiKeyLabel = "API key"))
        assertEquals(null, auditTargetText("", null, apiKeyLabel = "API key"))
    }

    @Test fun `a target with no id still names its type`() {
        assertEquals("user", auditTargetText("user", null, apiKeyLabel = "API key"))
    }
}
