package com.bloemstudios.admin.organization

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bloemstudios.admin.network.ErrorText
import com.bloemstudios.admin.ui.theme.LocalReducedMotionOverride
import com.bloemstudios.admin.ui.theme.VeilAdminTheme
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The organization support screens on a device, from fixture state: helping a person (reset, sign-ins, devices, profiles), the
 * organization's Now Playing, the Owner's support access and its banner. CI's screen is 320 x 640 dp, so every target must be
 * reachable and at least 48 dp tall there. No network, no credentials.
 */
@RunWith(AndroidJUnit4::class)
class OrganizationSupportScreensTest {
    @get:Rule val compose = createComposeRule()

    private val everything = SupportCapabilities(
        password_reset = SupportResetCapabilities(email = true, link = true), sessions = true, devices = true,
        profiles = SupportProfileCapabilities(add = true, rename = true, clear_pin = true, remove = true),
        streams = SupportStreamCapabilities(list = true, stop = true, message = true),
    )
    private val family = AccessGroup(id = "3", name = "Family", is_default = true)
    private val kids = AccessGroup(id = "4", name = "Kids")
    private val main = ProfileSummary("p0", "Sam", 3, "Family", "2026-11-12T08:00:00Z", revision = 2, has_pin = false, is_primary = true)
    private val living = ProfileSummary("p1", "Living room", 3, "Family", "2026-11-12T08:00:00Z", revision = 3, has_pin = true)
    private val sam = PersonSummary(
        organization_id = "o", account_id = 5, email = "sam@example.test", display_name = "Sam", membership_id = "m5",
        membership_status = "active", security_revision = 4, last_activity = "2026-11-12T09:30:00Z", profiles = listOf(main, living),
    )
    private val context = AdminContext("organization:o", "Acme Media", AdminContextScope.Organization, "o", "active",
        AdminAuthority.OrganizationAdmin, membershipId = "mine")
    private val tvSession = SupportSession("s1", "Sam's TV", "d1", "tvos", "password", "p1", "Living room", "2026-11-10T08:00:00Z", "2026-11-13T20:00:00Z", "2026-12-10T08:00:00Z")
    private val phoneSession = SupportSession("s2", "Phone", "d2", "android", "password", null, null, "2026-11-11T08:00:00Z", null, "2026-12-11T08:00:00Z")
    private val tv = SupportDevice("d1", "Sam's TV", "tvos", "2026-11-13T20:00:00Z", listOf(SupportDeviceProfile("p1", "Living room")))

