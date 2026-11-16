package com.bloemstudios.admin

import com.bloemstudios.admin.network.*
import com.bloemstudios.admin.streams.*
import com.bloemstudios.admin.users.*
import com.bloemstudios.admin.serverconfig.*
import com.bloemstudios.admin.servers.AdminServer
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ActionBoundaryTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val user = AdminUser("1", "fixture", "fixture@example.test", "user", true)
    private val session = AdminSession("s1", "user-1", "fixture", "profile-1", "Title", "movie", "direct", 0.0, 100.0, false, "Fixture")
    private val a = AdminServer("a", "https://a.example.test", "A")
    private val b = AdminServer("b", "https://b.example.test", "B")
    private fun body(path: String) = when {
        path.endsWith("/server/status") -> com.bloemstudios.admin.auth.V2Fixtures.SERVER_STATUS
        path.endsWith("/settings") -> "{}"
        path.endsWith("/admin/users") -> com.bloemstudios.admin.auth.V2Fixtures.adminUsersPage()
        Regex(".*/admin/users/[^/]+$").matches(path) -> com.bloemstudios.admin.auth.V2Fixtures.adminUser()
        else -> "[]"
    }
    /** A single user is read with its ETag, which guarded user writes require. */
    private fun headersFor(path: String) =
        if (Regex(".*/admin/users/[^/]+$").matches(path)) headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.ETag to listOf("\"v1\""))
        else json
    private fun client(handler: suspend MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData) = ApiClient("https://fixture.test", null, MockEngine(handler))

    @Test fun `load error survives action dismissal and confirmed delete survives failed reconciliation`() = runTest {
        val vm = UsersViewModel(); var writeFails=true
        val client=client { req -> when {
            req.method == HttpMethod.Get && req.url.encodedPath.endsWith("/admin/users/1") -> respond(body(req.url.encodedPath),headers=headersFor(req.url.encodedPath))
            req.method != HttpMethod.Get && !writeFails -> respond("{}",headers=json)
            else -> respond(com.bloemstudios.admin.auth.V2Fixtures.problem(400, "malformed_request", "failure"),HttpStatusCode.BadRequest,json)
        } }
        try {
            assertEquals(ActionResult.Failure,vm.delete(user,client)); val action=vm.actionError.value
            vm.load(client); assertNotNull(vm.loadError.value); assertEquals(action,vm.errorMessage.value)
            vm.dismissActionError(); assertNull(vm.actionError.value); assertEquals(vm.loadError.value,vm.errorMessage.value)
            writeFails=false; assertEquals(ActionResult.Success,vm.delete(user,client)); assertNull(vm.actionError.value);assertNotNull(vm.loadError.value)
        } finally { client.close() }
    }

    @Test fun `quota and delete conflict throughout delayed reconciliation and cancellation releases`() = runTest {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var writes=0
        val vm=UsersViewModel()
        val client=client { req -> when {
            req.method == HttpMethod.Get && req.url.encodedPath.endsWith("/admin/users/1") -> respond(body(req.url.encodedPath),headers=headersFor(req.url.encodedPath))
            req.method == HttpMethod.Get -> {entered.complete(Unit);release.await();respond(body(req.url.encodedPath),headers=json)}
            else -> {writes++;respond("{}",headers=json)}
        } }
        try {
            val first=launch { vm.delete(user,client) }; entered.await()
            assertEquals(setOf("1"),vm.pendingKeys.value)
            assertEquals(UpdateUserResult.Busy,vm.updateQuotas(user,UpdateUserQuotasRequest(),"\"v1\"",client))
            assertEquals(ActionResult.Busy,vm.delete(user,client));assertEquals(1,writes)
            first.cancelAndJoin();assertTrue(vm.pendingKeys.value.isEmpty())
            release.complete(Unit);assertEquals(ActionResult.Success,vm.delete(user,client));assertEquals(2,writes)
        } finally { release.complete(Unit); client.close() }
    }

    @Test fun `message busy is not sent and 409 stays local while shared failure persists`() = runTest {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var writes=0
        val vm=StreamsViewModel()
        val client=client { req ->
            writes++
            if(req.url.encodedPath.endsWith("/message")) {entered.complete(Unit);release.await();respond("{}",HttpStatusCode.Conflict,json)}
            else respond("""{"message":"pause failed"}""",HttpStatusCode.BadRequest,json)
        }
        try {
            vm.pause(session,client);val error=vm.actionError.value
            val first=async {vm.sendMessage(session,null,"hello",client)};entered.await()
            assertEquals(SendMessageResult.Busy,vm.sendMessage(session,null,"again",client));assertEquals(ActionResult.Busy,vm.stop(session,client))
            release.complete(Unit);assertEquals(SendMessageResult.ClientNotConnected,first.await());assertEquals(error,vm.actionError.value);assertEquals(2,writes)
        } finally {release.complete(Unit);client.close()}
    }

    @Test fun `conditional save keeps quoted original validator guards restart and retains confirmed receipt on read failure`() = runTest {
        val vm=ServerConfigViewModel(); val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var writes=0;var validator:String?=null
        val client=client { req ->
            if(req.method == HttpMethod.Put) {writes++;validator=req.headers[HttpHeaders.IfMatch];entered.complete(Unit);release.await();respond("""{"key":"server.name","value":"Draft","restart_required":true}""",headers=json)}
            else respond("{}",HttpStatusCode.ServiceUnavailable,json)
        }
        try {
            val original=SettingEditSnapshot(AdminSetting("server.name","Original"),"\"revision-1\"")
            val first=async {vm.save(original,"Draft",client)};entered.await()
            assertEquals(SettingSaveResult.Busy,vm.save(original,"Other",client));assertEquals(ActionResult.Busy,vm.restartServer(client))
            release.complete(Unit);val result=first.await();assertTrue(result is SettingSaveResult.Saved);assertTrue((result as SettingSaveResult.Saved).setting.restart_required)
            assertEquals("\"revision-1\"",validator);assertEquals(1,writes);assertNull(vm.actionError.value);assertNotNull(vm.loadError.value);assertTrue(vm.pendingKeys.value.isEmpty())
        } finally {release.complete(Unit);client.close()}
    }

    @Test fun `conditional save conflict and unauthorized are not replayed or shared`() = runTest {
        for(status in listOf(HttpStatusCode.PreconditionFailed,HttpStatusCode(428,"Precondition Required"),HttpStatusCode.Unauthorized)) {
            var writes=0
            val client=client { req -> assertEquals(HttpMethod.Put,req.method);writes++;respond("{}",status,json) }
            try {
                val vm=ServerConfigViewModel();val result=vm.save(SettingEditSnapshot(AdminSetting("k","old"),"\"r1\""),"draft",client)
                if(status==HttpStatusCode.Unauthorized) assertTrue(result is SettingSaveResult.Failed) else assertEquals(SettingSaveResult.Conflict,result)
                assertEquals(1,writes);assertNull(vm.actionError.value);assertTrue(vm.pendingKeys.value.isEmpty())
            } finally {client.close()}
        }
    }

    @Test fun `aggregate client lookup failures become receipts and release every family guard`() = runTest {
        val lookup:(AdminServer)->ApiClient={throw IllegalStateException("fixture")}
        val streams=AggregateStreamsViewModel();assertEquals(ActionResult.Failure,streams.pause(a,session,lookup));assertNotNull(streams.actionError.value);assertTrue(streams.pendingKeys.value.isEmpty())
        val users=AggregateUsersViewModel();assertEquals(ActionResult.Failure,users.delete(a,user,lookup));val error=users.actionError.value
        assertTrue(users.createUser(a,"fixture","fixture@example.test","synthetic","user",lookup) is CreateUserResult.Failure)
        assertTrue(users.updateQuotas(a,user,UpdateUserQuotasRequest(),"\"v1\"",lookup) is UpdateUserResult.Failure);assertEquals(error,users.actionError.value);assertTrue(users.pendingKeys.value.isEmpty())
        val settings=AggregateServerConfigViewModel();assertEquals(ActionResult.Failure,settings.restartServer(a,lookup));val settingsError=settings.actionError.value
        assertTrue(settings.save(a,SettingEditSnapshot(AdminSetting("k","old"),"\"r\""),"draft",lookup) is SettingSaveResult.Failed);assertEquals(settingsError,settings.actionError.value);assertTrue(settings.pendingKeys.value.isEmpty())
        val canceled:(AdminServer)->ApiClient={throw CancellationException("fixture")}
        for(action in listOf<suspend ()->Unit>({streams.pause(a,session,canceled)},{users.delete(a,user,canceled)},{settings.restartServer(a,canceled)})) {
            try {action();fail("caller cancellation must propagate")}catch(_:CancellationException){}
        }
        assertTrue(streams.pendingKeys.value.isEmpty());assertTrue(users.pendingKeys.value.isEmpty());assertTrue(settings.pendingKeys.value.isEmpty())
    }

    @Test fun `accepted aggregate intent precedes poll lock and duplicate is dropped`() = runTest {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var writes=0
        val first=client {req -> if(req.method==HttpMethod.Get){entered.complete(Unit);release.await()}else if(req.method!=HttpMethod.Get)writes++;respond(body(req.url.encodedPath),headers=headersFor(req.url.encodedPath))}
        val second=client {req -> if(req.method==HttpMethod.Get)respond(body(req.url.encodedPath),headers=headersFor(req.url.encodedPath)) else respond(com.bloemstudios.admin.auth.V2Fixtures.problem(400, "malformed_request", "new failure"),HttpStatusCode.BadRequest,json)}
        val clients:(AdminServer)->ApiClient={if(it.id=="a")first else second};val vm=AggregateUsersViewModel()
        try {
            val poll=launch {vm.load(listOf(a,b),clients)};entered.await()
            val older=launch(start=CoroutineStart.UNDISPATCHED) {vm.delete(a,user,clients)}
            assertEquals(ActionResult.Busy,vm.delete(a,user,clients))
            vm.delete(b,user,clients);val failure=vm.actionError.value;assertNotNull(failure)
            release.complete(Unit);poll.join();older.join();assertEquals(failure,vm.actionError.value);assertEquals(1,writes)
            vm.dismissActionError();assertNull(vm.actionError.value)
        }finally{release.complete(Unit);first.close();second.close()}
    }

    @Test fun `accepted result fence rejects old failures after newer success and dismissal`() = runTest {
        for(dismiss in listOf(false,true)) {
            val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var calls=0
            val client=client { req ->
                if(req.method == HttpMethod.Get) respond(body(req.url.encodedPath),headers=headersFor(req.url.encodedPath))
                else {calls++;if(req.url.encodedPath.endsWith("/1")){entered.complete(Unit);release.await();respond(com.bloemstudios.admin.auth.V2Fixtures.problem(400, "malformed_request", "old failure"),HttpStatusCode.BadRequest,json)}
                else if(dismiss)respond(com.bloemstudios.admin.auth.V2Fixtures.problem(400, "malformed_request", "new failure"),HttpStatusCode.BadRequest,json) else respond("{}",headers=json)}
            }
            try {
                val vm=UsersViewModel();val older=launch {vm.delete(user,client)};entered.await()
                vm.delete(user.copy(id="2"),client)
                assertEquals(ActionResult.Busy,vm.delete(user,client))
                if(dismiss)vm.dismissActionError()
                release.complete(Unit);older.join();assertNull(vm.actionError.value);assertEquals(2,calls)
            } finally {release.complete(Unit);client.close()}
        }
    }

    @Test fun `canceling aggregate lock waiter releases guard without sending a mutation`() = runTest {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var writes=0
        val client=client { req ->
            if(req.method==HttpMethod.Get){entered.complete(Unit);release.await()}
            else if(req.method!=HttpMethod.Get)writes++
            respond(body(req.url.encodedPath),headers=headersFor(req.url.encodedPath))
        }
        val vm=AggregateUsersViewModel();val lookup:(AdminServer)->ApiClient={client}
        try {
            val poll=launch {vm.load(listOf(a),lookup)};entered.await()
            val waiter=launch(start=CoroutineStart.UNDISPATCHED){vm.delete(a,user,lookup)}
            assertEquals(setOf("a:1"),vm.pendingKeys.value);waiter.cancelAndJoin()
            assertTrue(vm.pendingKeys.value.isEmpty());assertEquals(0,writes)
            release.complete(Unit);poll.join();assertEquals(ActionResult.Success,vm.delete(a,user,lookup));assertEquals(1,writes)
        }finally{release.complete(Unit);client.close()}
    }

    @Test fun `aggregate settings save and restart conflict through reconciliation without blocking another server`() = runTest {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var puts=0
        val first=client { req ->
            if(req.method==HttpMethod.Put){puts++;respond("""{"key":"k","value":"draft","restart_required":true}""",headers=json)}
            else {entered.complete(Unit);release.await();respond(body(req.url.encodedPath),headers=headersFor(req.url.encodedPath))}
        }
        val second=client {req ->respond(body(req.url.encodedPath),headers=headersFor(req.url.encodedPath))}
        val lookup:(AdminServer)->ApiClient={if(it.id=="a")first else second};val vm=AggregateServerConfigViewModel()
        try {
            val original=SettingEditSnapshot(AdminSetting("k","old"),"\"r1\"")
            val save=async {vm.save(a,original,"draft",lookup)};entered.await()
            assertEquals(SettingSaveResult.Busy,vm.save(a,original,"again",lookup));assertEquals(ActionResult.Busy,vm.restartServer(a,lookup))
            assertEquals(ActionResult.Success,vm.restartServer(b,lookup));assertEquals(1,puts)
            release.complete(Unit);assertTrue(save.await() is SettingSaveResult.Saved);assertTrue(vm.pendingKeys.value.isEmpty())
        }finally{release.complete(Unit);first.close();second.close()}
    }

    @Test fun `create busy never claims success during delayed reconciliation`() = runTest {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var writes=0
        val client=client { req ->when {
            req.method==HttpMethod.Post->{writes++;respond("{}",headers=json)}
            else->{entered.complete(Unit);release.await();respond(com.bloemstudios.admin.auth.V2Fixtures.adminUsersPage(),headers=json)}
        } }
        try {
            val vm=UsersViewModel();val first=async{vm.createUser("fixture","fixture@example.test","synthetic","user",client)};entered.await()
            assertEquals(CreateUserResult.Busy,vm.createUser("fixture","fixture@example.test","synthetic","user",client))
            assertEquals(1,writes);release.complete(Unit);assertEquals(CreateUserResult.Success,first.await());assertNull(vm.actionError.value)
        }finally{release.complete(Unit);client.close()}
    }
}
