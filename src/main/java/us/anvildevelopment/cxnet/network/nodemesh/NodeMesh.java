package us.anvildevelopment.cxnet.network.nodemesh;

import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import org.jetbrains.annotations.NotNull;
import us.anvildevelopment.cxnet.ConnectX;
import us.anvildevelopment.cxnet.Permission;
import us.anvildevelopment.cxnet.analytics.AnalyticData;
import us.anvildevelopment.cxnet.analytics.Analytics;
import us.anvildevelopment.cxnet.crypt.core.exceptions.DecryptionFailureException;
import us.anvildevelopment.cxnet.crypt.pgpainless.PainlessCryptProvider;
import us.anvildevelopment.cxnet.edge.NetworkBlock;
import us.anvildevelopment.cxnet.edge.NetworkRecord;
import us.anvildevelopment.cxnet.io.IOThread;
import us.anvildevelopment.cxnet.network.*;
import us.anvildevelopment.cxnet.network.events.*;
import us.anvildevelopment.cxnet.network.threads.EventProcessor;
import us.anvildevelopment.cxnet.network.threads.OutputProcessor;
import us.anvildevelopment.cxnet.network.threads.RetryProcessor;
import us.anvildevelopment.cxnet.network.threads.SocketWatcher;
import org.bouncycastle.openpgp.PGPPublicKeyRing;
import org.pgpainless.decryption_verification.MessageMetadata;
import us.anvildevelopment.util.tools.permissions.BasicEntry;
import us.anvildevelopment.util.tools.permissions.Entry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

public class NodeMesh {
    private static final Logger log = LoggerFactory.getLogger(NodeMesh.class);
    // Global static fields for security (shared across all peers)
    public static ConcurrentHashMap<String, Integer> timeout = new ConcurrentHashMap<>();
    public static ConcurrentHashMap<String, String> blacklist = new ConcurrentHashMap<>();
    public Set<String> ownAddresses = ConcurrentHashMap.newKeySet();
    public static ThreadPoolExecutor threadPool = (ThreadPoolExecutor) Executors.newFixedThreadPool(NodeConfig.pThreads);

    // Instance fields (per-peer)
    public ServerSocket serverSocket;
    public ConcurrentHashMap<String, Set<String>> transmissionIDMap = new ConcurrentHashMap<>();
    /** Guards against two IOThreads concurrently importing the same unknown peer (NewNode/CXHELLO). */
    private final ConcurrentHashMap<String, Boolean> inFlightImports = new ConcurrentHashMap<>();
    public PeerDirectory peerDirectory = null;
    public InConnectionManager in;
    public Connections connections = new Connections();
    public ConnectX connectX;

    /**
     * IP rate limiting for PEER_LIST_REQUEST
     * Tracks timestamps of requests per IP address
     * Rate limit: 3 requests per IP per hour
     * Key: IP address
     * Value: List of request timestamps (in milliseconds)
     */
    private final ConcurrentHashMap<String, List<Long>> peerRequestTimestamps = new ConcurrentHashMap<>();
    private static final int PEER_REQUEST_LIMIT = 3;
    private static final long PEER_REQUEST_WINDOW_MS = 60 * 60 * 1000; // 1 hour in milliseconds

    //Initial object must be signed by initiator
    //If it is a global resource encrypting for only the next recipient node is acceptable
    //If it is not a global resource it must be encrypted using only the end recipients key then re encrypted for transport
    public NodeMesh(ConnectX connectX) {
        this.connectX = connectX;
        this.peerDirectory = new PeerDirectory(connectX);
    }