    private fun state(
        caps: SupportCapabilities = everything,
        person: PersonSummary = sam,
        sessions: SupportList<SupportSession> = SupportList(true, listOf(tvSession, phoneSession)),
        devices: SupportList<SupportDevice> = SupportList(true, listOf(tv)),
        protectedMessage: ErrorText? = null,
        resetLink: ResetLink? = null,
        pending: Boolean = false,
    ) = PersonDetailState(
        loading = false, person = person, groups = listOf(family, kids), capabilities = caps, sessions = sessions, devices = devices,
        protectedMessage = protectedMessage, resetLink = resetLink, pending = pending,
    )

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) = compose.setContent {
        CompositionLocalProvider(LocalReducedMotionOverride provides true) { VeilAdminTheme { content() } }
    }

    private fun person(state: PersonDetailState, actions: PersonSupportActions = PersonSupportActions()) = show {
        PersonDetailContent(state, context, {}, {}, {}, {}, { _, _ -> }, actions)
    }

    private fun inDialog(text: String) = compose.onNode(hasText(text) and hasAnyAncestor(isDialog()))
    private fun inDialogContaining(text: String) = compose.onNode(hasText(text, substring = true) and hasAnyAncestor(isDialog()))
    private fun inCard(tag: String, text: String) = compose.onNode(hasText(text, substring = true) and hasAnyAncestor(hasTestTag(tag)))
    private fun inDialogTag(tag: String) = compose.onNode(hasTestTag(tag) and hasAnyAncestor(isDialog()))
    private fun scrollToTag(tag: String) = compose.onNodeWithTag("organization-page").performScrollToNode(hasTestTag(tag))
    private fun scrollToDescription(description: String) =
        compose.onNodeWithTag("organization-page").performScrollToNode(hasContentDescription(description))

    // ---- A person: gating ---------------------------------------------------------------------------------

    @Test
    fun aServerWithoutSupportShowsNothingNew() {
        person(state(caps = SupportCapabilities.NONE, sessions = SupportList(), devices = SupportList()))
        compose.onNodeWithTag("organization-person-detail").assertIsDisplayed()
        compose.onAllNodesWithText("Reset password").assertCountEquals(0)
        compose.onNodeWithTag("organization-support-sessions").assertDoesNotExist()
        compose.onNodeWithTag("organization-support-devices").assertDoesNotExist()
        compose.onAllNodesWithText("Add profile").assertCountEquals(0)
        compose.onAllNodesWithText("Rename").assertCountEquals(0)
        // The page still says what it cannot do, in the words that fit a server without the routes.
        scrollToTag("organization-person-limits")
        compose.onNodeWithText("Resetting a password", substring = true).assertExists()
    }

    @Test
    fun onlyWhatTheServerOffersIsDrawn() {
        person(state(caps = everything.copy(
            password_reset = SupportResetCapabilities(email = false, link = true), devices = false,
            profiles = SupportProfileCapabilities(add = false, rename = true, clear_pin = false, remove = false),
        )))
        scrollToTag("organization-support-reset-card")
        compose.onNodeWithTag("organization-support-reset-card").assertExists()
        compose.onNodeWithTag("organization-support-sessions").assertExists()
        compose.onNodeWithTag("organization-support-devices").assertDoesNotExist()
        compose.onAllNodesWithText("Add profile").assertCountEquals(0)
        compose.onAllNodesWithText("Clear PIN").assertCountEquals(0)
        compose.onAllNodesWithText("Remove").assertCountEquals(0)
        compose.onAllNodesWithText("Rename").assertCountEquals(2)
    }

    @Test
    fun aSuspendedPersonOrYourselfIsOfferedNoSupport() {
        person(state(person = sam.copy(membership_status = "suspended")))
        compose.onNodeWithTag("organization-support-reset-card").assertDoesNotExist()
        compose.onNodeWithTag("organization-support-sessions").assertDoesNotExist()
    }

    @Test
    fun aProtectedPersonIsExplainedAndOfferedNothing() {
        person(state(protectedMessage = ErrorText.Raw("This person owns the organization. Only the platform operator can help them."),
            sessions = SupportList(true, unavailable = true), devices = SupportList(true, unavailable = true)))
        scrollToTag("organization-support-protected")
        compose.onNodeWithText("You can't help this person").assertExists()
        compose.onNodeWithText("This person owns the organization. Only the platform operator can help them.").assertExists()
        compose.onNodeWithTag("organization-support-reset-card").assertDoesNotExist()
        compose.onAllNodesWithText("Add profile").assertCountEquals(0)
        compose.onAllNodesWithText("Rename").assertCountEquals(0)
    }

    // ---- Reset password -------------------------------------------------------------------------------------

    @Test
    fun resettingOffersEmailAndALinkAndSendsNothingUntilConfirmed() {
        val chosen = mutableListOf<String>()
        person(state(), PersonSupportActions(onReset = { chosen += it }))
        scrollToTag("organization-support-reset-card")
        compose.onNodeWithContentDescription("Reset password for Sam").assertHeightIsAtLeast(48.dp).performClick()
        inDialog("Reset the password for Sam?").assertExists()
        inDialogTag("organization-support-delivery-email").assertExists()
        inDialogTag("organization-support-delivery-link").assertExists()
        assertTrue("nothing sent yet", chosen.isEmpty())
        // Email is the default, then switching to a link.
        inDialogTag("organization-support-delivery-link").assertHeightIsAtLeast(48.dp).performClick()
        inDialogTag("organization-support-reset-confirm").performClick()
        assertEquals(listOf(ResetDelivery.LINK), chosen)
        compose.onAllNodesWithText("Reset the password for Sam?").assertCountEquals(0)
    }

    @Test
    fun emailIsDefaultAndCanBeSent() {
        val chosen = mutableListOf<String>()
        person(state(), PersonSupportActions(onReset = { chosen += it }))
        scrollToTag("organization-support-reset-card")
        compose.onNodeWithContentDescription("Reset password for Sam").performClick()
        inDialog("Send email").assertExists()
        inDialogTag("organization-support-reset-confirm").performClick()
        assertEquals(listOf(ResetDelivery.EMAIL), chosen)
    }

    @Test
    fun withoutEmailOnlyTheLinkIsOffered() {
        val chosen = mutableListOf<String>()
        person(state(caps = everything.copy(password_reset = SupportResetCapabilities(email = false, link = true))), PersonSupportActions(onReset = { chosen += it }))
        scrollToTag("organization-support-reset-card")
        compose.onNodeWithContentDescription("Reset password for Sam").performClick()
        inDialogTag("organization-support-delivery-email").assertDoesNotExist()
        inDialogTag("organization-support-delivery-link").assertExists()
        inDialog("Create link").assertExists()
        inDialogTag("organization-support-reset-confirm").performClick()
        assertEquals(listOf(ResetDelivery.LINK), chosen)
    }

    @Test
    fun cancellingTheResetSendsNothing() {
        val chosen = mutableListOf<String>()
        person(state(), PersonSupportActions(onReset = { chosen += it }))
        scrollToTag("organization-support-reset-card")
        compose.onNodeWithContentDescription("Reset password for Sam").performClick()
        inDialog("Cancel").performClick()
        assertTrue(chosen.isEmpty())
    }

    @Test
    fun theOneTimeLinkIsShownWithCopyAndShareAndGoneOnceClosed() {
        val link = mutableStateOf<ResetLink?>(ResetLink("Sam", "https://example.test/reset-password/one-time-secret", "2026-11-14T10:00:00Z"))
        var dismissed = 0
        show {
            PersonDetailContent(
                state(resetLink = link.value), context, {}, {}, {}, {}, { _, _ -> },
                PersonSupportActions(onDismissLink = { dismissed++; link.value = null }),
            )
        }
        inDialog("One-time reset link").assertExists()
        inDialogTag("organization-support-reset-link").assertExists()
        inDialog("https://example.test/reset-password/one-time-secret").assertExists()
        inDialogTag("organization-support-link-copy").assertHeightIsAtLeast(48.dp).assertIsEnabled()
        inDialogTag("organization-support-link-share").assertHeightIsAtLeast(48.dp).assertIsEnabled()
        inDialogTag("organization-support-link-copy").performClick()
        inDialog("Copied").assertExists()
        inDialogTag("organization-support-link-done").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, dismissed)
        compose.onAllNodesWithText("One-time reset link").assertCountEquals(0)
        compose.onAllNodesWithText("https://example.test/reset-password/one-time-secret", substring = true).assertCountEquals(0)
    }

    // ---- Sign-ins and devices ---------------------------------------------------------------------------------

    @Test
    fun signInsAreListedWithoutAnyAddress() {
        person(state())
        scrollToTag("organization-support-sessions")
        compose.onNodeWithText("Signed in").assertExists()
        inCard("organization-support-sessions", "Sam's TV · tvos").assertExists()
        inCard("organization-support-sessions", "Profile: Living room").assertExists()
        inCard("organization-support-sessions", "Phone · android").assertExists()
        compose.onAllNodesWithText("IP", substring = true).assertCountEquals(0)
    }

    @Test
    fun signingOutOneSessionAsksFirst() {
        val out = mutableListOf<String>()
        person(state(), PersonSupportActions(onSignOutSession = { out += it.id }))
        scrollToDescription("Sign out Sam on Sam's TV")
        compose.onNodeWithContentDescription("Sign out Sam on Sam's TV").assertHeightIsAtLeast(48.dp).performClick()
        inDialog("Sign Sam out of this session?").assertExists()
        assertTrue(out.isEmpty())
        inDialogTag("organization-support-confirm").performClick()
        assertEquals(listOf("s1"), out)
    }

    @Test
    fun signingOutEverywhereAsksFirstAndCanBeCancelled() {
        var everywhere = 0
        person(state(), PersonSupportActions(onSignOutEverywhere = { everywhere++ }))
        scrollToDescription("Sign Sam out everywhere")
        compose.onNodeWithContentDescription("Sign Sam out everywhere").assertHeightIsAtLeast(48.dp).performClick()
        inDialog("Sign Sam out everywhere?").assertExists()
        inDialog("Cancel").performClick()
        assertEquals(0, everywhere)
        compose.onNodeWithContentDescription("Sign Sam out everywhere").performClick()
        inDialogTag("organization-support-confirm").performClick()
        assertEquals(1, everywhere)
    }

    @Test
    fun signingOutADeviceAsksFirst() {
        val out = mutableListOf<String>()
        person(state(), PersonSupportActions(onSignOutDevice = { out += it.device_id }))
        scrollToDescription("Sign out Sam's TV")
        compose.onNodeWithContentDescription("Sign out Sam's TV").assertHeightIsAtLeast(48.dp).performClick()
        inDialog("Sign Sam out of Sam's TV?").assertExists()
        inDialogTag("organization-support-confirm").performClick()
        assertEquals(listOf("d1"), out)
    }

    @Test
    fun anEmptyOrUnavailableListSaysSo() {
        person(state(sessions = SupportList(true), devices = SupportList(true, unavailable = true)))
        scrollToTag("organization-support-sessions")
        compose.onNodeWithText("Not signed in anywhere").assertExists()
        compose.onAllNodesWithText("Sign out everywhere").assertCountEquals(0)
        scrollToTag("organization-support-devices")
        compose.onNodeWithText("The devices couldn't be loaded").assertExists()
    }

    @Test
    fun actionsAreDisabledWhileOneIsRunning() {
        person(state(pending = true))
        scrollToTag("organization-support-reset-card")
        compose.onNodeWithContentDescription("Reset password for Sam").assertIsNotEnabled()
    }

    // ---- Profiles --------------------------------------------------------------------------------------------

    @Test
    fun theMainProfileCanBeRenamedButNotRemovedAndOnlyAPinnedOneClearsItsPin() {
        person(state())
        scrollToTag("organization-person-profiles")
        compose.onNodeWithContentDescription("Rename Sam").assertExists()
        compose.onNodeWithContentDescription("Remove Sam").assertDoesNotExist()
        compose.onNodeWithContentDescription("Clear the PIN on Sam").assertDoesNotExist()
        compose.onNodeWithText("Main profile · No PIN").assertExists()
        compose.onNodeWithContentDescription("Rename Living room").assertExists()
        compose.onNodeWithContentDescription("Clear the PIN on Living room").assertExists()
        compose.onNodeWithContentDescription("Remove Living room").assertExists()
        compose.onNodeWithText("PIN set").assertExists()
    }

    @Test
    fun renamingStartsFromTheCurrentNameAndSavesTheNewOne() {
        val renamed = mutableListOf<Pair<String, String>>()
        person(state(), PersonSupportActions(onRenameProfile = { profile, name -> renamed += profile.id to name }))
        scrollToDescription("Rename Living room")
        compose.onNodeWithContentDescription("Rename Living room").assertHeightIsAtLeast(48.dp).performClick()
        inDialog("Rename Living room").assertExists()
        inDialogTag("organization-support-profile-name").assertExists()
        inDialog("Living room").assertExists()
        inDialogTag("organization-support-profile-name").performTextClearance()
        inDialogTag("organization-support-profile-name").performTextInput("Bedroom")
        inDialogTag("organization-support-profile-confirm").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf("p1" to "Bedroom"), renamed)
    }

    @Test
    fun clearingAPinAsksFirstAndSaysThePersonChoosesTheNewOne() {
        val cleared = mutableListOf<String>()
        person(state(), PersonSupportActions(onClearPin = { cleared += it.id }))
        scrollToDescription("Clear the PIN on Living room")
        compose.onNodeWithContentDescription("Clear the PIN on Living room").performClick()
        inDialog("Clear the PIN on Living room?").assertExists()
        inDialogContaining("Anyone can open this profile until the member chooses a new PIN.").assertExists()
        assertTrue(cleared.isEmpty())
        inDialogTag("organization-support-confirm").performClick()
        assertEquals(listOf("p1"), cleared)
    }

    @Test
    fun removingAProfileAsksFirstAndWarns() {
        val removed = mutableListOf<String>()
        person(state(), PersonSupportActions(onRemoveProfile = { removed += it.id }))
        scrollToDescription("Remove Living room")
        compose.onNodeWithContentDescription("Remove Living room").performClick()
        inDialog("Remove Living room?").assertExists()
        inDialogContaining("This can't be undone.").assertExists()
        inDialog("Cancel").performClick()
        assertTrue(removed.isEmpty())
        compose.onNodeWithContentDescription("Remove Living room").performClick()
        inDialogTag("organization-support-confirm").performClick()
        assertEquals(listOf("p1"), removed)
    }

    @Test
    fun addingAProfileNeedsANameAndOffersTheGroups() {
        val added = mutableListOf<Pair<String, String?>>()
        person(state(), PersonSupportActions(onAddProfile = { name, group -> added += name to group?.id }))
        scrollToDescription("Add a profile for Sam")
        compose.onNodeWithContentDescription("Add a profile for Sam").assertHeightIsAtLeast(48.dp).performClick()
        inDialog("Add a profile for Sam").assertExists()
        inDialogTag("organization-support-profile-confirm").assertIsNotEnabled()
        inDialogTag("organization-support-profile-name").performTextInput("Kids")
        inDialogTag("organization-support-profile-confirm").assertIsEnabled()
        inDialogTag("organization-support-group-4").assertHeightIsAtLeast(48.dp).performClick()
        inDialogTag("organization-support-profile-confirm").performClick()
        assertEquals(listOf("Kids" to "4"), added)
    }

    // ---- Now Playing -------------------------------------------------------------------------------------------

    private fun stream(
        id: String = "st1", person: String = "Sam", canStop: Boolean = true, canMessage: Boolean = true, method: String = "transcode",
    ) = SupportStream(
        session_id = id, account_id = 5, person_name = person, profile_id = "p1", profile_name = "Living room", title = "Pilot",
        series_name = "Show", season_number = 1, episode_number = 2, media_type = "episode", started_at = "2026-11-14T08:00:00Z",
        updated_at = "2026-11-14T08:30:00Z", position_seconds = 1800.0, duration_seconds = 3600, is_paused = false, play_method = method,
        video_decision = "transcode", audio_decision = "direct", device = SupportStreamDevice("Sam's TV", "tvos", "d1", "Bloem tvOS"),
        can_stop = canStop, can_message = canMessage,
    )

    private fun streams(
        state: OrganizationStreamsState,
        caps: SupportCapabilities = everything,
        onStop: (SupportStream) -> Unit = {},
        onMessage: suspend (SupportStream, String?, String) -> StreamMessageResult = { _, _, _ -> StreamMessageResult.Sent(false) },
    ) = show { OrganizationStreamsContent(state, caps, {}, {}, onStop, onMessage) }

    @Test
    fun theOrganizationStreamRowShowsWhoWhatAndHowWithoutAddressOrRouting() {
        streams(OrganizationStreamsState(loading = false, streams = listOf(stream())))
        compose.onNodeWithTag("stream-row:st1").assertExists()
        compose.onNodeWithText("Show · S1E2 · Pilot").assertExists()
        compose.onNodeWithText("Transcode").assertExists()
        compose.onNodeWithTag("stream-row:st1").performClick()
        compose.onNodeWithTag("stream-details:st1", useUnmergedTree = true).assertExists()
        compose.onAllNodesWithText("Client IP").assertCountEquals(0)
        compose.onAllNodesWithText("Playing node").assertCountEquals(0)
        compose.onAllNodesWithText("Network").assertCountEquals(0)
        compose.onNodeWithText("Bloem tvOS · Sam's TV", useUnmergedTree = true).assertExists()
    }

    @Test
    fun anEmptyOrUnavailableOrganizationSaysSo() {
        streams(OrganizationStreamsState(loading = false))
        compose.onNodeWithText("Nobody in this organization is watching right now").assertExists()
    }

    @Test
    fun aServerThatCannotListStreamsSaysSo() {
        streams(OrganizationStreamsState(loading = false, unavailable = true), SupportCapabilities.NONE)
        compose.onNodeWithText("This server can't show your organization's streams").assertExists()
    }

    @Test
    fun stoppingAStreamAsksFirst() {
        val stopped = mutableListOf<String>()
        streams(OrganizationStreamsState(loading = false, streams = listOf(stream())), onStop = { stopped += it.session_id })
        compose.onNodeWithTag(ORGANIZATION_STREAM_MENU_TAG_PREFIX + "st1").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("organization-stream-stop").performClick()
        inDialog("Stop Sam’s playback?").assertExists()
        assertTrue(stopped.isEmpty())
        inDialogTag("organization-stream-stop-confirm").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf("st1"), stopped)
    }

    @Test
    fun aStreamTheServerWillNotLetYouTouchHasNoMenu() {
        streams(OrganizationStreamsState(loading = false, streams = listOf(stream(canStop = false, canMessage = false))))
        compose.onNodeWithTag("stream-row:st1").assertExists()
        compose.onNodeWithTag(ORGANIZATION_STREAM_MENU_TAG_PREFIX + "st1").assertDoesNotExist()
    }

    @Test
    fun theMenuOffersOnlyWhatTheServerCanDo() {
        streams(OrganizationStreamsState(loading = false, streams = listOf(stream())), everything.copy(streams = SupportStreamCapabilities(list = true, stop = false, message = true)))
        compose.onNodeWithTag(ORGANIZATION_STREAM_MENU_TAG_PREFIX + "st1").performClick()
        compose.onNodeWithTag("organization-stream-message").assertExists()
        compose.onNodeWithTag("organization-stream-stop").assertDoesNotExist()
    }

    @Test
    fun aMessageNeedsTextAndShowsTheResult() {
        val sent = mutableListOf<Triple<String, String?, String>>()
        streams(
            OrganizationStreamsState(loading = false, streams = listOf(stream())),
            onMessage = { s, title, text -> sent += Triple(s.session_id, title, text); StreamMessageResult.Sent(queued = false) },
        )
        compose.onNodeWithTag(ORGANIZATION_STREAM_MENU_TAG_PREFIX + "st1").performClick()
        compose.onNodeWithTag("organization-stream-message").performClick()
        inDialog("Message Sam").assertExists()
        inDialogTag("organization-stream-message-send").assertIsNotEnabled()
        inDialogTag("organization-stream-message-title").performTextInput("Hello")
        inDialogTag("organization-stream-message-body").performTextInput("Please turn it down")
        inDialogTag("organization-stream-message-send").assertHeightIsAtLeast(48.dp).assertIsEnabled().performClick()
        compose.waitUntil(5_000) { sent.isNotEmpty() }
        assertEquals(listOf(Triple("st1", "Hello" as String?, "Please turn it down")), sent)
        inDialog("Message sent").assertExists()
        inDialogTag("organization-stream-message-close").performClick()
        compose.onAllNodesWithText("Message Sam").assertCountEquals(0)
    }

    @Test
    fun aPlayerThatIsNotConnectedIsSaidPlainly() {
        streams(
            OrganizationStreamsState(loading = false, streams = listOf(stream())),
            onMessage = { _, _, _ -> StreamMessageResult.ClientNotConnected },
        )
        compose.onNodeWithTag(ORGANIZATION_STREAM_MENU_TAG_PREFIX + "st1").performClick()
        compose.onNodeWithTag("organization-stream-message").performClick()
        inDialogTag("organization-stream-message-body").performTextInput("Hi")
        inDialogTag("organization-stream-message-send").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("This player isn't connected right now, so it can't show a message").fetchSemanticsNodes().isNotEmpty() }
        inDialog("This player isn't connected right now, so it can't show a message").assertExists()
    }

    // ---- The Owner's support access ---------------------------------------------------------------------------

    private fun organization(id: String, name: String, status: String = "active") =
        OrganizationSummary(id = id, name = name, status = status, active_membership_count = 10, membership_count = 12)

    private fun picker(
        state: SupportOrganizationsState,
        onOpen: (OrganizationSummary, String) -> Unit = { _, _ -> },
        onSearch: (String) -> Unit = {},
    ) = show { SupportAccessContent(state, onSearch, {}, {}, {}, onOpen) }

    @Test
    fun theOwnerChoosesAnOrganizationAndMustStateAReason() {
        val opened = mutableListOf<Pair<String, String>>()
        picker(
            SupportOrganizationsState(loading = false, organizations = listOf(organization("o1", "Acme Media"), organization("o2", "Beta TV", "suspended"), organization("o3", "Mine"))
                , memberOrganizationIds = setOf("o3")),
            onOpen = { org, reason -> opened += org.id to reason },
        )
        // The top bar names the page; the page itself carries the terms of support access.
        compose.onNodeWithText("Open a customer", substring = true).assertExists()
        compose.onNodeWithText("Suspended: can't be opened", substring = true).assertExists()
        compose.onNodeWithText("You're a member. Open it from the organization menu in the top bar.").assertExists()
        compose.onNodeWithContentDescription("Open Beta TV for support").assertDoesNotExist()
        compose.onNodeWithContentDescription("Open Mine for support").assertDoesNotExist()

        compose.onNodeWithContentDescription("Open Acme Media for support").assertHeightIsAtLeast(48.dp).performClick()
        inDialog("Open Acme Media").assertExists()
        inDialogTag("organization-support-reason-open").assertIsNotEnabled()
        inDialogTag("organization-support-reason").performTextInput("too short")
        inDialogTag("organization-support-reason-open").assertIsNotEnabled()
        inDialog("Enter at least 10 characters").assertExists()
        inDialogTag("organization-support-reason").performTextInput(" and now long enough")
        inDialogTag("organization-support-reason-open").assertIsEnabled().performClick()
        assertEquals(listOf("o1" to "too short and now long enough"), opened)
    }

    @Test
    fun cancellingTheReasonOpensNothing() {
        val opened = mutableListOf<String>()
        picker(SupportOrganizationsState(loading = false, organizations = listOf(organization("o1", "Acme Media"))), onOpen = { o, _ -> opened += o.id })
        compose.onNodeWithContentDescription("Open Acme Media for support").performClick()
        inDialog("Cancel").performClick()
        assertTrue(opened.isEmpty())
    }

    // ---- The banner ---------------------------------------------------------------------------------------------

    private fun banner(
        endsAt: Instant?, now: () -> Instant, ending: Boolean = false, error: ErrorText? = null, onEnd: () -> Unit = {},
    ) = show {
        CompositionLocalProvider(LocalSupportClock provides now) { SupportAccessBanner(endsAt, ending, error, onEnd) }
    }

    @Test
    fun theBannerCountsTheTimeLeftAndEndsTheAccessOnlyAfterAsking() {
        var ended = 0
        banner(Instant.parse("2026-11-14T14:00:00Z"), { Instant.parse("2026-11-14T10:45:00Z") }, onEnd = { ended++ })
        compose.onNodeWithText("Platform support access").assertExists()
        compose.onNodeWithText("3 h 15 min left").assertExists()
        compose.onNodeWithTag("organization-support-end").assertHeightIsAtLeast(48.dp).performClick()
        inDialog("End support access?").assertExists()
        assertEquals(0, ended)
        inDialog("Cancel").performClick()
        assertEquals(0, ended)
        compose.onNodeWithTag("organization-support-end").performClick()
        inDialogTag("organization-support-end-confirm").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, ended)
    }

    @Test
    fun underAnHourTheBannerCountsMinutes() {
        banner(Instant.parse("2026-11-14T14:00:00Z"), { Instant.parse("2026-11-14T13:20:10Z") })
        compose.onNodeWithText("40 min left").assertExists()
    }

    @Test
    fun anEndedAccessIsSaidAndOffersNothingToEnd() {
        banner(Instant.parse("2026-11-14T14:00:00Z"), { Instant.parse("2026-11-14T14:00:00Z") })
        compose.onNodeWithText("Support access has ended").assertExists()
        compose.onNodeWithTag("organization-support-end").assertDoesNotExist()
    }

    @Test
    fun aFailedEndIsShownOnTheBanner() {
        banner(
            Instant.parse("2026-11-14T14:00:00Z"), { Instant.parse("2026-11-14T10:00:00Z") },
            error = ErrorText.Res(com.bloemstudios.admin.R.string.organization_support_end_error),
        )
        compose.onNodeWithTag("organization-support-end-error").assertExists()
        compose.onNodeWithTag("organization-support-end").assertIsEnabled()
    }

    @Test
    fun whileEndingTheButtonIsDisabled() {
        banner(Instant.parse("2026-11-14T14:00:00Z"), { Instant.parse("2026-11-14T10:00:00Z") }, ending = true)
        compose.onNodeWithTag("organization-support-end").assertIsNotEnabled()
    }

    @Test
    fun theBannerIsOneAnnouncedBlock() {
        banner(Instant.parse("2026-11-14T14:00:00Z"), { Instant.parse("2026-11-14T10:00:00Z") })
        compose.onNodeWithContentDescription("Platform support access to this organization. 4 h left", substring = true).assertExists()
    }
}
