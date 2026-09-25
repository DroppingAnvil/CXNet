import org.junit.jupiter.api.*;
import us.anvildevelopment.cxnet.annotations.CXAppField;
import us.anvildevelopment.cxnet.annotations.CXAppMethod;
import us.anvildevelopment.cxnet.app.CXAppRequest;
import us.anvildevelopment.cxnet.app.CXAppResponse;
import us.anvildevelopment.cxnet.app.CXAppServer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers inherited @CXAppField and @CXAppMethod members, and INVOKE argument arity.
 *
 * buildCache previously scanned only getClass().getDeclaredFields() and getDeclaredMethods(), so a
 * shared base app's annotated members were silently absent from the cache. Because an unknown
 * target answers FORBIDDEN, that presented as a permission problem rather than a missing field,
 * which is the trap this test exists to keep shut.
 */
public class CXAppHierarchyTest {

    // ---------------------------------------------------------------------
    // A two-level app: shared base plus concrete subclass.
    // ---------------------------------------------------------------------

    static abstract class BaseApp extends CXAppServer {
        @CXAppField                       String  inheritedField = "from-base";
        @CXAppField(writable = true)      int     inheritedCount = 1;
        @CXAppField                       String  shadowed       = "base-value";

        @CXAppMethod String inheritedMethod() { return "base-method"; }
        @CXAppMethod String overridden()      { return "base-impl"; }
    }

    static class DerivedApp extends BaseApp {
        @CXAppField String ownField = "from-derived";
        // Shadows the base declaration; the derived one must win.
        @CXAppField String shadowed = "derived-value";

        @Override public String getAppID() { return "hierarchy-app"; }

        // Overrides without re-annotating: keeps the base annotation, runs this body.
        @Override String overridden() { return "derived-impl"; }

        @CXAppMethod String twoArgs(String a, String b) { return a + "|" + b; }
    }

    private DerivedApp app;

    @BeforeEach
    void setUp() {
        app = new DerivedApp();
        app.buildCache();
    }

    private CXAppResponse call(String op, String target, String... args) {
        return app.handle(new CXAppRequest("hierarchy-app", op, target,
                args.length == 0 ? null : args), "peer-001", null);
    }

    // ---------------------------------------------------------------------
    // Inherited members
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("An inherited @CXAppField is readable")
    void inheritedFieldIsVisible() {
        CXAppResponse r = call("READ", "inheritedField");
        assertTrue(r.success, "inherited field should be cached, got error=" + r.error);
        assertEquals("from-base", r.fields.get("inheritedField"));
    }

    @Test
    @DisplayName("An inherited @CXAppMethod is invokable")
    void inheritedMethodIsVisible() {
        CXAppResponse r = call("INVOKE", "inheritedMethod");
        assertTrue(r.success, "inherited method should be cached, got error=" + r.error);
        assertEquals("base-method", r.fields.get("_return"));
    }

    @Test
    @DisplayName("The subclass's own members still work alongside inherited ones")
    void ownMembersStillWork() {
        assertEquals("from-derived", call("READ", "ownField").fields.get("ownField"));
    }

    @Test
    @DisplayName("A shadowing field resolves to the most-derived declaration")
    void mostDerivedFieldWins() {
        assertEquals("derived-value", call("READ", "shadowed").fields.get("shadowed"));
    }

    @Test
    @DisplayName("An unannotated override keeps the base annotation and runs the override body")
    void overrideDispatchesVirtually() {
        CXAppResponse r = call("INVOKE", "overridden");
        assertTrue(r.success, "error=" + r.error);
        assertEquals("derived-impl", r.fields.get("_return"),
                "cached base Method must dispatch virtually to the override");
    }

    @Test
    @DisplayName("REFRESH includes inherited fields")
    void refreshIncludesInherited() {
        CXAppResponse r = call("REFRESH", null);
        assertTrue(r.success);
        assertTrue(r.fields.containsKey("inheritedField"), "REFRESH missed an inherited field");
        assertTrue(r.fields.containsKey("ownField"));
        assertEquals("derived-value", r.fields.get("shadowed"));
    }

    @Test
    @DisplayName("An inherited writable field can be written")
    void inheritedFieldIsWritable() {
        CXAppResponse r = call("WRITE", "inheritedCount", "42");
        assertTrue(r.success, "error=" + r.error);
        assertEquals("42", r.fields.get("inheritedCount"));
        assertEquals(42, app.inheritedCount);
    }

    @Test
    @DisplayName("A genuinely absent field is still refused")
    void absentFieldStillRefused() {
        // The hierarchy walk must not make unknown targets succeed.
        CXAppResponse r = call("READ", "noSuchField");
        assertFalse(r.success);
        assertEquals("FORBIDDEN", r.error);
    }

    // ---------------------------------------------------------------------
    // Argument arity: null padding removed
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Too few INVOKE arguments report MISSING_ARGUMENT, not HANDLER_ERROR")
    void tooFewArgs() {
        CXAppResponse r = call("INVOKE", "twoArgs", "only-one");
        assertFalse(r.success);
        assertEquals("MISSING_ARGUMENT", r.error,
                "a short call must not be padded with nulls and surface as a handler failure");
        assertTrue(r.message.contains("2"), "message should state the expected count: " + r.message);
    }

    @Test
    @DisplayName("Too many INVOKE arguments are rejected rather than truncated")
    void tooManyArgs() {
        CXAppResponse r = call("INVOKE", "twoArgs", "a", "b", "c");
        assertFalse(r.success);
        assertEquals("MISSING_ARGUMENT", r.error);
    }

    @Test
    @DisplayName("Exact arity still succeeds")
    void exactArity() {
        CXAppResponse r = call("INVOKE", "twoArgs", "a", "b");
        assertTrue(r.success, "error=" + r.error);
        assertEquals("a|b", r.fields.get("_return"));
    }

    @Test
    @DisplayName("A no-arg method accepts a null args array")
    void noArgMethodWithNullArgs() {
        CXAppResponse r = call("INVOKE", "inheritedMethod");
        assertTrue(r.success, "error=" + r.error);
    }
}
