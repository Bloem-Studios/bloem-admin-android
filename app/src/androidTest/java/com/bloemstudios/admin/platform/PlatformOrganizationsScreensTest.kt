package com.bloemstudios.admin.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.organization.OrganizationSummary
import com.bloemstudios.admin.ui.theme.VeilAdminTheme
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Platform › Organizations pages on a real device, from fixture state: no network, no credentials. */
@RunWith(AndroidJUnit4::class)
class PlatformOrganizationsScreensTest {
    @get:Rule val compose = createComposeRule()

    private val acme = OrganizationSummary(
        id = "o1", slug = "acme", name = "Acme Media", status = "active", owner_account_id = 4, policy_revision = 7,
        membership_count = 12, active_membership_count = 10, profile_count = 25, library_count = 3, entitlement_count = 2,
    )
    private val north = acme.copy(id = "o2", slug = "north-sea", name = "North Sea Media", status = "suspended", active_membership_count = 0)
    private val members = listOf(
        PlatformMembership("m4", "o1", 4, "active", "admin", 3, "pat@acme.test", "pat"),
        PlatformMembership("m5", "o1", 5, "suspended", "user", 2, "sam@acme.test", "sam"),
    )
    private val standard = EntitlementTemplate("standard", "Standard", 3, enabled = true, status = "enabled")
    private val premium = EntitlementTemplate("premium", "Premium", 2, enabled = true, status = "enabled")
    private val entitlement = OrganizationEntitlement(template_key = "standard", template_revision = 3, tenant_limits = TenantLimits(50, 4))
    private val dryRun = EntitlementDryRun(
        template_key = "premium", template_revision = 2, changed = true, dry_run_token = "t",
        changes = listOf(
            EntitlementChange(
                "template_revision",
                buildJsonObject { put("template_key", "standard"); put("template_revision", 3L) },
                buildJsonObject { put("template_key", "premium"); put("template_revision", 2L) },
            ),
            EntitlementChange("max_streams", JsonPrimitive(2), JsonPrimitive(4)),
        ),
    )

    private fun show(content: @Composable () -> Unit) = compose.setContent { VeilAdminTheme { content() } }

    private val words: PlanWords get() = PlanWords(InstrumentationRegistry.getInstrumentation().targetContext.resources)

    private fun inDialog(text: String) = compose.onNode(hasText(text) and hasAnyAncestor(isDialog()))

    private class Calls {
        var suspended: Boolean? = null
        var opened: Boolean = false
        var openedReason: String? = "unset"
        var previewed = 0
        var applied: String? = null
        var selected: String? = null
    }

    private fun page(
        state: PlatformOrganizationState,
        plan: OrganizationPlanState = OrganizationPlanState(loading = false, entitlement = entitlement, templates = listOf(standard, premium)),
        calls: Calls = Calls(),
    ) = show {
        PlatformOrganizationContent(
            state = state, plan = plan, words = words, onBack = {},
            onOpen = { calls.opened = true; calls.openedReason = it },
            onSetSuspended = { calls.suspended = it }, onMoreMembers = {}, onSelectPlan = { calls.selected = it },
            onPreviewPlan = { calls.previewed++ }, onApplyPlan = { calls.applied = it }, onRetry = {}, onDismiss = {}, onDismissPlan = {},
        )
    }

    private fun loaded(organization: OrganizationSummary = acme, access: OpenAccess = OpenAccess.None, owner: String? = "pat") =
        PlatformOrganizationState(
            loading = false, organization = organization, members = members, ownerName = owner, ownerResolved = true, openAccess = access,
        )

    // ---- The list -----------------------------------------------------------------------------------