    /**
     * Initialize and start all network processing threads
     * @param connectX ConnectX instance for thread context
     * @param port Port number for ServerSocket
     * @param outController Outbound connection controller
     * @return NodeMesh instance
     */
    public static NodeMesh initializeNetwork(ConnectX connectX, int port, OutConnectionController outController) throws IOException {
        // Create NodeMesh instance
        NodeMesh nodeMesh = new NodeMesh(connectX);

        // Initialize InConnectionManager with ServerSocket
        nodeMesh.in = new InConnectionManager(port, nodeMesh);

        // Start IO worker threads for job queue processing
        for (int i = 0; i < NodeConfig.ioThreads; i++) {
            IOThread ioWorker = new IOThread(NodeConfig.IO_THREAD_SLEEP, connectX, nodeMesh);
            ioWorker.run = true; // Enable the worker
            Thread ioThread = new Thread(ioWorker);
            ioThread.setName("IOThread-" + i);
            ioThread.start();
        }

        // Start SocketWatcher for incoming connections
        Thread socketWatcher = new Thread(new SocketWatcher(connectX, nodeMesh));
        socketWatcher.setName("SocketWatcher");
        socketWatcher.start();

        // Start EventProcessor for processing eventQueue
        Thread eventProcessor = new Thread(new EventProcessor(nodeMesh));
        eventProcessor.setName("EventProcessor");
        eventProcessor.start();

        // Start multiple OutputProcessor threads for parallel event processing
        // This significantly improves CXHELLO discovery timing by processing events concurrently
        log.info("[NodeMesh] Starting " + NodeConfig.outputProcessorThreads + " OutputProcessor threads");
        for (int i = 0; i < NodeConfig.outputProcessorThreads; i++) {
            Thread outputProcessor = new Thread(new OutputProcessor(outController));
            outputProcessor.setName("OutputProcessor-" + i);
            outputProcessor.start();
        }

        // Start RetryProcessor for handling failed events with exponential backoff
        Thread retryProcessor = new Thread(new RetryProcessor(outController));
        retryProcessor.setName("RetryProcessor");
        retryProcessor.start();

        return nodeMesh;
    }
    public void processNetworkInput(InputStream is, Socket socket) throws IOException, DecryptionFailureException, ClassNotFoundException, UnauthorizedNetworkConnectivityException {
        //TODO optimize streams
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        //TODO max size
        NetworkContainer nc = null;
        NetworkEvent ne;
        ByteArrayOutputStream baoss = new ByteArrayOutputStream();
        ByteArrayInputStream bais;
        String networkEvent = "";
        InputBundle ib;

        // Store socket address for error handling and LAN discovery (before socket might be closed)
        String socketAddress = (socket != null && socket.getInetAddress() != null)
            ? socket.getInetAddress().getHostAddress() : null;
        int socketPort = (socket != null) ? socket.getPort() : 0;

        // SECURITY FIX: NetworkContainer signature verification
        // NetworkContainers are signed by the transmitter to prove their identity (nc.iD)
        // We MUST verify this signature before trusting any container fields

        // Step 1: Read the signed NetworkContainer bytes from socket
        ByteArrayOutputStream signedContainerBytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int bytesRead;
        while ((bytesRead = is.read(buffer)) != -1) {
            signedContainerBytes.write(buffer, 0, bytesRead);
        }
        is.close();

        // Step 2: Strip signature to peek at transmitter ID (nc.iD) - NOT YET VERIFIED
        ByteArrayInputStream peekStream = new ByteArrayInputStream(signedContainerBytes.toByteArray());
        ByteArrayOutputStream peekBaos = new ByteArrayOutputStream();
        connectX.encryptionProvider.stripSignature(peekStream, peekBaos);
        String unverifiedContainerJson = peekBaos.toString(StandardCharsets.UTF_8);
        peekStream.close();
        peekBaos.close();

        // Deserialize UNVERIFIED container to extract transmitter ID
        try {
            log.debug("[NodeMesh] [STEP-1] Stripped outer container ({} raw bytes), deserializing", signedContainerBytes.size());
            log.debug("[NodeMesh] [STEP-1b] unverifiedContainerJson length={}, preview='{}'",
                unverifiedContainerJson.length(),
                unverifiedContainerJson.length() > 120 ? unverifiedContainerJson.substring(0, 120) : unverifiedContainerJson);
            NetworkContainer unverifiedContainer = (NetworkContainer) ConnectX.deserialize("cxJSON1", unverifiedContainerJson, NetworkContainer.class);
            log.debug("[NodeMesh] [STEP-2] Deserialized container: iD={}, se={}, nc.e={}",
                unverifiedContainer.iD, unverifiedContainer.se,
                (unverifiedContainer.e != null ? unverifiedContainer.e.length + " bytes" : "null"));

            if (unverifiedContainer.iD == null) {
                // No transmitter ID - check if this is an unauthenticated message (CXHELLO/NewNode)
                // These are the ONLY messages allowed without pre-known sender identity
                // We'll handle verification after importing the public key below
                Analytics.addData(AnalyticData.Tear, "NetworkContainer missing transmitter ID");
                log.info("NetworkContainer missing transmitter ID");
                throw new DecryptionFailureException();
            }

            ConnectX.checkSafety(unverifiedContainer.iD);

            log.debug("[NodeMesh] [STEP-3] Self-check: ownID={}, transmitterID={}", connectX.getOwnID(), unverifiedContainer.iD);
            // Self-rejection: drop any message claiming to be from our own cxID
            if (connectX.getOwnID().equals(unverifiedContainer.iD)) {
                if (socket != null) {
                    if (socket.getRemoteSocketAddress() != null)
                        ownAddresses.add(socket.getRemoteSocketAddress().toString());
                    socket.close();
                }
                return;
            }

            if (!ConnectX.isProviderPresent(unverifiedContainer.se)) {
                if (socket != null) socket.close();
                Analytics.addData(AnalyticData.Tear, "Unsupported serialization method " + unverifiedContainer.se);
                return;
            }

            // Step 3: Check if we have the transmitter's public key
            log.debug("[NodeMesh] [STEP-4] Checking public key for transmitter {}", unverifiedContainer.iD);
            boolean hasPublicKey = connectX.encryptionProvider.cacheCert(unverifiedContainer.iD, false, false, connectX);
            log.debug("[NodeMesh] [STEP-5] hasPublicKey={} for {}", hasPublicKey, unverifiedContainer.iD);

            if (!hasPublicKey) {
                // Unknown sender - ONLY CXHELLO and NewNode are allowed from unknown senders
                // Peek at event type to check if this is an unauthenticated bootstrap message
                ByteArrayInputStream eventPeekStream = new ByteArrayInputStream(unverifiedContainer.e);
                ByteArrayOutputStream eventPeekBaos = new ByteArrayOutputStream();
                connectX.encryptionProvider.stripSignature(eventPeekStream, eventPeekBaos);
                String eventJson = eventPeekBaos.toString(StandardCharsets.UTF_8);
                NetworkEvent peekedEvent = (NetworkEvent) ConnectX.deserialize(unverifiedContainer.se, eventJson, NetworkEvent.class);
                eventPeekStream.close();
                eventPeekBaos.close();

                log.debug("[NodeMesh] [STEP-6] Peeked inner event type: {}", peekedEvent.eT);
                if (peekedEvent.eT == null || !(peekedEvent.eT.equals("NewNode") || peekedEvent.eT.equals("CXHELLO") || peekedEvent.eT.equals("CXHELLO_RESPONSE") || peekedEvent.eT.equals("SEED_REQUEST") || peekedEvent.eT.equals("PeerFinding"))) {
                    // NOT a bootstrap message from unknown sender - REJECT
                    if (socket != null) socket.close();
                    Analytics.addData(AnalyticData.Tear, "Message from unknown sender " + unverifiedContainer.iD +
                                    " (event: " + peekedEvent.eT + ") - only CXHELLO/NewNode allowed");
                    log.info("[SECURITY] Rejected " + peekedEvent.eT + " from unknown sender " +
                                     unverifiedContainer.iD.substring(0, 8));
                    return;
                }

                // This IS a CXHELLO or NewNode - extract and import public key BEFORE verification
                log.info("[NodeMesh] Processing " + peekedEvent.eT + " from unknown sender (will import key first)");
                // NOTE: Key import and verification happens below in the NewNode/CXHELLO handling section
                // For now, we skip container verification and process the event
                // The event handler will import the key and THEN verify the container signature

                // Use the unverified container for now (will be verified after key import)
                log.debug("[NodeMesh] [STEP-7] Unknown sender path accepted, nc.iD={}", unverifiedContainer.iD);
                nc = unverifiedContainer;
                baos.close();
            } else {
                // Step 4: We have the public key - VERIFY NetworkContainer signature
                ByteArrayInputStream verifyStream = new ByteArrayInputStream(signedContainerBytes.toByteArray());
                ByteArrayOutputStream verifiedBaos = new ByteArrayOutputStream();
                boolean verified = connectX.encryptionProvider.verifyAndStrip(verifyStream, verifiedBaos, unverifiedContainer.iD);
                verifyStream.close();

                if (!verified) {
                    // Signature verification FAILED - transmitter ID is SPOOFED
                    if (socket != null) socket.close();
                    Analytics.addData(AnalyticData.Tear, "NetworkContainer signature verification FAILED for " + unverifiedContainer.iD);
                    log.info("[SECURITY] NetworkContainer signature verification FAILED - rejecting message from " +
                                     unverifiedContainer.iD.substring(0, 8));
                    throw new DecryptionFailureException();
                }

                // Signature VERIFIED - we can now trust nc.iD and all container fields
                String verifiedContainerJson = verifiedBaos.toString(StandardCharsets.UTF_8);
                nc = (NetworkContainer) ConnectX.deserialize("cxJSON1", verifiedContainerJson, NetworkContainer.class);
                verifiedBaos.close();
                baos.close();

                log.info("[SECURITY] ✓ NetworkContainer signature VERIFIED for " + nc.iD.substring(0, 8));
            }
            log.debug("[NodeMesh] [STEP-8] nc.e={}, nc.s={}", (nc.e != null ? nc.e.length + " bytes" : "null"), nc.s);
            bais = new ByteArrayInputStream(nc.e);
            Object o1;
            if (nc.s) {
                //TODO verification of full encrypt
                throw new DecryptionFailureException();
                //o1 = connectX.encryptionProvider.decrypt(bais, baoss);
            } else {
                // Check if transmitter ID is present
                if (nc.iD == null) {
                    Analytics.addData(AnalyticData.Tear, "NetworkContainer missing transmitter ID");
                    throw new DecryptionFailureException();
                }

                // TODO: PERFORMANCE - This NewNode handling is inefficient (2-3x signature operations)
                // We strip signature once to peek, then verify again after importing node.
                // This is ONLY acceptable because NewNode is the only event that requires this.
                // If more events need pre-verification processing, we need to redesign this
                // to avoid multiple signature operations (perhaps return signature data from strip).
                // For production: Monitor if other event types need similar handling.

                // SPECIAL HANDLING FOR NewNode AND CXHELLO EVENTS
                // Strip signature first to check event type
                ByteArrayOutputStream stripBaos = new ByteArrayOutputStream();
                connectX.encryptionProvider.stripSignature(bais, stripBaos);
                String strippedEventJson = stripBaos.toString(StandardCharsets.UTF_8);
                stripBaos.close(); // Close the strip stream
                NetworkEvent parsedEvent = (NetworkEvent) ConnectX.deserialize(nc.se, strippedEventJson, NetworkEvent.class);



                /// At this point in the code path we should have already done the basic input bundle processing
            /// we will create it now so we can implement new features of InputBundle
            ib = new InputBundle(parsedEvent, nc);

                log.debug("[NodeMesh] [STEP-9] Stripped inner event, parsedEvent.eT={}", parsedEvent.eT);
                // Check if this is a NewNode or CXHELLO event (both need public key import before verification)
                if (parsedEvent.eT != null && (parsedEvent.eT.equals("NewNode") || parsedEvent.eT.equals("CXHELLO"))) {
                    if (nc.iD.equals(connectX.getOwnID())) {
                        log.info("[NodeMesh] Rejecting peer finding from self");
                        assert socket != null;
                        ownAddresses.add(socket.getLocalSocketAddress().toString());
                        return;
                    }
                    // Guard: prevent two concurrent IOThreads from importing the same peer simultaneously
                    if (inFlightImports.putIfAbsent(nc.iD, Boolean.TRUE) != null) {
                        log.info("[NodeMesh] Skipping duplicate in-flight import for " + nc.iD.substring(0, 8));
                        return;
                    }
                    log.info("[NodeMesh] Processing " + parsedEvent.eT + " event from " + nc.iD);

                    // Import node BEFORE verification (we need the public key)
                    Node importedNode = null;
                    byte[] signedNodeBlobForPersistence = null; // For CXHELLO persistence
                    if (parsedEvent.d != null && parsedEvent.d.length > 0) {
                        try {
                            Node newNode;

                            // Extract Node from payload based on event type
                            log.debug("[NodeMesh] [STEP-10] Entering {}-specific payload processing", parsedEvent.eT);
                            if (parsedEvent.eT.equals("CXHELLO")) {
                                // CXHELLO payload is SIGNED - strip signature before deserializing
                                ByteArrayInputStream signedCXHelloInput = new ByteArrayInputStream(parsedEvent.d);
                                ByteArrayOutputStream strippedCXHelloOutput = new ByteArrayOutputStream();
                                connectX.encryptionProvider.stripSignature(signedCXHelloInput, strippedCXHelloOutput);
                                String payloadJson = strippedCXHelloOutput.toString(StandardCharsets.UTF_8);
                                signedCXHelloInput.close();
                                strippedCXHelloOutput.close();


                                // CXHELLO payload: CXHello class with signedNode byte array
                                CXHello cxhelloData =
                                    (CXHello) ConnectX.deserialize("cxJSON1", payloadJson,
                                        CXHello.class);

                                //SET OBJECT IN INPUT BUNDLE
                                //IMPORTANT
                                ib.object = cxhelloData;

                                // Extract signed Node blob from CXHELLO payload
                                byte[] signedNodeBlob = cxhelloData.signedNode;
                                if (signedNodeBlob != null) {
                                    // SECURITY: For unknown peers, we must strip BEFORE verification
                                    // (we need their public key from the Node object first)
                                    // Pattern: strip -> deserialize -> import -> verify -> rollback if verification fails

                                    // Step 1: Strip signature WITHOUT verification (we don't have their key yet for unknown peers)
                                    ByteArrayInputStream signedNodeInput = new ByteArrayInputStream(signedNodeBlob);
                                    ByteArrayOutputStream nodeOutput = new ByteArrayOutputStream();
                                    connectX.encryptionProvider.stripSignature(signedNodeInput, nodeOutput);
                                    signedNodeInput.close();

                                    // Step 2: Deserialize to Node object (contains public key)
                                    String nodeJson = nodeOutput.toString(StandardCharsets.UTF_8);
                                    newNode = (Node) ConnectX.deserialize("cxJSON1", nodeJson, Node.class);
                                    nodeOutput.close();

                                    // Save signed blob for .cxi persistence AND later verification
                                    signedNodeBlobForPersistence = signedNodeBlob;

                                    log.info("[NodeMesh] CXHELLO: Extracted Node from unknown peer (will verify after import)");
                                } else {
                                    log.info("[NodeMesh] CXHELLO missing signedNode for " + nc.iD.substring(0, 8));
                                    newNode = null;
                                }
                            } else {
                                // NewNode payload is a SIGNED Node object (from EventBuilder.signData())
                                String originSender = parsedEvent.p != null ? parsedEvent.p.oCXID : null;

                                if (originSender == null) {
                                    log.info("[NodeMesh] NewNode missing oCXID in path");
                                    throw new DecryptionFailureException();
                                }

                                ByteArrayInputStream signedPayloadStream = new ByteArrayInputStream(parsedEvent.d);
                                ByteArrayOutputStream strippedPayloadStream = new ByteArrayOutputStream();

                                // Check if we have the sender's key cached (known sender vs unknown sender)
                                boolean isKnownSender = connectX.encryptionProvider.cacheCert(originSender, true, false, connectX);

                                if (isKnownSender) {
                                    // Known sender: verify and strip signature
                                    Object verifyResult = connectX.encryptionProvider.verifyAndStrip(
                                        signedPayloadStream, strippedPayloadStream, originSender);

                                    // verifyResult holds a Boolean (auto-boxed from boolean return). Cast to unbox.
                                    if (!((Boolean) verifyResult)) {
                                        log.info("[NodeMesh] NewNode payload signature verification FAILED for {}", originSender);
                                        throw new DecryptionFailureException();
                                    }
                                } else {
                                    log.info("[NodeMesh] Processing NewNode from unknown sender (will import key first)");
                                    // Unknown sender: strip signature WITHOUT verification (we'll verify container signature after import)
                                    connectX.encryptionProvider.stripSignature(signedPayloadStream, strippedPayloadStream);
                                }

                                String strippedJson = strippedPayloadStream.toString(StandardCharsets.UTF_8);
                                //Strip signature from Event Payload
                                ByteArrayInputStream baiss = new ByteArrayInputStream(strippedJson.getBytes(StandardCharsets.UTF_8));
                                ByteArrayOutputStream fullyStrippedEvent = new ByteArrayOutputStream();
                                connectX.encryptionProvider.stripSignature(baiss, fullyStrippedEvent);
                                String fullyStrippedJSON = fullyStrippedEvent.toString(StandardCharsets.UTF_8);
                                newNode = (Node) ConnectX.deserialize("cxJSON1", fullyStrippedJSON, Node.class);

                                signedPayloadStream.close();
                                strippedPayloadStream.close();
                            }

                            // SECURITY: Validate node data
                            if (newNode.cxID == null || newNode.publicKey == null) {
                                log.info("[NodeMesh] NewNode missing cxID or publicKey");
                                throw new DecryptionFailureException();
                            }

                            // SECURITY: Verify transmitter matches the node being added
                            if (!newNode.cxID.equals(nc.iD)) {
                                log.info("[NodeMesh] NewNode cxID mismatch: " + newNode.cxID + " vs " + nc.iD);
                                throw new DecryptionFailureException();
                            }

                            // SECURITY: Reject if node's public key matches our own (self or key theft)
                            String ownPubKey = connectX.encryptionProvider.getPublicKey();
                            if (ownPubKey != null && ownPubKey.equals(newNode.publicKey)) {
                                log.info("[NodeMesh] Rejecting node with same public key as self: " + newNode.cxID.substring(0, 8));
                                throw new DecryptionFailureException();
                            }

                            log.debug("[NodeMesh] [STEP-11] Node extracted: cxID={}, hasPublicKey={}, signedBlob={}",
                                (newNode != null ? newNode.cxID : "null"),
                                (newNode != null ? (newNode.publicKey != null) : false),
                                (signedNodeBlobForPersistence != null ? signedNodeBlobForPersistence.length + " bytes" : "null"));
                            // Import the node with signed blob for CXHELLO persistence
                            if (signedNodeBlobForPersistence != null) {
                                peerDirectory.addNode(newNode, signedNodeBlobForPersistence, connectX.cxRoot);
                                log.info("[NodeMesh] Imported and PERSISTED CXHELLO node: " + newNode.cxID.substring(0, 8));
                            } else {
                                peerDirectory.addNode(newNode);
                                log.info("[NodeMesh] Imported NewNode: " + newNode.cxID.substring(0, 8));
                            }
                            importedNode = newNode;

                            log.debug("[NodeMesh] [STEP-12] Node imported, verifying container signature for {}", nc.iD);
                            // Now VERIFY the signature using the imported public key
                            ByteArrayInputStream verifyBais = new ByteArrayInputStream(nc.e);
                            ByteArrayOutputStream verifyBaos = new ByteArrayOutputStream();
                            o1 = connectX.encryptionProvider.verifyAndStrip(verifyBais, verifyBaos, nc.iD);

                            log.debug("[NodeMesh] [STEP-13] verifyAndStrip result={} for {}", o1, nc.iD);
                            // o1 holds a Boolean (verifyAndStrip returns boolean, auto-boxed to Object).
                            // Boolean.FALSE is never null, so the old "== null" check never triggered on failure.
                            if (!((Boolean) o1)) {
                                log.info("[NodeMesh] NewNode/CXHELLO container signature verification FAILED for {}", nc.iD);
                                // Rollback: Remove the imported node (memory AND filesystem)
                                peerDirectory.removeNode(importedNode.cxID, connectX.cxRoot);
                                throw new DecryptionFailureException();
                            }
                            log.info("[NodeMesh] Container signature VERIFIED for {}", newNode.cxID);
                            verifyBais.close();
                            verifyBaos.close();

                            // For CXHELLO: Also verify the signedNodeBlob signature (now that we have the public key)
                            if (parsedEvent.eT.equals("CXHELLO") && signedNodeBlobForPersistence != null) {
                                ByteArrayInputStream signedBlobVerifyInput = new ByteArrayInputStream(signedNodeBlobForPersistence);
                                ByteArrayOutputStream signedBlobVerifyOutput = new ByteArrayOutputStream();
                                boolean blobVerified = connectX.encryptionProvider.verifyAndStrip(
                                    signedBlobVerifyInput, signedBlobVerifyOutput, nc.iD);
                                signedBlobVerifyInput.close();
                                signedBlobVerifyOutput.close();

                                if (!blobVerified) {
                                    log.info("[NodeMesh] CXHELLO signedNode verification FAILED for {}", nc.iD);
                                    // Rollback: Remove the imported node (memory AND filesystem)
                                    peerDirectory.removeNode(importedNode.cxID, connectX.cxRoot);
                                    throw new DecryptionFailureException();
                                }
                                log.info("[NodeMesh] CXHELLO signedNode signature VERIFIED for {}", newNode.cxID);
                            }

                        } catch (DecryptionFailureException e) {
                            throw e;
                        } catch (Exception e) {
                            log.error("[NodeMesh] Failed to process NewNode/CXHELLO", e);
                            if (importedNode != null) {
                                // Rollback: Remove the imported node (memory AND filesystem)
                                peerDirectory.removeNode(importedNode.cxID, connectX.cxRoot);
                            }
                            throw new DecryptionFailureException();
                        }
                    } else {
                        log.info("[NodeMesh] NewNode event has no data");
                        throw new DecryptionFailureException();
                    }

                    log.debug("[NodeMesh] [STEP-14] CXHELLO/NewNode processing complete, handing off to event dispatch");
                    // Use the already parsed event
                    ne = parsedEvent;
                    networkEvent = strippedEventJson;
                    inFlightImports.remove(nc.iD);

                } else {
                    // Standard event: Verify signature (we already have public key)
                    ByteArrayInputStream verifyBais = new ByteArrayInputStream(nc.e);
                    o1 = connectX.encryptionProvider.verifyAndStrip(verifyBais, baoss, nc.iD);
                    networkEvent = baoss.toString(StandardCharsets.UTF_8);
                    ne = (NetworkEvent) ConnectX.deserialize(nc.se, networkEvent, NetworkEvent.class);
                    verifyBais.close();

                    //Add o1 check
                    if (!(boolean) o1) {
                        log.info("[NodeMesh] Signature verification failure, rejecting event. 003");
                        return;
                    }

                    /// Verifies event data payload and strips it of cryptography for easy access later with InputBundle.readyObject()
                if (!parsedEvent.e2e) {
                    byte[] signedEventd = ne.d;
                    ByteArrayInputStream baiss = new ByteArrayInputStream(signedEventd);
                    ByteArrayOutputStream strippedJSON = new ByteArrayOutputStream();
                    if (connectX.encryptionProvider.verifyAndStrip(baiss, strippedJSON, parsedEvent.p.oCXID)) {
                        ib.verifiedObjectBytes = strippedJSON.toByteArray();
                    } else {
                        log.info("[NodeMesh] Internal event data verification failure, rejecting event. 004");
                        return;
                    }
                    baiss.close();
                    strippedJSON.close();
                    ///  AFTER this point all helper features should be available in InputBundle
                } else {
                    byte[] encryptedd = ne.d;
                    ByteArrayInputStream baiss = new ByteArrayInputStream(encryptedd);
                    ByteArrayOutputStream decryptedJSON = new ByteArrayOutputStream();
                    //TODO
                    //TODO Move PGP specific actions back to PainlessCryptProvider, will need another refactor
                    MessageMetadata o3 = (MessageMetadata) connectX.encryptionProvider.decrypt(baiss, decryptedJSON, ne.p.oCXID, true);
                    byte[] arr1 = null;
                    arr1 = decryptedJSON.toByteArray();
                    if (arr1 != null && arr1.length > 0) {
                        //TODO This is fine for testing, but, we need to consider better handling of E2E events when peers have not met. Possibly including origin node data in E2E coms
                        String senderCXID = ne.p.oCXID;
                        //TODO More PGP specific code
                        connectX.nodeMesh.peerDirectory.lookup(senderCXID, true, true);
                        PGPPublicKeyRing originPub = ((PainlessCryptProvider) connectX.encryptionProvider).certCache.get(senderCXID);

                        if (senderCXID != null && originPub != null && o3.isVerifiedInlineSignedBy(originPub)) {
                            ib.verifiedObjectBytes = arr1;
                        } else {
                            log.info("[NodeMesh] E2E Decryption validation failure, 005");
                            log.info(senderCXID);
                            assert originPub != null;
                            log.info(originPub.getPublicKey().toString());
                            log.info(String.valueOf(o3.isVerifiedInlineSignedBy(originPub)));
                        }
                    } else {
                        log.info("[NodeMesh] Internal event data verification failure, rejecting event. 006");
                        return;
                    }
                    baiss.close();
                    decryptedJSON.close();
                }

                }
            }
            bais.close();
            baoss.close();

            // Track seen peers (memory-efficient using seenCXIDs set)
            if (nc.iD != null) {
                peerDirectory.seenCXIDs.add(nc.iD);
            }

            // SECURITY: Validate CXNET and CX scope messages
            // Only CXNET backendSet or NMI can send CXNET or CX-scoped messages
            if (ne.p != null && ne.p.scope != null &&
                (ne.p.scope.equalsIgnoreCase("CXNET") || ne.p.scope.equalsIgnoreCase("CX"))) {
                CXNetwork cxnet = connectX.getNetwork("CXNET");
                boolean authorized = isAuthorized(cxnet, nc);

                if (!authorized) {
                    if (socket != null) socket.close();
                    Analytics.addData(AnalyticData.Tear, "Unauthorized " + ne.p.scope + " message from " + nc.iD);
                    return;
                }
            }
        } catch (Exception e) {
            if (e instanceof MismatchedInputException) {
                return;
            }
            // Release in-flight import guard if this exception came from a NewNode/CXHELLO path
            if (nc != null && nc.iD != null) inFlightImports.remove(nc.iD);
            log.error("[NodeMesh] processNetworkInput unhandled exception from {}", socketAddress, e);
            if (!NodeConfig.devMode && socketAddress != null) {
                if (NodeMesh.timeout.containsKey(socketAddress)) {
                    //NodeMesh.blacklist.put(socketAddress, "Protocol not respected");
                } else {
                    //NodeMesh.timeout.put(socketAddress, 1000);
                }
            }
            if (socket != null) socket.close();
            return;
        }

        // SECURITY: Event ID is MANDATORY for duplicate detection and replay prevention
        if (ne.iD == null || ne.iD.isEmpty()) {
            if (socket != null) socket.close();
            Analytics.addData(AnalyticData.Tear, "NetworkEvent missing required event ID");
            return; // Reject events without IDs
        }

        // DUPLICATE DETECTION: Check if we've already processed this event
        // In a P2P mesh network, the same event can arrive via multiple relay paths
        // We only want to process each unique event (ne.iD) exactly once
        // putIfAbsent is atomic - prevents two concurrent IOThreads from both passing for the same event ID
        Set<String> transmitters = ConcurrentHashMap.newKeySet();
        transmitters.add(nc.iD);
        Set<String> seenFrom = transmissionIDMap.putIfAbsent(ne.iD, transmitters);
        if (seenFrom != null) {
            // We've already seen this event - drop it (don't process or relay again)
            seenFrom.add(nc.iD); // thread-safe tracking add
            if (socket != null) socket.close();
            return; // Duplicate event - drop silently
        } else {
            // First time seeing this event - already recorded via putIfAbsent above

            // Cleanup old entries to prevent unbounded memory growth
            // Keep last 10000 event IDs
            if (transmissionIDMap.size() > 10000) {
                // Remove oldest 1000 entries (simple FIFO cleanup)
                Iterator<String> iterator = transmissionIDMap.keySet().iterator();
                int removeCount = 1000;
                while (iterator.hasNext() && removeCount > 0) {
                    iterator.next();
                    iterator.remove();
                    removeCount--;
                }
            }
        }

        // SECURITY: Whitelist Mode Enforcement
        // If network has whitelistMode enabled, only registered nodes can transmit
        // Registered nodes are tracked in c1 (Admin) chain via REGISTER_NODE events
        if (ne.p != null && ne.p.network != null && nc.iD != null) {
            CXNetwork targetNetwork = connectX.getNetwork(ne.p.network);
            if (targetNetwork != null && targetNetwork.configuration != null &&
                targetNetwork.configuration.whitelistMode != null &&
                targetNetwork.configuration.whitelistMode) {

                // Check if sender is registered (stored in local DataContainer)
                if (!connectX.dataContainer.isNodeRegistered(ne.p.network, nc.iD)) {
                    // Node not registered - reject transmission
                    if (socket != null) socket.close();
                    Analytics.addData(AnalyticData.Tear, "Whitelist rejection: " + nc.iD +
                                    " not registered to network " + ne.p.network);
                    log.info("[WHITELIST] Rejected transmission from unregistered node {} to whitelist network {}", nc.iD, ne.p.network);
                    return; // Drop the event - do not queue
                }
                // Node is registered - allow transmission
                log.info("[WHITELIST] Accepted transmission from registered node {} to network {}", nc.iD, ne.p.network);
            }
        }

        // Record source address for peer address mapping (passive discovery)
        // This enables multi-path routing (bridge + direct + LAN)
        if (nc.iD != null && socketAddress != null && socketPort > 0) {
            String peerAddress = socketAddress + ":" + socketPort;
            connectX.dataContainer.recordLocalPeer(nc.iD, peerAddress, false); // Fallback: socket remote port (ephemeral)
        }

        // For CXHELLO events, also record the LISTENING port from payload with priority
        try {
            EventType et = EventType.valueOf(ne.eT);
            if ((et == EventType.CXHELLO || et == EventType.CXHELLO_RESPONSE) && ne.d != null) {

                CXHello helloData;

                /// If for some reason old method is used try it, otherwise use new InputBundle features
                if (ib.object == null) {
                    /// Try new InputBundle features first
                if (!ib.readyObject(CXHello.class, ib.nc.se, connectX)) {
                    log.info("[NODEMESH] WARNING: Deprecated use of NodeMesh, Use new InputBundle features!");
                    // Both CXHELLO and CXHELLO_RESPONSE payloads are now PGP-signed
                    // Strip signature before deserializing
                    ByteArrayInputStream signedInput = new ByteArrayInputStream(ne.d);
                    ByteArrayOutputStream strippedOutput = new ByteArrayOutputStream();
                    connectX.encryptionProvider.stripSignature(signedInput, strippedOutput);
                    String helloJson = strippedOutput.toString(StandardCharsets.UTF_8);
                    signedInput.close();
                    strippedOutput.close();

                    // Deserialize CXHello class
                    helloData =
                            (CXHello) ConnectX.deserialize("cxJSON1", helloJson,
                                    CXHello.class);
                } else {
                    helloData = (CXHello) ib.object;
                }
                } else {
                    helloData = (CXHello) ib.object;
                }

                //NEW INPUT BUNDLE HANDLING!! I AM SAVED

                String requestPeerID = helloData.peerID;
                int requestPort = helloData.port;
                String peerProvidedAddress = helloData.address;
                if (!requestPeerID.equals(connectX.getSelf().cxID)) {

                    // Record addresses with PRIORITY (prepends to address list)
                    // Priority order (index 0 = highest): peer-provided > socket-based > ephemeral

                    // 1. Record socket-based address (socket IP + listening port from payload)
                    if (socketAddress != null && requestPort > 0) {
                        String socketBasedAddress = socketAddress + ":" + requestPort;
                        connectX.dataContainer.recordLocalPeer(requestPeerID, socketBasedAddress, true);
                        log.info("[processNetworkInput] " + et.name() + ": Recorded socket-based address " +
                                socketBasedAddress + " for " + requestPeerID.substring(0, 8));
                    }

                    // 2. Record peer-provided address (HIGHEST priority - will be at index 0)
                    if (peerProvidedAddress != null && !peerProvidedAddress.isEmpty()) {
                        connectX.dataContainer.recordLocalPeer(requestPeerID, peerProvidedAddress, true);
                        log.info("[processNetworkInput] " + et.name() + ": Recorded PEER-PROVIDED address (highest priority) " +
                                peerProvidedAddress + " for " + requestPeerID.substring(0, 8));
                    }
                }
            }
        } catch (Exception e) {
            // Ignore errors in CXHELLO/CXHELLO_RESPONSE parsing - event will still be processed normally
            e.printStackTrace();
        }

        connectX.eventQueue.add(ib);
    }

