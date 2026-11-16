package com.bloemstudios.admin.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.bloemstudios.admin.realtime.RealtimeState
import com.bloemstudios.admin.servers.AdminServer
import com.bloemstudios.admin.streams.*
import com.bloemstudios.admin.ui.theme.VeilAdminTheme
import org.junit.Rule
import org.junit.Test

/** Renderer state exercises actual row controls without credentials or network. */
class ActionPendingUiTest {
    @get:Rule val compose = createComposeRule()
    private val row=AdminSession("s1","user-1","Alex","profile-1","River","movie","direct",0.0,100.0,false,"Fixture")
    @Test fun pendingStreamDisablesItsOverflowAndFailureCanBeDismissed() {
        val error=mutableStateOf<String?>("Fixture action failed")
        compose.setContent { VeilAdminTheme {
            StreamsVeilContent(true,listOf(row,row.copy(session_id="s2",username="Sam")),error.value,{},
                StreamActions({},{},{},{},{_,_,_->SendMessageResult.Success}),RealtimeState.Live,
                pendingKeys=setOf("s1"),onDismissError={error.value=null})
        } }
        compose.onNodeWithContentDescription("Actions for Alex").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Actions for Sam").assertIsEnabled()
        compose.onNodeWithText("Action in progress").assertIsDisplayed()
        compose.onNodeWithText("Dismiss").performClick()
        compose.onNodeWithText("Fixture action failed").assertDoesNotExist()
    }
    @Test fun aggregatePendingSessionDoesNotDisableSameIdOnAnotherServer() {
        val actions=AggregateStreamActions({_,_->},{_,_->},{_,_->},{_,_->},{_,_,_,_->SendMessageResult.Success},{},{})
        compose.setContent { VeilAdminTheme { Column {
            LiveServerStreamsSection(AdminServer("a","https://fixture.invalid","A"),listOf(row),RealtimeState.Live,null,false,actions,setOf("a:s1"))
            LiveServerStreamsSection(AdminServer("b","https://fixture.invalid","B"),listOf(row.copy(username="Sam")),RealtimeState.Live,null,false,actions,setOf("a:s1"))
        } } }
        compose.onNodeWithContentDescription("Actions for Alex").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Actions for Sam").assertIsEnabled().performClick()
        compose.onNodeWithText("Pause").assertIsEnabled()
    }
}
