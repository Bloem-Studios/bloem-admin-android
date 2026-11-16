package com.bloemstudios.admin.organization

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.ui.theme.VeilAdminTheme
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The second pass of organization mode on a device, from fixture state: bulk people actions, a person's page, access group
 * editing, giving a library back, and the cohort and decision detail the web shows. No network, no credentials.
 */
@RunWith(AndroidJUnit4::class)
class OrganizationWebParityScreensTest {
    @get:Rule val compose = createComposeRule()

    private val family = AccessGroup(id = "3", name = "Family", is_default = true, member_count = 6, max_streams = 2, max_profiles = 5,
        max_playback_quality = "1080p", download_allowed = true, requests_allowed = true, library_ids = listOf("1"), transcode_allowed = true)
    private val kids = AccessGroup(id = "4", name = "Kids", member_count = 4, max_streams = 1, max_profiles = 3)
    private val sam = PersonSummary(
        organization_id = "o", account_id = 5, email = "sam@example.test", display_name = "Sam", membership_id = "m5",
        membership_status = "active", security_revision = 4, last_activity = "2026-11-12T09:30:00Z",
        profiles = listOf(ProfileSummary("p1", "Living room", 3, "Family", "2026-11-12T08:00:00Z")),
    )
    private val ana = sam.copy(account_id = 6, email = "ana@example.test", display_name = "Ana", membership_id = "m6",
        membership_status = "suspended", security_revision = 2, profiles = emptyList())
    private val context = AdminContext("organization:o", "Acme Media", AdminContextScope.Organization, "o", "active",
        AdminAuthority.OrganizationAdmin, membershipId = "mine")