    private static boolean isAuthorized(CXNetwork cxnet, NetworkContainer nc) {
        boolean authorized = false;

        if (cxnet != null && nc.iD != null) {
            // Check if transmitter is in CXNET backendSet
            if (cxnet.configuration != null && cxnet.configuration.backendSet != null) {
                authorized = cxnet.configuration.backendSet.contains(nc.iD);
            }
            // Or check if transmitter is CXNET NMI
            if (!authorized && cxnet.configuration != null) {
                // NMI public key matches transmitter
                authorized = nc.iD.equals(cxnet.configuration.nmiPub);
            }
        }
        return authorized;
    }

    public void processEvent() throws IOException, DecryptionFailureException {
        InputBundle ib = connectX.eventQueue.poll();
        if (ib == null) return; // Queue is empty

            // Storage for decrypted event data (preserves original ib.ne.d)
            byte[] eventData;

            // Decrypt E2E encrypted events
            boolean e2eDecryptionFailed = false;
            if (ib.ne.e2e) {
                if (ib.verifiedObjectBytes != null && ib.verifiedObjectBytes.length > 0) {
                    // IOThread already decrypted and verified this in processNetworkInput
                    eventData = ib.verifiedObjectBytes;
                    log.info("[E2E] Received encrypted " + ib.ne.eT
                        + " from " + (ib.nc != null ? ib.nc.iD : "unknown")
                        + " (" + eventData.length + " bytes decrypted by IOThread)");
                } else {
                    try {
                        log.info("[E2E] Decrypting " + ib.ne.eT
                            + " from " + (ib.nc != null ? ib.nc.iD : "unknown"));
                        ByteArrayInputStream encryptedInput = new ByteArrayInputStream(ib.ne.d);
                        ByteArrayOutputStream decryptedOutput = new ByteArrayOutputStream();
                        connectX.encryptionProvider.decrypt(encryptedInput, decryptedOutput);
                        encryptedInput.close();
                        eventData = decryptedOutput.toByteArray();
                        decryptedOutput.close();
                        log.info("[E2E] Decrypted " + ib.ne.eT + " (" + eventData.length + " bytes)");
                    } catch (Exception e) {
                        log.info("[E2E] Failed to decrypt " + ib.ne.eT + " (not intended for us): " + e.getMessage());
                        e2eDecryptionFailed = true;
                        eventData = ib.ne.d;
                    }
                }
            } else {
                // No E2E encryption, use original data
                eventData = ib.ne.d;
            }

            log.debug(ib.nc.toString());
            log.debug(ib.ne.toString());
            log.debug(Arrays.toString(ib.signedEventBytes));
            EventType et = null;

            //ADDITIONAL VALIDATIONS
            //TODO
            //TODO VERY IMPORTANT, for now we are giving a blanket pass to CXHELLO events for security eval
            //This needs to be reviewed in the future
            //TODO
            if (EventType.valueOf(ib.ne.eT) != EventType.CXHELLO) {
                //Verify input bundle CXPATH
                if (ib.ne.p == null) {
                    Analytics.addData(AnalyticData.SecurityEvent, "Security failure, Missing CXPATH: " + ib.nc.oD + " Event Type: " + ib.ne.eT);
                    log.info("Security failure, Missing CXPATH: {} Event Type: {}", ib.nc.oD, ib.ne.eT);
                    return;
                }

                //VERIFY CONTAINER AND EVENT (Layer 1 + 2)
                //ib.ne - SIGNED BY ORIGIN UNMODIFIABLE
                //ib.nc - SIGNED BY LAST HOP, MODIFIABLE
                //It is critcal that they match to enforce multilayer cryptography
                if (!Objects.equals(ib.ne.p.oCXID, ib.nc.oD)) {
                    Analytics.addData(AnalyticData.SecurityEvent, "Security failure, mismatched CXIDs: " + ib.ne.p.oCXID + ", " + ib.nc.oD + " Event Type: " + ib.ne.eT);
                    log.info("Security failure, mismatched CXIDs: " + ib.ne.p.oCXID + ", " + ib.nc.oD + " Event Type: " + ib.ne.eT);
                    return;
                }
                //VERIFY INTERNAL DATA PAYLOAD
                // SECURITY: Use oCXID (origin sender) for signature verification, NOT cxID (destination)
                // This prevents spoofing attacks where attacker sets cxID to victim's ID
                if (!ib.ne.e2e) {
                    if (ib.verifiedObjectBytes != null && ib.verifiedObjectBytes.length > 0) {
                        // IOThread already verified and stripped this payload in processNetworkInput
                        eventData = ib.verifiedObjectBytes;
                        ib.strippedEventBytes = eventData;
                    } else {
                        ByteArrayInputStream signedInput = new ByteArrayInputStream(ib.ne.d);
                        ByteArrayOutputStream strippedOutput = new ByteArrayOutputStream();
                        if (!connectX.encryptionProvider.verifyAndStrip(signedInput, strippedOutput, ib.ne.p.oCXID)) {
                            Analytics.addData(AnalyticData.SecurityEvent, "Security failure, event payload data verification unsuccessful: " + ib.ne.p.oCXID + ", " + ib.nc.oD + " Event Type: " + ib.ne.eT);
                            log.info("Security failure, event payload data verification unsuccessful: " + ib.ne.p.oCXID + ", " + ib.nc.oD + " Event Type: " + ib.ne.eT);
                            return;
                        }
                        eventData = strippedOutput.toByteArray();
                        ib.strippedEventBytes = eventData;
                    }
                }
            }



            try {
                et = EventType.valueOf(ib.ne.eT);
            } catch (Exception ignored) {}
            if (et == null & !connectX.sendPluginEvent(ib, ib.ne.eT)) {
                Analytics.addData(AnalyticData.Tear, "Unsupported event - "+ib.ne.eT);
                log.info("UNABLE TO PROCESS UNKNOWN EVENT");
                if (NodeConfig.supportUnavailableServices) {
                    // Record unsupported events using the ORIGIN ID from NetworkEvent
                    if (ib.nc != null && ib.nc.iD != null && ib.signedEventBytes != null) {
                        connectX.Event(ib.ne, ib.ne.p.oCXID, ib.signedEventBytes);
                    }
                    //todo relay
                }
                return;
            }
            if (ib!=null) {
                //TODO non constant handling
                try {
                    switch (Objects.requireNonNull(et)) {
                        case NewNode:
                            // NewNode events are now sent with SIGNED Node blobs (via .signData())
                            // Extract and verify the signed blob, then save it for relay
                            byte[] signedNodeBlob = eventData;

                            // Verify and deserialize the signed Node blob (signed by origin sender)
                            Node node = (Node) connectX.getSignedObject(
                                ib.ne.p.oCXID, // Origin sender's cxID for signature verification
                                new ByteArrayInputStream(signedNodeBlob),
                                Node.class,
                                "cxJSON1"
                            );

                            if (node == null || node.cxID == null) {
                                log.info("[NodeMesh] NewNode verification failed - invalid signature");
                                return;
                            }

                            // Check if we already have this node
                            Node node1 = peerDirectory.lookup(node.cxID, true, true, connectX.cxRoot, connectX);
                            if (node1 != null) {
                                connectX.encryptionProvider.cacheCert(node1.cxID, true, false, connectX);
                                log.info("[NodeMesh] NewNode already exists: {}", node.cxID.substring(0, 8));
                                return;
                            }

                            // Add node WITH signed blob (preserves original signature for relay)
                            peerDirectory.addNode(node, signedNodeBlob, connectX.cxRoot);
                            log.info("[NodeMesh] Imported NewNode: {}", node.cxID.substring(0, 8));
                            log.info("[NodeMesh] NewNode signature VERIFIED and SAVED for relay");
                            break;

                        case CXHELLO:
                            // Node already verified and imported by processNetworkInput - look up and respond
                            log.info("[" + connectX.getOwnID() + "] CXHELLO request received from " + ib.nc.iD);
                            if (ib.nc.iD.equals(connectX.getOwnID())) {
                                log.info("[CXHELLO] Dropping CXHELLO from self in processEvent");
                                return;
                            }
                            Node requesterNode = peerDirectory.lookup(ib.nc.iD, true, true);
                            if (requesterNode != null) {
                                log.info("[CXHELLO] Peer discovered from " + ib.nc.iD.substring(0, 8));

                                // Sign our Node for .cxi persistence on receiver
                                String ourNodeJson = ConnectX.serialize("cxJSON1", connectX.getSelf());
                                ByteArrayInputStream nodeInput = new ByteArrayInputStream(ourNodeJson.getBytes(StandardCharsets.UTF_8));
                                ByteArrayOutputStream signedOutput = new ByteArrayOutputStream();
                                connectX.encryptionProvider.sign(nodeInput, signedOutput);
                                nodeInput.close();
                                byte[] ourSignedBlob = signedOutput.toByteArray();
                                signedOutput.close();

                                CXHello responsePayload = getResponsePayload(ourSignedBlob);

                                // Include NMI-signed bootstrap seed if the peer is requesting one
                                CXHello incomingHello = (ib.object instanceof CXHello) ? (CXHello) ib.object : null;
                                if (incomingHello != null && incomingHello.requestSeed
                                        && connectX.signedBootstrapSeed != null) {
                                    responsePayload.seedData = connectX.signedBootstrapSeed;
                                    log.info("[CXHELLO] Including bootstrap seed in response to {}", ib.nc.iD.substring(0, 8));
                                }

                                String responsePayloadJson = ConnectX.serialize("cxJSON1", responsePayload);

                                connectX.buildEvent(EventType.CXHELLO_RESPONSE, responsePayloadJson.getBytes(StandardCharsets.UTF_8))
                                    .toPeer(ib.nc.iD)
                                    .signData()
                                    .queue();

                                log.info("[CXHELLO] Queued CXHELLO_RESPONSE to {}", ib.nc.iD.substring(0, 8));
                            } else {
                                log.info("[CXHELLO] Node not found after import for {}", ib.nc.iD.substring(0, 8));
                            }
                            return; // Don't continue to fireEvent

                        case CXHELLO_RESPONSE:
                            // CXHELLO_RESPONSE payload: CXHello class with signed Node blob
                            log.info("[{}] CXHELLO_RESPONSE received from {}", connectX.getOwnID(), ib.nc.iD);
                            CXHello responseData;
                            /// NEW InputBundle features handling
                            if (ib.object != null) {
                                //Try new method first
                                if (!ib.readyObject(CXHello.class, ib.nc.se, connectX)) {
                                    log.info("[NodeMesh] Using depreciated method, use new InputBundle instead 002");
                                    String responsePayloadJson = new String(eventData, StandardCharsets.UTF_8);
                                    responseData =
                                            (CXHello) ConnectX.deserialize("cxJSON1", responsePayloadJson,
                                                    CXHello.class);
                                } else {
                                    responseData = (CXHello) ib.object;
                                }
                            } else {
                                responseData = (CXHello) ib.object;
                            }
                            // Get signed Node blob from CXHello payload
                            byte[] respSignedBlob = responseData.signedNode;

                            if (respSignedBlob != null) {
                                // Verify and deserialize the signed Node blob
                                ByteArrayInputStream respSignedInput = new ByteArrayInputStream(respSignedBlob);
                                ByteArrayOutputStream respVerifiedOutput = new ByteArrayOutputStream();
                                boolean respVerified = connectX.encryptionProvider.verifyAndStrip(respSignedInput, respVerifiedOutput, ib.nc.iD);
                                respSignedInput.close();

                                if (respVerified) {
                                    String respNodeJson = respVerifiedOutput.toString(StandardCharsets.UTF_8);
                                    Node responseNode = (Node) ConnectX.deserialize("cxJSON1", respNodeJson, Node.class);
                                    respVerifiedOutput.close();

                                    // Local address already recorded in processNetworkInput
                                    Node existingNode = peerDirectory.lookup(responseNode.cxID, true, true, connectX.cxRoot, connectX);
                                    if (existingNode != null) {
                                        connectX.encryptionProvider.cacheCert(existingNode.cxID, true, false, connectX);
                                        log.info("[CXHELLO_RESPONSE] Updated existing node: " + existingNode.cxID.substring(0, 8));
                                        return;
                                    }
                                    peerDirectory.addNode(responseNode, respSignedBlob, connectX.cxRoot);
                                    log.info("[CXHELLO_RESPONSE] Imported and PERSISTED new node: {}", responseNode.cxID.substring(0, 8));
                                } else {
                                    log.info("[CXHELLO_RESPONSE] Node signature verification FAILED for {}", ib.nc.iD.substring(0, 8));
                                }
                            }
                            // Apply NMI-signed bootstrap seed if included and we need one
                            if (responseData != null && responseData.seedData != null && connectX.bootstrapSearch) {
                                log.info("[CXHELLO_RESPONSE] Received bootstrap seed from {}, applying...", ib.nc.iD.substring(0, 8));
                                connectX.applySignedSeed(responseData.seedData);
                            }
                            return; // Don't continue to fireEvent
                    }
                } catch (Exception e) {
                    log.error("[NodeMesh] Cryptography failure on event {} From peer: {}", ib.ne.eT, ib.nc.iD);
                    log.debug(e.getMessage());
                    throw new DecryptionFailureException();
                }

                // After infrastructure handling, fire event to application layer
                // SKIP application layer if E2E decryption failed (event not intended for us)
                if (!e2eDecryptionFailed) {
                    fireEvent(ib);
                } else {
                    log.info("[E2E] Skipping application layer for undecryptable E2E event, will relay");
                }

                // DEBUG: Log auto-record attempt
                log.info("[Auto-Record DEBUG] Event type: " + ib.ne.eT + ", r=" + ib.ne.r +
                    ", nc=" + (ib.nc != null) + ", nc.iD=" + (ib.nc != null ? ib.nc.iD : "null") +
                    ", nc.oD=" + (ib.nc != null ? ib.nc.oD : "null"));

                // Auto-record events with r=true if ORIGINAL sender has permission
                if (ib.ne.r && ib.nc != null && ib.nc.oD != null) {
                    String senderID = ib.nc.oD;
                    //Additional verification after application layer
                    if (!ib.nc.oD.equals(ib.ne.p.oCXID)) {
                        Analytics.addData(AnalyticData.SecurityEvent, "Security failure, mismatched CXIDs: " + ib.ne.p.oCXID + ", " + ib.nc.oD + " Event Type: " + ib.ne.eT);
                        log.info("Security failure, mismatched CXIDs: {}, {} Event Type: {}", ib.ne.p.oCXID, ib.nc.oD, ib.ne.eT);
                        return;
                    }
                    String networkID = ib.ne.p != null ? ib.ne.p.network : null;

                    if (networkID != null) {
                        CXNetwork network = connectX.getNetwork(networkID);
                        if (network != null) {
                            // Determine target chain from event type (c1=admin, c2=resources, c3=events)
                            Long chainID = getChainID(network, et);

                            // Check if sender has Record permission for this chain
                            boolean hasPermission = network.checkChainPermission(senderID, Permission.Record.name(), chainID);

                            if (hasPermission) {
                                // Record event to blockchain with signed bytes
                                if (ib.signedEventBytes != null) {
                                    boolean recorded = connectX.Event(ib.ne, senderID, ib.signedEventBytes);
                                    if (recorded) {
                                        log.info("[Auto-Record] Event " + ib.ne.eT + " from " + senderID.substring(0, 8) + " recorded to chain " + chainID);
                                    }
                                } else {
                                    log.info("[Auto-Record] Cannot record event - missing signed bytes");
                                }
                            } else {
                                log.info("[Auto-Record] Event {} from {} NOT recorded - no permission", ib.ne.eT, senderID.substring(0, 8));
                            }
                        }
                    }
                }
            }
    }

