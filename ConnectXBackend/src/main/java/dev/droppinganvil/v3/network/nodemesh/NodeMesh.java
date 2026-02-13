package dev.droppinganvil.v3.network.nodemesh;

import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import dev.droppinganvil.v3.ConnectX;
import dev.droppinganvil.v3.analytics.AnalyticData;
import dev.droppinganvil.v3.analytics.Analytics;
import dev.droppinganvil.v3.crypt.core.exceptions.DecryptionFailureException;
import dev.droppinganvil.v3.crypt.pgpainless.PainlessCryptProvider;
import dev.droppinganvil.v3.network.*;
import dev.droppinganvil.v3.network.events.CXHello;
import dev.droppinganvil.v3.network.events.EventType;
import dev.droppinganvil.v3.network.events.NetworkContainer;
import dev.droppinganvil.v3.network.events.NetworkEvent;
import org.bouncycastle.openpgp.PGPPublicKeyRing;
import org.pgpainless.decryption_verification.OpenPgpMetadata;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

public class NodeMesh {
    // Global static fields for security (shared across all peers)
    public static ConcurrentHashMap<String, Integer> timeout = new ConcurrentHashMap<>();
    public static ConcurrentHashMap<String, String> blacklist = new ConcurrentHashMap<>();
    public HashSet<String> ownAddresses = new HashSet<>();
    public static ThreadPoolExecutor threadPool = (ThreadPoolExecutor) Executors.newFixedThreadPool(NodeConfig.pThreads);

