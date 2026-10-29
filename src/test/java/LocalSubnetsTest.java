import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import us.anvildevelopment.cxnet.network.nodemesh.LocalSubnets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Local subnet detection.
 *
 * The machine's real interfaces are whatever they are, so these assert the properties that must
 * hold on any host rather than specific subnets: CGNAT and public space are never local, the
 * machine's own address is, and malformed input is rejected rather than throwing.
 */
public class LocalSubnetsTest {

    @Test
    @DisplayName("CGNAT space is never local, even though it looks private")
    void cgnatIsNotLocal() {
        assertFalse(LocalSubnets.isLocalAddress("100.64.0.1"));
        assertFalse(LocalSubnets.isLocalAddress("100.100.50.50:9000"));
        assertFalse(LocalSubnets.isLocalAddress("100.127.255.254"));
    }

    @Test
    @DisplayName("Addresses just outside CGNAT are not excluded by the CGNAT rule itself")
    void cgnatBoundaries() {
        // 100.63.x and 100.128.x fall outside 100.64.0.0/10. Whether they are local depends on
        // this host's interfaces, so only assert the call is well-defined and does not throw.
        assertDoesNotThrow(() -> LocalSubnets.isLocalAddress("100.63.255.255"));
        assertDoesNotThrow(() -> LocalSubnets.isLocalAddress("100.128.0.0"));
    }

    @Test
    @DisplayName("Public addresses are not local")
    void publicIsNotLocal() {
        assertFalse(LocalSubnets.isLocalAddress("8.8.8.8:53"));
        assertFalse(LocalSubnets.isLocalAddress("1.1.1.1"));
        assertFalse(LocalSubnets.isLocalAddress("198.51.100.7:7000"));
    }

    @Test
    @DisplayName("Loopback is not treated as a local subnet peer")
    void loopbackIsNotLocal() {
        assertFalse(LocalSubnets.isLocalAddress("127.0.0.1:5000"));
    }

    @Test
    @DisplayName("Bridge and malformed addresses are rejected without throwing")
    void malformedInput() {
        assertFalse(LocalSubnets.isLocalAddress(null));
        assertFalse(LocalSubnets.isLocalAddress(""));
        assertFalse(LocalSubnets.isLocalAddress("cxHTTP1:https://bridge.example/x"));
        assertFalse(LocalSubnets.isLocalAddress("not-an-address"));
        assertFalse(LocalSubnets.isLocalAddress("10.0.0"));
        assertFalse(LocalSubnets.isLocalAddress("999.1.1.1"));
        assertFalse(LocalSubnets.isLocalAddress("10.0.0.-1"));
        assertFalse(LocalSubnets.isLocalAddress("::1"));
    }

    @Test
    @DisplayName("This machine's own non-loopback address is on a local subnet")
    void ownAddressIsLocal() {
        String own = us.anvildevelopment.cxnet.network.nodemesh.LANScanner.getLocalIP();
        if (own == null) return; // no usable interface in this environment
        assertTrue(LocalSubnets.isLocalAddress(own),
                "own interface address should match an interface subnet; matched: "
                        + LocalSubnets.describe());
        assertTrue(LocalSubnets.isLocalAddress(own + ":5000"), "a :port must not change the verdict");
    }

    @Test
    @DisplayName("invalidate() forces a re-read without breaking later checks")
    void invalidateIsSafe() {
        LocalSubnets.isLocalAddress("10.0.0.1");
        LocalSubnets.invalidate();
        assertDoesNotThrow(() -> LocalSubnets.isLocalAddress("10.0.0.1"));
        assertNotNull(LocalSubnets.describe());
    }
}