    @NotNull
    private CXHello getResponsePayload(byte[] ourSignedBlob) {
        int listeningPort = (in.serverSocket != null) ? in.serverSocket.getLocalPort() : 0;

        String ourAddress = null;
        if (in.serverSocket != null && listeningPort > 0) {
            String localIP = LANScanner.getLocalIP();
            if (localIP != null) {
                ourAddress = localIP + ":" + listeningPort;
            }
        }

        CXHello responsePayload =
            new CXHello(connectX.getOwnID(), listeningPort, ourSignedBlob, ourAddress);
        return responsePayload;
    }

    private static Long getChainID(CXNetwork network, EventType et) {
        Long chainID = network.networkDictionary.c3;  // Default to c3 for most events
        if (et != null) {
            // Admin events go to c1
            chainID = switch (et) {
                case REGISTER_NODE, BLOCK_NODE, UNBLOCK_NODE, GRANT_PERMISSION, REVOKE_PERMISSION,
                     ZERO_TRUST_ACTIVATION -> network.networkDictionary.c1;
                default -> chainID;
            };
        }
        return chainID;
    }

    /**
     * Check if an IP address is rate-limited for PEER_LIST_REQUEST
     * Cleans up old timestamps (older than 1 hour) before checking
     * @param ipAddress IP address to check
     * @return true if IP has exceeded rate limit (3 requests per hour)
     */
    private boolean isPeerRequestRateLimited(String ipAddress) {
        long now = System.currentTimeMillis();
        long cutoff = now - PEER_REQUEST_WINDOW_MS;

        // Get or create timestamp list for this IP
        List<Long> timestamps = peerRequestTimestamps.computeIfAbsent(
            ipAddress,
            k -> new CopyOnWriteArrayList<>()
        );

        // Remove timestamps older than 1 hour
        timestamps.removeIf(timestamp -> timestamp < cutoff);

        // Check if limit exceeded
        return timestamps.size() >= PEER_REQUEST_LIMIT;
    }

    /**
     * Record a PEER_LIST_REQUEST from an IP address
     * Should only be called after checking isPeerRequestRateLimited()
     * @param ipAddress IP address making the request
     */
    private void recordPeerRequest(String ipAddress) {
        long now = System.currentTimeMillis();
        List<Long> timestamps = peerRequestTimestamps.computeIfAbsent(
            ipAddress,
            k -> new CopyOnWriteArrayList<>()
        );
        timestamps.add(now);
    }

