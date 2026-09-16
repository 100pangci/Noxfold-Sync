package com.nutomic.syncthingandroid.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nutomic.syncthingandroid.SyncthingApp
import com.nutomic.syncthingandroid.model.Folder
import com.nutomic.syncthingandroid.model.SystemStatus
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Callback-level tests for [RestApi] after the phase6a engine swap (Volley GetRequest/
 * PostRequest classes replaced by [com.nutomic.syncthingandroid.http.ApiClientBridge] on top
 * of the suspend [com.nutomic.syncthingandroid.http.ApiClient]).
 *
 * The bridge scope runs on an [UnconfinedTestDispatcher], so callbacks fire as soon as the
 * OkHttp call completes; tests await them with latches instead of relying on the main looper.
 * A path-based [Dispatcher] answers every request the real startup sequence issues
 * (version/config/status, pending devices/folders, db/completion, connections, stats),
 * keeping the tests order-independent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = SyncthingApp::class)
class RestApiTest {

    private val testScheduler = TestCoroutineScheduler()
    private val bridgeScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var server: MockWebServer
    private lateinit var dispatcher: StartupDispatcher
    private lateinit var restApi: RestApi

    private companion object {
        const val API_KEY = "test-api-key"
        const val LOCAL_DEVICE_ID = "LOCAL-DEVICE-ID-7LTUV3P"
        const val REMOTE_DEVICE_ID = "REMOTE-DEVICE-ID-2NDQT3B"

        const val VERSION_JSON = "{\"version\": \"v1.29.0-test\"}"

        const val SYSTEM_STATUS_JSON =
                "{\"myID\": \"$LOCAL_DEVICE_ID\", \"urVersionMax\": 3}"

        const val CONFIG_JSON = """
            {
              "version": 37,
              "devices": [
                {
                  "deviceID": "$LOCAL_DEVICE_ID",
                  "name": "This Device",
                  "addresses": ["dynamic"],
                  "compression": "metadata",
                  "introducedBy": "",
                  "introducer": false,
                  "paused": false,
                  "autoAcceptFolders": false,
                  "untrusted": false
                },
                {
                  "deviceID": "$REMOTE_DEVICE_ID",
                  "name": "Remote Device",
                  "addresses": ["dynamic"],
                  "compression": "metadata",
                  "introducedBy": "",
                  "introducer": false,
                  "paused": false,
                  "autoAcceptFolders": false,
                  "untrusted": false
                }
              ],
              "folders": [
                {
                  "id": "f1",
                  "label": "Folder One",
                  "path": "/data/f1",
                  "type": "sendreceive",
                  "paused": false,
                  "devices": [{"deviceID": "$REMOTE_DEVICE_ID"}]
                }
              ],
              "gui": {"address": "127.0.0.1:8384"},
              "options": {"urAccepted": -1, "urSeen": 3},
              "remoteIgnoredDevices": []
            }
            """

        const val PENDING_DEVICES_JSON = "{}"
        const val PENDING_FOLDERS_JSON = "{}"

        const val DB_COMPLETION_JSON =
                "{\"completion\": 100, \"needBytes\": 0, \"globalBytes\": 1, \"needDeletes\": 0}"

        const val CONNECTIONS_JSON = "{\"connections\": {}, \"total\": {}}"

        const val STATS_DEVICE_JSON = "{}"
    }

    /**
     * Answers every request the startup sequence may issue, matched by path prefix, and
     * counts hits per path so tests can await asynchronously issued follow-up requests.
     * Responses queued via [enqueue] are served first (for endpoints tested with exact
     * responses), falling back to the canned startup bodies.
     */
    private class StartupDispatcher : Dispatcher() {
        private val queuedResponses = java.util.concurrent.LinkedBlockingQueue<MockResponse>()
        val hits = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()

        fun enqueue(response: MockResponse) {
            queuedResponses.add(response)
        }

        fun hitsOf(pathPrefix: String): Int = hits[pathPrefix]?.get() ?: 0

