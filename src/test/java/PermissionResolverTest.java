import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import us.anvildevelopment.cxnet.network.permissions.PermissionResolver;
import us.anvildevelopment.util.tools.permissions.BasicEntry;
import us.anvildevelopment.util.tools.permissions.BasicPermissionContainer;
import us.anvildevelopment.util.tools.permissions.Entry;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Hierarchical resolution over a real BasicPermissionContainer.
 *
 * Deliberately not a mock: the whole point of this class is that the existing container already
 * provides storage, explicit deny and default deny, and only matching was missing. Testing
 * against the real thing is what proves that claim.
 */
public class PermissionResolverTest {

    private static BasicPermissionContainer container() {
        BasicPermissionContainer c = new BasicPermissionContainer();
        c.permissionSet = new HashMap<>();
        return c;
    }

    private static void grant(BasicPermissionContainer c, String identity, String node, boolean allow) {
        Map<String, Entry> perms = c.permissionSet.computeIfAbsent(identity, k -> new HashMap<>());
        perms.put(node, new BasicEntry(node, allow, 10));
    }

    private static final String ALICE = "alice";
    private static final String BOB = "bob";

    // -----------------------------------------------------------------------------------
    // The gap this class exists to fill
    // -----------------------------------------------------------------------------------

    @Test
    @DisplayName("The container alone cannot match a wildcard -- this is the gap being filled")
    void containerCannotMatchWildcards() {
        BasicPermissionContainer c = container();
        grant(c, ALICE, "CXNET.broadcast.*", true);

        // Exact-match semantics: the stored wildcard key matches only itself.
        assertTrue(c.allowed(ALICE, "CXNET.broadcast.*"),
                "the container matches the literal wildcard string");
        assertFalse(c.allowed(ALICE, "CXNET.broadcast.DIRECTORY_UPDATE"),
                "but it cannot expand that wildcard -- hence the resolver");

        // The resolver closes exactly that gap and nothing else.
        assertTrue(PermissionResolver.allowed(c, ALICE, "CXNET.broadcast.DIRECTORY_UPDATE"));
    }

    @Test
    @DisplayName("Default deny when no grant mentions the node")
    void defaultDeny() {
        BasicPermissionContainer c = container();
        assertFalse(PermissionResolver.allowed(c, ALICE, "CXNET.broadcast.ANYTHING"));
        assertEquals(PermissionResolver.Verdict.UNSET,
                PermissionResolver.resolve(c, ALICE, "CXNET.broadcast.ANYTHING"));
    }

    // -----------------------------------------------------------------------------------
    // Specificity
    // -----------------------------------------------------------------------------------

    @Test
    @DisplayName("Candidate keys are generated most specific first")
    void candidateOrder() {
        List<String> candidates = PermissionResolver.candidates("CXNET.broadcast.DIRECTORY_UPDATE");
        assertEquals(List.of(
                "CXNET.broadcast.DIRECTORY_UPDATE",
                "CXNET.broadcast.*",
                "CXNET.*",
                "*"), candidates);
    }

    @Test
    @DisplayName("Most specific match wins over a broader one")
    void mostSpecificWins() {
        BasicPermissionContainer c = container();
        grant(c, ALICE, "CXNET.*", true);
        grant(c, ALICE, "CXNET.broadcast.SECRET", false);

        assertTrue(PermissionResolver.allowed(c, ALICE, "CXNET.broadcast.OTHER"),
                "covered by the broad allow");
        assertFalse(PermissionResolver.allowed(c, ALICE, "CXNET.broadcast.SECRET"),
                "the specific deny must win");
    }

    @Test
    @DisplayName("Explicit deny beats a less specific allow")
    void explicitDenyBeatsBroaderAllow() {
        BasicPermissionContainer c = container();
        grant(c, ALICE, "*", true);
        grant(c, ALICE, "CXNET.broadcast.*", false);

        assertFalse(PermissionResolver.allowed(c, ALICE, "CXNET.broadcast.DIRECTORY_UPDATE"));
        assertTrue(PermissionResolver.allowed(c, ALICE, "net1.broadcast.SYNC"),
                "the global allow still applies outside the denied subtree");
    }

    @Test
    @DisplayName("An allow can re-open a subtree denied more broadly")
    void specificAllowInsideBroaderDeny() {
        BasicPermissionContainer c = container();
        grant(c, ALICE, "CXNET.broadcast.*", false);
        grant(c, ALICE, "CXNET.broadcast.DIRECTORY_UPDATE", true);

        assertTrue(PermissionResolver.allowed(c, ALICE, "CXNET.broadcast.DIRECTORY_UPDATE"));
        assertFalse(PermissionResolver.allowed(c, ALICE, "CXNET.broadcast.ELSE"));
    }

    // -----------------------------------------------------------------------------------
    // DEFAULT_NODE fallback
    // -----------------------------------------------------------------------------------

    @Test
    @DisplayName("DEFAULT_NODE supplies the network default when the identity has no rule")
    void defaultNodeFallback() {
        BasicPermissionContainer c = container();
        grant(c, PermissionResolver.DEFAULT_NODE, "net1.relay.*", true);

        assertTrue(PermissionResolver.allowed(c, ALICE, "net1.relay.SYNC"),
                "an open network's members inherit the default");
        assertTrue(PermissionResolver.allowed(c, BOB, "net1.relay.SYNC"));
        assertFalse(PermissionResolver.allowed(c, ALICE, "net1.broadcast.SYNC"),
                "the default only covers what it grants");
    }