    public boolean fireEvent(InputBundle ib) {
        NetworkEvent ne = ib.ne;
        NetworkContainer nc = ib.nc;
        byte[] signedEventBytes = ib.signedEventBytes;
        // Use stripped event bytes if available (already verified in processEvent)
        byte[] eventPayload = (ib.strippedEventBytes != null) ? ib.strippedEventBytes : ne.d;

        // CRITICAL: Verify signature before processing any event
        // This ensures the event was actually signed by the claimed sender
        // IMPORTANT: Use oCXID (origin sender) NOT cxID (destination/target)
        // For CXN broadcasts, cxID may be null but oCXID always identifies the signer
        if (signedEventBytes != null && ne != null && ne.p != null && ne.p.oCXID != null) {
            try {
                ByteArrayInputStream verifyStream = new ByteArrayInputStream(signedEventBytes);
                ByteArrayOutputStream verifiedOutput = new ByteArrayOutputStream();
                boolean verified = connectX.encryptionProvider.verifyAndStrip(verifyStream, verifiedOutput, ne.p.oCXID);
                verifyStream.close();

                if (!verified) {
                    String cxidDisplay = (ne.p.oCXID != null && ne.p.oCXID.length() >= 8) ? ne.p.oCXID.substring(0, 8) : (ne.p.oCXID != null ? ne.p.oCXID : "NULL");
                    log.info("[SECURITY] Event signature verification FAILED for {} - REJECTING EVENT", cxidDisplay);
                    return false; // Reject the event
                }
                String cxidDisplay = (ne.p.oCXID != null && ne.p.oCXID.length() >= 8) ? ne.p.oCXID.substring(0, 8) : (ne.p.oCXID != null ? ne.p.oCXID : "NULL");
                log.info("[SECURITY] Event signature verified for {}", cxidDisplay);
            } catch (Exception e) {
                log.info("[SECURITY] Error verifying event signature: {}", e.getMessage());
                return false; // Reject on error
            }
        }

        boolean handledLocally = false;

        // Step 1: Try plugin system for application-level handling
        if (connectX.sendPluginEvent(ib, ne.eT)) {
            handledLocally = true;
        }

        // Step 2: Handle known EventTypes locally if not already handled
        if (!handledLocally && ne.eT != null) {
            try {
                EventType et = EventType.valueOf(ne.eT);
                switch (et) {
                    case MESSAGE:
                        // Display received message
                        String message = new String(ib.verifiedObjectBytes, StandardCharsets.UTF_8);
                        log.info("\n[" + connectX.getOwnID() + "] RECEIVED MESSAGE: " + message);
                        if (ne.p != null) {
                            log.info("  From network: " + ne.p.network);
                            log.info("  Scope: " + ne.p.scope);
                        }
                        handledLocally = true;
                        break;
                    case PeerFinding:
                        try {
                            // Parse PeerFinding request/response
                            PeerFinding pf =
                                (PeerFinding) ConnectX.deserialize("cxJSON1", new String(ib.verifiedObjectBytes, StandardCharsets.UTF_8),
                                    PeerFinding.class);
                           // dev.droppinganvil.v3.network.events.PeerFinding pff = dev.droppinganvil.v3.network.events.PeerFinding) connectX.encryptionProvider.verifyAndStrip()

                            if ("request".equals(pf.t)) {
                                // Handle PeerFinding request - respond with our known peers
                                log.info("[PeerFinding] Request from " + nc.iD.substring(0, 8) +
                                    (pf.network != null ? " for network: " + pf.network : ""));

                                // Get network context (default to CXNET)
                                String requestedNetwork = pf.network != null ? pf.network : "CXNET";
                                CXNetwork network = connectX.getNetwork(requestedNetwork);

                                if (network != null) {
                                    // Build response with signed node blobs (up to 50 random peers)
                                    PeerFinding response =
                                        new PeerFinding();
                                    response.t = "response";
                                    response.network = requestedNetwork;
                                    response.signedNodes = new ArrayList<>();

                                    // Collect peers from PeerDirectory
                                    List<String> peerIDs = new ArrayList<>(peerDirectory.hv.keySet());
                                    Collections.shuffle(peerIDs); // Randomize

                                    int count = 0;
                                    for (String peerID : peerIDs) {
                                        if (count >= 50) break; // Limit to 50
                                        if (peerID.equals(nc.iD) || peerID.equals(connectX.getOwnID())) continue; // Skip requester and self

                                        // Get signed node blob (original signature preserved)
                                        byte[] signedNode = peerDirectory.getSignedNode(peerID);
                                        if (signedNode != null) {
                                            response.signedNodes.add(signedNode);
                                            count++;
                                        }
                                    }

                                    log.info("[PeerFinding] Responding with " + count + " signed peers");

                                    // Populate peers field: 30% of seen peers, then 30% of their addresses (max 20)
                                    Set<String> allAddresses = new HashSet<>();

                                    // Select 30% of seen peers
                                    List<String> seenPeerIDs = new ArrayList<>(peerDirectory.seenCXIDs);
                                    Collections.shuffle(seenPeerIDs);
                                    // 30% rule 0.3
                                    int seenPeerCount = (int) Math.ceil(seenPeerIDs.size() * 0.3);

                                    for (int i = 0; i < Math.min(seenPeerCount, seenPeerIDs.size()); i++) {
                                        String peerID = seenPeerIDs.get(i);
                                        // Get all addresses for this peer
                                        List<String> peerAddresses = peerDirectory.getAllAddresses(peerID, connectX);
                                        allAddresses.addAll(peerAddresses);
                                    }

                                    // Select 30% of addresses (max 20)
                                    List<String> addressList = new ArrayList<>(allAddresses);
                                    Collections.shuffle(addressList);
                                    int addressCount = Math.min(20, (int) Math.ceil(addressList.size() * 0.3));
                                    List<String> selectedAddresses = addressList.subList(0, Math.min(addressCount, addressList.size()));

                                    //Set response
                                    response.peers.addAll(addressList);

                                    log.info("[PeerFinding] Added " + selectedAddresses.size() + " peer addresses from " + seenPeerIDs.size() + " seen peers");

                                    // Using new Event Builder API

                                    connectX.buildEvent(EventType.PeerFinding, ConnectX.serialize("cxJSON1", response).getBytes(StandardCharsets.UTF_8))
                                            .targetPeer(nc.iD)
                                            .toPeer(nc.iD)
                                            .scope("CXS")
                                            .signData()
                                            .queue();

                                } else {
                                    log.info("[PeerFinding] Network " + requestedNetwork + " not found");
                                }

                            } else if ("response".equals(pf.t)) {
                                // Handle PeerFinding response - import discovered peers
                                log.info("[PeerFinding] Response from " + nc.iD.substring(0, 8) +
                                    " with " + (pf.signedNodes != null ? pf.signedNodes.size() : 0) + " peers");

                                if (pf.signedNodes != null) {
                                    int imported = 0;
                                    for (Object signedNodeObj : pf.signedNodes) {
                                        try {
                                            byte[] signedNodeBytes;
                                            if (signedNodeObj instanceof byte[]) {
                                                signedNodeBytes = (byte[]) signedNodeObj;
                                            } else if (signedNodeObj instanceof Byte[]) {
                                                Byte[] boxedBytes = (Byte[]) signedNodeObj;
                                                signedNodeBytes = new byte[boxedBytes.length];
                                                for (int i = 0; i < boxedBytes.length; i++) {
                                                    signedNodeBytes[i] = boxedBytes[i];
                                                }
                                            } else {
                                                continue;
                                            }

                                            // Import node WITHOUT verification first (need public key from node)
                                            // Pattern: strip -> deserialize -> import -> verify -> rollback if fail
                                            ByteArrayInputStream signedInput = new ByteArrayInputStream(signedNodeBytes);
                                            ByteArrayOutputStream strippedOutput = new ByteArrayOutputStream();
                                            connectX.encryptionProvider.stripSignature(signedInput, strippedOutput);
                                            signedInput.close();

                                            String nodeJson = strippedOutput.toString(StandardCharsets.UTF_8);
                                            Node discoveredPeer = (Node) ConnectX.deserialize("cxJSON1", nodeJson, Node.class);
                                            strippedOutput.close();

                                            if (discoveredPeer != null && discoveredPeer.cxID != null) {
                                                // Add with signed blob for persistence and relaying
                                                peerDirectory.addNode(discoveredPeer, signedNodeBytes, connectX.cxRoot);

                                                // Now verify the signature using the imported public key
                                                ByteArrayInputStream verifyInput = new ByteArrayInputStream(signedNodeBytes);
                                                ByteArrayOutputStream verifyOutput = new ByteArrayOutputStream();
                                                boolean verified = connectX.encryptionProvider.verifyAndStrip(
                                                    verifyInput, verifyOutput, discoveredPeer.cxID);
                                                verifyInput.close();
                                                verifyOutput.close();

                                                if (!verified) {
                                                    // Signature verification FAILED - rollback
                                                    peerDirectory.removeNode(discoveredPeer.cxID, connectX.cxRoot);
                                                    log.error("[PeerFinding] Signature verification FAILED for " +
                                                        discoveredPeer.cxID.substring(0, 8) + " - rolled back");
                                                    continue;
                                                }

                                                imported++;
                                                log.info("[PeerFinding]   + " + discoveredPeer.cxID.substring(0, 8) +
                                                    " (verified)");

                                                // Send NewNode with SIGNED Node blob (receiver will save original signed blob for relay)
                                                String selfJson = ConnectX.serialize("cxJSON1", connectX.getSelf());
                                                connectX.buildEvent(EventType.NewNode, selfJson.getBytes(StandardCharsets.UTF_8))
                                                    .signData()  // Sign Node JSON to create signed blob (preserves sender signature)
                                                    .toPeer(discoveredPeer.cxID)
                                                    .queue();
                                            }
                                        } catch (Exception e) {
                                            log.error("[PeerFinding] Failed to import peer: " + e.getMessage());
                                        }
                                    }
                                    log.info("[PeerFinding] Imported " + imported + " new peers");
                                }
                                //TODO Security eval
                                if (pf.peers != null && pf.peers.size() <= 20) {
                                    int added = 0;
                                    for (String peerAddr : pf.peers) {
                                        if (!connectX.isValidPeerAddress(peerAddr)) {
                                            log.info("[PeerFinding] Rejected invalid address: " + peerAddr);
                                            continue;
                                        }
                                        if (connectX.isSelfAddress(peerAddr)) {
                                            log.info("[PeerFinding] Skipped own address: " + peerAddr);
                                            continue;
                                        }
                                        connectX.dataContainer.waitingAddresses.add(peerAddr);
                                        added++;
                                    }
                                    log.info("[PeerFinding] Imported " + added + "/" + pf.peers.size() + " addresses from " + ne.p.oCXID);
                                }

                            }
                        } catch (Exception e) {
                            log.info("[PeerFinding] Error: " + e.getMessage());
                            e.printStackTrace();
                        }
                        handledLocally = true;
                        break;
                    case SEED_REQUEST:
                        log.info("[" + connectX.getOwnID() + "] Seed request received from " + nc.iD);
                        try {
                            // Parse request to get network ID
                            String requestedNetwork = "CXNET";
                            ib.readyObject(SeedExchange.class, ib.nc.se, connectX);
                            if (ib.object != null) {
                                SeedExchange seedReq =
                                    (SeedExchange) ib.object;
                                if (seedReq.network != null) {
                                    requestedNetwork = seedReq.network;
                                }
                            }

                            CXNetwork network = connectX.getNetwork(requestedNetwork);
                            if (network != null) {
                                // Create dynamic seed from current peer state
                                Seed dynamicSeed = Seed.fromCurrentPeers(peerDirectory);
                                dynamicSeed.seedID = UUID.randomUUID().toString();
                                dynamicSeed.timestamp = System.currentTimeMillis();
                                dynamicSeed.networkID = requestedNetwork;
                                dynamicSeed.networks = new ArrayList<>();
                                dynamicSeed.networks.add(network);

                                // Try to get signed EPOCH seed blob (prefer in-RAM copy, fall back to disk)
                                byte[] epochSeedBlob = connectX.signedBootstrapSeed;
                                if (epochSeedBlob == null && network.configuration != null
                                        && network.configuration.currentSeedID != null) {
                                    File seedFile = new File(new File(connectX.cxRoot, "seeds"),
                                            network.configuration.currentSeedID + ".cxn");
                                    if (seedFile.exists()) {
                                        try {
                                            epochSeedBlob = java.nio.file.Files.readAllBytes(seedFile.toPath());
                                        } catch (Exception ex) {
                                            log.warn("[SEED] Could not read seed blob from disk", ex);
                                        }
                                    }
                                }

                                // Determine if this peer is authoritative (NMI/backend)
                                boolean isAuthoritative = false;
                                if (network.configuration != null && network.configuration.backendSet != null) {
                                    isAuthoritative = network.configuration.backendSet.contains(connectX.getOwnID());
                                }

                                // Build response
                                SeedExchange response =
                                    new SeedExchange();
                                response.network = requestedNetwork;
                                response.dynamicSeed = dynamicSeed;
                                response.epochSeedBlob = epochSeedBlob;
                                response.authoritative = isAuthoritative;
                                response.senderID = connectX.getOwnID();
                                response.c1 = network.c1.current != null ? network.c1.current.block : 0L;
                                response.c2 = network.c2.current != null ? network.c2.current.block : 0L;
                                response.c3 = network.c3.current != null ? network.c3.current.block : 0L;

                                if (epochSeedBlob != null && epochSeedBlob.length > 0) {
                                    StringBuilder hex = new StringBuilder();
                                    for (int i = 0; i < Math.min(16, epochSeedBlob.length); i++) hex.append(String.format("%02X ", epochSeedBlob[i]));
                                    log.debug("[SEED] epochSeedBlob first bytes: {}", hex.toString().trim());
                                }
                                log.info("[SEED] Responding to {}: dynamic={} peers, epochBlob={}, authoritative={}",
                                    nc.iD.substring(0, 8), dynamicSeed.hvPeers.size(),
                                    epochSeedBlob != null ? epochSeedBlob.length + " bytes" : "none",
                                    isAuthoritative);

                                String responseJson = ConnectX.serialize("cxJSON1", response);

                                // Send response using EventBuilder pattern
                                ConnectX.EventBuilder eb = connectX.buildEvent(EventType.SEED_RESPONSE, responseJson.getBytes(StandardCharsets.UTF_8))
                                    .toPeer(nc.iD)
                                    .signData();
                                //I think this old method was to allow the original requester to provide a temporary address
                                if (ne.p != null) {
                                    eb.getPath().network = ne.p.network;
                                    eb.getPath().scope = ne.p.scope;
                                    eb.getPath().bridge = ne.p.bridge;
                                    eb.getPath().bridgeArg = ne.p.bridgeArg;
                                }
                                eb.queue();

                            } else {
                                log.info("[SEED] Network " + requestedNetwork + " not found");
                            }
                        } catch (Exception e) {
                            log.info("[SEED] Error: {}", e.getMessage());
                            e.printStackTrace();
                        }
                        handledLocally = true;
                        break;
                    case SEED_RESPONSE:
                        log.info("[" + connectX.getOwnID() + "] Seed response received from " + nc.iD);
                        try {
                            ib.readyObject(SeedExchange.class, ib.nc.se, connectX);
                            SeedExchange seedResp =
                                (SeedExchange) ib.object;

                            // Map wire object to consensus tracker
                            ConnectX.SeedResponseData responseData = new ConnectX.SeedResponseData();
                            responseData.dynamicSeed = seedResp.dynamicSeed;
                            responseData.epochSeedBlob = seedResp.epochSeedBlob;
                            responseData.authoritative = seedResp.authoritative != null && seedResp.authoritative;
                            responseData.senderID = nc.iD;
                            responseData.timestamp = System.currentTimeMillis();
                            Map<String, Number> chainHeights = new HashMap<>();
                            chainHeights.put("c1", seedResp.c1 != null ? seedResp.c1 : 0L);
                            chainHeights.put("c2", seedResp.c2 != null ? seedResp.c2 : 0L);
                            chainHeights.put("c3", seedResp.c3 != null ? seedResp.c3 : 0L);
                            responseData.chainHeights = chainHeights;

                            // Determine target network from wire field (always present in SeedExchange)
                            String targetNetwork = seedResp.network != null ? seedResp.network : "CXNET";
                            if (targetNetwork.isBlank() && responseData.dynamicSeed != null
                                    && responseData.dynamicSeed.networkID != null) {
                                targetNetwork = responseData.dynamicSeed.networkID;
                            }

                            log.info("[SEED CONSENSUS] Response for {} from {} | authoritative={} epochBlob={} dynamicSeed={}",
                                targetNetwork, nc.iD.substring(0, 8), responseData.authoritative,
                                responseData.epochSeedBlob != null ? responseData.epochSeedBlob.length + "b" : "none",
                                responseData.dynamicSeed != null);

                            // PRIORITY 1: If this is EPOCH or any peer with a signed blob, apply immediately
                            if (responseData.epochSeedBlob != null) {
                                log.info("[SEED CONSENSUS] Received signed seed blob ({} bytes) from {} -- applying",
                                    responseData.epochSeedBlob.length,
                                    responseData.authoritative ? "EPOCH (authoritative)" : "peer " + nc.iD.substring(0, 8));
                                connectX.applySignedSeed(responseData.epochSeedBlob);
                                handledLocally = true;
                                break;
                            }

                            // PRIORITY 2: Multi-peer consensus for dynamic seeds only
                            // Store response in consensus map
                            if (!connectX.seedConsensusMap.containsKey(targetNetwork)) {
                                connectX.seedConsensusMap.put(targetNetwork, new ConcurrentHashMap<>());
                            }
                            connectX.seedConsensusMap.get(targetNetwork).put(nc.iD, responseData);

                            ConcurrentHashMap<String, ConnectX.SeedResponseData> responses =
                                connectX.seedConsensusMap.get(targetNetwork);

                            log.info("[SEED CONSENSUS] Stored dynamic response from {} ({}/3)",
                                nc.iD.substring(0, 8), responses.size());

                            if (responses.size() >= 3) {
                                log.info("[SEED CONSENSUS] Triggering consensus vote...");
                                performSeedConsensus(connectX, targetNetwork, responses);
                                connectX.seedConsensusMap.remove(targetNetwork);
                            }

                        } catch (Exception e) {
                            log.error("[SEED CONSENSUS] Error processing SEED_RESPONSE", e);
                        }
                        handledLocally = true;
                        break;
                    case CHAIN_STATUS_REQUEST:
                        log.info("[{}] Chain status request received from {}", connectX.getOwnID(), nc.iD);
                        try {
                            ib.readyObject(ChainStatus.class, ib.nc.se, connectX);
                            ChainStatus chainReq =
                                (ChainStatus) ib.object;
                            String networkID = chainReq != null ? chainReq.network : null;

                            CXNetwork network = connectX.getNetwork(networkID);
                            if (network != null) {
                                ChainStatus response =
                                    new ChainStatus(
                                        networkID,
                                        network.c1.current != null ? network.c1.current.block : 0L,
                                        network.c2.current != null ? network.c2.current.block : 0L,
                                        network.c3.current != null ? network.c3.current.block : 0L
                                    );

                                String statusJson = ConnectX.serialize("cxJSON1", response);

                                // Send response using EventBuilder pattern
                                connectX.buildEvent(EventType.CHAIN_STATUS_RESPONSE, statusJson.getBytes(StandardCharsets.UTF_8))
                                    .toPeer(nc.iD)
                                    .signData()
                                    .queue();

                                log.info("[CHAIN_STATUS] Sent status for {} (c1:{} c2:{} c3:{})", networkID, response.c1, response.c2, response.c3);
                            } else {
                                log.info("[CHAIN_STATUS] Network not found: {}", networkID);
                            }
                        } catch (Exception e) {
                            log.info("[CHAIN_STATUS] Error handling request: {}", e.getMessage());
                            e.printStackTrace();
                        }
                        handledLocally = true;
                        break;
                    case CHAIN_STATUS_RESPONSE:
                        log.info("[" + connectX.getOwnID() + "] Chain status response received from " + nc.iD);
                        try {
                            ib.readyObject(ChainStatus.class, ib.nc.se, connectX);
                            ChainStatus chainResp =
                                (ChainStatus) ib.object;

                            log.info("[CHAIN_STATUS] Remote chain heights for {} from {}:", chainResp.network, nc.iD.substring(0, 8));
                            log.info("  c1: {}", chainResp.c1);
                            log.info("  c2: {}", chainResp.c2);
                            log.info("  c3: {}", chainResp.c3);

                            // Store response for multipeer verification
                            CXNetwork network = connectX.getNetwork(chainResp.network);
                            if (network != null) {
                                // Check if this response is from NMI (always trust NMI)
                                boolean isNMI = network.configuration.backendSet != null &&
                                               network.configuration.backendSet.contains(nc.iD);

                                if (isNMI) {
                                    log.info("[CHAIN_STATUS] Response from NMI/Backend - TRUSTED");
                                    // NMI response is authoritative, initiate sync immediately
                                    initiateSyncFromPeer(connectX, network, chainResp.network, chainResp, nc.iD);
                                } else {
                                    // Peer response - store for multipeer verification
                                    log.info("[CHAIN_STATUS] Response from peer - storing for verification");
                                    // TODO: Implement multipeer consensus mechanism
                                    // For now, skip peer-only responses (require NMI or multiple peer consensus)
                                }
                            }
                        } catch (Exception e) {
                            log.error("[CHAIN_STATUS] Error handling response: {}", e.getMessage());
                            e.printStackTrace();
                        }
                        handledLocally = true;
                        break;
                    case BLOCK_REQUEST:
                        log.info("[{}] Block request received from {}", connectX.getOwnID(), nc.iD);
                        try {
                            ib.readyObject(BlockExchange.class, ib.nc.se, connectX);
                            BlockExchange blockReq =
                                (BlockExchange) ib.object;
                            String networkID = blockReq.network;
                            Long chainID = blockReq.chain;
                            Long blockID = blockReq.block;

                            CXNetwork network = connectX.getNetwork(networkID);
                            if (network != null) {
                                // Get the requested chain
                                NetworkRecord targetChain = null;
                                if (chainID.equals(network.networkDictionary.c1)) targetChain = network.c1;
                                else if (chainID.equals(network.networkDictionary.c2)) targetChain = network.c2;
                                else if (chainID.equals(network.networkDictionary.c3)) targetChain = network.c3;

                                if (targetChain != null) {
                                    // Try to get block from memory first
                                    NetworkBlock block = targetChain.blockMap.get(blockID);

                                    // If not in memory, try loading from disk
                                    if (block == null) {
                                        try {
                                            block = connectX.blockchainPersistence.loadBlock(networkID, chainID, blockID);
                                        } catch (Exception e) {
                                            log.info("[BLOCK_REQUEST] Block not found on disk: {}", e.getMessage());
                                        }
                                    }

                                    if (block != null) {
                                        // Wrap block in BlockExchange for typed response
                                        BlockExchange blockResp =
                                            new BlockExchange(networkID, chainID, blockID);
                                        blockResp.blockData = block;
                                        String blockJson = ConnectX.serialize("cxJSON1", blockResp);

                                        // Send response using EventBuilder pattern
                                        ConnectX.EventBuilder eb = connectX.buildEvent(EventType.BLOCK_RESPONSE, blockJson.getBytes(StandardCharsets.UTF_8))
                                            .toPeer(nc.iD)
                                            .signData();
                                            
                                        //More special handling...
                                        if (ne.p != null) {
                                            eb.getPath().network = ne.p.network;
                                            eb.getPath().scope = ne.p.scope;
                                            eb.getPath().bridge = ne.p.bridge;
                                            eb.getPath().bridgeArg = ne.p.bridgeArg;
                                        }
                                        eb.queue();

                                        log.info("[BLOCK_REQUEST] Sent block {} from chain {} ({} events)", blockID, chainID, block.networkEvents.size());
                                    } else {
                                        log.info("[BLOCK_REQUEST] Block not found: {}", blockID);
                                    }
                                } else {
                                    log.info("[BLOCK_REQUEST] Chain not found: {}", chainID);
                                }
                            } else {
                                log.info("[BLOCK_REQUEST] Network not found: {}", networkID);
                            }
                        } catch (Exception e) {
                            log.info("[BLOCK_REQUEST] Error handling request: {}", e.getMessage());
                            e.printStackTrace();
                        }
                        handledLocally = true;
                        break;
                    case BLOCK_RESPONSE:
                        log.info("[{}] Block response received from {}", connectX.getOwnID(), nc.iD);
                        try {
                            ib.readyObject(BlockExchange.class, ib.nc.se, connectX);
                            BlockExchange blockResp =
                                (BlockExchange) ib.object;
                            NetworkBlock block = blockResp.blockData;

                            log.info("[BLOCK_RESPONSE] Received block {} ({} events)", block.block, block.networkEvents.size());

                            String networkID = blockResp.network;
                            Long chainID = blockResp.chain;

                            if (networkID == null) {
                                log.info("[BLOCK_RESPONSE] Cannot determine network ID from event path");
                                handledLocally = true;
                                break;
                            }

                            // Get network and determine which chain this block belongs to
                            CXNetwork network = connectX.getNetwork(networkID);
                            if (network == null) {
                                log.info("[BLOCK_RESPONSE] Network not found: {}", networkID);
                                handledLocally = true;
                                break;
                            }

                            // Determine target chain based on block's chain ID from metadata
                            NetworkRecord targetChain = null;
                            if (block.chain != null) {
                                chainID = block.chain;
                                if (chainID.equals(network.networkDictionary.c1)) targetChain = network.c1;
                                else if (chainID.equals(network.networkDictionary.c2)) targetChain = network.c2;
                                else if (chainID.equals(network.networkDictionary.c3)) targetChain = network.c3;
                            }

                            if (targetChain == null) {
                                log.info("[BLOCK_RESPONSE] Cannot determine target chain for block");
                                handledLocally = true;
                                break;
                            }

                            // Check if sender is EPOCH/NMI/backend (authoritative source)
                            boolean isAuthoritative = network.configuration != null &&
                                                     network.configuration.backendSet != null &&
                                                     network.configuration.backendSet.contains(nc.iD);

                            if (isAuthoritative && !network.zT) {
                                // EPOCH/NMI response in non-zero-trust mode - accept immediately as source of truth
                                log.info("[BLOCK_RESPONSE] EPOCH/NMI source - accepting as authoritative");
                                applyBlockToChain(connectX, network, targetChain, block, networkID, chainID, nc.iD);

                            } else {
                                // Peer response OR zero trust mode - use consensus
                                log.info("[BLOCK_RESPONSE] Using consensus{}", network.zT ? " (zero trust mode)" : " (peer response)");

                                // Create request key for this block
                                String requestKey = BlockConsensusTracker.createRequestKey(networkID, chainID, block.block);

                                // Record this response in consensus tracker
                                boolean recorded = connectX.blockConsensusTracker.recordResponse(requestKey, nc.iD, block);

                                if (recorded) {
                                    // Check if consensus has been reached
                                    boolean consensusReached = connectX.blockConsensusTracker.checkConsensus(requestKey);

                                    if (consensusReached) {
                                        // Get consensus block
                                        NetworkBlock consensusBlock =
                                            connectX.blockConsensusTracker.getConsensusBlock(requestKey);

                                        if (consensusBlock != null) {
                                            log.info("[BLOCK_CONSENSUS] Consensus reached for block {}", consensusBlock.block);

                                            // Apply consensus block
                                            applyBlockToChain(connectX, network, targetChain, consensusBlock, networkID, chainID, nc.iD);

                                            // Clean up this request
                                            connectX.blockConsensusTracker.removeRequest(requestKey);
                                        }
                                    } else {
                                        log.info("[BLOCK_CONSENSUS] Waiting for more responses...");
                                    }
                                } else {
                                    log.info("[BLOCK_RESPONSE] Failed to record response in consensus tracker");
                                }
                            }

                        } catch (Exception e) {
                            log.info("[BLOCK_RESPONSE] Error handling response: " + e.getMessage());
                            e.printStackTrace();
                        }
                        handledLocally = true;
                        break;
                    case BLOCK_NODE:
                        log.info("[{}] BLOCK_NODE event received from {}", connectX.getOwnID(), nc.iD);
                        try {
                            ib.readyObject(NodeModeration.class, ib.nc.se, connectX);
                            NodeModeration blockData =
                                (NodeModeration) ib.object;

                            log.info("[BLOCK_NODE] Blocking node " + blockData.nodeID + " on network " + blockData.network + " (reason: " + blockData.reason + ")");

                            if ("CXNET".equals(blockData.network)) {
                                connectX.blockNodeCXNET(blockData.nodeID, blockData.reason);
                            } else {
                                connectX.dataContainer.blockNode(blockData.network, blockData.nodeID, blockData.reason);
                                log.info("[BLOCK_NODE] Node " + blockData.nodeID + " blocked from network " + blockData.network);
                            }

                            ne.executeOnSync = true;

                        } catch (Exception e) {
                            log.info("[BLOCK_NODE] Error handling event: {}", e.getMessage());
                            e.printStackTrace();
                        }
                        handledLocally = true;
                        break;
                    case UNBLOCK_NODE:
                        log.info("[{}] UNBLOCK_NODE event received from {}", connectX.getOwnID(), nc.iD);
                        try {
                            ib.readyObject(NodeModeration.class, ib.nc.se, connectX);
                            NodeModeration unblockData =
                                (NodeModeration) ib.object;

                            log.info("[UNBLOCK_NODE] Unblocking node {} from network {}", unblockData.nodeID, unblockData.network);

                            if ("CXNET".equals(unblockData.network)) {
                                connectX.unblockNodeCXNET(unblockData.nodeID);
                            } else {
                                String removedReason = connectX.dataContainer.unblockNode(unblockData.network, unblockData.nodeID);
                                if (removedReason != null) {
                                    log.info("[UNBLOCK_NODE] Node {} unblocked from network {} (was blocked for: {})", unblockData.nodeID, unblockData.network, removedReason);
                                }
                            }

                            ne.executeOnSync = true;

                        } catch (Exception e) {
                            log.error("[UNBLOCK_NODE] Error handling event ", e);
                        }
                        handledLocally = true;
                        break;
                    case REGISTER_NODE:
                        log.info("[{}] REGISTER_NODE event received from {}", connectX.getOwnID(), nc.iD);
                        try {
                            ib.readyObject(NodeRegistration.class, ib.nc.se, connectX);
                            NodeRegistration registerData =
                                (NodeRegistration) ib.object;

                            log.info("[REGISTER_NODE] Registering node " + registerData.nodeID + " to network " + registerData.network +
                                             " (approved by " + registerData.approver + ")");

                            connectX.dataContainer.networkRegisteredNodes.computeIfAbsent(registerData.network, k -> new HashSet<>()).add(registerData.nodeID);
                            log.info("[REGISTER_NODE] Node " + registerData.nodeID + " registered to network " + registerData.network);
                            log.info("[REGISTER_NODE] Total registered nodes: " +
                                connectX.dataContainer.networkRegisteredNodes.get(registerData.network).size());

                            ne.executeOnSync = true;

                        } catch (Exception e) {
                            log.error("[REGISTER_NODE] Error handling event ", e);
                        }
                        handledLocally = true;
                        break;
                    case GRANT_PERMISSION:
                        log.info("[{}] GRANT_PERMISSION event received from {}", connectX.getOwnID(), nc.iD);
                        try {
                            ib.readyObject(PermissionChange.class, ib.nc.se, connectX);
                            PermissionChange grantData =
                                (PermissionChange) ib.object;

                            int priority = grantData.priority != null ? grantData.priority : 10;
                            log.info("[GRANT_PERMISSION] Granting {} permission to node {} on network {} chain {} (priority: {})", grantData.permission, grantData.nodeID, grantData.network, grantData.chain, priority);

                            CXNetwork network = connectX.getNetwork(grantData.network);
                            if (network != null) {
                                String permKey = grantData.permission + "-" + grantData.chain;
                                Entry entry =
                                    new BasicEntry(permKey, true, priority);
                                Map<String, Entry> nodePerms =
                                    network.networkPermissions.permissionSet.computeIfAbsent(grantData.nodeID, k -> new HashMap<>());
                                nodePerms.put(permKey, entry);
                                log.info("[GRANT_PERMISSION] Permission granted successfully");
                            } else {
                                log.info("[GRANT_PERMISSION] Network not found: {}", grantData.network);
                            }

                            ne.executeOnSync = true;

                        } catch (Exception e) {
                            log.error("[GRANT_PERMISSION] Error handling event", e);
                        }
                        handledLocally = true;
                        break;
                    case REVOKE_PERMISSION:
                        log.info("[{}] REVOKE_PERMISSION event received from {}", connectX.getOwnID(), nc.iD);
                        try {
                            ib.readyObject(PermissionChange.class, ib.nc.se, connectX);
                            PermissionChange revokeData =
                                (PermissionChange) ib.object;

                            log.info("[REVOKE_PERMISSION] Revoking {} permission from node {} on network {} chain {}", revokeData.permission, revokeData.nodeID, revokeData.network, revokeData.chain);

                            CXNetwork network = connectX.getNetwork(revokeData.network);
                            if (network != null) {
                                String permKey = revokeData.permission + "-" + revokeData.chain;
                                Map<String, Entry> nodePerms =
                                    network.networkPermissions.permissionSet.get(revokeData.nodeID);
                                if (nodePerms != null) {
                                    nodePerms.remove(permKey);
                                    if (nodePerms.isEmpty()) {
                                        network.networkPermissions.permissionSet.remove(revokeData.nodeID);
                                    }
                                    log.info("[REVOKE_PERMISSION] Permission revoked successfully");
                                } else {
                                    log.info("[REVOKE_PERMISSION] Node had no permissions to revoke");
                                }
                            } else {
                                log.info("[REVOKE_PERMISSION] Network not found: " + revokeData.network);
                            }

                            ne.executeOnSync = true;

                        } catch (Exception e) {
                            log.error("[REVOKE_PERMISSION] Error handling event ", e);
                        }
                        handledLocally = true;
                        break;
                    case ZERO_TRUST_ACTIVATION:
                        log.info("[{}] ZERO_TRUST_ACTIVATION event received from {}", connectX.getOwnID(), nc.iD);
                        try {
                            ib.readyObject(ZeroTrustActivation.class, ib.nc.se, connectX);
                            ZeroTrustActivation ztData =
                                (ZeroTrustActivation) ib.object;

                            log.info("[ZERO_TRUST_ACTIVATION] Activating zero trust mode for network {}", ztData.network);
                            log.info("[ZERO_TRUST_ACTIVATION] WARNING: This operation is IRREVERSIBLE");

                            // Get the network
                            CXNetwork network = connectX.getNetwork(ztData.network);
                            if (network != null) {
                                // Verify sender is NMI (first backend)
                                boolean isNMI = network.configuration != null &&
                                               network.configuration.backendSet != null &&
                                               !network.configuration.backendSet.isEmpty() &&
                                               network.configuration.backendSet.get(0).equals(nc.iD);

                                if (!isNMI) {
                                    log.info("[ZERO_TRUST_ACTIVATION] Rejected: sender {} is not NMI", nc.iD.substring(0, 8));
                                    handledLocally = true;
                                    break;
                                }

                                // Check if already in zero trust mode
                                if (network.zT) {
                                    log.info("[ZERO_TRUST_ACTIVATION] Network already in zero trust mode, ignoring duplicate activation");
                                    handledLocally = true;
                                    break;
                                }

                                // Activate zero trust mode
                                network.zT = true;
                                log.info("[ZERO_TRUST_ACTIVATION] Set network.zT = true");
                                log.info("[ZERO_TRUST_ACTIVATION] Seed zT flag: " + ztData.zT);

                                log.info("[ZERO_TRUST_ACTIVATION] Zero trust mode activated for " + ztData.network);
                                log.info("[ZERO_TRUST_ACTIVATION] NMI permissions are now blocked");
                                log.info("[ZERO_TRUST_ACTIVATION] Network is fully decentralized");

                                // Persist network configuration
                                try {
                                    if (connectX.blockchainPersistence != null) {
                                        connectX.blockchainPersistence.saveChainMetadata(network.c1, ztData.network);
                                        connectX.blockchainPersistence.saveChainMetadata(network.c2, ztData.network);
                                        connectX.blockchainPersistence.saveChainMetadata(network.c3, ztData.network);
                                        log.info("[ZERO_TRUST_ACTIVATION] Network configuration persisted");
                                    }
                                } catch (Exception persistEx) {
                                    log.info("[ZERO_TRUST_ACTIVATION] Failed to persist configuration: {}", persistEx.getMessage());
                                }

                                // TODO: Trigger blockchain re-sync using zero trust consensus protocols
                                // This will be implemented when multi-peer block querying and reconciliation are ready

                            } else {
                                log.info("[ZERO_TRUST_ACTIVATION] Network not found: " + ztData.network);
                            }

                            // This is a state-modifying event that should be recorded to c1 (Admin) chain
                            // System reads c1 to rebuild zero trust state during bootstrap/sync
                            ne.executeOnSync = true;

                        } catch (Exception e) {
                            log.error("[ZERO_TRUST_ACTIVATION] Error handling event ", e);
                        }
                        handledLocally = true;
                        break;
                    case PEER_LIST_REQUEST:
                        log.info("[{}] PEER_LIST_REQUEST received from {}", connectX.getOwnID(), nc.iD);
                        try {
                            // Get requester's IP address (from socket if available)
                            String requesterIP = nc.iD; // TODO: Extract actual IP from socket/connection context

                            // Check rate limiting: 3 requests per IP per hour
                            if (isPeerRequestRateLimited(requesterIP)) {
                                log.info("[PEER_LIST_REQUEST] Rate limit exceeded for IP {} (3 per hour)", requesterIP);
                                handledLocally = true;
                                break;
                            }

                            // Record this request for rate limiting
                            recordPeerRequest(requesterIP);

                            // Collect all known peers from PeerDirectory
                            List<Node> allPeers = new ArrayList<>();
                            if (peerDirectory.hv != null) allPeers.addAll(peerDirectory.hv.values());
                            if (peerDirectory.seen != null) allPeers.addAll(peerDirectory.seen.values());
                            if (peerDirectory.peerCache != null) allPeers.addAll(peerDirectory.peerCache.values());

                            // Get peer count (30% of known peers or max 10)
                            int knownPeerCount = allPeers.size();
                            int maxPeers = Math.min(10, (int) Math.ceil(knownPeerCount * 0.3));

                            // Select random peers and extract only IP:port
                            List<String> peerIPs = new ArrayList<>();

                            // Shuffle and take up to maxPeers
                            Collections.shuffle(allPeers);
                            for (int i = 0; i < Math.min(maxPeers, allPeers.size()); i++) {
                                Node peer = allPeers.get(i);
                                if (peer.addr != null) {
                                    peerIPs.add(peer.addr); // addr is already in "host:port" format
                                }
                            }

                            PeerList response = new PeerList();
                            response.ips = peerIPs;
                            String responseJson = ConnectX.serialize("cxJSON1", response);

                            log.info("[PEER_LIST_REQUEST] Sending {} peer IPs to {}", peerIPs.size(), nc.iD);

                            // Send response using EventBuilder pattern
                            ConnectX.EventBuilder eb = connectX.buildEvent(EventType.PEER_LIST_RESPONSE, responseJson.getBytes(StandardCharsets.UTF_8))
                                .toPeer(nc.iD)
                                .signData();
                            if (ne.p != null) {
                                eb.getPath().network = ne.p.network;
                                eb.getPath().scope = ne.p.scope;
                                eb.getPath().bridge = ne.p.bridge;
                               eb.getPath().bridgeArg = ne.p.bridgeArg;
                            }
                                eb.queue();

                        } catch (Exception e) {
                            log.error("[PEER_LIST_REQUEST] Error handling request ", e);
                        }
                        handledLocally = true;
                        break;
                    case PEER_LIST_RESPONSE:
                        log.info("[{}] PEER_LIST_RESPONSE received from {}", connectX.getOwnID(), nc.iD);
                        try {
                            ib.readyObject(PeerList.class, ib.nc.se, connectX);
                            PeerList peerList = (PeerList) ib.object;

                            log.info("[PEER_LIST_RESPONSE] Received {} peer IPs", peerList.ips.size());

                            for (String ipPort : peerList.ips) {
                                log.info("[PEER_LIST_RESPONSE] Received peer: {}", ipPort);
                                // TODO: Connect to IP:port, send NewNode or SEED_REQUEST, add to PeerDirectory
                            }

                        } catch (Exception e) {
                            log.error("[PEER_LIST_RESPONSE] Error handling response ", e);
                        }
                        handledLocally = true;
                        break;
                }
            } catch (IllegalArgumentException ignored) {
                // Not a known EventType constant, already tried plugins above
            } catch (Exception e) {
                log.error("Unknown Error", e);
            }
        }

        // Step 3: Check if event needs relaying to specific target (like isLocal() pattern)
        if (ne.p != null && ne.p.cxID != null && !ne.p.cxID.isEmpty()) {
            // Event has specific target - check if it's for us
            if (!ne.p.cxID.equals(connectX.getOwnID())) {
                // Not for us - relay to target node if we know them
                try {
                    Node targetNode = peerDirectory.lookup(ne.p.cxID, true, true);
                    if (targetNode != null) {
                        // Create OutputBundle for relay - preserve original sender's signature
                        NetworkContainer relayContainer = new NetworkContainer();
                        relayContainer.se = "cxJSON1";
                        relayContainer.s = false;
                        relayContainer.oD = nc.oD; // Preserve original sender
                        // Use signedEventBytes to preserve original NetworkEvent signature
                        OutputBundle relayBundle = new OutputBundle(ne, null, null, signedEventBytes, relayContainer);
                        connectX.queueEvent(relayBundle);
                        log.info("[" + connectX.getOwnID() + "] Relaying event to: " + ne.p.cxID);
                        return true;
                    }
                } catch (Exception e) {
                    log.error("Error during step 3", e);
                }
            }
        }

        // Step 4: Handle relay logic based on TransmitPref
        // Only relay if we didn't transmit this container (prevent relay loops)
        if (nc != null && nc.iD != null && nc.iD.equals(connectX.getOwnID())) {
            return handledLocally; // We transmitted this, don't relay
        }

        // Get TransmitPref (defaults if null)
        TransmitPref tP = (nc != null && nc.tP != null) ? nc.tP : new TransmitPref();
        String transmitterID = (nc != null && nc.iD != null) ? nc.iD : "";

        // Step 4a: Handle directOnly mode - no relay
        if (tP.directOnly) {
            return handledLocally; // Direct-only, no relaying
        }

        // Step 4b: Handle peerProxy mode - record to blockchain + distribute to all peers
        if (tP.peerProxy) {
            // Try to record to blockchain using original sender's signed bytes
            // Signature already verified at top of fireEvent()
            if (nc != null && nc.e != null && ne.p != null && ne.p.cxID != null) {
                try {
                    // Record with original sender's signature (never re-sign)
                    connectX.Event(ne, ne.p.cxID, nc.e);
                } catch (Exception e) {
                    log.info("[PeerProxy] Error recording event: {}", e.getMessage());
                }
            }

            // Distribute to all peers for eventual delivery
            for (Node peer : peerDirectory.hv.values()) {
                if (!peer.cxID.equals(connectX.getOwnID()) && !peer.cxID.equals(transmitterID)) {
                    try {
                        NetworkContainer relayContainer = new NetworkContainer();
                        relayContainer.se = "cxJSON1";
                        relayContainer.s = false;
                        relayContainer.tP = tP; // Preserve TransmitPref
                        relayContainer.oD = nc.oD; // Preserve original sender
                        // Use signedEventBytes to preserve original NetworkEvent signature
                        OutputBundle relayBundle = new OutputBundle(ne, null, null, signedEventBytes, relayContainer);
                        connectX.queueEvent(relayBundle);
                    } catch (Exception e) {
                        log.error("An error has occurred inside NodeMesh", e);
                    }
                }
            }
            return handledLocally;
        }

        // Step 4c: Handle peerBroad mode - global cross-network transmission
        if (tP.peerBroad) {
            //log.info("[RELAY DEBUG] peerBroad=true, broadcasting to " + peerDirectory.hv.size() + " peers");
            // Broadcast to all peers across all networks
            for (Node peer : peerDirectory.hv.values()) {
                if (!peer.cxID.equals(connectX.getOwnID()) && !peer.cxID.equals(transmitterID)) {
                    try {
                        NetworkContainer relayContainer = new NetworkContainer();
                        relayContainer.se = "cxJSON1";
                        relayContainer.s = false;
                        relayContainer.tP = tP; // Preserve TransmitPref
                        relayContainer.oD = nc.oD; // Preserve original sender (must be set)
                        // Use signedEventBytes to preserve original NetworkEvent signature
                        OutputBundle relayBundle = new OutputBundle(ne, null, null, signedEventBytes, relayContainer);
                        connectX.queueEvent(relayBundle);
                    } catch (Exception e) {
                        log.error("An error has occurred inside NodeMesh", e);
                    }
                }
            }
            return handledLocally;
        }

        // Step 4d: Default behavior for CXN scope - priority routing through backend + all peers
        // This is the standard mode when TransmitPref flags are all false
        if (ne.p != null && ne.p.scope != null && ne.p.scope.equalsIgnoreCase("CXN") && ne.p.network != null) {
            // Log CXN message reception and relay decision
            String eventType = (ne.eT != null) ? ne.eT : "UNKNOWN";
            String originalSender = (nc.oD != null) ? nc.oD.substring(0, 8) : "UNKNOWN";
            String transmitter = (transmitterID != null) ? transmitterID.substring(0, 8) : "UNKNOWN";
            log.info("[CXN Relay] Received {} from {} (original: {}) - preparing relay...", eventType, transmitter, originalSender);

            CXNetwork cxn = connectX.getNetwork(ne.p.network);
            if (cxn != null && cxn.configuration != null && cxn.configuration.backendSet != null) {
                // Send to all peers, with backend getting priority (sent first)
                Set<String> sentTo = new HashSet<>();

                // Priority: Send to backend infrastructure first
                for (String backendID : cxn.configuration.backendSet) {
                    if (!backendID.equals(connectX.getOwnID()) && !backendID.equals(transmitterID)) {
                        try {
                            Node backendNode = peerDirectory.lookup(backendID, true, true);
                            if (backendNode != null) {
                                NetworkContainer relayContainer = new NetworkContainer();
                                relayContainer.se = "cxJSON1";
                                relayContainer.s = false;
                                relayContainer.tP = tP; // Preserve TransmitPref
                                relayContainer.oD = nc.oD; // Preserve original sender
                                // Use signedEventBytes to preserve original NetworkEvent signature
                                OutputBundle relayBundle = new OutputBundle(ne, null, null, signedEventBytes, relayContainer);
                                connectX.queueEvent(relayBundle);
                                sentTo.add(backendID);
                            }
                        } catch (Exception e) {
                            log.error("An error has occurred inside NodeMesh", e);
                        }
                    }
                }

                // Then send to all other peers (not already sent to)
                // Try hv peers first (high-value/stored), then seen (real-time active connections)
                for (Node peer : peerDirectory.hv.values()) {
                    if (!peer.cxID.equals(connectX.getOwnID()) &&
                        !peer.cxID.equals(transmitterID) &&
                        !sentTo.contains(peer.cxID)) {
                        try {
                            NetworkContainer relayContainer = new NetworkContainer();
                            relayContainer.se = "cxJSON1";
                            relayContainer.s = false;
                            relayContainer.tP = tP; // Preserve TransmitPref
                            relayContainer.oD = nc.oD; // Preserve original sender
                            // Use signedEventBytes to preserve original NetworkEvent signature
                            OutputBundle relayBundle = new OutputBundle(ne, null, null, signedEventBytes, relayContainer);
                            connectX.queueEvent(relayBundle);
                            sentTo.add(peer.cxID);
                        } catch (Exception e) {
                            log.error("An error has occurred inside NodeMesh", e);
                        }
                    }
                }

                // Also try seen peers (may have additional real-time connections not in hv)
                for (Node peer : peerDirectory.seen.values()) {
                    if (!peer.cxID.equals(connectX.getOwnID()) &&
                        !peer.cxID.equals(transmitterID) &&
                        !sentTo.contains(peer.cxID)) {
                        try {
                            NetworkContainer relayContainer = new NetworkContainer();
                            relayContainer.se = "cxJSON1";
                            relayContainer.s = false;
                            relayContainer.tP = tP; // Preserve TransmitPref
                            relayContainer.oD = nc.oD; // Preserve original sender
                            // Use signedEventBytes to preserve original NetworkEvent signature
                            OutputBundle relayBundle = new OutputBundle(ne, null, null, signedEventBytes, relayContainer);
                            connectX.queueEvent(relayBundle);
                            sentTo.add(peer.cxID);
                        } catch (Exception e) {
                            log.error("An error has occurred inside NodeMesh", e);
                        }
                    }
                }

                // CRITICAL: Also check DataContainer for locally discovered peers
                // These peers may not be in peerDirectory.hv/seen yet, but are locally reachable via LAN
                if (connectX.dataContainer != null && connectX.dataContainer.localPeerAddresses != null) {
                    for (String peerID : connectX.dataContainer.localPeerAddresses.keySet()) {
                        if (!peerID.equals(connectX.getOwnID()) &&
                            !peerID.equals(transmitterID) &&
                            !sentTo.contains(peerID)) {
                            try {
                                // Try to relay to this LAN peer
                                NetworkContainer relayContainer = new NetworkContainer();
                                relayContainer.se = "cxJSON1";
                                relayContainer.s = false;
                                relayContainer.tP = tP; // Preserve TransmitPref
                                relayContainer.oD = nc.oD; // Preserve original sender

                                // Create temporary node with LAN address for transmission
                                Node tempPeer = new Node();
                                tempPeer.cxID = peerID;
                                List<String> addresses = connectX.dataContainer.getLocalPeerAddresses(peerID);
                                if (!addresses.isEmpty()) {
                                    tempPeer.addr = addresses.get(0); // Use first known LAN address
                                }

                                // Use signedEventBytes to preserve original NetworkEvent signature
                                OutputBundle relayBundle = new OutputBundle(ne, tempPeer, null, signedEventBytes, relayContainer);
                                connectX.queueEvent(relayBundle);
                                sentTo.add(peerID);
                            } catch (Exception e) {
                                log.error("An error has occurred inside NodeMesh", e);
                            }
                        }
                    }
                }

                // Log relay summary
                log.info("[CXN Relay] Queued {} for relay to {} peers", eventType, sentTo.size());
            }
        }

        // Step 5: Record to blockchain if scope is CX (must be from authorized sender)
        if (ne.p != null && ne.p.scope != null && ne.p.scope.equalsIgnoreCase("CX")) {
            // CX scope requires authorization from CXNET backendSet or NMI
            if (nc != null && nc.iD != null) {
                connectX.Event(ne, nc.iD, signedEventBytes);
            }
        }

        return handledLocally;
    }
    public boolean connectNetwork(CXNetwork cxnet) {
        //TODO implement network connection
        return false;
    }

