import org.junit.jupiter.api.*;
import us.anvildevelopment.cxnet.annotations.CXAppField;
import us.anvildevelopment.cxnet.annotations.CXAppMethod;
import us.anvildevelopment.cxnet.app.CXAppClient;
import us.anvildevelopment.cxnet.app.CXAppRequest;
import us.anvildevelopment.cxnet.app.CXAppResponse;
import us.anvildevelopment.cxnet.app.CXAppServer;
import us.anvildevelopment.cxnet.edge.DataContainer;
import us.anvildevelopment.util.tools.permissions.BasicEntry;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the CXApp system.
 * Tests reflection caching, READ/WRITE/INVOKE/REFRESH ops, permission
 * allow/deny, buildHTML field placeholders, and invoke placeholder generation.
 * No network or ConnectX instance required.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class CXAppUnitTest {

    // -------------------------------------------------------------------------
    // Inline server definition
    // -------------------------------------------------------------------------

    static class TestServer extends CXAppServer {
        @CXAppField(readable = true, writable = true)
        public String name = "default";

        @CXAppField(readable = true, writable = false)
        public int count = 0;

        @CXAppField(readable = true, writable = false, permission = "admin")
        public String secret = "s3cr3t";

        @CXAppMethod
        private String greet(String who) {
            return "Hello, " + who + "!";
        }

        @CXAppMethod
        private void increment() {
            count++;
        }

        @CXAppMethod(permission = "admin")
        private String reset() {
            name = "default";
            count = 0;
            return "reset";
        }

        @Override public String getAppID() { return "test-app"; }
    }

    // -------------------------------------------------------------------------
    // Inline client definition
    // -------------------------------------------------------------------------

    static class TestClient extends CXAppClient {
        @CXAppField
        public String name;

        @CXAppField
        public int count;

        @Override public String getAppID() { return "test-app"; }

        @Override
        public String getTemplate() {
            return "<h1>{{name}}</h1>"
                 + "<p>Count: {{count}}</p>"
                 + "{{invoke:greet:Say Hello:Name}}"
                 + "{{invoke:increment:Increment}}";
        }
    }

    // -------------------------------------------------------------------------
    // Shared state
    // -------------------------------------------------------------------------

    private static TestServer server;
    private static TestClient client;
    private static DataContainer dc;
    private static final String CALLER = "peer-caller-001";
    private static final String ADMIN  = "peer-admin-002";

    @BeforeAll
    static void setup() {
        server = new TestServer();
        server.buildCache();

        client = new TestClient();
        client.buildCache();

        dc = new DataContainer();
        dc.cxidAppPermissions.permissionSet
                .computeIfAbsent(ADMIN, k -> new HashMap<>())
                .put("admin", new BasicEntry("admin", true, 100));
    }

    @AfterEach
    void resetServerState() {
        server.name  = "default";
        server.count = 0;
        // Ensure admin permission is active after each test
        dc.cxidAppPermissions.permissionSet
                .computeIfAbsent(ADMIN, k -> new HashMap<>())
                .put("admin", new BasicEntry("admin", true, 100));
    }

    // -------------------------------------------------------------------------
    // READ
    // -------------------------------------------------------------------------

    @Test
    @Order(1)
    @DisplayName("READ: returns field value")
    void testRead() {
        CXAppResponse r = server.handle(new CXAppRequest("test-app", "READ", "name", null), CALLER, dc);
        assertTrue(r.success);
        assertEquals("default", r.fields.get("name"));
    }

    @Test
    @Order(2)
    @DisplayName("READ: unknown field returns failure")
    void testReadUnknownField() {
        CXAppResponse r = server.handle(new CXAppRequest("test-app", "READ", "doesNotExist", null), CALLER, dc);
        assertFalse(r.success);
        assertNotNull(r.error);
    }

    // -------------------------------------------------------------------------
    // WRITE
    // -------------------------------------------------------------------------

    @Test
    @Order(3)
    @DisplayName("WRITE: updates writable field and confirms new value")
    void testWrite() {
        CXAppResponse r = server.handle(new CXAppRequest("test-app", "WRITE", "name", new String[]{"updated"}), CALLER, dc);
        assertTrue(r.success);
        assertEquals("updated", r.fields.get("name"));
        assertEquals("updated", server.name);
    }

    @Test
    @Order(4)
    @DisplayName("WRITE: read-only field is rejected")
    void testWriteReadOnly() {
        CXAppResponse r = server.handle(new CXAppRequest("test-app", "WRITE", "count", new String[]{"99"}), CALLER, dc);
        assertFalse(r.success);
    }

    @Test
    @Order(5)
    @DisplayName("WRITE: missing args returns failure")
    void testWriteMissingArgs() {
        CXAppResponse r = server.handle(new CXAppRequest("test-app", "WRITE", "name", null), CALLER, dc);
        assertFalse(r.success);
    }

    // -------------------------------------------------------------------------
    // INVOKE
    // -------------------------------------------------------------------------

    @Test
    @Order(6)
    @DisplayName("INVOKE: method with arg returns correct value")
    void testInvokeWithArg() {
        CXAppResponse r = server.handle(new CXAppRequest("test-app", "INVOKE", "greet", new String[]{"World"}), CALLER, dc);
        assertTrue(r.success);
        assertEquals("Hello, World!", r.fields.get("_return"));
    }

    @Test
    @Order(7)
    @DisplayName("INVOKE: void method mutates state, no _return entry")
    void testInvokeVoid() {
        int before = server.count;
        CXAppResponse r = server.handle(new CXAppRequest("test-app", "INVOKE", "increment", null), CALLER, dc);
        assertTrue(r.success);
        assertFalse(r.fields.containsKey("_return"));
        assertEquals(before + 1, server.count);
    }

    @Test
    @Order(8)
    @DisplayName("INVOKE: unknown method returns failure")
    void testInvokeUnknown() {
        CXAppResponse r = server.handle(new CXAppRequest("test-app", "INVOKE", "nonExistent", null), CALLER, dc);
        assertFalse(r.success);
    }

    // -------------------------------------------------------------------------
    // REFRESH
    // -------------------------------------------------------------------------

    @Test
    @Order(9)
    @DisplayName("REFRESH: returns all readable fields for unpermissioned caller")
    void testRefreshUnpermissioned() {
        server.name  = "refresh-test";
        server.count = 7;
        CXAppResponse r = server.handle(new CXAppRequest("test-app", "REFRESH", null, null), CALLER, dc);
        assertTrue(r.success);
        assertEquals("refresh-test", r.fields.get("name"));
        assertEquals("7", r.fields.get("count"));
        assertFalse(r.fields.containsKey("secret"), "permission-gated field excluded");
    }

    @Test
    @Order(10)
    @DisplayName("REFRESH: admin sees permission-gated fields")
    void testRefreshAdmin() {
        CXAppResponse r = server.handle(new CXAppRequest("test-app", "REFRESH", null, null), ADMIN, dc);
        assertTrue(r.success);
        assertTrue(r.fields.containsKey("secret"));
        assertEquals("s3cr3t", r.fields.get("secret"));
    }

    // -------------------------------------------------------------------------
    // Permissions
    // -------------------------------------------------------------------------

    @Test
    @Order(11)
    @DisplayName("Permission: READ of gated field denied without permission")
    void testPermissionReadDenied() {
        CXAppResponse r = server.handle(new CXAppRequest("test-app", "READ", "secret", null), CALLER, dc);
        assertFalse(r.success);
    }

    @Test
    @Order(12)
    @DisplayName("Permission: READ of gated field allowed with permission")
    void testPermissionReadAllowed() {
        CXAppResponse r = server.handle(new CXAppRequest("test-app", "READ", "secret", null), ADMIN, dc);
        assertTrue(r.success);
        assertEquals("s3cr3t", r.fields.get("secret"));
    }

    @Test
    @Order(13)
    @DisplayName("Permission: INVOKE of gated method denied without permission")
    void testPermissionInvokeDenied() {
        CXAppResponse r = server.handle(new CXAppRequest("test-app", "INVOKE", "reset", null), CALLER, dc);
        assertFalse(r.success);
    }

    @Test
    @Order(14)
    @DisplayName("Permission: INVOKE of gated method allowed with permission")
    void testPermissionInvokeAllowed() {
        server.name = "dirty";
        CXAppResponse r = server.handle(new CXAppRequest("test-app", "INVOKE", "reset", null), ADMIN, dc);
        assertTrue(r.success);
        assertEquals("reset", r.fields.get("_return"));
        assertEquals("default", server.name);
    }

    @Test
    @Order(15)
    @DisplayName("Permission: revoke by flipping allow=false denies access")
    void testPermissionRevoke() {
        dc.cxidAppPermissions.permissionSet.get(ADMIN).get("admin").setAllow(false);
        CXAppResponse r = server.handle(new CXAppRequest("test-app", "READ", "secret", null), ADMIN, dc);
        assertFalse(r.success);
    }

    // -------------------------------------------------------------------------
    // buildHTML / applyAndRender
    // -------------------------------------------------------------------------

    @Test
    @Order(16)
    @DisplayName("buildHTML: field placeholders replaced with current values")
    void testBuildHTMLFieldPlaceholders() {
        server.name  = "RProx";
        server.count = 42;
        CXAppResponse refresh = server.handle(new CXAppRequest("test-app", "REFRESH", null, null), CALLER, dc);
        String html = client.applyAndRender(refresh);

        assertTrue(html.contains("RProx"),  "name value present");
        assertTrue(html.contains("42"),     "count value present");
        assertFalse(html.contains("{{name}}"),  "no leftover name placeholder");
        assertFalse(html.contains("{{count}}"), "no leftover count placeholder");
    }

    @Test
    @Order(17)
    @DisplayName("buildHTML: no-arg invoke generates button with data attributes")
    void testBuildHTMLInvokeButton() {
        CXAppResponse refresh = server.handle(new CXAppRequest("test-app", "REFRESH", null, null), CALLER, dc);
        String html = client.applyAndRender(refresh);

        assertTrue(html.contains("data-cxapp-invoke=\"increment\""), "invoke attr present");
        assertTrue(html.contains("data-cxapp-appid=\"test-app\""),   "appid attr present");
        assertTrue(html.contains("Increment"),                        "button label present");
    }

    @Test
    @Order(18)
    @DisplayName("buildHTML: invoke with args generates form with labeled inputs")
    void testBuildHTMLInvokeForm() {
        CXAppResponse refresh = server.handle(new CXAppRequest("test-app", "REFRESH", null, null), CALLER, dc);
        String html = client.applyAndRender(refresh);

        assertTrue(html.contains("<form"),                           "form element present");
        assertTrue(html.contains("data-cxapp-invoke=\"greet\""),    "greet invoke attr present");
        assertTrue(html.contains("placeholder=\"Name\""),           "arg label as placeholder");
        assertTrue(html.contains("Say Hello"),                       "submit label present");
    }

    @Test
    @Order(19)
    @DisplayName("buildHTML: no leftover invoke placeholders in output")
    void testBuildHTMLNoLeftoverInvokePlaceholders() {
        CXAppResponse refresh = server.handle(new CXAppRequest("test-app", "REFRESH", null, null), CALLER, dc);
        String html = client.applyAndRender(refresh);

        assertFalse(html.contains("{{invoke:"), "no leftover invoke placeholders");
    }
}