        private fun json(body: String): MockResponse =
                MockResponse().setHeader("Content-Type", "application/json").setBody(body)

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path!!
            val response = queuedResponses.poll() ?: when {
                path.startsWith("/rest/system/version") -> json(VERSION_JSON)
                path.startsWith("/rest/system/config") -> json(CONFIG_JSON)
                path.startsWith("/rest/system/status") -> json(SYSTEM_STATUS_JSON)
                path.startsWith("/rest/cluster/pending/devices") -> json(PENDING_DEVICES_JSON)
                path.startsWith("/rest/cluster/pending/folders") -> json(PENDING_FOLDERS_JSON)
                path.startsWith("/rest/db/completion") -> json(DB_COMPLETION_JSON)
                path.startsWith("/rest/system/connections") -> json(CONNECTIONS_JSON)
                path.startsWith("/rest/stats/device") -> json(STATS_DEVICE_JSON)
                else -> MockResponse().setResponseCode(404).setBody("unexpected path: $path")
            }
            hits.computeIfAbsent(path.substringBefore('?')) {
                java.util.concurrent.atomic.AtomicInteger()
            }.incrementAndGet()
            return response
        }
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        dispatcher = StartupDispatcher()
        server.dispatcher = dispatcher
        server.start()
        restApi = RestApi(
                context,
                server.url("/").toUrl(),
                API_KEY,
                { },                // onApiAvailable
                { },                // onConfigChanged
                bridgeScope,
        )
    }

    @After
    fun tearDown() {
        bridgeScope.cancel()
        server.shutdown()
    }

    // region Startup: readConfigFromRestApi

    @Test
    fun readConfigFromRestApi_callsOnApiAvailable_andCachesVersionConfigAndLocalDeviceId() {
        val apiAvailable = CountDownLatch(1)
        restApi = RestApi(
                context,
                server.url("/").toUrl(),
                API_KEY,
                { apiAvailable.countDown() },
                { },
                bridgeScope,
        )

        restApi.readConfigFromRestApi()

        assertTrue("onApiAvailable not reached within timeout",
                apiAvailable.await(10, TimeUnit.SECONDS))
        assertEquals("v1.29.0-test", restApi.version)
        assertTrue(restApi.isConfigLoaded)
        assertEquals(LOCAL_DEVICE_ID, restApi.localDevice.deviceID)

        val folders: List<Folder> = restApi.folders
        assertEquals(1, folders.size)
        assertEquals("f1", folders[0].id)
        assertEquals("Folder One", folders[0].label)
    }

    /**
     * onReloadConfigComplete issues the pending devices/folders queries asynchronously; the
     * onApiAvailable callback does not wait for them, so await via the dispatcher counters.
     */
    private fun awaitPathHit(pathPrefix: String) {
        val deadline = System.currentTimeMillis() + 10_000
        while (dispatcher.hitsOf(pathPrefix) == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertTrue("no request hit $pathPrefix within timeout", dispatcher.hitsOf(pathPrefix) > 0)
    }

    @Test
    fun readConfigFromRestApi_queriesPendingDevicesAndFolders() {
        restApi.readConfigFromRestApi()

        awaitPathHit("/rest/cluster/pending/devices")
        awaitPathHit("/rest/cluster/pending/folders")
    }

    @Test
    fun getSystemStatus_parsesListenerResult() {
        val latch = CountDownLatch(1)
        val received = arrayOfNulls<SystemStatus>(1)

        restApi.getSystemStatus { status ->
            received[0] = status
            latch.countDown()
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS))
        assertNotNull(received[0])
        assertEquals(LOCAL_DEVICE_ID, received[0]!!.myID)
        assertEquals(3, received[0]!!.urVersionMax)
    }

    // endregion

    // region Config changes: sendConfig

    @Test
    fun sendConfig_postsConfigJson_andNotifiesConfigListener() {
        val configChanged = CountDownLatch(1)
        restApi = RestApi(
                context,
                server.url("/").toUrl(),
                API_KEY,
                { },
                { configChanged.countDown() },
                bridgeScope,
        )
        restApi.readConfigFromRestApi()
        awaitConfigLoaded()

        restApi.sendConfig()

        assertTrue("onConfigChanged not called within timeout",
                configChanged.await(10, TimeUnit.SECONDS))
        // Startup requests may still be in flight; scan the queue until the POST shows up.
        var recorded: okhttp3.mockwebserver.RecordedRequest? = null
        val deadline = System.currentTimeMillis() + 10_000
        while (recorded == null && System.currentTimeMillis() < deadline) {
            val next = server.takeRequest(100, TimeUnit.MILLISECONDS) ?: continue
            if (next.method == "POST" && next.path == "/rest/system/config") {
                recorded = next
            }
        }
        assertNotNull("no POST /rest/system/config reached the server", recorded)
        assertEquals("POST", recorded!!.method)
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"folders\""))
        assertTrue(body.contains("f1"))
    }

    // endregion

    // region Events: getEvents

    @Test
    fun getEvents_dispatchesEvents_andReportsLastId() {
        val eventsJson = """
            [
              {"id": 5, "type": "StateChanged", "time": "2026-01-01T00:00:05Z",
               "data": {"folder": "f1", "to": "idle"}},
              {"id": 7, "type": "Ping", "time": "2026-01-01T00:00:07Z", "data": {}}
            ]
            """.trimIndent()
        dispatcher.enqueue(MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(eventsJson))

        val done = CountDownLatch(1)
        val eventTypes = java.util.Collections.synchronizedList(ArrayList<String>())
        var lastId = 0L

        restApi.getEvents(3, 100, object : RestApi.OnReceiveEventListener {
            override fun onError() {
                throw AssertionError("unexpected onError")
            }
            override fun onEvent(event: com.nutomic.syncthingandroid.model.Event,
                                 json: kotlinx.serialization.json.JsonElement) {
                eventTypes.add(event.type)
            }
            override fun onDone(lastId_: Long) {
                lastId = lastId_
                done.countDown()
            }
        })

        assertTrue("onDone not reached within timeout", done.await(10, TimeUnit.SECONDS))
        assertEquals(listOf("StateChanged", "Ping"), eventTypes)
        assertEquals(7L, lastId)

        val recorded = server.takeRequest()
        assertEquals("/rest/events", recorded.path!!.substringBefore('?'))
        assertEquals("3", recorded.requestUrl!!.queryParameter("since"))
        assertEquals("100", recorded.requestUrl!!.queryParameter("limit"))
    }

    @Test
    fun getEvents_callsOnError_onHttpFailure() {
        dispatcher.enqueue(MockResponse().setResponseCode(500).setBody("boom"))

        val error = CountDownLatch(1)
        restApi.getEvents(0, 100, object : RestApi.OnReceiveEventListener {
            override fun onError() {
                error.countDown()
            }
            override fun onEvent(event: com.nutomic.syncthingandroid.model.Event,
                                 json: kotlinx.serialization.json.JsonElement) {
                throw AssertionError("unexpected onEvent")
            }
            override fun onDone(lastId: Long) {
                throw AssertionError("unexpected onDone")
            }
        })

        assertTrue("onError not called within timeout", error.await(10, TimeUnit.SECONDS))
    }

    // endregion

    // region POST actions

    @Test
    fun overrideChanges_andRescanAll_postToTheirEndpoints() {
        restApi.overrideChanges("f1")
        restApi.rescanAll()

        // Both posts are issued concurrently, so their arrival order is not deterministic.
        val requests = ArrayList<okhttp3.mockwebserver.RecordedRequest>(2)
        repeat(2) {
            val recorded = server.takeRequest(10, TimeUnit.SECONDS)
            assertNotNull("expected two POST requests", recorded)
            requests.add(recorded!!)
        }
        val paths = requests.map { it.path!!.substringBefore('?') }.toSet()
        assertEquals(setOf("/rest/db/override", "/rest/db/scan"), paths)
        val override = requests.first { it.path!!.startsWith("/rest/db/override") }
        assertEquals("f1", override.requestUrl!!.queryParameter("folder"))
    }

    @Test
    fun rescanFolderByPath_postsScanForMatchingFolder() {
        restApi.readConfigFromRestApi()
        awaitConfigLoaded()

        restApi.rescanFolderByPath("/data/f1")

        // Startup requests may still be in flight; scan the queue until the POST shows up.
        var recorded: okhttp3.mockwebserver.RecordedRequest? = null
        val deadline = System.currentTimeMillis() + 10_000
        while (recorded == null && System.currentTimeMillis() < deadline) {
            val next = server.takeRequest(100, TimeUnit.MILLISECONDS) ?: continue
            if (next.method == "POST" && next.path!!.startsWith("/rest/db/scan")) {
                recorded = next
            }
        }
        assertNotNull("no POST /rest/db/scan reached the server", recorded)
        assertEquals("f1", recorded!!.requestUrl!!.queryParameter("folder"))
    }

    @Test
    fun rescanFolderByPath_noConfiguredFolder_isNoop() {
        restApi.readConfigFromRestApi()
        awaitConfigLoaded()

        restApi.rescanFolderByPath("/data/does-not-exist")

        // No scan POST may appear for an unknown folder path. Give the (async,
        // fire-and-forget) call enough time to have posted if it were going to.
        val deadline = System.currentTimeMillis() + 1_000
        while (System.currentTimeMillis() < deadline) {
            val next = server.takeRequest(100, TimeUnit.MILLISECONDS) ?: continue
            assertTrue(
                "unexpected request reached the server: ${next.method} ${next.path}",
                !(next.method == "POST" && next.path!!.startsWith("/rest/db/scan"))
            )
        }
    }

    // endregion

    /** Awaits the asynchronous startup query burst so tests that issue their own requests
     * afterwards run against a loaded config. */
    private fun awaitConfigLoaded() {
        val deadline = System.currentTimeMillis() + 10_000
        while (!restApi.isConfigLoaded && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertTrue("config not loaded within timeout", restApi.isConfigLoaded)
    }
}