    /**
     * Apply a block to the blockchain after consensus or authoritative source
     * Handles adding to chain, persisting to disk, and queuing state events
     */
    private void applyBlockToChain(ConnectX connectX, CXNetwork network,
                                  NetworkRecord targetChain,
                                  NetworkBlock block,
                                  String networkID, Long chainID, String senderID) {
        try {
            // Add block to local blockchain in memory
            targetChain.blockMap.put(block.block, block);
            log.info("[BLOCK_APPLY] Added block {} to chain {} in memory", block.block, chainID);

            // Update current block pointer if this is the latest block
            if (targetChain.current == null || block.block > targetChain.current.block) {
                targetChain.current = block;
                log.info("[BLOCK_APPLY] Updated current block to {}", block.block);
            }

            // Save block to disk for persistence
            try {
                connectX.blockchainPersistence.saveBlock(networkID, chainID, block);
                log.info("[BLOCK_APPLY] Saved block {} to disk", block.block);
            } catch (Exception e) {
                log.info("[BLOCK_APPLY] Failed to save block to disk: {}", e.getMessage());
            }

            // Prepare block: verify and deserialize all events
            block.prepare(connectX);

            // Queue state-modifying events for processing to rebuild state
            int stateEvents = 0;
            int skippedEvents = 0;
            for (NetworkEvent event : block.deserializedEvents.values()) {
                if (event.executeOnSync) {
                    // Queue event for processing through existing framework
                    NetworkContainer eventContainer =
                        new NetworkContainer();
                    eventContainer.se = "cxJSON1";
                    eventContainer.s = false;
                    eventContainer.iD = senderID; // Mark as coming from the sender

                    InputBundle ib = new InputBundle(event, eventContainer);
                    connectX.eventQueue.add(ib);

                    stateEvents++;
                    log.info("[BLOCK_SYNC] Queued state event: {}", event.eT);
                } else {
                    // Skip ephemeral events (messages, pings, etc.)
                    skippedEvents++;
                }
            }

            log.info("[BLOCK_SYNC] Block {} processed:", block.block);
            log.info("  - Added to chain {}", chainID);
            log.info("  - Saved to disk");
            log.info("  - Queued {} state events", stateEvents);
            log.info("  - Skipped {} ephemeral events", skippedEvents);

        } catch (Exception e) {
            log.info("[BLOCK_APPLY] Error applying block ", e);
        }
    }

