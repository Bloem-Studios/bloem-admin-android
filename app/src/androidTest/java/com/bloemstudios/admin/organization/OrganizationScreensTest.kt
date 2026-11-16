package com.bloemstudios.admin.organization

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.ui.theme.VeilAdminTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The organization pages on a real device, from fixture state: no network, no credentials. */
@RunWith(AndroidJUnit4::class)
class OrganizationScreensTest {
    @get:Rule val compose = createComposeRule()

    private val family = AccessGroup(id = "3", name = "Family", is_default = true, member_count = 6, max_streams = 2, max_profiles = 5,
        max_playback_quality = "original", download_allowed = true, requests_allowed = true, library_ids = listOf("1", "2"))
    private val kids = AccessGroup(id = "4", name = "Kids", member_count = 1, max_streams = 1, max_profiles = 3)
    private val sam = PersonSummary(
        organization_id = "o", account_id = 5, email = "sam@example.test", display_name = "Sam", membership_id = "m5",
        membership_status = "active", security_revision = 4, last_activity = "2026-11-12T09:30:00Z",
        profiles = listOf(ProfileSummary("p1", "Living room", 3, "Family", "2026-11-12T08:00:00Z")),
    )
    private val ana = sam.copy(account_id = 6, email = "ana@example.test", display_name = "Ana", membership_id = "m6",
        membership_status = "suspended", security_revision = 2, profiles = emptyList())
    private val me = sam.copy(account_id = 1, email = "owner@example.test", display_name = "Pat Owner", membership_id = "mine",
        legacy_role = "admin", profiles = emptyList())
    private val context = AdminContext("organization:o", "Acme Media", AdminContextScope.Organization, "o", "active",
        AdminAuthority.OrganizationAdmin, membershipId = "mine")

