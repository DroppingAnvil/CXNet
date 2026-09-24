import org.junit.jupiter.api.*;
import us.anvildevelopment.cxnet.app.CXAppDispatcher;
import us.anvildevelopment.cxnet.app.CXAppRequests;
import us.anvildevelopment.cxnet.app.CXAppResponse;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the CXApp request/response correlation registry.
 *
 * This covers the in-process calling path, which previously did not exist at all: the only way to
 * reach a CXApp was the loopback HTTP bridge. No network or ConnectX instance is required, since
 * CXAppRequests is pure correlation logic over a dispatcher.
 */
public class CXAppRequestsTest {

    private CXAppDispatcher dispatcher;
    private CXAppRequests requests;

    @BeforeEach
    void setUp() {
        dispatcher = new CXAppDispatcher(2, 8, 1000);
        requests   = new CXAppRequests(dispatcher);
    }

    @AfterEach
    void tearDown() {
        dispatcher.shutdown();
    }

    private static CXAppResponse okResponse(String appID, String field, String value) {
        Map<String, String> fields = new HashMap<>();
        fields.put(field, value);
        return CXAppResponse.ok(appID, fields);
    }

    @Test
    @DisplayName("A response delivered for a registered sid completes that future")
    void completesOnMatchingSid() throws Exception {
        CompletableFuture<CXAppResponse> f = requests.register("sid-1", "test-app", 5000);
        assertFalse(f.isDone(), "future should not complete before a response arrives");

        requests.complete("sid-1", okResponse("test-app", "count", "7"));

        CXAppResponse r = f.get(2, TimeUnit.SECONDS);
        assertTrue(r.success);
        assertEquals("7", r.fields.get("count"));
    }

    @Test
    @DisplayName("Completion runs off the calling thread, not inline")
    void completesOffCallingThread() throws Exception {
        // The caller of complete() is the EventProcessor in production. If the future completed
        // inline, every caller's callback chain would run on that single mesh thread, which is the
        // stall CXAppDispatcher exists to prevent.
        CompletableFuture<CXAppResponse> f = requests.register("sid-thread", "test-app", 5000);

        AtomicReference<String> callbackThread = new AtomicReference<>();
        CompletableFuture<Void> observed = f.thenAccept(
                r -> callbackThread.set(Thread.currentThread().getName()));

        String callerThread = Thread.currentThread().getName();
        requests.complete("sid-thread", okResponse("test-app", "count", "1"));
        observed.get(2, TimeUnit.SECONDS);

        assertNotNull(callbackThread.get());
        assertNotEquals(callerThread, callbackThread.get(),
                "callback must not run on the thread that delivered the response");
    }

    @Test
    @DisplayName("A request that is never answered times out")
    void timesOutWhenNoResponseArrives() {
        CompletableFuture<CXAppResponse> f = requests.register("sid-timeout", "test-app", 150);

        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> f.get(3, TimeUnit.SECONDS));
        assertInstanceOf(TimeoutException.class, ex.getCause(),
                "an unanswered request should fail with TimeoutException");
    }

    @Test
    @DisplayName("An unknown sid is ignored rather than throwing")
    void unknownSidIsIgnored() {
        // Normal in production: the sid belongs to the browser bridge, or to a request that
        // already timed out and gave up.
        assertDoesNotThrow(() -> requests.complete("never-registered", okResponse("test-app", "a", "b")));
        assertDoesNotThrow(() -> requests.complete(null, okResponse("test-app", "a", "b")));
    }

    @Test
    @DisplayName("Pending entries are released on both completion and timeout")
    void pendingIsReleasedOnBothOutcomes() throws Exception {
        CompletableFuture<CXAppResponse> answered = requests.register("sid-a", "test-app", 5000);
        CompletableFuture<CXAppResponse> abandoned = requests.register("sid-b", "test-app", 150);
        assertEquals(2, requests.pendingCount());

        requests.complete("sid-a", okResponse("test-app", "x", "1"));
        answered.get(2, TimeUnit.SECONDS);

        assertThrows(ExecutionException.class, () -> abandoned.get(3, TimeUnit.SECONDS));

        // Without the whenComplete removal in register(), the timed-out entry would leak here.
        assertEquals(0, requests.pendingCount(),
                "neither a completed nor a timed-out request should stay pending");
    }

    @Test
    @DisplayName("A failure response completes normally and is not an exception")
    void failureResponseCompletesNormally() throws Exception {
        // success == false means the server answered and refused. Only transport problems complete
        // the future exceptionally, so callers branch on error rather than catching.
        CompletableFuture<CXAppResponse> f = requests.register("sid-fail", "test-app", 5000);
        requests.complete("sid-fail",
                CXAppResponse.fail("test-app", us.anvildevelopment.cxnet.app.CXAppError.FORBIDDEN));

        CXAppResponse r = f.get(2, TimeUnit.SECONDS);
        assertFalse(r.success);
        assertEquals("FORBIDDEN", r.error);
        assertNotNull(r.message);
    }

    @Test
    @DisplayName("Concurrent requests are correlated to their own sids")
    void correlatesConcurrentRequests() throws Exception {
        CompletableFuture<CXAppResponse> a = requests.register("sid-x", "app-one", 5000);
        CompletableFuture<CXAppResponse> b = requests.register("sid-y", "app-two", 5000);

        // Answered out of order, which is the normal case across different peers.
        requests.complete("sid-y", okResponse("app-two", "v", "bee"));
        requests.complete("sid-x", okResponse("app-one", "v", "ay"));

        assertEquals("ay",  a.get(2, TimeUnit.SECONDS).fields.get("v"));
        assertEquals("bee", b.get(2, TimeUnit.SECONDS).fields.get("v"));
    }
}