    private void initiateSyncFromPeer(ConnectX connectX, CXNetwork network, String networkID,
                                     ChainStatus remoteChainStatus, String peerID) {
        try {
            long remoteC1 = remoteChainStatus.c1 != null ? remoteChainStatus.c1 : 0L;
            long remoteC2 = remoteChainStatus.c2 != null ? remoteChainStatus.c2 : 0L;
            long remoteC3 = remoteChainStatus.c3 != null ? remoteChainStatus.c3 : 0L;

            long localC1 = network.c1.current != null ? network.c1.current.block : -1;
            long localC2 = network.c2.current != null ? network.c2.current.block : -1;
            long localC3 = network.c3.current != null ? network.c3.current.block : -1;

            log.info("[CHAIN_SYNC] Local chain heights:");
            log.info("  c1: {}", localC1);
            log.info("  c2: {}", localC2);
            log.info("  c3: {}", localC3);

            Node remotePeer = peerDirectory.lookup(peerID, true, true);
            if (remotePeer == null) {
                log.info("[CHAIN_SYNC] Cannot sync - peer not found: {}", peerID);
                return;
            }

            // Sync c1 (Admin chain) first - most critical for state
            if (remoteC1 > localC1) {
                log.info("[CHAIN_SYNC] c1 is behind, requesting {} blocks", remoteC1 - localC1);
                requestMissingBlocks(connectX, networkID, network.networkDictionary.c1, localC1 + 1, remoteC1, remotePeer);
            }

            // Sync c2 (Resources chain)
            if (remoteC2 > localC2) {
                log.info("[CHAIN_SYNC] c2 is behind, requesting {} blocks", remoteC2 - localC2);
                requestMissingBlocks(connectX, networkID, network.networkDictionary.c2, localC2 + 1, remoteC2, remotePeer);
            }

            // Sync c3 (Events chain)
            if (remoteC3 > localC3) {
                log.info("[CHAIN_SYNC] c3 is behind, requesting {} blocks", remoteC3 - localC3);
                requestMissingBlocks(connectX, networkID, network.networkDictionary.c3, localC3 + 1, remoteC3, remotePeer);
            }

            if (remoteC1 <= localC1 && remoteC2 <= localC2 && remoteC3 <= localC3) {
                log.info("[CHAIN_SYNC] Local chains are up to date!");
            }
        } catch (Exception e) {
            log.error("[CHAIN_SYNC] Error initiating sync ", e);
        }
    }