    @Test
    @DisplayName("A per-identity deny overrides a permissive network default")
    void identityDenyOverridesDefault() {
        BasicPermissionContainer c = container();
        grant(c, PermissionResolver.DEFAULT_NODE, "net1.relay.*", true);
        grant(c, BOB, "net1.relay.*", false);

        assertTrue(PermissionResolver.allowed(c, ALICE, "net1.relay.SYNC"));
        assertFalse(PermissionResolver.allowed(c, BOB, "net1.relay.SYNC"),
                "this is the lever for killing one misbehaving relay on an open network");
    }

    @Test
    @DisplayName("A per-identity allow works where the default denies")
    void identityAllowOverridesDefault() {
        BasicPermissionContainer c = container();
        grant(c, PermissionResolver.DEFAULT_NODE, "CXNET.broadcast.*", false);
        grant(c, ALICE, "CXNET.broadcast.*", true);

        assertTrue(PermissionResolver.allowed(c, ALICE, "CXNET.broadcast.DIRECTORY_UPDATE"),
                "a granted backend broadcasts on a closed CXNET");
        assertFalse(PermissionResolver.allowed(c, BOB, "CXNET.broadcast.DIRECTORY_UPDATE"));
    }

    // -----------------------------------------------------------------------------------
    // Plugin namespacing
    // -----------------------------------------------------------------------------------

    @Test
    @DisplayName("Plugin event types are namespaced, so one plugin's grant does not leak to another")
    void pluginNamespacing() {
        BasicPermissionContainer c = container();
        grant(c, ALICE, "net1.broadcast.myplugin.*", true);

        assertTrue(PermissionResolver.allowed(c, ALICE, "net1.broadcast.myplugin.SYNC"));
        assertTrue(PermissionResolver.allowed(c, ALICE, "net1.broadcast.myplugin.PING"));
        assertFalse(PermissionResolver.allowed(c, ALICE, "net1.broadcast.otherplugin.SYNC"),
                "a grant on one plugin's namespace must not cover another's");
    }

    @Test
    @DisplayName("A network-wide broadcast grant does cover plugin events beneath it")
    void networkGrantCoversPlugins() {
        BasicPermissionContainer c = container();
        grant(c, ALICE, "net1.broadcast.*", true);
        assertTrue(PermissionResolver.allowed(c, ALICE, "net1.broadcast.myplugin.SYNC"));
    }

    // -----------------------------------------------------------------------------------
    // SYSTEM bypass
    // -----------------------------------------------------------------------------------

    @Test
    @DisplayName("A SYSTEM identity is rejected rather than hitting the container's bypass")
    void systemIdentityRejected() {
        BasicPermissionContainer c = container();

        // The underlying container short-circuits SYSTEM to true for any permission at all.
        assertTrue(c.allowed("SYSTEM", "anything.at.all"),
                "this bypass is why the resolver needs a real runtime guard, not an assert");

        assertFalse(PermissionResolver.allowed(c, "SYSTEM", "CXNET.broadcast.ANYTHING"));
        assertEquals(PermissionResolver.Verdict.DENY,
                PermissionResolver.resolve(c, "SYSTEM", "CXNET.broadcast.ANYTHING"));
        assertFalse(PermissionResolver.allowed(c, "xxSYSTEMxx", "CXNET.broadcast.ANYTHING"),
                "substring matches are refused too, matching the existing assert's intent");
    }

    // -----------------------------------------------------------------------------------
    // Edges
    // -----------------------------------------------------------------------------------

    @Test
    @DisplayName("Null and empty inputs resolve to UNSET rather than throwing")
    void nullSafety() {
        BasicPermissionContainer c = container();
        assertEquals(PermissionResolver.Verdict.UNSET, PermissionResolver.resolve(null, ALICE, "a.b"));
        assertEquals(PermissionResolver.Verdict.UNSET, PermissionResolver.resolve(c, null, "a.b"));
        assertEquals(PermissionResolver.Verdict.UNSET, PermissionResolver.resolve(c, ALICE, null));
        assertEquals(PermissionResolver.Verdict.UNSET, PermissionResolver.resolve(c, ALICE, ""));
        assertFalse(PermissionResolver.allowed(c, ALICE, null));
    }

    @Test
    @DisplayName("A single-segment node still resolves against a global wildcard")
    void singleSegmentNode() {
        BasicPermissionContainer c = container();
        grant(c, ALICE, "*", true);
        assertTrue(PermissionResolver.allowed(c, ALICE, "CXNET"));
    }

    @Test
    @DisplayName("node() joins parts and skips empties")
    void nodeBuilder() {
        assertEquals("CXNET.broadcast.SYNC", PermissionResolver.node("CXNET", "broadcast", "SYNC"));
        assertEquals("CXNET.broadcast", PermissionResolver.node("CXNET", "broadcast", null));
        assertEquals("CXNET.broadcast", PermissionResolver.node("CXNET", "broadcast", ""));
    }

    @Test
    @DisplayName("Resolution is generic: relay and directory nodes need no special handling")
    void genericNodes() {
        BasicPermissionContainer c = container();
        grant(c, ALICE, "net1.relay.*", true);
        grant(c, ALICE, "net1.directory.write", true);

        assertTrue(PermissionResolver.allowed(c, ALICE, "net1.relay.ANYTHING"));
        assertTrue(PermissionResolver.allowed(c, ALICE, "net1.directory.write"));
        assertFalse(PermissionResolver.allowed(c, ALICE, "net1.directory.read"));
    }
}