    /** A button inside the open dialog, not the same word on the page behind it. */
    private fun inDialog(text: String) =
        compose.onNode(hasText(text) and hasAnyAncestor(isDialog()))

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) = compose.setContent { VeilAdminTheme { content() } }

    // ---- People -------------------------------------------------------------------------------------

    @Test
    fun peopleShowsEveryoneAndHidesSuspendForTheAdministratorThemselves() {
        show {
            PeopleContent(PeopleState(loading = false, people = listOf(me, sam, ana), groups = listOf(family, kids), approximateTotal = 3),
                context, {}, {}, {}, {}, { _, _ -> }, { _, _, _ -> })
        }
        compose.onNodeWithText("Pat Owner").assertIsDisplayed()
        compose.onNodeWithText("Administrator · You", substring = true).assertIsDisplayed()
        compose.onNodeWithText("About 3 people").assertIsDisplayed()
        // The filters take room, so the rows below Pat Owner are scrolled to.
        compose.onNodeWithTag("organization-page").performScrollToNode(hasText("Ana"))
        compose.onNodeWithText("Sam").assertIsDisplayed()
        compose.onNodeWithText("Ana").assertIsDisplayed()
        compose.onNodeWithContentDescription("Actions for Sam: Suspend").assertIsDisplayed()
        compose.onNodeWithContentDescription("Actions for Ana: Reactivate").assertIsDisplayed()
        compose.onNodeWithContentDescription("Actions for Pat Owner: Suspend").assertDoesNotExist()
        // There is no password reset: the server offers none to organization administrators.
        compose.onNodeWithText("Reset password", substring = true, ignoreCase = true).assertDoesNotExist()
    }

    @Test
    fun suspendAsksFirstThenReports() {
        var asked: Pair<Long, Boolean>? = null
        show {
            PeopleContent(PeopleState(loading = false, people = listOf(sam), groups = listOf(family)), context,
                {}, {}, {}, {}, { person, suspend -> asked = person.account_id to suspend }, { _, _, _ -> })
        }
        compose.onNodeWithContentDescription("Actions for Sam: Suspend").performClick()
        compose.onNodeWithText("Suspend Sam?").assertIsDisplayed()
        assertNull("nothing happens before confirming", asked)
        compose.onNodeWithText("Cancel").performClick()
        assertNull(asked)

        compose.onNodeWithContentDescription("Actions for Sam: Suspend").performClick()
        inDialog("Suspend").performClick()
        assertEquals(5L to true, asked)
    }

    @Test
    fun aProfileCanBeMovedToAnotherAccessGroup() {
        var moved: Triple<Long, String, String>? = null
        show {
            PeopleContent(PeopleState(loading = false, people = listOf(sam), groups = listOf(family, kids)), context,
                {}, {}, {}, {}, { _, _ -> }, { person, profile, group -> moved = Triple(person.account_id, profile.id, group.id) })
        }
        compose.onNodeWithContentDescription("Move Living room to an access group").performClick()
        compose.onNodeWithText("Move Living room").assertIsDisplayed()
        compose.onNodeWithText("Kids").performClick()
        assertEquals(Triple(5L, "p1", "4"), moved)
    }

    @Test
    fun searchAndFiltersReachTheCaller() {
        val filters = mutableListOf<PeopleFilter>()
        val state = mutableStateOf(PeopleState(loading = false, people = listOf(sam), groups = listOf(family, kids)))
        show { PeopleContent(state.value, context, { filters += it }, {}, {}, {}, { _, _ -> }, { _, _, _ -> }) }
        compose.onNodeWithTag("organization-people-search").performTextInput("sam")
        compose.onNodeWithContentDescription("Membership status: Any status").performClick()
        compose.onNodeWithText("Suspended").performClick()
        assertEquals(PeopleFilter(query = "sam", statuses = setOf("suspended")), filters.last())
        compose.onNodeWithContentDescription("Access group: Any access group").performClick()
        compose.onNodeWithText("Kids").performClick()
        assertEquals(4L, filters.last().groupId)
    }

    @Test
    fun aPendingPersonCannotBeChangedTwice() {
        show {
            PeopleContent(PeopleState(loading = false, people = listOf(sam), groups = listOf(family), pendingAccountIds = setOf(5L)), context,
                {}, {}, {}, {}, { _, _ -> }, { _, _, _ -> })
        }
        compose.onNodeWithText("Working…").assertIsDisplayed()
        compose.onNodeWithContentDescription("Actions for Sam: Suspend").assertDoesNotExist()
        compose.onNodeWithContentDescription("Move Living room to an access group").assertIsNotEnabled()
    }

    @Test
    fun peopleShowAnErrorBannerAndLoadMore() {
        var more = 0
        show {
            PeopleContent(PeopleState(loading = false, people = listOf(sam), nextCursor = "n2", error = ErrorText.Raw("This changed on the server")),
                context, {}, { more++ }, {}, {}, { _, _ -> }, { _, _, _ -> })
        }
        compose.onNodeWithText("This changed on the server").assertIsDisplayed()
        compose.onNodeWithTag("organization-people-more").performScrollTo().performClick()
        assertEquals(1, more)
    }

    // ---- Other pages --------------------------------------------------------------------------------

    @Test
    fun overviewShowsTheNumbersAndOwner() {
        val summary = OrganizationSummary("o", "acme", "Acme Media", "active", 4, 9, false, 12, 10, 25, 3, 2)
        show { OverviewContent(OverviewState(loading = false, summary = summary, ownerName = "Pat Owner", ownerResolved = true)) {} }
        compose.onNodeWithText("About").assertIsDisplayed()
        compose.onNodeWithText("12").assertIsDisplayed()
        compose.onNodeWithText("10 active").assertIsDisplayed()
        compose.onNodeWithText("Pat Owner").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Account 4").assertCountEquals(0)
        compose.onNodeWithText("Settings revision").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun overviewNeverShowsTheOwnersAccountNumber() {
        val summary = OrganizationSummary("o", "acme", "Acme Media", "active", 4, 9, false, 12, 10, 25, 3, 2)
        show { OverviewContent(OverviewState(loading = false, summary = summary, ownerName = null, ownerResolved = true)) {} }
        compose.onNodeWithText("Name unavailable").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Account 4").assertCountEquals(0)
    }

    @Test
    fun pullingDownOnAnOrganizationPageRefreshesIt() {
        val summary = OrganizationSummary("o", "acme", "Acme Media", "active", 4, 9, false, 12, 10, 25, 3, 2)
        var refreshes = 0
        show { OverviewContent(OverviewState(loading = false, summary = summary), onRefresh = { refreshes++ }) {} }
        compose.onNodeWithTag("organization-page").performTouchInput { swipeDown() }
        compose.waitUntil(5_000) { refreshes == 1 }
        // The indicator ends, so a second pull refreshes again.
        compose.waitForIdle()
        compose.onNodeWithTag("organization-page").performTouchInput { swipeDown() }
        compose.waitUntil(5_000) { refreshes == 2 }
    }

    @Test
    fun aPageWithNoRefreshSuppliedDoesNotStartOne() {
        val summary = OrganizationSummary("o", "acme", "Acme Media", "active", 4, 9, false, 12, 10, 25, 3, 2)
        var retried = 0
        show { OverviewContent(OverviewState(loading = false, summary = summary)) { retried++ } }
        compose.onNodeWithTag("organization-page").performTouchInput { swipeDown() }
        compose.waitForIdle()
        assertEquals(0, retried)
    }

    @Test
    fun overviewThatFailedShowsTheReasonAndRetries() {
        var retried = 0
        show { OverviewContent(OverviewState(loading = false, error = ErrorText.Raw("Tenant administration is unavailable"))) { retried++ } }
        compose.onNodeWithText("Tenant administration is unavailable").assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        assertEquals(1, retried)
    }

    @Test
    fun accessGroupsListWithMemberCountsAndOfferNewAndEdit() {
        show { AccessGroupsContent(AccessGroupsState(loading = false, groups = listOf(family, kids)), onRetry = {}) }
        // The top bar names the page, so the page has no heading of its own.
        compose.onNodeWithText("Access groups").assertDoesNotExist()
        compose.onNodeWithText("Family").assertIsDisplayed()
        compose.onNodeWithText("Default").assertIsDisplayed()
        compose.onNodeWithText("6 members").assertIsDisplayed()
        compose.onNodeWithText("1 member").assertIsDisplayed()
        compose.onNodeWithTag("organization-group-new").assertIsDisplayed()
        compose.onNodeWithContentDescription("Edit Kids").assertIsDisplayed()
    }

    @Test
    fun aSharedLibraryCanBePausedAfterConfirming() {
        var changed: Pair<Long, String>? = null
        val shared = OrganizationLibrary(2, "Shared films", "movie", "entitled", LibraryEntitlement("e", "o", "active", 9))
        val owned = OrganizationLibrary(1, "Home videos", "movie", "owned", LibraryEntitlement("e2", "o", "active", 3))
        show {
            LibrariesContent(
                LibrariesState(loading = false, libraries = listOf(owned, shared)), onRetry = {},
                onSetStatus = { library, status -> changed = library.folder_id to status },
            )
        }
        compose.onNodeWithText("Shared by the server").assertIsDisplayed()
        compose.onNodeWithText("Owned by this organization").assertIsDisplayed()
        compose.onNodeWithContentDescription("Pause for Home videos").assertDoesNotExist()
        compose.onNodeWithContentDescription("Pause for Shared films").performClick()
        compose.onNodeWithText("Pause Shared films?").assertIsDisplayed()
        assertNull(changed)
        inDialog("Pause").performClick()
        assertEquals(2L to "suspended", changed)
    }

    @Test
    fun aPausedLibraryOffersRestore() {
        val paused = OrganizationLibrary(2, "Shared films", "movie", "entitled", LibraryEntitlement("e", "o", "suspended", 9))
        show { LibrariesContent(LibrariesState(loading = false, libraries = listOf(paused)), onRetry = {}, onSetStatus = { _, _ -> }) }
        compose.onNodeWithText("Paused: members can't use it until it is restored").assertIsDisplayed()
        compose.onNodeWithContentDescription("Restore for Shared films").assertIsDisplayed()
    }

    @Test
    fun invitationsListSendAndRevoke() {
        val pending = OrganizationInvitation("i1", "new@example.test", "link", status = "pending", invited_by_name = "Pat Owner", expires_at = "2026-11-20T00:00:00Z", access_group_id = "3")
        val accepted = OrganizationInvitation("i2", "old@example.test", "link", status = "accepted")
        var sent: Triple<String, Long?, String?>? = null
        var revoked: String? = null
        var resent: String? = null
        show {
            InvitationsContent(
                state = InvitationsState(loading = false, invitations = listOf(pending, accepted), groups = listOf(family)),
                onRetry = {}, onDismissError = {},
                onSend = { email, group, note -> sent = Triple(email, group, note); InvitationResult.Failed(ErrorText.Raw("An account with this email already exists")) },
                onResend = { resent = it.id }, onRevoke = { revoked = it.id }, onDismissLink = {},
            )
        }
        compose.onNodeWithText("new@example.test").assertIsDisplayed()
        compose.onNodeWithText("Pending").assertIsDisplayed()
        compose.onNodeWithText("Accepted").assertIsDisplayed()
        compose.onNodeWithContentDescription("Revoke: old@example.test").assertDoesNotExist()

        compose.onNodeWithContentDescription("Resend with a fresh link: new@example.test").performClick()
        assertEquals("i1", resent)

        compose.onNodeWithContentDescription("Revoke: new@example.test").performClick()
        compose.onNodeWithText("Revoke the invitation for new@example.test? Their link will stop working immediately.").assertIsDisplayed()
        inDialog("Revoke").performClick()
        assertEquals("i1", revoked)

        compose.onNodeWithTag("organization-invite").performClick()
        compose.onNodeWithTag("organization-invite-create").assertIsNotEnabled()
        compose.onNodeWithTag("organization-invite-email").performTextInput("kim@example.test")
        compose.onNodeWithTag("organization-invite-create").assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals(Triple("kim@example.test", null, "" ), sent?.let { Triple(it.first, it.second, it.third ?: "") })
        compose.onNodeWithText("An account with this email already exists").assertIsDisplayed()
    }

    @Test
    fun theClaimLinkIsShownOnceWithTheDeliverItYourselfNote() {
        var dismissed = 0
        val link = ClaimLink(OrganizationInvitation("i3", "kim@example.test", "link", status = "pending"), "https://media.example.test/invite/tok")
        show {
            InvitationsContent(
                InvitationsState(loading = false, invitations = emptyList(), claimLink = link),
                {}, {}, { _, _, _ -> InvitationResult.Failed(ErrorText.Raw("x")) }, {}, {}, { dismissed++ },
            )
        }
        compose.onNodeWithText("Invitation link ready").assertIsDisplayed()
        compose.onNodeWithTag("organization-claim-link").assertIsDisplayed()
        compose.onNodeWithText("https://media.example.test/invite/tok").assertIsDisplayed()
        compose.onNodeWithText("Copy link").performClick()
        compose.onNodeWithText("Link copied").assertIsDisplayed()
        compose.onNodeWithText("Done").performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun activityNamesActionsLikeTheWebAndPagesOlder() {
        var older = 0
        val events = listOf(
            OrganizationAuditEvent(2, "people", "2026-11-12T09:30:00Z", 4, "people.membership_updated", "abc", "success"),
            OrganizationAuditEvent(1, "people", "2026-11-11T09:30:00Z", null, "people.bulk_job_failed", "def", "partial_failure"),
            OrganizationAuditEvent(0, "x", "2026-11-10T09:30:00Z", null, "thing.new_code", "ghi", "success"),
        )
        show { ActivityContent(OrganizationActivityState(loading = false, events = events, nextCursor = "c"), {}) { older++ } }
        compose.onNodeWithText("Membership changed").assertIsDisplayed()
        compose.onNodeWithText("Bulk change failed").assertIsDisplayed()
        compose.onNodeWithText("Some not changed").assertIsDisplayed()
        compose.onNodeWithText("Thing new code").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("organization-activity-older").performScrollTo().performClick()
        assertEquals(1, older)
    }

    @Test
    fun activityNamesPlatformSupportAndShowsItsReason() {
        val events = listOf(
            OrganizationAuditEvent(3, "lifecycle", "2026-11-12T09:30:00Z", null, "support_access.opened", "abc", "success",
                actor_authority = "platform_support", reason = "Customer asked for help with a stuck scan", support_access_id = "a1"),
            OrganizationAuditEvent(2, "people", "2026-11-12T09:00:00Z", null, "people.membership_updated", "abc", "success", actor_authority = "platform_support"),
            OrganizationAuditEvent(1, "people", "2026-11-12T08:00:00Z", 4, "people.membership_updated", "def", "success", actor_authority = "organization_admin"),
            OrganizationAuditEvent(0, "entitlement", "2026-11-12T07:00:00Z", null, "entitlement.paused", "ghi", "success"),
        )
        show { ActivityContent(OrganizationActivityState(loading = false, events = events), {}) {} }
        val whenText = formatWhen("2026-11-12T09:30:00Z")
        compose.onNodeWithText("$whenText · By Platform support").assertIsDisplayed()
        compose.onNodeWithText("Reason: Customer asked for help with a stuck scan").assertIsDisplayed()
        compose.onNodeWithText("${formatWhen("2026-11-12T09:00:00Z")} · By Platform support").assertIsDisplayed()
        compose.onNodeWithText("${formatWhen("2026-11-12T08:00:00Z")} · By account 4").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("${formatWhen("2026-11-12T07:00:00Z")} · By Bloem").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun cohortsAndDecisionsAreReadOnlyLists() {
        val cohort = EntitlementCohort(
            cohort_id = "c1", organization_id = "o", name = "Family plan", revision = 2, access_group_id = 3, source_template_key = "family",
            source_template_revision = 1, derivation_kind = "exact_template",
            policy = PolicyView(max_streams = 2, max_profiles = 5, max_playback_quality = "original"), member_count = 6, created_at = "2026-10-01T00:00:00Z",
        )
        show { CohortsContent(CohortsState(loading = false, cohorts = listOf(cohort)), onRetry = {}) }
        compose.onNodeWithText("Family plan").assertIsDisplayed()
        compose.onNodeWithText("6 accounts").assertIsDisplayed()
        compose.onNodeWithText("From plan family, revision 1").assertIsDisplayed()
    }

    @Test
    fun decisionsShowAllowedAndRefused() {
        val decisions = listOf(
            PolicyDecision(2, "2026-11-12T09:30:00Z", PolicyDecisionSubject(5, "p1"), PolicyDecisionGroupRef(3, "Family"), "watch", true, "allowed_by_group"),
            PolicyDecision(1, "2026-11-12T09:00:00Z", PolicyDecisionSubject(0, ""), PolicyDecisionGroupRef(), "download", false, "download_not_allowed"),
        )
        show { DecisionsContent(DecisionsState(loading = false, decisions = decisions), onRetry = {}, onMore = {}) }
        compose.onNodeWithText("Watch: allowed").assertIsDisplayed()
        compose.onNodeWithText("Download: refused").assertIsDisplayed()
        compose.onNodeWithText("Account not recorded").assertIsDisplayed()
    }

    @Test
    fun touchTargetsMeetTheMinimum() {
        show {
            PeopleContent(PeopleState(loading = false, people = listOf(sam), groups = listOf(family)), context,
                {}, {}, {}, {}, { _, _ -> }, { _, _, _ -> })
        }
        compose.onNodeWithContentDescription("Actions for Sam: Suspend").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("Move Living room to an access group").assertHeightIsAtLeast(48.dp)
    }
}