    /**
     * Request a range of missing blocks from a peer
     * Sends BLOCK_REQUEST for each block in the range
     */
    private void requestMissingBlocks(ConnectX connectX, String networkID, Long chainID,
                                     long startBlock, long endBlock, Node remotePeer) {
        try {
            for (long blockNum = startBlock; blockNum <= endBlock; blockNum++) {
                String requestJson = ConnectX.serialize("cxJSON1",
                    new BlockExchange(networkID, chainID, blockNum));

                // Send request using EventBuilder pattern
                ConnectX.EventBuilder eb = connectX.buildEvent(EventType.BLOCK_REQUEST, requestJson.getBytes(StandardCharsets.UTF_8))
                    .toPeer(remotePeer.cxID)
                    .signData();
                eb.getPath().network = networkID;
                eb.queue();

                log.info("[CHAIN_SYNC] Requested block {} from chain {}", blockNum, chainID);
            }
        } catch (Exception e) {
            log.error("[CHAIN_SYNC] Error requesting blocks ", e);
        }
    }

    /**
     * Apply a seed after consensus decision
     * Saves EPOCH seeds to disk, adds peers, caches certificates, registers networks
     */
    private void applySeedConsensus(ConnectX connectX, Seed seed,
                                          boolean isEpochSeed, String consensusReason, String targetNetwork) {
        try {
            log.info("[SEED CONSENSUS] Applying seed: {} | reason: {} | networks={} peers={} certs={}",
                seed.seedID, consensusReason, seed.networks.size(), seed.hvPeers.size(), seed.certificates.size());

            // Add peers to directory (skip self by cxID or public key)
            int peersAdded = 0;
            for (Node peer : seed.hvPeers) {
                if (connectX.isSelfNode(peer)) {
                    log.info("[SEED CONSENSUS] Skipped self in hvPeers: " + peer.cxID);
                    continue;
                }
                try {
                    peerDirectory.addNode(peer);
                    peersAdded++;
                } catch (Exception e) {
                    // Ignore duplicate peer errors
                }
            }
            log.info("[SEED CONSENSUS] ✓ Added " + peersAdded + " peers to directory");

            // Apply seed to ConnectX (registers networks)
            seed.apply(connectX);

            // Cache certificates
            int certsAdded = 0;
            for (Map.Entry<String, String> cert : seed.certificates.entrySet()) {
                try {
                    connectX.encryptionProvider.cacheCert(cert.getKey(), false, false, connectX);
                    certsAdded++;
                } catch (Exception e) {
                    // Ignore cert errors
                }
            }
            log.info("[SEED CONSENSUS] Network {} ready: {} certs cached", targetNetwork, certsAdded);

        } catch (Exception e) {
            log.error("[SEED CONSENSUS] Error applying seed", e);
        }
    }

    /**
     * Perform multi-peer consensus voting on dynamic seeds
     * Compares chain heights, detects conflicts, applies majority consensus
     * If conflicts detected, requests fresh seed from EPOCH as tiebreaker
     */
    private static void performSeedConsensus(ConnectX connectX, String targetNetwork,
                                            ConcurrentHashMap<String, ConnectX.SeedResponseData> responses) {
        try {
            log.info("[SEED CONSENSUS] Multi-peer voting for {} with {} responses", targetNetwork, responses.size());

            // Priority 1: Check if EPOCH responded with a dynamic seed (authoritative)
            for (ConnectX.SeedResponseData r : responses.values()) {
                if (r.authoritative && r.dynamicSeed != null) {
                    log.info("[SEED CONSENSUS] EPOCH authoritative dynamic seed -- applying");
                    connectX.nodeMesh.applySeedConsensus(connectX, r.dynamicSeed, false,
                        "EPOCH dynamic seed (authoritative)", targetNetwork);
                    return;
                }
            }

            // Priority 2: Compare chain heights across peers for consensus
            Map<String, Integer> heightVotes = new HashMap<>();
            for (ConnectX.SeedResponseData r : responses.values()) {
                if (r.chainHeights != null) {
                    String heightSig = "c1:" + r.chainHeights.get("c1") +
                                     ",c2:" + r.chainHeights.get("c2") +
                                     ",c3:" + r.chainHeights.get("c3");
                    heightVotes.put(heightSig, heightVotes.getOrDefault(heightSig, 0) + 1);
                }
            }

            String majorityHeights = null;
            int maxVotes = 0;
            for (Map.Entry<String, Integer> vote : heightVotes.entrySet()) {
                if (vote.getValue() > maxVotes) {
                    maxVotes = vote.getValue();
                    majorityHeights = vote.getKey();
                }
            }

            double consensusPercent = responses.isEmpty() ? 0.0 : (double) maxVotes / responses.size();
            log.info("[SEED CONSENSUS] Majority: {}/{} ({}%)", maxVotes, responses.size(),
                String.format("%.0f", consensusPercent * 100));

            if (consensusPercent >= 0.51) {
                for (ConnectX.SeedResponseData r : responses.values()) {
                    if (r.chainHeights != null) {
                        String heightSig = "c1:" + r.chainHeights.get("c1") +
                                         ",c2:" + r.chainHeights.get("c2") +
                                         ",c3:" + r.chainHeights.get("c3");
                        if (heightSig.equals(majorityHeights) && r.dynamicSeed != null) {
                            connectX.nodeMesh.applySeedConsensus(connectX, r.dynamicSeed, false,
                                "Peer consensus (" + String.format("%.0f%%", consensusPercent * 100) +
                                " agreement) from " + r.senderID.substring(0, 8), targetNetwork);
                            return;
                        }
                    }
                }
            } else {
                // No consensus -- fall back to local signed seed blob if available
                log.warn("[SEED CONSENSUS] Consensus failed ({}%) -- trying local seed blob fallback",
                    String.format("%.0f", consensusPercent * 100));
                File seedsDir = new File(connectX.cxRoot, "seeds");
                if (seedsDir.exists()) {
                    File[] seedFiles = seedsDir.listFiles((dir, name) -> name.endsWith(".cxn"));
                    if (seedFiles != null && seedFiles.length > 0) {
                        File latestSeed = seedFiles[0];
                        for (File f : seedFiles) {
                            if (f.lastModified() > latestSeed.lastModified()) latestSeed = f;
                        }
                        log.info("[SEED CONSENSUS] Loading local seed blob: {}", latestSeed.getName());
                        byte[] blob = java.nio.file.Files.readAllBytes(latestSeed.toPath());
                        connectX.applySignedSeed(blob);
                        return;
                    }
                }
                log.error("[SEED CONSENSUS] Cannot resolve -- no local seed blob available");
            }

        } catch (Exception e) {
            log.error("[SEED CONSENSUS] Voting error", e);
        }
    }
}