    // Instance fields (per-peer)
    public ServerSocket serverSocket;
    public ConcurrentHashMap<String, ArrayList<String>> transmissionIDMap = new ConcurrentHashMap<>();
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
    private ConcurrentHashMap<String, java.util.List<Long>> peerRequestTimestamps = new ConcurrentHashMap<>();
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
    public static NodeMesh initializeNetwork(dev.droppinganvil.v3.ConnectX connectX, int port, OutConnectionController outController) throws IOException {
        // Create NodeMesh instance
        NodeMesh nodeMesh = new NodeMesh(connectX);

        // Initialize InConnectionManager with ServerSocket
        nodeMesh.in = new InConnectionManager(port, nodeMesh);

        // Start IO worker threads for job queue processing
        for (int i = 0; i < NodeConfig.ioThreads; i++) {
            dev.droppinganvil.v3.io.IOThread ioWorker = new dev.droppinganvil.v3.io.IOThread(NodeConfig.IO_THREAD_SLEEP, connectX, nodeMesh);
            ioWorker.run = true; // Enable the worker
            Thread ioThread = new Thread(ioWorker);
            ioThread.setName("IOThread-" + i);
            ioThread.start();
        }

        // Start SocketWatcher for incoming connections
        Thread socketWatcher = new Thread(new dev.droppinganvil.v3.network.threads.SocketWatcher(connectX, nodeMesh));
        socketWatcher.setName("SocketWatcher");
        socketWatcher.start();

        // Start EventProcessor for processing eventQueue
        Thread eventProcessor = new Thread(new dev.droppinganvil.v3.network.threads.EventProcessor(nodeMesh));
        eventProcessor.setName("EventProcessor");
        eventProcessor.start();

        // Start multiple OutputProcessor threads for parallel event processing
        // This significantly improves CXHELLO discovery timing by processing events concurrently
        System.out.println("[NodeMesh] Starting " + NodeConfig.outputProcessorThreads + " OutputProcessor threads");
        for (int i = 0; i < NodeConfig.outputProcessorThreads; i++) {
            Thread outputProcessor = new Thread(new dev.droppinganvil.v3.network.threads.OutputProcessor(outController));
            outputProcessor.setName("OutputProcessor-" + i);
            outputProcessor.start();
        }

        // Start RetryProcessor for handling failed events with exponential backoff
        Thread retryProcessor = new Thread(new dev.droppinganvil.v3.network.threads.RetryProcessor(outController));
        retryProcessor.setName("RetryProcessor");
        retryProcessor.start();

        return nodeMesh;
    }
    public void processNetworkInput(InputStream is, Socket socket) throws IOException, DecryptionFailureException, ClassNotFoundException, UnauthorizedNetworkConnectivityException {
        //TODO optimize streams
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        //TODO max size
        NetworkContainer nc;
        NetworkEvent ne;
        ByteArrayOutputStream baoss = new ByteArrayOutputStream();
        ByteArrayInputStream bais;
        String networkEvent = "";
        /**
         * Use new InputBundle features
         */
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
        String unverifiedContainerJson = peekBaos.toString("UTF-8");
        peekStream.close();
        peekBaos.close();

        // Deserialize UNVERIFIED container to extract transmitter ID
        try {
            NetworkContainer unverifiedContainer = (NetworkContainer) ConnectX.deserialize("cxJSON1", unverifiedContainerJson, NetworkContainer.class);

            if (unverifiedContainer.iD == null) {
                // No transmitter ID - check if this is an unauthenticated message (CXHELLO/NewNode)
                // These are the ONLY messages allowed without pre-known sender identity
                // We'll handle verification after importing the public key below
                Analytics.addData(AnalyticData.Tear, "NetworkContainer missing transmitter ID");
                throw new DecryptionFailureException();
            }

            ConnectX.checkSafety(unverifiedContainer.iD);

            if (!ConnectX.isProviderPresent(unverifiedContainer.se)) {
                if (socket != null) socket.close();
                Analytics.addData(AnalyticData.Tear, "Unsupported serialization method " + unverifiedContainer.se);
                return;
            }

            // Step 3: Check if we have the transmitter's public key
            boolean hasPublicKey = connectX.encryptionProvider.cacheCert(unverifiedContainer.iD, false, false, connectX);

            if (!hasPublicKey) {
                // Unknown sender - ONLY CXHELLO and NewNode are allowed from unknown senders
                // Peek at event type to check if this is an unauthenticated bootstrap message
                ByteArrayInputStream eventPeekStream = new ByteArrayInputStream(unverifiedContainer.e);
                ByteArrayOutputStream eventPeekBaos = new ByteArrayOutputStream();
                connectX.encryptionProvider.stripSignature(eventPeekStream, eventPeekBaos);
                String eventJson = eventPeekBaos.toString("UTF-8");
                NetworkEvent peekedEvent = (NetworkEvent) ConnectX.deserialize(unverifiedContainer.se, eventJson, NetworkEvent.class);
                eventPeekStream.close();
                eventPeekBaos.close();

                if (peekedEvent.eT == null || !(peekedEvent.eT.equals("NewNode") || peekedEvent.eT.equals("CXHELLO") || peekedEvent.eT.equals("CXHELLO_RESPONSE") || peekedEvent.eT.equals("SEED_REQUEST") || peekedEvent.eT.equals("PeerFinding"))) {
                    // NOT a bootstrap message from unknown sender - REJECT
                    if (socket != null) socket.close();
                    Analytics.addData(AnalyticData.Tear, "Message from unknown sender " + unverifiedContainer.iD +
                                    " (event: " + peekedEvent.eT + ") - only CXHELLO/NewNode allowed");
                    System.err.println("[SECURITY] Rejected " + peekedEvent.eT + " from unknown sender " +
                                     unverifiedContainer.iD.substring(0, 8));
                    return;
                }

                // This IS a CXHELLO or NewNode - extract and import public key BEFORE verification
                System.out.println("[NodeMesh] Processing " + peekedEvent.eT + " from unknown sender (will import key first)");
                // NOTE: Key import and verification happens below in the NewNode/CXHELLO handling section
                // For now, we skip container verification and process the event
                // The event handler will import the key and THEN verify the container signature

                // Use the unverified container for now (will be verified after key import)
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
                    System.err.println("[SECURITY] NetworkContainer signature verification FAILED - rejecting message from " +
                                     unverifiedContainer.iD.substring(0, 8));
                    throw new DecryptionFailureException();
                }

                // Signature VERIFIED - we can now trust nc.iD and all container fields
                String verifiedContainerJson = verifiedBaos.toString("UTF-8");
                nc = (NetworkContainer) ConnectX.deserialize("cxJSON1", verifiedContainerJson, NetworkContainer.class);
                verifiedBaos.close();
                baos.close();

                System.out.println("[SECURITY] ✓ NetworkContainer signature VERIFIED for " + nc.iD.substring(0, 8));
            }
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
                String strippedEventJson = stripBaos.toString("UTF-8");
                stripBaos.close(); // Close the strip stream
                NetworkEvent parsedEvent = (NetworkEvent) ConnectX.deserialize(nc.se, strippedEventJson, NetworkEvent.class);



                /// At this point in the code path we should have already done the basic input bundle processing
            /// we will create it now so we can implement new features of InputBundle
            ib = new InputBundle(parsedEvent, nc);

                // Check if this is a NewNode or CXHELLO event (both need public key import before verification)
                if (parsedEvent.eT != null && (parsedEvent.eT.equals("NewNode") || parsedEvent.eT.equals("CXHELLO"))) {
                    if (nc.iD.equals(connectX.getOwnID())) {
                        System.out.println("[NodeMesh] Rejecting peer finding from self");
                        ownAddresses.add(socket.getLocalSocketAddress().toString());
                        return;
                    }
                    System.out.println("[NodeMesh] Processing " + parsedEvent.eT + " event from " + nc.iD);

                    // Import node BEFORE verification (we need the public key)
                    Node importedNode = null;
                    byte[] signedNodeBlobForPersistence = null; // For CXHELLO persistence
                    if (parsedEvent.d != null && parsedEvent.d.length > 0) {
                        try {
                            Node newNode;

                            // Extract Node from payload based on event type
                            if (parsedEvent.eT.equals("CXHELLO")) {
                                // CXHELLO payload is SIGNED - strip signature before deserializing
                                ByteArrayInputStream signedCXHelloInput = new ByteArrayInputStream(parsedEvent.d);
                                ByteArrayOutputStream strippedCXHelloOutput = new ByteArrayOutputStream();
                                connectX.encryptionProvider.stripSignature(signedCXHelloInput, strippedCXHelloOutput);
                                String payloadJson = strippedCXHelloOutput.toString("UTF-8");
                                signedCXHelloInput.close();
                                strippedCXHelloOutput.close();


                                // CXHELLO payload: CXHello class with signedNode byte array
                                dev.droppinganvil.v3.network.events.CXHello cxhelloData =
                                    (dev.droppinganvil.v3.network.events.CXHello) ConnectX.deserialize("cxJSON1", payloadJson,
                                        dev.droppinganvil.v3.network.events.CXHello.class);

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
                                    java.io.ByteArrayInputStream signedNodeInput = new java.io.ByteArrayInputStream(signedNodeBlob);
                                    java.io.ByteArrayOutputStream nodeOutput = new java.io.ByteArrayOutputStream();
                                    connectX.encryptionProvider.stripSignature(signedNodeInput, nodeOutput);
                                    signedNodeInput.close();

                                    // Step 2: Deserialize to Node object (contains public key)
                                    String nodeJson = nodeOutput.toString("UTF-8");
                                    newNode = (Node) ConnectX.deserialize("cxJSON1", nodeJson, Node.class);
                                    nodeOutput.close();

                                    // Save signed blob for .cxi persistence AND later verification
                                    signedNodeBlobForPersistence = signedNodeBlob;

                                    System.out.println("[NodeMesh] CXHELLO: Extracted Node from unknown peer (will verify after import)");
                                } else {
                                    System.err.println("[NodeMesh] CXHELLO missing signedNode for " + nc.iD.substring(0, 8));
                                    newNode = null;
                                }
                            } else {
                                // NewNode payload is a SIGNED Node object (from EventBuilder.signData())
                                String originSender = parsedEvent.p != null ? parsedEvent.p.oCXID : null;

                                if (originSender == null) {
                                    System.err.println("[NodeMesh] NewNode missing oCXID in path");
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

                                    if (verifyResult == null) {
                                        System.err.println("[NodeMesh] NewNode payload signature verification FAILED for " + originSender);
                                        throw new DecryptionFailureException();
                                    }
                                } else {
                                    System.out.println("[NodeMesh] Processing NewNode from unknown sender (will import key first)");
                                    // Unknown sender: strip signature WITHOUT verification (we'll verify container signature after import)
                                    connectX.encryptionProvider.stripSignature(signedPayloadStream, strippedPayloadStream);
                                }

                                String strippedJson = strippedPayloadStream.toString("UTF-8");
                                //Strip signature from Event Payload
                                ByteArrayInputStream baiss = new ByteArrayInputStream(strippedJson.getBytes("UTF-8"));
                                ByteArrayOutputStream fullyStrippedEvent = new ByteArrayOutputStream();
                                connectX.encryptionProvider.stripSignature(baiss, fullyStrippedEvent);
                                String fullyStrippedJSON = fullyStrippedEvent.toString("UTF-8");
                                newNode = (Node) ConnectX.deserialize("cxJSON1", fullyStrippedJSON, Node.class);

                                signedPayloadStream.close();
                                strippedPayloadStream.close();
                            }

                            // SECURITY: Validate node data
                            if (newNode.cxID == null || newNode.publicKey == null) {
                                System.err.println("[NodeMesh] NewNode missing cxID or publicKey");
                                throw new DecryptionFailureException();
                            }

                            // SECURITY: Verify transmitter matches the node being added
                            if (!newNode.cxID.equals(nc.iD)) {
                                System.err.println("[NodeMesh] NewNode cxID mismatch: " + newNode.cxID + " vs " + nc.iD);
                                throw new DecryptionFailureException();
                            }

                            // Import the node with signed blob for CXHELLO persistence
                            if (signedNodeBlobForPersistence != null) {
                                peerDirectory.addNode(newNode, signedNodeBlobForPersistence, connectX.cxRoot);
                                System.out.println("[NodeMesh] Imported and PERSISTED CXHELLO node: " + newNode.cxID.substring(0, 8));
                            } else {
                                peerDirectory.addNode(newNode);
                                System.out.println("[NodeMesh] Imported NewNode: " + newNode.cxID.substring(0, 8));
                            }
                            importedNode = newNode;

                            // Now VERIFY the signature using the imported public key
                            ByteArrayInputStream verifyBais = new ByteArrayInputStream(nc.e);
                            ByteArrayOutputStream verifyBaos = new ByteArrayOutputStream();
                            o1 = connectX.encryptionProvider.verifyAndStrip(verifyBais, verifyBaos, nc.iD);

                            if (o1 == null) {
                                System.err.println("[NodeMesh] NewNode/CXHELLO container signature verification FAILED for " + nc.iD);
                                // Rollback: Remove the imported node (memory AND filesystem)
                                peerDirectory.removeNode(importedNode.cxID, connectX.cxRoot);
                                throw new DecryptionFailureException();
                            }
                            System.out.println("[NodeMesh] Container signature VERIFIED for " + newNode.cxID);
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
                                    System.err.println("[NodeMesh] CXHELLO signedNode verification FAILED for " + nc.iD);
                                    // Rollback: Remove the imported node (memory AND filesystem)
                                    peerDirectory.removeNode(importedNode.cxID, connectX.cxRoot);
                                    throw new DecryptionFailureException();
                                }
                                System.out.println("[NodeMesh] CXHELLO signedNode signature VERIFIED for " + newNode.cxID);
                            }

                        } catch (DecryptionFailureException e) {
                            throw e;
                        } catch (Exception e) {
                            e.printStackTrace();
                            System.err.println("[NodeMesh] Failed to process NewNode: " + e.getMessage());
                            if (importedNode != null) {
                                // Rollback: Remove the imported node (memory AND filesystem)
                                peerDirectory.removeNode(importedNode.cxID, connectX.cxRoot);
                            }
                            throw new DecryptionFailureException();
                        }
                    } else {
                        System.err.println("[NodeMesh] NewNode event has no data");
                        throw new DecryptionFailureException();
                    }

                    // Use the already parsed event
                    ne = parsedEvent;
                    networkEvent = strippedEventJson;
                    System.out.print("Reached event data, data is never used and will be cleaned up by GC shortly");

                } else {
                    // Standard event: Verify signature (we already have public key)
                    ByteArrayInputStream verifyBais = new ByteArrayInputStream(nc.e);
                    o1 = connectX.encryptionProvider.verifyAndStrip(verifyBais, baoss, nc.iD);
                    networkEvent = baoss.toString("UTF-8");
                    ne = (NetworkEvent) ConnectX.deserialize(nc.se, networkEvent, NetworkEvent.class);
                    verifyBais.close();

                    //Add o1 check
                    if (!(boolean) o1) {
                        System.out.println("[NodeMesh] Signature verification failure, rejecting event. 003");
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
                        System.out.println("[NodeMesh] Internal event data verification failure, rejecting event. 004");
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
                    OpenPgpMetadata o3 = (OpenPgpMetadata) connectX.encryptionProvider.decrypt(baiss, decryptedJSON, ne.p.oCXID, true);
                    byte[] arr1 = null;
                    arr1 = decryptedJSON.toByteArray();
                    if (arr1 != null && arr1.length > 0) {
                        //TODO This is fine for testing, but, we need to consider better handling of E2E events when peers have not met. Possibly including origin node data in E2E coms
                        String senderCXID = ne.p.oCXID;
                        //TODO More PGP specific code
                        connectX.nodeMesh.peerDirectory.lookup(senderCXID, true, true);
                        PGPPublicKeyRing originPub = ((PainlessCryptProvider) connectX.encryptionProvider).certCache.get(senderCXID);

                        if (senderCXID != null && originPub != null && o3.containsVerifiedSignatureFrom(originPub)) {
                            ib.verifiedObjectBytes = arr1;
                        } else {
                            System.out.println("[NodeMesh] E2E Decryption validation failure, 005");
                            System.out.println(senderCXID);
                            System.out.println(originPub.getPublicKey().toString());
                            System.out.println(o3.containsVerifiedSignatureFrom(originPub));
                        }
                    } else {
                        System.out.println("[NodeMesh] Internal event data verification failure, rejecting event. 006");
                        return;
                    }
                    baiss.close();
                    decryptedJSON.close();
                }

                }
            }
            bais.close();
            baoss.close();

            // Track seen peers (memory-efficient using seenCXIDs list)
            if (nc.iD != null && !peerDirectory.seenCXIDs.contains(nc.iD)) {
                peerDirectory.seenCXIDs.add(nc.iD);
            }

            // SECURITY: Validate CXNET and CX scope messages
            // Only CXNET backendSet or NMI can send CXNET or CX-scoped messages
            if (ne.p != null && ne.p.scope != null &&
                (ne.p.scope.equalsIgnoreCase("CXNET") || ne.p.scope.equalsIgnoreCase("CX"))) {
                CXNetwork cxnet = connectX.getNetwork("CXNET");
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
            e.printStackTrace();
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
        ArrayList<String> seenFrom = transmissionIDMap.get(ne.iD);
        if (seenFrom != null) {
            // We've already seen this event - drop it (don't process or relay again)
            // Add this transmitter to the list for tracking purposes
            if (!seenFrom.contains(nc.iD)) {
                seenFrom.add(nc.iD);
            }
            if (socket != null) socket.close();
            return; // Duplicate event - drop silently
        } else {
            // First time seeing this event - record it and continue processing
            ArrayList<String> transmitters = new ArrayList<>();
            transmitters.add(nc.iD);
            transmissionIDMap.put(ne.iD, transmitters);

            // Cleanup old entries to prevent unbounded memory growth
            // Keep last 10000 event IDs
            if (transmissionIDMap.size() > 10000) {
                // Remove oldest 1000 entries (simple FIFO cleanup)
                java.util.Iterator<String> iterator = transmissionIDMap.keySet().iterator();
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
                    System.err.println("[WHITELIST] Rejected transmission from unregistered node " +
                                     nc.iD + " to whitelist network " + ne.p.network);
                    return; // Drop the event - do not queue
                }
                // Node is registered - allow transmission
                System.out.println("[WHITELIST] Accepted transmission from registered node " +
                                 nc.iD + " to network " + ne.p.network);
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

                dev.droppinganvil.v3.network.events.CXHello helloData;

                /// If for some reason old method is used try it, otherwise use new InputBundle features
                if (ib.object == null) {
                    /// Try new InputBundle features first
                if (!ib.readyObject(CXHello.class, ib.nc.se, connectX)) {
                    System.out.println("[NODEMESH] WARNING: Deprecated use of NodeMesh, Use new InputBundle features!");
                    // Both CXHELLO and CXHELLO_RESPONSE payloads are now PGP-signed
                    // Strip signature before deserializing
                    ByteArrayInputStream signedInput = new ByteArrayInputStream(ne.d);
                    ByteArrayOutputStream strippedOutput = new ByteArrayOutputStream();
                    connectX.encryptionProvider.stripSignature(signedInput, strippedOutput);
                    String helloJson = strippedOutput.toString("UTF-8");
                    signedInput.close();
                    strippedOutput.close();

                    // Deserialize CXHello class
                    helloData =
                            (dev.droppinganvil.v3.network.events.CXHello) ConnectX.deserialize("cxJSON1", helloJson,
                                    dev.droppinganvil.v3.network.events.CXHello.class);
                } else {
                    helloData = (CXHello) ib.object;
                }
                } else {
                    helloData = (dev.droppinganvil.v3.network.events.CXHello) ib.object;
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
                        System.out.println("[processNetworkInput] " + et.name() + ": Recorded socket-based address " +
                                socketBasedAddress + " for " + requestPeerID.substring(0, 8));
                    }

                    // 2. Record peer-provided address (HIGHEST priority - will be at index 0)
                    if (peerProvidedAddress != null && !peerProvidedAddress.isEmpty()) {
                        connectX.dataContainer.recordLocalPeer(requestPeerID, peerProvidedAddress, true);
                        System.out.println("[processNetworkInput] " + et.name() + ": Recorded PEER-PROVIDED address (highest priority) " +
                                peerProvidedAddress + " for " + requestPeerID.substring(0, 8));
                    }
                }
            }
        } catch (Exception e) {
            // Ignore errors in CXHELLO/CXHELLO_RESPONSE parsing - event will still be processed normally
            e.printStackTrace();
        }

        synchronized (connectX.eventQueue) {
            /// New InputBundle features handling
            connectX.eventQueue.add(ib);
        }
    }

    public void processEvent() throws IOException, DecryptionFailureException {
        synchronized (connectX.eventQueue) {
            InputBundle ib = connectX.eventQueue.poll();
            if (ib == null) return; // Queue is empty

            // Storage for decrypted event data (preserves original ib.ne.d)
            byte[] eventData;

            // Decrypt E2E encrypted events
            boolean e2eDecryptionFailed = false;
            if (ib.ne.e2e) {
                try {
                    System.out.println("[E2E] Decrypting E2E encrypted event");
                    ByteArrayInputStream encryptedInput = new ByteArrayInputStream(ib.ne.d);
                    ByteArrayOutputStream decryptedOutput = new ByteArrayOutputStream();

                    // Decrypt the event data
                    connectX.encryptionProvider.decrypt(encryptedInput, decryptedOutput);
                    encryptedInput.close();

                    // Store decrypted data (original ib.ne.d remains encrypted)
                    eventData = decryptedOutput.toByteArray();
                    decryptedOutput.close();

                    System.out.println("[E2E] Successfully decrypted E2E event data");
                } catch (Exception e) {
                    System.err.println("[E2E] Failed to decrypt E2E event (not intended for us): " + e.getMessage());
                    // E2E message not for us - skip application layer but STILL RELAY to other peers
                    e2eDecryptionFailed = true;
                    eventData = ib.ne.d; // Keep encrypted data for relay
                }
            } else {
                // No E2E encryption, use original data
                eventData = ib.ne.d;
            }

            System.out.print(ib.nc);
            System.out.print(ib.ne);
            System.out.print(ib.signedEventBytes);
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
                    System.out.println("Security failure, Missing CXPATH: " + ib.nc.oD + " Event Type: " + ib.ne.eT);
                    return;
                }

                //VERIFY CONTAINER AND EVENT (Layer 1 + 2)
                //ib.ne - SIGNED BY ORIGIN UNMODIFIABLE
                //ib.nc - SIGNED BY LAST HOP, MODIFIABLE
                //It is critcal that they match to enforce multilayer cryptography
                if (!Objects.equals(ib.ne.p.oCXID, ib.nc.oD)) {
                    Analytics.addData(AnalyticData.SecurityEvent, "Security failure, mismatched CXIDs: " + ib.ne.p.oCXID + ", " + ib.nc.oD + " Event Type: " + ib.ne.eT);
                    System.out.println("Security failure, mismatched CXIDs: " + ib.ne.p.oCXID + ", " + ib.nc.oD + " Event Type: " + ib.ne.eT);
                    return;
                }
                //VERIFY INTERNAL DATA PAYLOAD
                // SECURITY: Use oCXID (origin sender) for signature verification, NOT cxID (destination)
                // This prevents spoofing attacks where attacker sets cxID to victim's ID
                ByteArrayInputStream signedInput = new ByteArrayInputStream(ib.ne.d);
                ByteArrayOutputStream strippedOutput = new ByteArrayOutputStream();
                if (!ib.ne.e2e) {
                    if (!connectX.encryptionProvider.verifyAndStrip(signedInput, strippedOutput, ib.ne.p.oCXID)) {
                        Analytics.addData(AnalyticData.SecurityEvent, "Security failure, event payload data verification unsuccessful: " + ib.ne.p.oCXID + ", " + ib.nc.oD + " Event Type: " + ib.ne.eT);
                        System.out.println("Security failure, event payload data verification unsuccessful: " + ib.ne.p.oCXID + ", " + ib.nc.oD + " Event Type: " + ib.ne.eT);
                        return;
                    }
                    // Update eventData with stripped payload (signature removed)
                    eventData = strippedOutput.toByteArray();
                    // Store stripped bytes in InputBundle for fireEvent() to use
                    ib.strippedEventBytes = eventData;
                }
            }



            try {
                et = EventType.valueOf(ib.ne.eT);
            } catch (Exception ignored) {}
            if (et == null & !connectX.sendPluginEvent(ib, ib.ne.eT)) {
                Analytics.addData(AnalyticData.Tear, "Unsupported event - "+ib.ne.eT);
                System.out.print("UNABLE TO PROCESS UNKNOWN EVENT");
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
                    switch (et) {
                        case NewNode:
                            // NewNode events are now sent with SIGNED Node blobs (via .signData())
                            // Extract and verify the signed blob, then save it for relay
                            byte[] signedNodeBlob = eventData;

                            // Verify and deserialize the signed Node blob (signed by origin sender)
                            Node node = (Node) connectX.getSignedObject(
                                ib.ne.p.oCXID, // Origin sender's cxID for signature verification
                                new java.io.ByteArrayInputStream(signedNodeBlob),
                                Node.class,
                                "cxJSON1"
                            );

                            if (node == null || node.cxID == null) {
                                System.err.println("[NodeMesh] NewNode verification failed - invalid signature");
                                return;
                            }

                            // Check if we already have this node
                            Node node1 = peerDirectory.lookup(node.cxID, true, true, connectX.cxRoot, connectX);
                            if (node1 != null) {
                                connectX.encryptionProvider.cacheCert(node1.cxID, true, false, connectX);
                                System.out.println("[NodeMesh] NewNode already exists: " + node.cxID.substring(0, 8));
                                return;
                            }

                            // Add node WITH signed blob (preserves original signature for relay)
                            peerDirectory.addNode(node, signedNodeBlob, connectX.cxRoot);
                            System.out.println("[NodeMesh] Imported NewNode: " + node.cxID.substring(0, 8));
                            System.out.println("[NodeMesh] NewNode signature VERIFIED and SAVED for relay");
                            break;

                        case CXHELLO:
                            //if (!(ib.ne.p.getScope().equals(Scope.CXS) | ib.ne.p.getScope().equals(Scope.LAN))) {
                             //   System.out.println("[NodeMesh] ERROR: CXHELLO received in incorrect scope, Only CXS or LAN is allowed");
                            //    return;
                            //}
                            // CXHELLO sends signed Node blob - import it with persistence
                            System.out.println("[" + connectX.getOwnID() + "] CXHELLO request received from " + ib.nc.iD);

                            /// Deserialize CXHello payload - we have already done this earlier in the code path, use new InputBundle features

                     //       String cxhelloPayloadJson = new String(eventData, "UTF-8");
                      //      dev.droppinganvil.v3.network.events.CXHello cxhelloData =
                      //          (dev.droppinganvil.v3.network.events.CXHello) ConnectX.deserialize("cxJSON1", cxhelloPayloadJson,
                      //              dev.droppinganvil.v3.network.events.CXHello.class);
                            dev.droppinganvil.v3.network.events.CXHello cxhelloData = (dev.droppinganvil.v3.network.events.CXHello) ib.object;
                            // Get signed Node blob from payload
                            byte[] cxhelloSignedBlob = cxhelloData.signedNode;

                            if (cxhelloSignedBlob != null) {
                                // Verify and deserialize the signed Node blob (using sender's ID from container)
                                java.io.ByteArrayInputStream signedInput = new java.io.ByteArrayInputStream(cxhelloSignedBlob);
                                java.io.ByteArrayOutputStream verifiedOutput = new java.io.ByteArrayOutputStream();
                                boolean verified = connectX.encryptionProvider.verifyAndStrip(signedInput, verifiedOutput, ib.nc.iD);
                                signedInput.close();

                                if (verified) {
                                    String nodeJson = verifiedOutput.toString("UTF-8");
                                    Node cxhelloNode = (Node) ConnectX.deserialize("cxJSON1", nodeJson, Node.class);
                                    verifiedOutput.close();

                                    // Add node WITH signed blob for .cxi persistence
                                    Node existingCxhelloNode = peerDirectory.lookup(cxhelloNode.cxID, true, true, connectX.cxRoot, connectX);
                                    if (existingCxhelloNode == null) {
                                        peerDirectory.addNode(cxhelloNode, cxhelloSignedBlob, connectX.cxRoot);
                                        System.out.println("[CXHELLO] Imported and PERSISTED new node: " + cxhelloNode.cxID.substring(0, 8));
                                    } else {
                                        connectX.encryptionProvider.cacheCert(existingCxhelloNode.cxID, true, false, connectX);
                                        System.out.println("[CXHELLO] Updated existing node: " + existingCxhelloNode.cxID.substring(0, 8));
                                    }

                                    // Now lookup the node for response routing
                                    Node requesterNode = peerDirectory.lookup(ib.nc.iD, true, true);
                                    if (requesterNode != null) {
                                        System.out.println("[CXHELLO] Peer discovered from " + ib.nc.iD.substring(0, 8));

                                        // Sign our Node for .cxi persistence on receiver
                                        String ourNodeJson = ConnectX.serialize("cxJSON1", connectX.getSelf());
                                        java.io.ByteArrayInputStream nodeInput = new java.io.ByteArrayInputStream(ourNodeJson.getBytes("UTF-8"));
                                        java.io.ByteArrayOutputStream signedOutput = new java.io.ByteArrayOutputStream();
                                        connectX.encryptionProvider.sign(nodeInput, signedOutput);
                                        nodeInput.close();
                                        byte[] ourSignedBlob = signedOutput.toByteArray();
                                        signedOutput.close();

                                        // Send CXHELLO_RESPONSE with our peerID, port, address, and signed Node blob using CXHello class
                                        int listeningPort = (in.serverSocket != null) ? in.serverSocket.getLocalPort() : 0;

                                        // Get public/local address of our receiving socket
                                        String ourAddress = null;
                                        if (in.serverSocket != null && listeningPort > 0) {
                                            String localIP = dev.droppinganvil.v3.network.nodemesh.LANScanner.getLocalIP();
                                            if (localIP != null) {
                                                ourAddress = localIP + ":" + listeningPort;
                                            }
                                        }

                                        dev.droppinganvil.v3.network.events.CXHello responsePayload =
                                            new dev.droppinganvil.v3.network.events.CXHello(connectX.getOwnID(), listeningPort, ourSignedBlob, ourAddress);

                                        String responsePayloadJson = ConnectX.serialize("cxJSON1", responsePayload);

                                        // Send response using EventBuilder pattern
                                        connectX.buildEvent(EventType.CXHELLO_RESPONSE, responsePayloadJson.getBytes("UTF-8"))
                                            .toPeer(ib.nc.iD)
                                            .signData()
                                            .queue();

                                        System.out.println("[CXHELLO] Queued CXHELLO_RESPONSE to " + ib.nc.iD.substring(0, 8));
                                    }
                                } else {
                                    System.err.println("[CXHELLO] Node signature verification FAILED for " + ib.nc.iD.substring(0, 8));
                                }
                            }
                            return; // Don't continue to fireEvent

                        case CXHELLO_RESPONSE:
                            // CXHELLO_RESPONSE payload: CXHello class with signed Node blob
                            System.out.println("[" + connectX.getOwnID() + "] CXHELLO_RESPONSE received from " + ib.nc.iD);
                            dev.droppinganvil.v3.network.events.CXHello responseData;
                            /// NEW InputBundle features handling
                            if (ib.object != null) {
                                //Try new method first
                                if (!ib.readyObject(CXHello.class, ib.nc.se, connectX)) {
                                    System.out.println("[NodeMesh] Using depreciated method, use new InputBundle instead 002");
                                    String responsePayloadJson = new String(eventData, "UTF-8");
                                    responseData =
                                            (dev.droppinganvil.v3.network.events.CXHello) ConnectX.deserialize("cxJSON1", responsePayloadJson,
                                                    dev.droppinganvil.v3.network.events.CXHello.class);
                                } else {
                                    responseData = (CXHello) ib.object;
                                }
                            } else {
                                responseData = (dev.droppinganvil.v3.network.events.CXHello) ib.object;
                            }
                            // Get signed Node blob from CXHello payload
                            byte[] respSignedBlob = responseData.signedNode;

                            if (respSignedBlob != null) {
                                // Verify and deserialize the signed Node blob
                                java.io.ByteArrayInputStream respSignedInput = new java.io.ByteArrayInputStream(respSignedBlob);
                                java.io.ByteArrayOutputStream respVerifiedOutput = new java.io.ByteArrayOutputStream();
                                boolean respVerified = connectX.encryptionProvider.verifyAndStrip(respSignedInput, respVerifiedOutput, ib.nc.iD);
                                respSignedInput.close();

                                if (respVerified) {
                                    String respNodeJson = respVerifiedOutput.toString("UTF-8");
                                    Node responseNode = (Node) ConnectX.deserialize("cxJSON1", respNodeJson, Node.class);
                                    respVerifiedOutput.close();

                                    // Local address already recorded in processNetworkInput
                                    Node existingNode = peerDirectory.lookup(responseNode.cxID, true, true, connectX.cxRoot, connectX);
                                    if (existingNode != null) {
                                        connectX.encryptionProvider.cacheCert(existingNode.cxID, true, false, connectX);
                                        System.out.println("[CXHELLO_RESPONSE] Updated existing node: " + existingNode.cxID.substring(0, 8));
                                        return;
                                    }
                                    peerDirectory.addNode(responseNode, respSignedBlob, connectX.cxRoot);
                                    System.out.println("[CXHELLO_RESPONSE] Imported and PERSISTED new node: " + responseNode.cxID.substring(0, 8));
                                } else {
                                    System.err.println("[CXHELLO_RESPONSE] Node signature verification FAILED for " + ib.nc.iD.substring(0, 8));
                                }
                            }
                            return; // Don't continue to fireEvent
                    }
                } catch (Exception e) {
                    System.out.println("[NodeMesh] Cryptography failure on event " + ib.ne.eT + " From peer: " + ib.nc.iD);
                    e.printStackTrace();
                    throw new DecryptionFailureException();
                }

                // After infrastructure handling, fire event to application layer
                // SKIP application layer if E2E decryption failed (event not intended for us)
                if (!e2eDecryptionFailed) {
                    fireEvent(ib);
                } else {
                    System.out.println("[E2E] Skipping application layer for undecryptable E2E event, will relay");
                }

                // DEBUG: Log auto-record attempt
                System.out.println("[Auto-Record DEBUG] Event type: " + ib.ne.eT + ", r=" + ib.ne.r +
                    ", nc=" + (ib.nc != null) + ", nc.iD=" + (ib.nc != null ? ib.nc.iD : "null") +
                    ", nc.oD=" + (ib.nc != null ? ib.nc.oD : "null"));

                // Auto-record events with r=true if ORIGINAL sender has permission
                if (ib.ne.r && ib.nc != null && ib.nc.oD != null) {
                    String senderID = ib.nc.oD;
                    //Additional verification after application layer
                    if (!ib.nc.oD.equals(ib.ne.p.oCXID)) {
                        Analytics.addData(AnalyticData.SecurityEvent, "Security failure, mismatched CXIDs: " + ib.ne.p.oCXID + ", " + ib.nc.oD + " Event Type: " + ib.ne.eT);
                        System.out.println("Security failure, mismatched CXIDs: " + ib.ne.p.oCXID + ", " + ib.nc.oD+ " Event Type: " + ib.ne.eT);
                        return;
                    }
                    String networkID = ib.ne.p != null ? ib.ne.p.network : null;

                    if (networkID != null) {
                        CXNetwork network = connectX.getNetwork(networkID);
                        if (network != null) {
                            // Determine target chain from event type (c1=admin, c2=resources, c3=events)
                            Long chainID = network.networkDictionary.c3;  // Default to c3 for most events
                            if (et != null) {
                                switch (et) {
                                    case REGISTER_NODE:
                                    case BLOCK_NODE:
                                    case UNBLOCK_NODE:
                                    case GRANT_PERMISSION:
                                    case REVOKE_PERMISSION:
                                    case ZERO_TRUST_ACTIVATION:
                                        chainID = network.networkDictionary.c1;  // Admin events go to c1
                                        break;
                                }
                            }

                            // Check if sender has Record permission for this chain
                            boolean hasPermission = network.checkChainPermission(senderID, dev.droppinganvil.v3.Permission.Record.name(), chainID);

                            if (hasPermission) {
                                // Record event to blockchain with signed bytes
                                if (ib.signedEventBytes != null) {
                                    boolean recorded = connectX.Event(ib.ne, senderID, ib.signedEventBytes);
                                    if (recorded) {
                                        System.out.println("[Auto-Record] Event " + ib.ne.eT + " from " + senderID.substring(0, 8) + " recorded to chain " + chainID);
                                    }
                                } else {
                                    System.err.println("[Auto-Record] Cannot record event - missing signed bytes");
                                }
                            } else {
                                System.out.println("[Auto-Record] Event " + ib.ne.eT + " from " + senderID.substring(0, 8) + " NOT recorded - no permission");
                            }
                        }
                    }
                }
            }
        }
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
        java.util.List<Long> timestamps = peerRequestTimestamps.computeIfAbsent(
            ipAddress,
            k -> new java.util.concurrent.CopyOnWriteArrayList<>()
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
        java.util.List<Long> timestamps = peerRequestTimestamps.computeIfAbsent(
            ipAddress,
            k -> new java.util.concurrent.CopyOnWriteArrayList<>()
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
                java.io.ByteArrayInputStream verifyStream = new java.io.ByteArrayInputStream(signedEventBytes);
                java.io.ByteArrayOutputStream verifiedOutput = new java.io.ByteArrayOutputStream();
                boolean verified = connectX.encryptionProvider.verifyAndStrip(verifyStream, verifiedOutput, ne.p.oCXID);
                verifyStream.close();

                if (!verified) {
                    String cxidDisplay = (ne.p.oCXID != null && ne.p.oCXID.length() >= 8) ? ne.p.oCXID.substring(0, 8) : (ne.p.oCXID != null ? ne.p.oCXID : "NULL");
                    System.err.println("[SECURITY] Event signature verification FAILED for " + cxidDisplay + " - REJECTING EVENT");
                    return false; // Reject the event
                }
                String cxidDisplay = (ne.p.oCXID != null && ne.p.oCXID.length() >= 8) ? ne.p.oCXID.substring(0, 8) : (ne.p.oCXID != null ? ne.p.oCXID : "NULL");
                System.out.println("[SECURITY] Event signature verified for " + cxidDisplay);
            } catch (Exception e) {
                System.err.println("[SECURITY] Error verifying event signature: " + e.getMessage());
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
                        String message = new String(ib.verifiedObjectBytes, "UTF-8");
                        System.out.println("\n[" + connectX.getOwnID() + "] RECEIVED MESSAGE: " + message);
                        if (ne.p != null) {
                            System.out.println("  From network: " + ne.p.network);
                            System.out.println("  Scope: " + ne.p.scope);
                        }
                        handledLocally = true;
                        break;
                    case PeerFinding:
                        try {
                            // Parse PeerFinding request/response
                            dev.droppinganvil.v3.network.events.PeerFinding pf =
                                (dev.droppinganvil.v3.network.events.PeerFinding) ConnectX.deserialize("cxJSON1", new String(ib.verifiedObjectBytes, "UTF-8"),
                                    dev.droppinganvil.v3.network.events.PeerFinding.class);
                           // dev.droppinganvil.v3.network.events.PeerFinding pff = dev.droppinganvil.v3.network.events.PeerFinding) connectX.encryptionProvider.verifyAndStrip()

                            if ("request".equals(pf.t)) {
                                // Handle PeerFinding request - respond with our known peers
                                System.out.println("[PeerFinding] Request from " + nc.iD.substring(0, 8) +
                                    (pf.network != null ? " for network: " + pf.network : ""));

                                // Get network context (default to CXNET)
                                String requestedNetwork = pf.network != null ? pf.network : "CXNET";
                                CXNetwork network = connectX.getNetwork(requestedNetwork);

                                if (network != null) {
                                    // Build response with signed node blobs (up to 50 random peers)
                                    dev.droppinganvil.v3.network.events.PeerFinding response =
                                        new dev.droppinganvil.v3.network.events.PeerFinding();
                                    response.t = "response";
                                    response.network = requestedNetwork;
                                    response.signedNodes = new java.util.ArrayList<>();

                                    // Collect peers from PeerDirectory
                                    java.util.List<String> peerIDs = new java.util.ArrayList<>(peerDirectory.hv.keySet());
                                    java.util.Collections.shuffle(peerIDs); // Randomize

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

                                    System.out.println("[PeerFinding] Responding with " + count + " signed peers");

                                    // Populate peers field: 30% of seen peers, then 30% of their addresses (max 20)
                                    java.util.Set<String> allAddresses = new java.util.HashSet<>();

                                    // Select 30% of seen peers
                                    java.util.List<String> seenPeerIDs = new java.util.ArrayList<>(peerDirectory.seenCXIDs);
                                    java.util.Collections.shuffle(seenPeerIDs);
                                    // 30% rule 0.3
                                    int seenPeerCount = (int) Math.ceil(seenPeerIDs.size() * 0.3);

                                    for (int i = 0; i < Math.min(seenPeerCount, seenPeerIDs.size()); i++) {
                                        String peerID = seenPeerIDs.get(i);
                                        // Get all addresses for this peer
                                        java.util.List<String> peerAddresses = peerDirectory.getAllAddresses(peerID, connectX);
                                        allAddresses.addAll(peerAddresses);
                                    }

                                    // Select 30% of addresses (max 20)
                                    java.util.List<String> addressList = new java.util.ArrayList<>(allAddresses);
                                    java.util.Collections.shuffle(addressList);
                                    int addressCount = Math.min(20, (int) Math.ceil(addressList.size() * 0.3));
                                    java.util.List<String> selectedAddresses = addressList.subList(0, Math.min(addressCount, addressList.size()));

                                    //Set response
                                    response.peers.addAll(addressList);

                                    System.out.println("[PeerFinding] Added " + selectedAddresses.size() + " peer addresses from " + seenPeerIDs.size() + " seen peers");

                                    // Using new Event Builder API

                                    // Send response
                                    //String responseJson = ConnectX.serialize("cxJSON1", response);
                                    //NetworkEvent responseEvent = new NetworkEvent();
                                    //responseEvent.eT = EventType.PeerFinding.name();
                                    //responseEvent.iD = java.util.UUID.randomUUID().toString();
                                    //responseEvent.d = responseJson.getBytes("UTF-8");

                                    // Reply to sender
                                   //responseEvent.p = new dev.droppinganvil.v3.network.CXPath();
                                    //responseEvent.p.cxID = nc.iD;
                                    //responseEvent.p.scope = "CXS"; // Single peer response
                                   // if (ne.p != null) {
                                    //    responseEvent.p.bridge = ne.p.bridge;
                                    //    responseEvent.p.bridgeArg = ne.p.bridgeArg;
                                   // }

                                   // Node requesterNode = peerDirectory.lookup(nc.iD, true, true);
                                   // NetworkContainer responseContainer = new NetworkContainer();
                                   // responseContainer.se = "cxJSON1";
                                   // responseContainer.s = false;
                                   // OutputBundle responseBundle = new OutputBundle(responseEvent, requesterNode, null, null, responseContainer);
                                  //  connectX.queueEvent(responseBundle);

                                    connectX.buildEvent(EventType.PeerFinding, ConnectX.serialize("cxJSON1", response).getBytes("UTF-8"))
                                            .targetPeer(nc.iD)
                                            .toPeer(nc.iD)
                                            .scope("CXS")
                                            .signData()
                                            .queue();

                                } else {
                                    System.out.println("[PeerFinding] Network " + requestedNetwork + " not found");
                                }

                            } else if ("response".equals(pf.t)) {
                                // Handle PeerFinding response - import discovered peers
                                System.out.println("[PeerFinding] Response from " + nc.iD.substring(0, 8) +
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
                                            java.io.ByteArrayInputStream signedInput = new java.io.ByteArrayInputStream(signedNodeBytes);
                                            java.io.ByteArrayOutputStream strippedOutput = new java.io.ByteArrayOutputStream();
                                            connectX.encryptionProvider.stripSignature(signedInput, strippedOutput);
                                            signedInput.close();

                                            String nodeJson = strippedOutput.toString("UTF-8");
                                            Node discoveredPeer = (Node) ConnectX.deserialize("cxJSON1", nodeJson, Node.class);
                                            strippedOutput.close();

                                            if (discoveredPeer != null && discoveredPeer.cxID != null) {
                                                // Add with signed blob for persistence and relaying
                                                peerDirectory.addNode(discoveredPeer, signedNodeBytes, connectX.cxRoot);

                                                // Now verify the signature using the imported public key
                                                java.io.ByteArrayInputStream verifyInput = new java.io.ByteArrayInputStream(signedNodeBytes);
                                                java.io.ByteArrayOutputStream verifyOutput = new java.io.ByteArrayOutputStream();
                                                boolean verified = connectX.encryptionProvider.verifyAndStrip(
                                                    verifyInput, verifyOutput, discoveredPeer.cxID);
                                                verifyInput.close();
                                                verifyOutput.close();

                                                if (!verified) {
                                                    // Signature verification FAILED - rollback
                                                    peerDirectory.removeNode(discoveredPeer.cxID, connectX.cxRoot);
                                                    System.err.println("[PeerFinding] Signature verification FAILED for " +
                                                        discoveredPeer.cxID.substring(0, 8) + " - rolled back");
                                                    continue;
                                                }

                                                imported++;
                                                System.out.println("[PeerFinding]   + " + discoveredPeer.cxID.substring(0, 8) +
                                                    " (verified)");

                                                // Send NewNode with SIGNED Node blob (receiver will save original signed blob for relay)
                                                String selfJson = ConnectX.serialize("cxJSON1", connectX.getSelf());
                                                connectX.buildEvent(EventType.NewNode, selfJson.getBytes("UTF-8"))
                                                    .signData()  // Sign Node JSON to create signed blob (preserves sender signature)
                                                    .toPeer(discoveredPeer.cxID)
                                                    .queue();
                                            }
                                        } catch (Exception e) {
                                            System.err.println("[PeerFinding] Failed to import peer: " + e.getMessage());
                                        }
                                    }
                                    System.out.println("[PeerFinding] Imported " + imported + " new peers");
                                }
                                if (pf.peers != null && pf.peers.size() <= 20) {
                                    //TODO security eval
                                    connectX.dataContainer.waitingAddresses.addAll(pf.peers);
                                    System.out.println("[PeerFinding] Imported addresses for connections from " + ne.p.oCXID);
                                }

                            }
                        } catch (Exception e) {
                            System.err.println("[PeerFinding] Error: " + e.getMessage());
                            e.printStackTrace();
                        }
                        handledLocally = true;
                        break;
                    case SEED_REQUEST:
                        System.out.println("[" + connectX.getOwnID() + "] Seed request received from " + nc.iD);
                        try {
                            // Parse request to get network ID
                            String requestedNetwork = "CXNET";
                            if (ne.d != null && ne.d.length > 0) {
                                try {
                                    String requestJson = new String(eventPayload, "UTF-8");
                                    java.util.Map<String, Object> req = (java.util.Map<String, Object>)
                                        ConnectX.deserialize("cxJSON1", requestJson, java.util.Map.class);
                                    if (req.containsKey("network")) {
                                        requestedNetwork = (String) req.get("network");
                                    }
                                } catch (Exception e) {
                                    // Use default CXNET
                                }
                            }

                            CXNetwork network = connectX.getNetwork(requestedNetwork);
                            if (network != null) {
                                // Create dynamic seed from current peer state
                                dev.droppinganvil.v3.network.Seed dynamicSeed = dev.droppinganvil.v3.network.Seed.fromCurrentPeers(peerDirectory);
                                dynamicSeed.seedID = java.util.UUID.randomUUID().toString();
                                dynamicSeed.timestamp = System.currentTimeMillis();
                                dynamicSeed.networkID = requestedNetwork;
                                dynamicSeed.networks = new java.util.ArrayList<>();
                                dynamicSeed.networks.add(network);

                                // Try to load EPOCH's signed seed from disk
                                dev.droppinganvil.v3.network.Seed epochSeed = null;
                                if (network.configuration != null && network.configuration.currentSeedID != null) {
                                    java.io.File seedsDir = new java.io.File(connectX.cxRoot, "seeds");
                                    java.io.File seedFile = new java.io.File(seedsDir, network.configuration.currentSeedID + ".cxn");

                                    if (seedFile.exists()) {
                                        epochSeed = dev.droppinganvil.v3.network.Seed.load(seedFile);
                                    }
                                }

                                // Determine if this peer is authoritative (NMI/backend)
                                boolean isAuthoritative = false;
                                if (network.configuration != null && network.configuration.backendSet != null) {
                                    isAuthoritative = network.configuration.backendSet.contains(connectX.getOwnID());
                                }

                                // Build response with BOTH seeds
                                java.util.Map<String, Object> response = new java.util.HashMap<>();
                                response.put("dynamicSeed", dynamicSeed);           // Current peer state
                                response.put("epochSeed", epochSeed);               // Signed seed from EPOCH (null if not available)
                                response.put("authoritative", isAuthoritative);     // Is this EPOCH/NMI?
                                response.put("senderID", connectX.getOwnID());

                                // Add blockchain heights for consensus
                                java.util.Map<String, Long> chainHeights = new java.util.HashMap<>();
                                chainHeights.put("c1", network.c1.current != null ? network.c1.current.block : 0L);
                                chainHeights.put("c2", network.c2.current != null ? network.c2.current.block : 0L);
                                chainHeights.put("c3", network.c3.current != null ? network.c3.current.block : 0L);
                                response.put("chainHeights", chainHeights);

                                System.out.println("[SEED] Responding to " + nc.iD.substring(0, 8));
                                System.out.println("[SEED]   Dynamic peers: " + dynamicSeed.hvPeers.size());
                                System.out.println("[SEED]   EPOCH seed: " + (epochSeed != null ? "available" : "none"));
                                System.out.println("[SEED]   Authoritative: " + isAuthoritative);

                                String responseJson = ConnectX.serialize("cxJSON1", response);

                                // Send response using EventBuilder pattern
                                ConnectX.EventBuilder eb = connectX.buildEvent(EventType.SEED_RESPONSE, responseJson.getBytes("UTF-8"))
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
                                System.out.println("[SEED] Network " + requestedNetwork + " not found");
                            }
                        } catch (Exception e) {
                            System.err.println("[SEED] Error: " + e.getMessage());
                            e.printStackTrace();
                        }
                        handledLocally = true;
                        break;
                    case SEED_RESPONSE:
                        System.out.println("[" + connectX.getOwnID() + "] Seed response received from " + nc.iD);
                        try {
                            // Parse response with consensus metadata
                            String responseJson = new String(eventPayload, "UTF-8");
                            java.util.Map<String, Object> response =
                                (java.util.Map<String, Object>) ConnectX.deserialize("cxJSON1", responseJson, java.util.Map.class);

                            // Extract and store response data
                            ConnectX.SeedResponseData responseData = new ConnectX.SeedResponseData();
                            responseData.dynamicSeed = (dev.droppinganvil.v3.network.Seed) response.get("dynamicSeed");
                            responseData.epochSeed = (dev.droppinganvil.v3.network.Seed) response.get("epochSeed");
                            responseData.authoritative = response.containsKey("authoritative") ?
                                (Boolean) response.get("authoritative") : false;
                            responseData.senderID = nc.iD;
                            responseData.timestamp = System.currentTimeMillis();
                            responseData.chainHeights = (java.util.Map<String, Number>) response.get("chainHeights");

                            // Determine target network
                            String targetNetwork = "CXNET";
                            if (responseData.epochSeed != null && responseData.epochSeed.networkID != null) {
                                targetNetwork = responseData.epochSeed.networkID;
                            } else if (responseData.dynamicSeed != null && responseData.dynamicSeed.networkID != null) {
                                targetNetwork = responseData.dynamicSeed.networkID;
                            }

                            System.out.println("[SEED CONSENSUS] Received response for " + targetNetwork);
                            System.out.println("  From: " + nc.iD.substring(0, 8));
                            System.out.println("  Authoritative (EPOCH): " + responseData.authoritative);
                            System.out.println("  Has EPOCH seed: " + (responseData.epochSeed != null));
                            System.out.println("  Has dynamic seed: " + (responseData.dynamicSeed != null));

                            // PRIORITY 1: If this is EPOCH with signed seed, trust immediately
                            if (responseData.authoritative && responseData.epochSeed != null) {
                                System.out.println("[SEED CONSENSUS] ✓ EPOCH responded with signed seed - IMMEDIATE TRUST");
                                applySeedConsensus(connectX, responseData.epochSeed, true,
                                    "EPOCH signed seed (direct from NMI)", targetNetwork);
                                handledLocally = true;
                                break;
                            }

                            // PRIORITY 2: If peer forwarded EPOCH's signed seed, trust it
                            if (responseData.epochSeed != null) {
                                System.out.println("[SEED CONSENSUS] ✓ Peer forwarded EPOCH signed seed - HIGH TRUST");
                                applySeedConsensus(connectX, responseData.epochSeed, true,
                                    "EPOCH signed seed (peer-forwarded by " + nc.iD.substring(0, 8) + ")", targetNetwork);
                                handledLocally = true;
                                break;
                            }

                            // PRIORITY 3: Multi-peer consensus for dynamic seeds
                            // Store response in consensus map
                            if (!connectX.seedConsensusMap.containsKey(targetNetwork)) {
                                connectX.seedConsensusMap.put(targetNetwork, new java.util.concurrent.ConcurrentHashMap<>());
                            }
                            connectX.seedConsensusMap.get(targetNetwork).put(nc.iD, responseData);

                            System.out.println("[SEED CONSENSUS] Stored response from " + nc.iD.substring(0, 8));
                            System.out.println("[SEED CONSENSUS] Responses collected: " +
                                connectX.seedConsensusMap.get(targetNetwork).size());

                            // Trigger consensus if we have enough responses (3) or if EPOCH responded
                            java.util.concurrent.ConcurrentHashMap<String, ConnectX.SeedResponseData> responses =
                                connectX.seedConsensusMap.get(targetNetwork);

                            boolean hasEpochResponse = false;
                            for (ConnectX.SeedResponseData r : responses.values()) {
                                if (r.authoritative) {
                                    hasEpochResponse = true;
                                    break;
                                }
                            }

                            if (responses.size() >= 3 || hasEpochResponse) {
                                System.out.println("[SEED CONSENSUS] Triggering consensus vote...");
                                performSeedConsensus(connectX, targetNetwork, responses);

                                // Clear consensus map after applying
                                connectX.seedConsensusMap.remove(targetNetwork);
                            } else {
                                System.out.println("[SEED CONSENSUS] Waiting for more responses... (" +
                                    responses.size() + "/3)");
                            }

                        } catch (Exception e) {
                            System.err.println("[SEED CONSENSUS] Error: " + e.getMessage());
                            e.printStackTrace();
                        }
                        handledLocally = true;
                        break;
                    case CHAIN_STATUS_REQUEST:
                        System.out.println("[" + connectX.getOwnID() + "] Chain status request received from " + nc.iD);
                        try {
                            // Parse request to get network ID
                            //STRIP SIG FIRST
                            ByteArrayInputStream bais = new ByteArrayInputStream(eventPayload);
                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
                            connectX.encryptionProvider.stripSignature(bais, baos);
                            String requestJson = baos.toString("UTF-8");
                            java.util.Map<String, Object> request =
                                (java.util.Map<String, Object>) ConnectX.deserialize("cxJSON1", requestJson, java.util.Map.class);
                            String networkID = (String) request.get("network");

                            CXNetwork network = connectX.getNetwork(networkID);
                            if (network != null) {
                                // Build response with current block heights
                                java.util.Map<String, Long> chainStatus = new java.util.HashMap<>();
                                chainStatus.put("c1", network.c1.current != null ? network.c1.current.block : 0L);
                                chainStatus.put("c2", network.c2.current != null ? network.c2.current.block : 0L);
                                chainStatus.put("c3", network.c3.current != null ? network.c3.current.block : 0L);

                                // Create response with network ID and status
                                java.util.Map<String, Object> response = new java.util.HashMap<>();
                                response.put("network", networkID);
                                response.put("status", chainStatus);

                                String statusJson = ConnectX.serialize("cxJSON1", response);

                                // Send response using EventBuilder pattern
                                connectX.buildEvent(EventType.CHAIN_STATUS_RESPONSE, statusJson.getBytes("UTF-8"))
                                    .toPeer(nc.iD)
                                    .signData()
                                    .queue();

                                System.out.println("[CHAIN_STATUS] Sent status for " + networkID +
                                                 " (c1:" + chainStatus.get("c1") +
                                                 " c2:" + chainStatus.get("c2") +
                                                 " c3:" + chainStatus.get("c3") + ")");
                            } else {
                                System.err.println("[CHAIN_STATUS] Network not found: " + networkID);
                            }
                        } catch (Exception e) {
                            System.err.println("[CHAIN_STATUS] Error handling request: " + e.getMessage());
                            e.printStackTrace();
                        }
                        handledLocally = true;
                        break;
                    case CHAIN_STATUS_RESPONSE:
                        System.out.println("[" + connectX.getOwnID() + "] Chain status response received from " + nc.iD);
                        try {
                            String statusJson = new String(eventPayload, "UTF-8");
                            java.util.Map<String, Object> response =
                                (java.util.Map<String, Object>) ConnectX.deserialize("cxJSON1", statusJson, java.util.Map.class);

                            String networkID = (String) response.get("network");
                            java.util.Map<String, Number> chainStatus = (java.util.Map<String, Number>) response.get("status");

                            System.out.println("[CHAIN_STATUS] Remote chain heights for " + networkID + " from " + nc.iD.substring(0, 8) + ":");
                            System.out.println("  c1: " + chainStatus.get("c1"));
                            System.out.println("  c2: " + chainStatus.get("c2"));
                            System.out.println("  c3: " + chainStatus.get("c3"));

                            // Store response for multipeer verification
                            CXNetwork network = connectX.getNetwork(networkID);
                            if (network != null) {
                                // Check if this response is from NMI (always trust NMI)
                                boolean isNMI = network.configuration.backendSet != null &&
                                               network.configuration.backendSet.contains(nc.iD);

                                if (isNMI) {
                                    System.out.println("[CHAIN_STATUS] Response from NMI/Backend - TRUSTED");
                                    // NMI response is authoritative, initiate sync immediately
                                    initiateSyncFromPeer(connectX, network, networkID, chainStatus, nc.iD);
                                } else {
                                    // Peer response - store for multipeer verification
                                    System.out.println("[CHAIN_STATUS] Response from peer - storing for verification");
                                    // TODO: Implement multipeer consensus mechanism
                                    // For now, skip peer-only responses (require NMI or multiple peer consensus)
                                }
                            }
                        } catch (Exception e) {
                            System.err.println("[CHAIN_STATUS] Error handling response: " + e.getMessage());
                            e.printStackTrace();
                        }
                        handledLocally = true;
                        break;
                    case BLOCK_REQUEST:
                        System.out.println("[" + connectX.getOwnID() + "] Block request received from " + nc.iD);
                        try {
                            // Parse request
                            String requestJson = new String(eventPayload, "UTF-8");
                            java.util.Map<String, Object> request =
                                (java.util.Map<String, Object>) ConnectX.deserialize("cxJSON1", requestJson, java.util.Map.class);
                            String networkID = (String) request.get("network");
                            Long chainID = ((Number) request.get("chain")).longValue();
                            Long blockID = ((Number) request.get("block")).longValue();

                            CXNetwork network = connectX.getNetwork(networkID);
                            if (network != null) {
                                // Get the requested chain
                                dev.droppinganvil.v3.edge.NetworkRecord targetChain = null;
                                if (chainID.equals(network.networkDictionary.c1)) targetChain = network.c1;
                                else if (chainID.equals(network.networkDictionary.c2)) targetChain = network.c2;
                                else if (chainID.equals(network.networkDictionary.c3)) targetChain = network.c3;

                                if (targetChain != null) {
                                    // Try to get block from memory first
                                    dev.droppinganvil.v3.edge.NetworkBlock block = targetChain.blockMap.get(blockID);

                                    // If not in memory, try loading from disk
                                    if (block == null) {
                                        try {
                                            block = connectX.blockchainPersistence.loadBlock(networkID, chainID, blockID);
                                        } catch (Exception e) {
                                            System.err.println("[BLOCK_REQUEST] Block not found on disk: " + e.getMessage());
                                        }
                                    }

                                    if (block != null) {
                                        // Serialize block as payload
                                        String blockJson = ConnectX.serialize("cxJSON1", block);

                                        // Send response using EventBuilder pattern
                                        ConnectX.EventBuilder eb = connectX.buildEvent(EventType.BLOCK_RESPONSE, blockJson.getBytes("UTF-8"))
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

                                        System.out.println("[BLOCK_REQUEST] Sent block " + blockID + " from chain " + chainID +
                                                         " (" + block.networkEvents.size() + " events)");
                                    } else {
                                        System.err.println("[BLOCK_REQUEST] Block not found: " + blockID);
                                    }
                                } else {
                                    System.err.println("[BLOCK_REQUEST] Chain not found: " + chainID);
                                }
                            } else {
                                System.err.println("[BLOCK_REQUEST] Network not found: " + networkID);
                            }
                        } catch (Exception e) {
                            System.err.println("[BLOCK_REQUEST] Error handling request: " + e.getMessage());
                            e.printStackTrace();
                        }
                        handledLocally = true;
                        break;
                    case BLOCK_RESPONSE:
                        System.out.println("[" + connectX.getOwnID() + "] Block response received from " + nc.iD);
                        try {
                            // Deserialize block
                            String blockJson = new String(eventPayload, "UTF-8");
                            dev.droppinganvil.v3.edge.NetworkBlock block =
                                (dev.droppinganvil.v3.edge.NetworkBlock) ConnectX.deserialize("cxJSON1", blockJson, dev.droppinganvil.v3.edge.NetworkBlock.class);

                            System.out.println("[BLOCK_RESPONSE] Received block " + block.block +
                                             " (" + block.networkEvents.size() + " events)");

                            // Get network ID from NetworkEvent path (CXN scope includes network)
                            String networkID = ne.p != null ? ne.p.network : null;
                            Long chainID = block.chain;

                            if (networkID == null) {
                                System.err.println("[BLOCK_RESPONSE] Cannot determine network ID from event path");
                                handledLocally = true;
                                break;
                            }

                            // Get network and determine which chain this block belongs to
                            CXNetwork network = connectX.getNetwork(networkID);
                            if (network == null) {
                                System.err.println("[BLOCK_RESPONSE] Network not found: " + networkID);
                                handledLocally = true;
                                break;
                            }

                            // Determine target chain based on block's chain ID from metadata
                            dev.droppinganvil.v3.edge.NetworkRecord targetChain = null;
                            if (block.chain != null) {
                                chainID = block.chain;
                                if (chainID.equals(network.networkDictionary.c1)) targetChain = network.c1;
                                else if (chainID.equals(network.networkDictionary.c2)) targetChain = network.c2;
                                else if (chainID.equals(network.networkDictionary.c3)) targetChain = network.c3;
                            }

                            if (targetChain == null) {
                                System.err.println("[BLOCK_RESPONSE] Cannot determine target chain for block");
                                handledLocally = true;
                                break;
                            }

                            // Check if sender is EPOCH/NMI/backend (authoritative source)
                            boolean isAuthoritative = network.configuration != null &&
                                                     network.configuration.backendSet != null &&
                                                     network.configuration.backendSet.contains(nc.iD);

                            if (isAuthoritative && !network.zT) {
                                // EPOCH/NMI response in non-zero-trust mode - accept immediately as source of truth
                                System.out.println("[BLOCK_RESPONSE] EPOCH/NMI source - accepting as authoritative");
                                applyBlockToChain(connectX, network, targetChain, block, networkID, chainID, nc.iD);

                            } else {
                                // Peer response OR zero trust mode - use consensus
                                System.out.println("[BLOCK_RESPONSE] Using consensus" +
                                                 (network.zT ? " (zero trust mode)" : " (peer response)"));

                                // Create request key for this block
                                String requestKey = BlockConsensusTracker.createRequestKey(networkID, chainID, block.block);

                                // Record this response in consensus tracker
                                boolean recorded = connectX.blockConsensusTracker.recordResponse(requestKey, nc.iD, block);

                                if (recorded) {
                                    // Check if consensus has been reached
                                    boolean consensusReached = connectX.blockConsensusTracker.checkConsensus(requestKey);

                                    if (consensusReached) {
                                        // Get consensus block
                                        dev.droppinganvil.v3.edge.NetworkBlock consensusBlock =
                                            connectX.blockConsensusTracker.getConsensusBlock(requestKey);

                                        if (consensusBlock != null) {
                                            System.out.println("[BLOCK_CONSENSUS] Consensus reached for block " + consensusBlock.block);

                                            // Apply consensus block
                                            applyBlockToChain(connectX, network, targetChain, consensusBlock, networkID, chainID, nc.iD);

                                            // Clean up this request
                                            connectX.blockConsensusTracker.removeRequest(requestKey);
                                        }
                                    } else {
                                        System.out.println("[BLOCK_CONSENSUS] Waiting for more responses...");
                                    }
                                } else {
                                    System.err.println("[BLOCK_RESPONSE] Failed to record response in consensus tracker");
                                }
                            }

                        } catch (Exception e) {
                            System.err.println("[BLOCK_RESPONSE] Error handling response: " + e.getMessage());
                            e.printStackTrace();
                        }
                        handledLocally = true;
                        break;
                    case BLOCK_NODE:
                        System.out.println("[" + connectX.getOwnID() + "] BLOCK_NODE event received from " + nc.iD);
                        try {
                            // Parse payload: {network: "NETWORKID", nodeID: "UUID", reason: "spam"}
                            String blockJson = new String(eventPayload, "UTF-8");
                            java.util.Map<String, Object> blockData =
                                (java.util.Map<String, Object>) ConnectX.deserialize("cxJSON1", blockJson, java.util.Map.class);

                            String networkID = (String) blockData.get("network");
                            String nodeID = (String) blockData.get("nodeID");
                            String reason = (String) blockData.get("reason");

                            System.out.println("[BLOCK_NODE] Blocking node " + nodeID + " on network " + networkID + " (reason: " + reason + ")");

                            // Check if CXNET-level or network-specific block
                            if ("CXNET".equals(networkID)) {
                                // CXNET-level block: blocks ALL transmissions from node globally
                                connectX.blockNodeCXNET(nodeID, reason);
                            } else {
                                // Network-specific block (stored in local DataContainer)
                                connectX.dataContainer.blockNode(networkID, nodeID, reason);
                                System.out.println("[BLOCK_NODE] Node " + nodeID + " blocked from network " + networkID);
                            }

                            // This is a state-modifying event that should be recorded to c1 (Admin) chain
                            ne.executeOnSync = true;

                        } catch (Exception e) {
                            System.err.println("[BLOCK_NODE] Error handling event: " + e.getMessage());
                            e.printStackTrace();
                        }
                        handledLocally = true;
                        break;
                    case UNBLOCK_NODE:
                        System.out.println("[" + connectX.getOwnID() + "] UNBLOCK_NODE event received from " + nc.iD);
                        try {
                            // Parse payload: {network: "NETWORKID", nodeID: "UUID"}
                            String unblockJson = new String(eventPayload, "UTF-8");
                            java.util.Map<String, Object> unblockData =
                                (java.util.Map<String, Object>) ConnectX.deserialize("cxJSON1", unblockJson, java.util.Map.class);

                            String networkID = (String) unblockData.get("network");
                            String nodeID = (String) unblockData.get("nodeID");

                            System.out.println("[UNBLOCK_NODE] Unblocking node " + nodeID + " from network " + networkID);

                            // Check if CXNET-level or network-specific unblock
                            if ("CXNET".equals(networkID)) {
                                // CXNET-level unblock
                                connectX.unblockNodeCXNET(nodeID);
                            } else {
                                // Network-specific unblock (stored in local DataContainer)
                                String removedReason = connectX.dataContainer.unblockNode(networkID, nodeID);
                                if (removedReason != null) {
                                    System.out.println("[UNBLOCK_NODE] Node " + nodeID + " unblocked from network " + networkID +
                                                     " (was blocked for: " + removedReason + ")");
                                }
                            }

                            // This is a state-modifying event that should be recorded to c1 (Admin) chain
                            ne.executeOnSync = true;

                        } catch (Exception e) {
                            System.err.println("[UNBLOCK_NODE] Error handling event: " + e.getMessage());
                            e.printStackTrace();
                        }
                        handledLocally = true;
                        break;
                    case REGISTER_NODE:
                        System.out.println("[" + connectX.getOwnID() + "] REGISTER_NODE event received from " + nc.iD);
                        try {
                            // Parse payload: {network: "NETWORKID", nodeID: "UUID", approver: "APPROVER_UUID"}
                            String registerJson = new String(eventPayload, "UTF-8");
                            java.util.Map<String, Object> registerData =
                                (java.util.Map<String, Object>) ConnectX.deserialize("cxJSON1", registerJson, java.util.Map.class);

                            String networkID = (String) registerData.get("network");
                            String nodeID = (String) registerData.get("nodeID");
                            String approver = (String) registerData.get("approver");

                            System.out.println("[REGISTER_NODE] Registering node " + nodeID + " to network " + networkID +
                                             " (approved by " + approver + ")");

                            // Add to registered nodes set (stored in local DataContainer)
                            connectX.dataContainer.networkRegisteredNodes.computeIfAbsent(networkID, k -> new java.util.HashSet<>()).add(nodeID);
                            System.out.println("[REGISTER_NODE] Node " + nodeID + " registered to network " + networkID);
                            System.out.println("[REGISTER_NODE] Total registered nodes: " +
                                connectX.dataContainer.networkRegisteredNodes.get(networkID).size());

                            // This is a state-modifying event that should be recorded to c1 (Admin) chain
                            // System reads c1 to rebuild registeredNodes set during bootstrap/sync
                            ne.executeOnSync = true;

                        } catch (Exception e) {
                            System.err.println("[REGISTER_NODE] Error handling event: " + e.getMessage());
                            e.printStackTrace();
                        }
                        handledLocally = true;
                        break;
                    case GRANT_PERMISSION:
                        System.out.println("[" + connectX.getOwnID() + "] GRANT_PERMISSION event received from " + nc.iD);
                        try {
                            // Parse payload: {network: "NETWORKID", nodeID: "UUID", permission: "Record", chain: 3, priority: 10}
                            String grantJson = new String(eventPayload, "UTF-8");
                            java.util.Map<String, Object> grantData =
                                (java.util.Map<String, Object>) ConnectX.deserialize("cxJSON1", grantJson, java.util.Map.class);

                            String networkID = (String) grantData.get("network");
                            String nodeID = (String) grantData.get("nodeID");
                            String permission = (String) grantData.get("permission");
                            Object chainObj = grantData.get("chain");
                            Long chainID = chainObj instanceof Integer ? ((Integer) chainObj).longValue() : (Long) chainObj;
                            Object priorityObj = grantData.get("priority");
                            int priority = priorityObj instanceof Integer ? (Integer) priorityObj : 10;

                            System.out.println("[GRANT_PERMISSION] Granting " + permission + " permission to node " + nodeID +
                                             " on network " + networkID + " chain " + chainID + " (priority: " + priority + ")");

                            // Get the network
                            CXNetwork network = connectX.getNetwork(networkID);
                            if (network != null) {
                                // Create permission entry
                                String permKey = permission + "-" + chainID;
                                us.anvildevelopment.util.tools.permissions.Entry entry =
                                    new us.anvildevelopment.util.tools.permissions.BasicEntry(permKey, true, priority);

                                // Add to network permissions
                                java.util.Map<String, us.anvildevelopment.util.tools.permissions.Entry> nodePerms =
                                    network.networkPermissions.permissionSet.computeIfAbsent(nodeID, k -> new java.util.HashMap<>());
                                nodePerms.put(permKey, entry);

                                System.out.println("[GRANT_PERMISSION] Permission granted successfully");
                            } else {
                                System.err.println("[GRANT_PERMISSION] Network not found: " + networkID);
                            }

                            // This is a state-modifying event that should be recorded to c1 (Admin) chain
                            // System reads c1 to rebuild permissions during bootstrap/sync
                            ne.executeOnSync = true;

                        } catch (Exception e) {
                            System.err.println("[GRANT_PERMISSION] Error handling event: " + e.getMessage());
                            e.printStackTrace();
                        }
                        handledLocally = true;
                        break;
                    case REVOKE_PERMISSION:
                        System.out.println("[" + connectX.getOwnID() + "] REVOKE_PERMISSION event received from " + nc.iD);
                        try {
                            // Parse payload: {network: "NETWORKID", nodeID: "UUID", permission: "Record", chain: 3}
                            String revokeJson = new String(eventPayload, "UTF-8");
                            java.util.Map<String, Object> revokeData =
                                (java.util.Map<String, Object>) ConnectX.deserialize("cxJSON1", revokeJson, java.util.Map.class);

                            String networkID = (String) revokeData.get("network");
                            String nodeID = (String) revokeData.get("nodeID");
                            String permission = (String) revokeData.get("permission");
                            Object chainObj = revokeData.get("chain");
                            Long chainID = chainObj instanceof Integer ? ((Integer) chainObj).longValue() : (Long) chainObj;

                            System.out.println("[REVOKE_PERMISSION] Revoking " + permission + " permission from node " + nodeID +
                                             " on network " + networkID + " chain " + chainID);

                            // Get the network
                            CXNetwork network = connectX.getNetwork(networkID);
                            if (network != null) {
                                // Remove permission entry
                                String permKey = permission + "-" + chainID;
                                java.util.Map<String, us.anvildevelopment.util.tools.permissions.Entry> nodePerms =
                                    network.networkPermissions.permissionSet.get(nodeID);
                                if (nodePerms != null) {
                                    nodePerms.remove(permKey);
                                    if (nodePerms.isEmpty()) {
                                        network.networkPermissions.permissionSet.remove(nodeID);
                                    }
                                    System.out.println("[REVOKE_PERMISSION] Permission revoked successfully");
                                } else {
                                    System.out.println("[REVOKE_PERMISSION] Node had no permissions to revoke");
                                }
                            } else {
                                System.err.println("[REVOKE_PERMISSION] Network not found: " + networkID);
                            }

                            // This is a state-modifying event that should be recorded to c1 (Admin) chain
                            // System reads c1 to rebuild permissions during bootstrap/sync
                            ne.executeOnSync = true;

                        } catch (Exception e) {
                            System.err.println("[REVOKE_PERMISSION] Error handling event: " + e.getMessage());
                            e.printStackTrace();
                        }
                        handledLocally = true;
                        break;
                    case ZERO_TRUST_ACTIVATION:
                        System.out.println("[" + connectX.getOwnID() + "] ZERO_TRUST_ACTIVATION event received from " + nc.iD);
                        try {
                            // Parse payload: {network: "NETWORKID", seed: {...}}
                            String ztJson = new String(eventPayload, "UTF-8");
                            java.util.Map<String, Object> ztData =
                                (java.util.Map<String, Object>) ConnectX.deserialize("cxJSON1", ztJson, java.util.Map.class);

                            String networkID = (String) ztData.get("network");
                            java.util.Map<String, Object> seedData = (java.util.Map<String, Object>) ztData.get("seed");

                            System.out.println("[ZERO_TRUST_ACTIVATION] Activating zero trust mode for network " + networkID);
                            System.out.println("[ZERO_TRUST_ACTIVATION] WARNING: This operation is IRREVERSIBLE");

                            // Get the network
                            CXNetwork network = connectX.getNetwork(networkID);
                            if (network != null) {
                                // Verify sender is NMI (first backend)
                                boolean isNMI = network.configuration != null &&
                                               network.configuration.backendSet != null &&
                                               !network.configuration.backendSet.isEmpty() &&
                                               network.configuration.backendSet.get(0).equals(nc.iD);

                                if (!isNMI) {
                                    System.err.println("[ZERO_TRUST_ACTIVATION] Rejected: sender " + nc.iD.substring(0, 8) + " is not NMI");
                                    handledLocally = true;
                                    break;
                                }

                                // Check if already in zero trust mode
                                if (network.zT) {
                                    System.out.println("[ZERO_TRUST_ACTIVATION] Network already in zero trust mode, ignoring duplicate activation");
                                    handledLocally = true;
                                    break;
                                }

                                // Activate zero trust mode
                                network.zT = true;
                                System.out.println("[ZERO_TRUST_ACTIVATION] Set network.zT = true");

                                // Apply seed data updates if provided
                                if (seedData != null && seedData.containsKey("zT")) {
                                    Boolean ztFlag = (Boolean) seedData.get("zT");
                                    System.out.println("[ZERO_TRUST_ACTIVATION] Seed zT flag: " + ztFlag);
                                }

                                System.out.println("[ZERO_TRUST_ACTIVATION] Zero trust mode activated for " + networkID);
                                System.out.println("[ZERO_TRUST_ACTIVATION] NMI permissions are now blocked");
                                System.out.println("[ZERO_TRUST_ACTIVATION] Network is fully decentralized");

                                // Persist network configuration
                                try {
                                    if (connectX.blockchainPersistence != null) {
                                        connectX.blockchainPersistence.saveChainMetadata(network.c1, networkID);
                                        connectX.blockchainPersistence.saveChainMetadata(network.c2, networkID);
                                        connectX.blockchainPersistence.saveChainMetadata(network.c3, networkID);
                                        System.out.println("[ZERO_TRUST_ACTIVATION] Network configuration persisted");
                                    }
                                } catch (Exception persistEx) {
                                    System.err.println("[ZERO_TRUST_ACTIVATION] Failed to persist configuration: " + persistEx.getMessage());
                                }

                                // TODO: Trigger blockchain re-sync using zero trust consensus protocols
                                // This will be implemented when multi-peer block querying and reconciliation are ready

                            } else {
                                System.err.println("[ZERO_TRUST_ACTIVATION] Network not found: " + networkID);
                            }

                            // This is a state-modifying event that should be recorded to c1 (Admin) chain
                            // System reads c1 to rebuild zero trust state during bootstrap/sync
                            ne.executeOnSync = true;

                        } catch (Exception e) {
                            System.err.println("[ZERO_TRUST_ACTIVATION] Error handling event: " + e.getMessage());
                            e.printStackTrace();
                        }
                        handledLocally = true;
                        break;
                    case PEER_LIST_REQUEST:
                        System.out.println("[" + connectX.getOwnID() + "] PEER_LIST_REQUEST received from " + nc.iD);
                        try {
                            // Get requester's IP address (from socket if available)
                            String requesterIP = nc.iD; // TODO: Extract actual IP from socket/connection context

                            // Check rate limiting: 3 requests per IP per hour
                            if (isPeerRequestRateLimited(requesterIP)) {
                                System.out.println("[PEER_LIST_REQUEST] Rate limit exceeded for IP " + requesterIP + " (3 per hour)");
                                handledLocally = true;
                                break;
                            }

                            // Record this request for rate limiting
                            recordPeerRequest(requesterIP);

                            // Collect all known peers from PeerDirectory
                            java.util.List<Node> allPeers = new java.util.ArrayList<>();
                            if (peerDirectory.hv != null) allPeers.addAll(peerDirectory.hv.values());
                            if (peerDirectory.seen != null) allPeers.addAll(peerDirectory.seen.values());
                            if (peerDirectory.peerCache != null) allPeers.addAll(peerDirectory.peerCache.values());

                            // Get peer count (30% of known peers or max 10)
                            int knownPeerCount = allPeers.size();
                            int maxPeers = Math.min(10, (int) Math.ceil(knownPeerCount * 0.3));

                            // Select random peers and extract only IP:port
                            java.util.List<String> peerIPs = new java.util.ArrayList<>();

                            // Shuffle and take up to maxPeers
                            java.util.Collections.shuffle(allPeers);
                            for (int i = 0; i < Math.min(maxPeers, allPeers.size()); i++) {
                                Node peer = allPeers.get(i);
                                if (peer.addr != null) {
                                    peerIPs.add(peer.addr); // addr is already in "host:port" format
                                }
                            }

                            // Serialize response: {ips: ["192.168.1.100:49152", "10.0.0.5:49153", ...]}
                            java.util.Map<String, Object> response = new java.util.HashMap<>();
                            response.put("ips", peerIPs);
                            String responseJson = ConnectX.serialize("cxJSON1", response);

                            System.out.println("[PEER_LIST_REQUEST] Sending " + peerIPs.size() + " peer IPs to " + nc.iD);

                            // Send response using EventBuilder pattern
                            ConnectX.EventBuilder eb = connectX.buildEvent(EventType.PEER_LIST_RESPONSE, responseJson.getBytes("UTF-8"))
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
                            System.err.println("[PEER_LIST_REQUEST] Error handling request: " + e.getMessage());
                            e.printStackTrace();
                        }
                        handledLocally = true;
                        break;
                    case PEER_LIST_RESPONSE:
                        System.out.println("[" + connectX.getOwnID() + "] PEER_LIST_RESPONSE received from " + nc.iD);
                        try {
                            // Parse response: {ips: ["192.168.1.100:49152", ...]}
                            String responseJson = new String(eventPayload, "UTF-8");
                            java.util.Map<String, Object> response =
                                (java.util.Map<String, Object>) ConnectX.deserialize("cxJSON1", responseJson, java.util.Map.class);

                            java.util.List<String> peerIPs = (java.util.List<String>) response.get("ips");

                            System.out.println("[PEER_LIST_RESPONSE] Received " + peerIPs.size() + " peer IPs");

                            // TODO: Contact each IP for Node info/seed
                            // For each IP:
                            //   1. Connect to IP:port
                            //   2. Send NewNode or SEED_REQUEST
                            //   3. Receive Node info and add to PeerDirectory
                            //   4. Cache certificate
                            for (String ipPort : peerIPs) {
                                System.out.println("[PEER_LIST_RESPONSE] Received peer: " + ipPort);
                                // TODO: Implement actual connection logic
                            }

                        } catch (Exception e) {
                            System.err.println("[PEER_LIST_RESPONSE] Error handling response: " + e.getMessage());
                            e.printStackTrace();
                        }
                        handledLocally = true;
                        break;
                }
            } catch (IllegalArgumentException ignored) {
                // Not a known EventType constant, already tried plugins above
            } catch (Exception e) {
                e.printStackTrace();
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
                        System.out.println("[" + connectX.getOwnID() + "] Relaying event to: " + ne.p.cxID);
                        return true;
                    }
                } catch (Exception e) {
                    e.printStackTrace();
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
                    System.err.println("[PeerProxy] Error recording event: " + e.getMessage());
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
                        e.printStackTrace();
                    }
                }
            }
            return handledLocally;
        }

        // Step 4c: Handle peerBroad mode - global cross-network transmission
        if (tP.peerBroad) {
            //System.out.println("[RELAY DEBUG] peerBroad=true, broadcasting to " + peerDirectory.hv.size() + " peers");
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
                        e.printStackTrace();
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
            System.out.println("[CXN Relay] Received " + eventType + " from " + transmitter +
                " (original: " + originalSender + ") - preparing relay...");

            CXNetwork cxn = connectX.getNetwork(ne.p.network);
            if (cxn != null && cxn.configuration != null && cxn.configuration.backendSet != null) {
                // Send to all peers, with backend getting priority (sent first)
                java.util.Set<String> sentTo = new java.util.HashSet<>();

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
                            e.printStackTrace();
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
                            e.printStackTrace();
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
                            e.printStackTrace();
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
                                java.util.List<String> addresses = connectX.dataContainer.getLocalPeerAddresses(peerID);
                                if (!addresses.isEmpty()) {
                                    tempPeer.addr = addresses.get(0); // Use first known LAN address
                                }

                                // Use signedEventBytes to preserve original NetworkEvent signature
                                OutputBundle relayBundle = new OutputBundle(ne, tempPeer, null, signedEventBytes, relayContainer);
                                connectX.queueEvent(relayBundle);
                                sentTo.add(peerID);
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                    }
                }

                // Log relay summary
                System.out.println("[CXN Relay] Queued " + eventType + " for relay to " + sentTo.size() + " peers");
            }
        }

        // Step 5: Record to blockchain if scope is CX (must be from authorized sender)
        if (ne.p != null && ne.p.scope != null && ne.p.scope.equalsIgnoreCase("CX")) {
            // CX scope requires authorization from CXNET backendSet or NMI
            if (nc != null && nc.iD != null) {
                connectX.Event(ne, nc.iD);
            }
        }

        return handledLocally;
    }
    public boolean connectNetwork(CXNetwork cxnet) {
        //TODO implement network connection
        return false;
    }

    /**
     * Initiate blockchain sync from a trusted peer (NMI/Backend)
     * Compares local and remote chain heights and requests missing blocks
     */
    /**
     * Apply a block to the blockchain after consensus or authoritative source
     * Handles adding to chain, persisting to disk, and queuing state events
     */
    private void applyBlockToChain(ConnectX connectX, CXNetwork network,
                                  dev.droppinganvil.v3.edge.NetworkRecord targetChain,
                                  dev.droppinganvil.v3.edge.NetworkBlock block,
                                  String networkID, Long chainID, String senderID) {
        try {
            // Add block to local blockchain in memory
            targetChain.blockMap.put(block.block, block);
            System.out.println("[BLOCK_APPLY] Added block " + block.block + " to chain " + chainID + " in memory");

            // Update current block pointer if this is the latest block
            if (targetChain.current == null || block.block > targetChain.current.block) {
                targetChain.current = block;
                System.out.println("[BLOCK_APPLY] Updated current block to " + block.block);
            }

            // Save block to disk for persistence
            try {
                connectX.blockchainPersistence.saveBlock(networkID, chainID, block);
                System.out.println("[BLOCK_APPLY] Saved block " + block.block + " to disk");
            } catch (Exception e) {
                System.err.println("[BLOCK_APPLY] Failed to save block to disk: " + e.getMessage());
            }

            // Prepare block: verify and deserialize all events
            block.prepare(connectX);

            // Queue state-modifying events for processing to rebuild state
            int stateEvents = 0;
            int skippedEvents = 0;
            for (NetworkEvent event : block.deserializedEvents.values()) {
                if (event.executeOnSync) {
                    // Queue event for processing through existing framework
                    dev.droppinganvil.v3.network.events.NetworkContainer eventContainer =
                        new dev.droppinganvil.v3.network.events.NetworkContainer();
                    eventContainer.se = "cxJSON1";
                    eventContainer.s = false;
                    eventContainer.iD = senderID; // Mark as coming from the sender

                    InputBundle ib = new InputBundle(event, eventContainer);
                    connectX.eventQueue.add(ib);

                    stateEvents++;
                    System.out.println("[BLOCK_SYNC] Queued state event: " + event.eT);
                } else {
                    // Skip ephemeral events (messages, pings, etc.)
                    skippedEvents++;
                }
            }

            System.out.println("[BLOCK_SYNC] Block " + block.block + " processed:");
            System.out.println("  - Added to chain " + chainID);
            System.out.println("  - Saved to disk");
            System.out.println("  - Queued " + stateEvents + " state events");
            System.out.println("  - Skipped " + skippedEvents + " ephemeral events");

        } catch (Exception e) {
            System.err.println("[BLOCK_APPLY] Error applying block: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void initiateSyncFromPeer(ConnectX connectX, CXNetwork network, String networkID,
                                     java.util.Map<String, Number> remoteChainStatus, String peerID) {
        try {
            long remoteC1 = remoteChainStatus.get("c1").longValue();
            long remoteC2 = remoteChainStatus.get("c2").longValue();
            long remoteC3 = remoteChainStatus.get("c3").longValue();

            long localC1 = network.c1.current != null ? network.c1.current.block : -1;
            long localC2 = network.c2.current != null ? network.c2.current.block : -1;
            long localC3 = network.c3.current != null ? network.c3.current.block : -1;

            System.out.println("[CHAIN_SYNC] Local chain heights:");
            System.out.println("  c1: " + localC1);
            System.out.println("  c2: " + localC2);
            System.out.println("  c3: " + localC3);

            Node remotePeer = peerDirectory.lookup(peerID, true, true);
            if (remotePeer == null) {
                System.err.println("[CHAIN_SYNC] Cannot sync - peer not found: " + peerID);
                return;
            }

            // Sync c1 (Admin chain) first - most critical for state
            if (remoteC1 > localC1) {
                System.out.println("[CHAIN_SYNC] c1 is behind, requesting " + (remoteC1 - localC1) + " blocks");
                requestMissingBlocks(connectX, networkID, network.networkDictionary.c1, localC1 + 1, remoteC1, remotePeer);
            }

            // Sync c2 (Resources chain)
            if (remoteC2 > localC2) {
                System.out.println("[CHAIN_SYNC] c2 is behind, requesting " + (remoteC2 - localC2) + " blocks");
                requestMissingBlocks(connectX, networkID, network.networkDictionary.c2, localC2 + 1, remoteC2, remotePeer);
            }

            // Sync c3 (Events chain)
            if (remoteC3 > localC3) {
                System.out.println("[CHAIN_SYNC] c3 is behind, requesting " + (remoteC3 - localC3) + " blocks");
                requestMissingBlocks(connectX, networkID, network.networkDictionary.c3, localC3 + 1, remoteC3, remotePeer);
            }

            if (remoteC1 <= localC1 && remoteC2 <= localC2 && remoteC3 <= localC3) {
                System.out.println("[CHAIN_SYNC] Local chains are up to date!");
            }
        } catch (Exception e) {
            System.err.println("[CHAIN_SYNC] Error initiating sync: " + e.getMessage());
            e.printStackTrace();
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
                // Create BLOCK_REQUEST event
                java.util.Map<String, Object> request = new java.util.HashMap<>();
                request.put("network", networkID);
                request.put("chain", chainID);
                request.put("block", blockNum);

                String requestJson = ConnectX.serialize("cxJSON1", request);

                // Send request using EventBuilder pattern
                ConnectX.EventBuilder eb = connectX.buildEvent(EventType.BLOCK_REQUEST, requestJson.getBytes("UTF-8"))
                    .toPeer(remotePeer.cxID)
                    .signData();
                eb.getPath().network = networkID;
                eb.queue();

                System.out.println("[CHAIN_SYNC] Requested block " + blockNum + " from chain " + chainID);
            }
        } catch (Exception e) {
            System.err.println("[CHAIN_SYNC] Error requesting blocks: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Apply a seed after consensus decision
     * Saves EPOCH seeds to disk, adds peers, caches certificates, registers networks
     */
    private void applySeedConsensus(ConnectX connectX, dev.droppinganvil.v3.network.Seed seed,
                                          boolean isEpochSeed, String consensusReason, String targetNetwork) {
        try {
            System.out.println("[SEED CONSENSUS] Applying seed: " + seed.seedID);
            System.out.println("[SEED CONSENSUS] Reason: " + consensusReason);
            System.out.println("[SEED CONSENSUS] Networks: " + seed.networks.size());
            System.out.println("[SEED CONSENSUS] Peers: " + seed.hvPeers.size());
            System.out.println("[SEED CONSENSUS] Certificates: " + seed.certificates.size());

            // Save EPOCH signed seeds to disk so this peer can forward them
            if (isEpochSeed) {
                java.io.File seedsDir = new java.io.File(connectX.cxRoot, "seeds");
                if (!seedsDir.exists()) {
                    seedsDir.mkdirs();
                }
                java.io.File seedFile = new java.io.File(seedsDir, seed.seedID + ".cxn");
                seed.save(seedFile);
                System.out.println("[SEED CONSENSUS] ✓ Saved EPOCH seed: " + seedFile.getName());
                System.out.println("[SEED CONSENSUS] ✓ This peer can now forward EPOCH seed to others!");
            }

            // Add peers to directory
            int peersAdded = 0;
            for (dev.droppinganvil.v3.network.nodemesh.Node peer : seed.hvPeers) {
                try {
                    peerDirectory.addNode(peer);
                    peersAdded++;
                } catch (Exception e) {
                    // Ignore duplicate peer errors
                }
            }
            System.out.println("[SEED CONSENSUS] ✓ Added " + peersAdded + " peers to directory");

            // Apply seed to ConnectX (registers networks)
            seed.apply(connectX);
            System.out.println("[SEED CONSENSUS] ✓ Networks registered");

            // Cache certificates
            int certsAdded = 0;
            for (java.util.Map.Entry<String, String> cert : seed.certificates.entrySet()) {
                try {
                    connectX.encryptionProvider.cacheCert(cert.getKey(), false, false, connectX);
                    certsAdded++;
                } catch (Exception e) {
                    // Ignore cert errors
                }
            }
            System.out.println("[SEED CONSENSUS] ✓ Cached " + certsAdded + " certificates");
            System.out.println("[SEED CONSENSUS] ✓✓✓ Network " + targetNetwork + " is READY!");

        } catch (Exception e) {
            System.err.println("[SEED CONSENSUS] Error applying seed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Perform multi-peer consensus voting on dynamic seeds
     * Compares chain heights, detects conflicts, applies majority consensus
     * If conflicts detected, requests fresh seed from EPOCH as tiebreaker
     */
    private static void performSeedConsensus(ConnectX connectX, String targetNetwork,
                                            java.util.concurrent.ConcurrentHashMap<String, ConnectX.SeedResponseData> responses) {
        try {
            System.out.println("[SEED CONSENSUS] === MULTI-PEER VOTING ===");
            System.out.println("[SEED CONSENSUS] Responses: " + responses.size());

            // Priority 1: Check if EPOCH responded (always trust EPOCH)
            for (ConnectX.SeedResponseData r : responses.values()) {
                if (r.authoritative && r.dynamicSeed != null) {
                    System.out.println("[SEED CONSENSUS] ✓ EPOCH dynamic seed found - TRUSTING");
                    connectX.nodeMesh.applySeedConsensus(connectX, r.dynamicSeed, false,
                        "EPOCH dynamic seed (authoritative)", targetNetwork);
                    return;
                }
            }

            // Priority 2: Compare chain heights across peers for consensus
            java.util.Map<String, Integer> heightVotes = new java.util.HashMap<>();
            for (ConnectX.SeedResponseData r : responses.values()) {
                if (r.chainHeights != null) {
                    // Create signature from chain heights
                    String heightSig = "c1:" + r.chainHeights.get("c1") +
                                     ",c2:" + r.chainHeights.get("c2") +
                                     ",c3:" + r.chainHeights.get("c3");
                    heightVotes.put(heightSig, heightVotes.getOrDefault(heightSig, 0) + 1);
                }
            }

            System.out.println("[SEED CONSENSUS] Chain height voting:");
            for (java.util.Map.Entry<String, Integer> vote : heightVotes.entrySet()) {
                System.out.println("[SEED CONSENSUS]   " + vote.getKey() + " → " + vote.getValue() + " votes");
            }

            // Find majority consensus (>50% agreement)
            String majorityHeights = null;
            int maxVotes = 0;
            for (java.util.Map.Entry<String, Integer> vote : heightVotes.entrySet()) {
                if (vote.getValue() > maxVotes) {
                    maxVotes = vote.getValue();
                    majorityHeights = vote.getKey();
                }
            }

            double consensusPercent = (double) maxVotes / responses.size();
            System.out.println("[SEED CONSENSUS] Majority: " + maxVotes + "/" + responses.size() +
                             " (" + String.format("%.0f%%", consensusPercent * 100) + ")");

            if (consensusPercent >= 0.51) {
                // Majority consensus achieved - use seed from majority peer
                System.out.println("[SEED CONSENSUS] ✓ CONSENSUS REACHED (" +
                                 String.format("%.0f%%", consensusPercent * 100) + " agreement)");

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
                // No consensus - conflict detected
                System.out.println("[SEED CONSENSUS] ✗ CONSENSUS FAILED - Peer conflict detected");
                System.out.println("[SEED CONSENSUS] No majority agreement (only " +
                                 String.format("%.0f%%", consensusPercent * 100) + "%)");
                System.out.println("[SEED CONSENSUS] → Requesting authoritative seed from EPOCH...");

                // TODO: Send SEED_REQUEST directly to EPOCH as tiebreaker
                // For now, use fallback: load signed EPOCH seed from disk if available
                java.io.File seedsDir = new java.io.File(connectX.cxRoot, "seeds");
                if (seedsDir.exists()) {
                    java.io.File[] seedFiles = seedsDir.listFiles((dir, name) -> name.endsWith(".cxn"));
                    if (seedFiles != null && seedFiles.length > 0) {
                        // Load most recent seed
                        java.io.File latestSeed = seedFiles[0];
                        for (java.io.File f : seedFiles) {
                            if (f.lastModified() > latestSeed.lastModified()) {
                                latestSeed = f;
                            }
                        }
                        System.out.println("[SEED CONSENSUS] Using fallback: Signed EPOCH seed from disk");
                        dev.droppinganvil.v3.network.Seed epochSeed =
                            dev.droppinganvil.v3.network.Seed.load(latestSeed);
                        connectX.nodeMesh.applySeedConsensus(connectX, epochSeed, true,
                            "EPOCH signed seed (disk fallback - peer conflict)", targetNetwork);
                        return;
                    }
                }

                System.err.println("[SEED CONSENSUS] ✗ Cannot resolve - no EPOCH seed available");
                System.err.println("[SEED CONSENSUS] Network may be compromised or EPOCH offline");
            }

        } catch (Exception e) {
            System.err.println("[SEED CONSENSUS] Voting error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