    @Test
    fun theListShowsEachOrganizationWithItsStatusAndCounts() {
        show {
            PlatformOrganizationsContent(
                PlatformOrganizationsState(loading = false, organizations = listOf(acme, north), nextCursor = "c"),
                onSearch = {}, onStatus = {}, onRetry = {}, onMore = {}, onOpen = {}, onDismiss = {},
            )
        }
        compose.onNodeWithText("Acme Media").assertIsDisplayed()
        compose.onAllNodesWithText("12 people · 25 profiles · 3 libraries", substring = true).onFirst().assertIsDisplayed()
        compose.onNodeWithText("Active").assertIsDisplayed()
        compose.onNodeWithText("Suspended").assertIsDisplayed()
        compose.onNodeWithText("Any status").assertIsDisplayed()
        compose.onNodeWithTag("platform-orgs-more").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun anEmptyListSaysNoOrganizationMatches() {
        show {
            PlatformOrganizationsContent(
                PlatformOrganizationsState(loading = false, organizations = emptyList()),
                onSearch = {}, onStatus = {}, onRetry = {}, onMore = {}, onOpen = {}, onDismiss = {},
            )
        }
        compose.onNodeWithText("No organizations match these filters").assertIsDisplayed()
    }

    @Test
    fun aRefusedListSaysWhatHappenedAndOffersTryAgain() {
        var retried = 0
        show {
            PlatformOrganizationsContent(
                PlatformOrganizationsState(loading = false, error = ErrorText.Res(com.bloemstudios.admin.R.string.platform_orgs_no_access)),
                onSearch = {}, onStatus = {}, onRetry = { retried++ }, onMore = {}, onOpen = {}, onDismiss = {},
            )
        }
        compose.onNodeWithText("This account can't manage organizations", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        assertEquals(1, retried)
    }

    @Test
    fun tappingARowOpensThatOrganization() {
        var opened: String? = null
        show {
            PlatformOrganizationsContent(
                PlatformOrganizationsState(loading = false, organizations = listOf(acme, north)),
                onSearch = {}, onStatus = {}, onRetry = {}, onMore = {}, onOpen = { opened = it.id }, onDismiss = {},
            )
        }
        compose.onNodeWithTag("platform-org-o2").performClick()
        assertEquals("o2", opened)
    }

    // ---- One organization ---------------------------------------------------------------------------

    @Test
    fun theOrganizationShowsItsOwnerByNameAndNeverByAccountNumber() {
        page(loaded())
        compose.onNodeWithText("pat", substring = true).assertIsDisplayed()
        compose.onAllNodesWithText("Account 4").assertCountEquals(0)
        compose.onNodeWithTag("organization-page").performScrollToNode(hasText("Members"))
        compose.onNodeWithText("Members").assertIsDisplayed()
        compose.onNodeWithTag("organization-page").performScrollToNode(hasTestTag("platform-member-m5"))
        compose.onNodeWithTag("platform-member-m5").assertIsDisplayed()
        compose.onNodeWithText("sam@acme.test").assertIsDisplayed()
    }

    @Test
    fun anOwnerThatCannotBeNamedSaysSo() {
        page(loaded(owner = null))
        compose.onNodeWithText("Name unavailable").assertIsDisplayed()
        compose.onAllNodesWithText("Account 4").assertCountEquals(0)
    }

    @Test
    fun openAsAdministratorIsShownOnlyWhenTheAccountMay() {
        page(loaded(access = OpenAccess.None))
        compose.onAllNodesWithText("Open as administrator").assertCountEquals(0)
    }

    @Test
    fun aMemberOpensTheOrganizationWithoutAReason() {
        val calls = Calls()
        page(loaded(access = OpenAccess.Membership), calls = calls)
        compose.onNodeWithText("Open as administrator").performClick()
        assertTrue(calls.opened)
        assertNull(calls.openedReason)
    }

    @Test
    fun theOwnerOpensAnOrganizationTheyAreNotInOnlyAfterStatingAReason() {
        val calls = Calls()
        page(loaded(access = OpenAccess.Support), calls = calls)
        compose.onNodeWithText("Open as administrator").performClick()
        assertTrue("nothing opens before the reason is given", !calls.opened)
        compose.onNodeWithTag("organization-support-reason").performTextInput("A customer cannot sign in and asked for help")
        compose.onNodeWithTag("organization-support-reason-open").performClick()
        assertTrue(calls.opened)
        assertEquals("A customer cannot sign in and asked for help", calls.openedReason)
    }

    @Test
    fun suspendingNamesWhoLosesAccessAndSendsNothingUntilConfirmed() {
        val calls = Calls()
        page(loaded(), calls = calls)
        compose.onNodeWithTag("platform-org-suspend").performScrollTo().performClick()
        inDialog("Suspend Acme Media").assertIsDisplayed()
        inDialog("10 active members lose access to Acme Media until you reactivate it. Nothing is deleted.").assertIsDisplayed()
        assertNull(calls.suspended)
        compose.onNodeWithTag("platform-org-suspend-confirm").performClick()
        assertEquals(true, calls.suspended)
    }

    @Test
    fun cancellingTheSuspendConfirmationChangesNothing() {
        val calls = Calls()
        page(loaded(), calls = calls)
        compose.onNodeWithTag("platform-org-suspend").performScrollTo().performClick()
        inDialog("Cancel").performClick()
        assertNull(calls.suspended)
        compose.onAllNodesWithText("Suspend Acme Media").assertCountEquals(0)
    }

    @Test
    fun aSuspendedOrganizationOffersReactivateAndStatesWhatItDoes() {
        val calls = Calls()
        page(loaded(organization = north), calls = calls)
        compose.onAllNodesWithText("Suspend organization").assertCountEquals(0)
        compose.onNodeWithTag("platform-org-reactivate").performScrollTo().performClick()
        inDialog("Members of North Sea Media can use it again.").assertIsDisplayed()
        compose.onNodeWithTag("platform-org-reactivate-confirm").performClick()
        assertEquals(false, calls.suspended)
    }

    // ---- Plan ---------------------------------------------------------------------------------------

    @Test
    fun thePlanShowsTheCurrentPlanWithItsRevisionAndTheLimits() {
        page(loaded())
        compose.onNodeWithTag("organization-page").performScrollToNode(hasTestTag("platform-plan"))
        compose.onAllNodesWithText("Standard, revision 3").onFirst().assertExists()
        compose.onNodeWithText("50 screens · 4 conversions at once").assertExists()
    }

    @Test
    fun aServerWithoutPlansShowsNoPlanSection() {
        page(loaded(), plan = OrganizationPlanState(loading = false, available = false))
        compose.onAllNodesWithText("Plan").assertCountEquals(0)
    }

    @Test
    fun previewIsOfferedOnlyOnceAPlanIsChosen() {
        val calls = Calls()
        page(loaded(), plan = OrganizationPlanState(loading = false, entitlement = entitlement, templates = listOf(standard, premium)), calls = calls)
        compose.onNodeWithTag("platform-plan-preview").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("platform-plan-choice-premium").performScrollTo().performClick()
        assertEquals("premium", calls.selected)
    }

    @Test
    fun aChosenPlanCanBePreviewed() {
        val calls = Calls()
        page(
            loaded(), plan = OrganizationPlanState(loading = false, entitlement = entitlement, templates = listOf(standard, premium), selectedKey = "premium"),
            calls = calls,
        )
        compose.onNodeWithTag("platform-plan-preview").performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, calls.previewed)
    }

    @Test
    fun theConfirmationShowsTheDryRunChangesInPlainWordsBeforeAnythingIsApplied() {
        val calls = Calls()
        page(
            loaded(),
            plan = OrganizationPlanState(
                loading = false, entitlement = entitlement, templates = listOf(standard, premium), selectedKey = "premium",
                preview = PlanPreview(premium, dryRun),
            ),
            calls = calls,
        )
        compose.onNodeWithTag("platform-plan-changes").performScrollTo()
        compose.onNodeWithText("These settings change").assertIsDisplayed()
        compose.onNodeWithTag("platform-plan-apply").performScrollTo().performClick()
        inDialog("Put Acme Media on Premium?").assertIsDisplayed()
        inDialog("Plan: Standard, revision 3 → Premium, revision 2").assertIsDisplayed()
        inDialog("Screens at once: 2 → 4").assertIsDisplayed()
        inDialog("Members on the default settings get the changes you reviewed. Access groups the organization made itself stay as they are.").assertExists()
        assertNull("nothing is applied until it is confirmed", calls.applied)
        compose.onNodeWithTag("platform-plan-apply-confirm").performClick()
        assertEquals("Acme Media", calls.applied)
    }

    @Test
    fun aPreviewWithNothingToChangeCannotBeApplied() {
        page(
            loaded(),
            plan = OrganizationPlanState(
                loading = false, entitlement = entitlement, templates = listOf(standard, premium), selectedKey = "standard",
                preview = PlanPreview(standard, dryRun.copy(changed = false, changes = emptyList(), template_key = "standard", template_revision = 3)),
            ),
        )
        compose.onNodeWithText("Nothing changes: it already uses this plan").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Apply plan").assertCountEquals(0)
    }
}