    private fun inDialog(text: String) = compose.onNode(hasText(text) and hasAnyAncestor(isDialog()))
    /** The page is a lazy list: bring the row with [tag] into the tree before looking at it. */
    private fun scrollToTag(tag: String) = compose.onNodeWithTag("organization-page").performScrollToNode(hasTestTag(tag))
    private fun scrollToDescription(description: String) = compose.onNodeWithTag("organization-page").performScrollToNode(hasContentDescription(description))

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) = compose.setContent { VeilAdminTheme { content() } }

    // ---- People: sort and recent activity -------------------------------------------------------------------

    @Test
    fun sortAndRecentActivityReachTheCaller() {
        val filters = mutableListOf<PeopleFilter>()
        show {
            PeopleContent(PeopleState(loading = false, people = listOf(sam), groups = listOf(family)), context,
                { filters += it }, {}, {}, {}, { _, _ -> }, { _, _, _ -> })
        }
        compose.onNodeWithContentDescription("Sort people: Name").performClick()
        compose.onNodeWithText("Recent activity").performClick()
        assertEquals(PeopleSort.RECENT_ACTIVITY, filters.last().sort)

        compose.onNodeWithContentDescription("Active since: Any time").performClick()
        compose.onNodeWithText("Last 7 days").performClick()
        assertEquals(7, filters.last().activeWithinDays)
    }

    @Test
    fun theChosenSortAndRecentActivityAreShownOnTheirMenus() {
        show {
            PeopleContent(
                PeopleState(loading = false, people = listOf(sam), filter = PeopleFilter(sort = PeopleSort.EMAIL, activeWithinDays = 30)),
                context, {}, {}, {}, {}, { _, _ -> }, { _, _, _ -> },
            )
        }
        compose.onNodeWithContentDescription("Sort people: Email").assertIsDisplayed()
        compose.onNodeWithContentDescription("Active since: Last 30 days").assertIsDisplayed()
    }

    // ---- People: choosing several and changing them -----------------------------------------------------------

    @Test
    fun peopleCanBeTickedAndChangedTogether() {
        val state = mutableStateOf(PeopleState(loading = false, people = listOf(sam, ana), groups = listOf(family, kids), approximateTotal = 2))
        val toggled = mutableListOf<Long>()
        val applied = mutableListOf<BulkAction>()
        show {
            PeopleContent(
                state.value, context, {}, {}, {}, {}, { _, _ -> }, { _, _, _ -> },
                onStartSelecting = { state.value = state.value.copy(selecting = true) },
                onToggle = { id ->
                    toggled += id
                    val picked = state.value.pickedIds
                    state.value = state.value.copy(pickedIds = if (id in picked) picked - id else picked + id)
                },
                onApply = { applied += it },
            )
        }
        compose.onNodeWithTag("organization-people-select").performClick()
        compose.onNodeWithTag("organization-people-bulk-bar").assertDoesNotExist()
        compose.onNodeWithContentDescription("Select Sam").performScrollTo().performClick()
        // The bulk bar now shows above the list, which can push the next row below a phone's fold.
        compose.onNodeWithTag("organization-page").performScrollToNode(hasContentDescription("Select Ana"))
        compose.onNodeWithContentDescription("Select Ana").performClick()
        assertEquals(listOf(5L, 6L), toggled)
        compose.onNodeWithText("2 people selected").assertIsDisplayed()

        // Every bulk action is on screen, even on a 320dp-wide phone.
        compose.onNodeWithTag("organization-people-bulk-suspend").assertIsDisplayed().performClick()
        compose.onNodeWithText("Suspend their memberships?").assertIsDisplayed()
        compose.onNodeWithText("This changes the 2 people you picked in Acme Media, one after another").assertIsDisplayed()
        assertTrue("nothing happens before confirming", applied.isEmpty())
        compose.onNodeWithTag("organization-people-bulk-confirm").performClick()
        assertEquals(listOf<BulkAction>(BulkAction.Suspend), applied)
    }

    @Test
    fun movingTheChosenPeopleAsksForTheGroupThenConfirms() {
        val applied = mutableListOf<BulkAction>()
        show {
            PeopleContent(
                PeopleState(loading = false, people = listOf(sam), groups = listOf(family, kids), selecting = true, pickedIds = setOf(5L)),
                context, {}, {}, {}, {}, { _, _ -> }, { _, _, _ -> }, onApply = { applied += it },
            )
        }
        compose.onNodeWithTag("organization-people-bulk-move").performClick()
        compose.onNodeWithText("Move their profiles to an access group").assertIsDisplayed()
        inDialog("Kids").performClick()
        compose.onNodeWithText("Move their profiles to Kids?").assertIsDisplayed()
        compose.onNodeWithTag("organization-people-bulk-confirm").performClick()
        assertEquals(listOf<BulkAction>(BulkAction.AssignGroup(kids)), applied)
    }

    @Test
    fun selectAllSaysWhoMatchesAndWhoIsLeftOut() {
        val applied = mutableListOf<BulkAction>()
        var selectedAll = 0
        val filter = PeopleFilter(query = "sam", statuses = setOf("active"), groupId = 3, activeWithinDays = 7)
        show {
            PeopleContent(
                PeopleState(
                    loading = false, people = listOf(sam), groups = listOf(family, kids), approximateTotal = 40, filter = filter, selecting = true,
                    matching = PeopleSelection("token", matched = 40, excluded = 3, expires_at = "2026-11-13T12:30:00Z"),
                ),
                context, {}, {}, {}, {}, { _, _ -> }, { _, _, _ -> }, onSelectAll = { selectedAll++ }, onApply = { applied += it },
            )
        }
        compose.onNodeWithText("40 people selected").assertIsDisplayed()
        compose.onNodeWithTag("organization-people-select-all").assertIsNotEnabled()
        compose.onNodeWithTag("organization-people-bulk-reactivate").performClick()
        compose.onNodeWithText("Reactivate their memberships?").assertIsDisplayed()
        compose.onNodeWithText(
            "This changes 40 people in Acme Media (matching “sam”, active, in Family, active in the last 7 days). " +
                "People who match later aren't included. 3 more match but are left out",
        ).assertIsDisplayed()
        compose.onNodeWithTag("organization-people-bulk-confirm").performClick()
        assertEquals(listOf<BulkAction>(BulkAction.Reactivate), applied)
        assertEquals(0, selectedAll)
    }

    @Test
    fun selectAllIsOfferedWhileChoosing() {
        var selectedAll = 0
        show {
            PeopleContent(
                PeopleState(loading = false, people = listOf(sam), groups = listOf(family), approximateTotal = 12, selecting = true),
                context, {}, {}, {}, {}, { _, _ -> }, { _, _, _ -> }, onSelectAll = { selectedAll++ },
            )
        }
        compose.onNodeWithText("Select all 12").performClick()
        assertEquals(1, selectedAll)
    }

    @Test
    fun theBulkResultNamesWhoWasSkippedAndWhyInTheWebsWords() {
        var dismissed = 0
        val job = BulkJob(
            "job-1", "completed", progress_current = 3, progress_total = 3, succeeded = 1,
            skipped = listOf(BulkRecordResult(6, "already_applied"), BulkRecordResult(9, "protected_owner")),
            failed = listOf(BulkRecordResult(5, "mutation_failed")),
        )
        show {
            PeopleContent(
                PeopleState(loading = false, people = listOf(sam), report = BulkReport(BulkAction.Suspend, job, mapOf(6L to "Ana", 5L to "Sam"))),
                context, {}, {}, {}, {}, { _, _ -> }, { _, _, _ -> }, onDismissReport = { dismissed++ },
            )
        }
        compose.onNodeWithText("Some people were changed").assertIsDisplayed()
        compose.onNodeWithText("3 of 3 done").assertIsDisplayed()
        compose.onNodeWithText("1 changed · 2 skipped · 1 failed").assertIsDisplayed()
        compose.onNodeWithText("Ana: already had these settings").assertIsDisplayed()
        compose.onNodeWithText("Account 9: is the organization owner and can't be changed here").assertIsDisplayed()
        compose.onNodeWithText("Sam: couldn't be saved").assertIsDisplayed()
        compose.onNodeWithTag("organization-bulk-dismiss").performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun aRunningChangeCannotBeDismissedAndLocksTheRows() {
        val job = BulkJob("job-1", "running", progress_current = 1, progress_total = 4)
        show {
            PeopleContent(
                PeopleState(loading = false, people = listOf(sam), selecting = true, pickedIds = setOf(5L), report = BulkReport(BulkAction.Suspend, job)),
                context, {}, {}, {}, {}, { _, _ -> }, { _, _, _ -> },
            )
        }
        compose.onNodeWithText("Changing people…").assertIsDisplayed()
        compose.onNodeWithTag("organization-bulk-dismiss").assertDoesNotExist()
        compose.onNodeWithTag("organization-people-bulk-bar").assertDoesNotExist()
        compose.onNodeWithText("Working…").assertIsDisplayed()
    }

    @Test
    fun touchTargetsOfTheNewControlsMeetTheMinimum() {
        show {
            PeopleContent(
                PeopleState(loading = false, people = listOf(sam), groups = listOf(family), selecting = true, pickedIds = setOf(5L), approximateTotal = 5),
                context, {}, {}, {}, {}, { _, _ -> }, { _, _, _ -> },
            )
        }
        compose.onNodeWithTag("organization-people-bulk-suspend").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("organization-people-bulk-move").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("Select Sam").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("Details for Sam").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("organization-people-done-selecting").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun theDetailsButtonOpensThePerson() {
        var opened: Long? = null
        show {
            PeopleContent(PeopleState(loading = false, people = listOf(sam)), context, {}, {}, {}, {}, { _, _ -> }, { _, _, _ -> }, onOpen = { opened = it.account_id })
        }
        compose.onNodeWithContentDescription("Details for Sam").performClick()
        assertEquals(5L, opened)
    }

    // ---- One person ----------------------------------------------------------------------------------------------

    @Test
    fun aPersonsPageShowsMembershipProfilesGroupsAndRecentChanges() {
        val change = OrganizationAuditEvent(7, "people", "2026-11-12T09:30:00Z", 4, "people.membership_updated", "m5", "success")
        show {
            PersonDetailContent(
                PersonDetailState(loading = false, person = sam, groups = listOf(family, kids), changes = listOf(change), changesLooked = 50),
                context, {}, {}, {}, {}, { _, _ -> },
            )
        }
        compose.onNodeWithText("Sam").assertIsDisplayed()
        compose.onNodeWithText("sam@example.test").assertIsDisplayed()
        compose.onNodeWithText("Active").assertIsDisplayed()
        compose.onNodeWithText("Account 5 · revision 4").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Living room").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Access group: Family").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Streams: 2 · Profiles: 5 · Up to 1080p").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Membership changed").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Changes to this membership among the latest 50 on the organization").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aPersonsPageSaysWhatTheServerDoesNotOfferAndOffersNoResetOrSignOut() {
        show { PersonDetailContent(PersonDetailState(loading = false, person = sam), context, {}, {}, {}, {}, { _, _ -> }) }
        compose.onNodeWithTag("organization-person-limits").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Resetting a password, signing someone out and editing a profile aren't available", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Reset password", ignoreCase = true).assertDoesNotExist()
        compose.onNodeWithText("Sign out", substring = true, ignoreCase = true).assertDoesNotExist()
    }

    @Test
    fun suspendingFromAPersonsPageAsksFirst() {
        val asked = mutableListOf<Boolean>()
        show {
            PersonDetailContent(PersonDetailState(loading = false, person = sam), context, {}, {}, {}, { asked += it }, { _, _ -> })
        }
        compose.onNodeWithContentDescription("Actions for Sam: Suspend").performClick()
        compose.onNodeWithText("Suspend Sam?").assertIsDisplayed()
        assertTrue(asked.isEmpty())
        inDialog("Suspend").performClick()
        assertEquals(listOf(true), asked)
    }

    @Test
    fun theAdministratorCannotSuspendThemselvesFromTheirOwnPage() {
        val me = sam.copy(account_id = 1, display_name = "Pat Owner", membership_id = "mine", legacy_role = "admin")
        show { PersonDetailContent(PersonDetailState(loading = false, person = me), context, {}, {}, {}, {}, { _, _ -> }) }
        compose.onNodeWithContentDescription("Actions for Pat Owner: Suspend").assertDoesNotExist()
        compose.onNodeWithText("Administrator · You").assertIsDisplayed()
    }

    @Test
    fun aPersonTheServerNoLongerShowsIsSaidSo() {
        show { PersonDetailContent(PersonDetailState(loading = false, person = null, gone = true), context, {}, {}, {}, {}, { _, _ -> }) }
        compose.onNodeWithText("This person isn't in this organization any more").assertIsDisplayed()
    }

    @Test
    fun aProfileOnAPersonsPageCanMoveToAnotherGroup() {
        var moved: Pair<String, String>? = null
        show {
            PersonDetailContent(
                PersonDetailState(loading = false, person = sam, groups = listOf(family, kids)), context, {}, {}, {}, {},
                { profile, group -> moved = profile.id to group.id },
            )
        }
        compose.onNodeWithContentDescription("Move Living room to an access group").performScrollTo().performClick()
        inDialog("Kids").performClick()
        assertEquals("p1" to "4", moved)
    }

    // ---- Access groups: new, edit, delete ---------------------------------------------------------------------------

    @Test
    fun aNewGroupNeedsANameThenIsCreated() {
        var created: String? = null
        show { AccessGroupsContent(AccessGroupsState(loading = false, groups = listOf(family)), onRetry = {}, onCreate = { created = it }) }
        compose.onNodeWithTag("organization-group-new").performClick()
        compose.onNodeWithTag("organization-group-create").assertIsNotEnabled()
        compose.onNodeWithTag("organization-group-new-name").performTextInput("Guests")
        compose.onNodeWithTag("organization-group-create").assertIsEnabled().performClick()
        assertEquals("Guests", created)
    }

    private val libraries = listOf(
        OrganizationLibrary(1, "Films", "movie", "owned"),
        OrganizationLibrary(2, "Shared shows", "show", "entitled", LibraryEntitlement("e", "o", "suspended", 3)),
    )

    @Test
    fun theGroupEditorSavesWhatWasChanged() {
        var saved: AccessGroupDraft? = null
        show {
            AccessGroupsContent(
                AccessGroupsState(loading = false, groups = listOf(family, kids), libraries = libraries), onRetry = {}, editing = kids,
                onSave = { _, draft -> saved = draft },
            )
        }
        compose.onNodeWithTag("organization-group-name").performTextClearance()
        compose.onNodeWithTag("organization-group-name").performTextInput("Little ones")
        scrollToTag("organization-group-chosen-libraries")
        compose.onNodeWithTag("organization-group-chosen-libraries").performClick()
        scrollToTag("organization-group-library-1")
        compose.onNodeWithTag("organization-group-library-1").performClick()
        scrollToTag("organization-group-quality-2160p")
        compose.onNodeWithTag("organization-group-quality-2160p").performClick()
        scrollToTag("organization-group-max-streams")
        compose.onNodeWithTag("organization-group-max-streams").performTextClearance()
        compose.onNodeWithTag("organization-group-max-streams").performTextInput("3")
        scrollToTag("organization-group-save")
        compose.onNodeWithTag("organization-group-save").performClick()
        val draft = checkNotNull(saved)
        assertEquals("Little ones", draft.name)
        assertEquals(3L, draft.maxStreams)
        assertEquals(setOf("1"), draft.libraryIds)
        assertEquals("2160p", draft.maxPlaybackQuality)
    }

    @Test
    fun aPausedSharedLibraryIsMarkedAndAGroupCanStillBeAllLibraries() {
        show {
            AccessGroupsContent(
                AccessGroupsState(loading = false, groups = listOf(kids), libraries = libraries), onRetry = {},
                editing = kids.copy(library_ids = listOf("1", "2")),
            )
        }
        scrollToTag("organization-group-library-2")
        compose.onNodeWithText("Shared shows (paused or withdrawn)").assertIsDisplayed()
        scrollToTag("organization-group-all-libraries")
        compose.onNodeWithTag("organization-group-all-libraries").assertIsDisplayed()
    }

    @Test
    fun deletingAGroupExplainsWhereItsProfilesGoAndAsksFirst() {
        var deleted: String? = null
        show {
            AccessGroupsContent(
                AccessGroupsState(loading = false, groups = listOf(family, kids)), onRetry = {}, editing = kids, onDelete = { deleted = it.id },
            )
        }
        scrollToTag("organization-group-delete")
        compose.onNodeWithTag("organization-group-delete").assertIsEnabled().performClick()
        compose.onNodeWithText("Delete “Kids”?").assertIsDisplayed()
        compose.onNodeWithText("4 profiles in this organization will be reassigned to Family, this organization's default group. Their own restrictions are unchanged.").assertIsDisplayed()
        assertNull(deleted)
        compose.onNodeWithTag("organization-group-delete-confirm").performClick()
        assertEquals("4", deleted)
    }

    @Test
    fun theDefaultGroupCannotBeDeleted() {
        show { AccessGroupsContent(AccessGroupsState(loading = false, groups = listOf(family, kids)), onRetry = {}, editing = family) }
        scrollToTag("organization-group-delete")
        compose.onNodeWithTag("organization-group-delete").assertIsNotEnabled()
        compose.onNodeWithText("The default group can't be deleted. Make another group the default first.").assertIsDisplayed()
    }

    @Test
    fun convertedDownloadsFollowDownloadsInTheEditor() {
        var saved: AccessGroupDraft? = null
        val noDownloads = kids.copy(download_allowed = false, download_transcode_allowed = true)
        show {
            AccessGroupsContent(
                AccessGroupsState(loading = false, groups = listOf(noDownloads)), onRetry = {}, editing = noDownloads, onSave = { _, draft -> saved = draft },
            )
        }
        scrollToDescription("Download converted copies")
        compose.onNodeWithContentDescription("Download converted copies").assertIsNotEnabled()
        scrollToTag("organization-group-save")
        compose.onNodeWithTag("organization-group-save").performClick()
        assertTrue("saving never writes converted downloads without downloads", !checkNotNull(saved).toBody(1).download_transcode_allowed)
    }

    @Test
    fun anErrorFromTheServerIsShownOverTheEditor() {
        show {
            AccessGroupsContent(
                AccessGroupsState(loading = false, groups = listOf(kids), error = ErrorText.Raw("This group is managed by entitlement policy and can only be changed there")),
                onRetry = {}, editing = kids,
            )
        }
        compose.onNodeWithText("This group is managed by entitlement policy and can only be changed there").assertIsDisplayed()
    }

    // ---- Libraries: ceiling and giving back ----------------------------------------------------------------------------

    @Test
    fun theCeilingCountsOnlyWhatMembersCanUseNow() {
        val active = OrganizationLibrary(3, "Active share", "movie", "entitled", LibraryEntitlement("e3", "o", "active", 1))
        val withdrawn = OrganizationLibrary(4, "Old share", "movie", "entitled", LibraryEntitlement("e4", "o", "revoked", 1))
        show { LibrariesContent(LibrariesState(loading = false, libraries = libraries + active + withdrawn), onRetry = {}, onSetStatus = { _, _ -> }) }
        compose.onNodeWithText("Most your members can reach").assertIsDisplayed()
        compose.onNodeWithText("2 libraries members can use now").assertIsDisplayed()
        compose.onNodeWithText("1 owned · 1 shared and active · 1 paused · 1 withdrawn").assertIsDisplayed()
        compose.onNodeWithText("Withdrawn").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun givingALibraryBackAsksFirstAndIsOnlyForSharedOnes() {
        var givenBack: Long? = null
        val shared = OrganizationLibrary(3, "Active share", "movie", "entitled", LibraryEntitlement("e3", "o", "active", 1))
        val withdrawn = OrganizationLibrary(4, "Old share", "movie", "entitled", LibraryEntitlement("e4", "o", "revoked", 1))
        show {
            LibrariesContent(
                LibrariesState(loading = false, libraries = listOf(libraries[0], shared, withdrawn)), onRetry = {},
                onSetStatus = { _, _ -> }, onGiveBack = { givenBack = it.folder_id },
            )
        }
        compose.onNodeWithContentDescription("Give back for Films").assertDoesNotExist()
        compose.onNodeWithContentDescription("Give back for Old share").assertDoesNotExist()
        compose.onNodeWithContentDescription("Give back for Active share").performScrollTo().performClick()
        compose.onNodeWithText("Give back Active share?").assertIsDisplayed()
        compose.onNodeWithText("Members lose this library for good. Only a platform administrator can share it again. No files are deleted.").assertIsDisplayed()
        assertNull(givenBack)
        compose.onNodeWithTag("organization-library-give-back-confirm").performClick()
        assertEquals(3L, givenBack)
    }

    @Test
    fun aPausedSharedLibraryOffersRestoreAndGiveBack() {
        show { LibrariesContent(LibrariesState(loading = false, libraries = listOf(libraries[1])), onRetry = {}, onSetStatus = { _, _ -> }) }
        compose.onNodeWithContentDescription("Restore for Shared shows").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Give back for Shared shows").performScrollTo().assertIsDisplayed()
    }

    // ---- Cohorts and decisions at the web's detail ---------------------------------------------------------------------

    private val cohort = EntitlementCohort(
        cohort_id = "c1", organization_id = "o", name = "Family plan", revision = 2, access_group_id = 3, source_template_key = "family",
        source_template_revision = 1, parent_cohort_id = "c0", derivation_kind = "exact_template",
        policy = PolicyView(library_ids = listOf(1L, 9L), playback_allowed = true, max_streams = 2, max_profiles = 5, transcode_allowed = true,
            max_transcodes = 0, download_allowed = true, max_playback_quality = "2160p", allowed_permissions = listOf(GroupPermissions.LIVE_TV), requests_allowed = true),
        policy_digest = "abc123", member_count = 6, created_by_account_id = 4, created_at = "2026-10-01T00:00:00Z",
    )

    @Test
    fun aCohortShowsItsSettingsAsTheWebDoes() {
        show { CohortsContent(CohortsState(loading = false, cohorts = listOf(cohort), libraries = libraries), onRetry = {}) }
        compose.onNodeWithText("From plan family, revision 1").assertIsDisplayed()
        compose.onNodeWithText("Films, Library 9").assertIsDisplayed()
        compose.onNodeWithText("2 screens").assertIsDisplayed()
        compose.onNodeWithText("No limit").assertIsDisplayed()
        compose.onNodeWithText("Up to 4K").assertIsDisplayed()
        compose.onNodeWithText("Watch live TV").assertIsDisplayed()
    }

    @Test
    fun aCohortsDetailsShowHowItWasMadeAndByWhom() {
        show { CohortsContent(CohortsState(loading = false, cohorts = listOf(cohort), libraries = libraries), onRetry = {}) }
        compose.onNodeWithText("Cohort ID").assertDoesNotExist()
        compose.onNodeWithTag("organization-cohort-details-c1").performScrollTo().performClick()
        // On a phone-height screen the details run below the fold; scroll each into view.
        listOf("From a plan", "Started from cohort", "Account 4", "abc123", "c1").forEach {
            compose.onNodeWithText(it).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun archivedCohortsSitUnderTheirOwnHeading() {
        val archived = cohort.copy(cohort_id = "c2", name = "Old plan", archived = true)
        show { CohortsContent(CohortsState(loading = false, cohorts = listOf(cohort, archived)), onRetry = {}) }
        compose.onNodeWithText("Old plan").assertDoesNotExist()
        // The page is a lazy list: scroll the list itself, so items below the fold on a small screen are laid out.
        compose.onNodeWithTag("organization-page").performScrollToNode(hasTestTag("organization-cohorts-archived-toggle"))
        compose.onNodeWithTag("organization-cohorts-archived-toggle").performClick()
        val note = "Kept for the record. Nobody can be moved to an archived cohort."
        compose.onNodeWithTag("organization-page").performScrollToNode(hasText(note))
        compose.onNodeWithText(note).assertIsDisplayed()
        compose.onNodeWithTag("organization-page").performScrollToNode(hasText("Old plan"))
        compose.onNodeWithText("Old plan").assertIsDisplayed()
    }

    private val refused = PolicyDecisionDetail(
        id = 4, timestamp = "2026-11-12T09:30:00Z", organization = PolicyDecisionOrganizationRef("o", "m1"),
        subject = PolicyDecisionSubject(5, "p1"), group = PolicyDecisionGroupRef(3, "Family"), library_ceiling = listOf(1L),
        action = "library_access_check", resource = JsonObject(mapOf("library_id" to JsonPrimitive(7), "token" to JsonPrimitive("[redacted]"))),
        allowed = false, reason_code = "item_outside_user_libraries",
        policy_versions = listOf(PolicyVersionRef("vendor", null, 4), PolicyVersionRef("custom", "Kids rule", 2)),
    )

    @Test
    fun aDecisionListOffersSeeWhyAndOpensTheCheck() {
        var opened: Long? = null
        val decision = PolicyDecision(4, "2026-11-12T09:30:00Z", PolicyDecisionSubject(5, "p1"), PolicyDecisionGroupRef(3, "Family"), "download", false, "download_permission_required")
        show { DecisionsContent(DecisionsState(loading = false, decisions = listOf(decision)), onRetry = {}, onMore = {}, onOpen = { opened = it }) }
        compose.onNodeWithText("Download: refused").assertIsDisplayed()
        compose.onNodeWithText("Their settings don't allow downloads", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Download: refused: see why").performClick()
        assertEquals(4L, opened)
    }

    @Test
    fun aDecisionsDetailExplainsTheRefusalAndHidesSensitiveValues() {
        var closed = 0
        show {
            DecisionsContent(
                DecisionsState(loading = false, decisions = emptyList(), selectedId = 4, detail = refused, libraries = libraries), onRetry = {}, onMore = {},
                onClose = { closed++ },
            )
        }
        compose.onNodeWithText("Library access check: refused").assertIsDisplayed()
        compose.onNodeWithText("The title is in a library they can't use").assertIsDisplayed()
        compose.onNodeWithText("Account 5 · Profile p1").assertIsDisplayed()
        compose.onNodeWithText("Family").assertIsDisplayed()
        compose.onNodeWithText("Films").assertIsDisplayed()
        compose.onNodeWithText("Library id").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Sensitive value hidden").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("[redacted]").assertDoesNotExist()
        compose.onNodeWithTag("organization-decision-technical").performScrollTo()
        compose.onNodeWithText("Details").performClick()
        compose.onNodeWithText("vendor version 4, custom Kids rule version 2").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("item_outside_user_libraries").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("organization-decision-back").performClick()
        assertEquals(1, closed)
    }
}
