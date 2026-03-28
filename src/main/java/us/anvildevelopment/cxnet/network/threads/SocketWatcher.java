/*
 * Copyright (c) 2021. Christopher Willett
 * All Rights Reserved
 */

package us.anvildevelopment.cxnet.network.threads;

import us.anvildevelopment.cxnet.ConnectX;
import us.anvildevelopment.cxnet.io.NetworkInputIOJob;
import us.anvildevelopment.cxnet.network.nodemesh.NodeConfig;
import us.anvildevelopment.cxnet.network.nodemesh.NodeMesh;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.Socket;

public class SocketWatcher implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(SocketWatcher.class);
    public static boolean active = true;
    public ConnectX cx;
    private NodeMesh nodeMesh;
    private int localPort;

    public SocketWatcher(ConnectX cx, NodeMesh nodeMesh) {
        this.cx = cx;
        this.nodeMesh = nodeMesh;
        // Get local port from ServerSocket
        try {
            this.localPort = nodeMesh.in.serverSocket.getLocalPort();
        } catch (Exception e) {
            this.localPort = -1;
        }
    }
    @Override
    public void run() {
        while (active) {
            try {
                Socket s = nodeMesh.in.serverSocket.accept();
                if (s != null) {
                    long acceptTime = System.currentTimeMillis();
                    String remoteAddr = s.getInetAddress().getHostAddress() + ":" + s.getPort();
                    log.debug("[RX:{}/{}] ACCEPT from {} (connected={}, closed={})", localPort, cx.getOwnID().substring(0,8), remoteAddr, s.isConnected(), s.isClosed());

                    if (!NodeMesh.blacklist.containsKey(s.getInetAddress().getHostAddress()) & !NodeMesh.timeout.containsKey(s.getInetAddress().getHostAddress())) {
                        java.io.InputStream rawStream = s.getInputStream();
                        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();

                        long setupTime = System.currentTimeMillis();
                        int available = rawStream.available();
                        String peerPrefix = "[RX:" + localPort + "/" + cx.getOwnID().substring(0,8) + "]";
                        log.debug("{} Setup {}ms, available={} bytes", peerPrefix, (setupTime - acceptTime), available);

                        s.setSoTimeout(400); // 400ms timeout
                        byte[] chunk = new byte[8192];
                        int bytesRead;
                        try {
                            long readStart = System.currentTimeMillis();
                            bytesRead = rawStream.read(chunk);
                            long readDuration = System.currentTimeMillis() - readStart;

                            if (bytesRead > 0) {
                                log.debug("{} Read {} bytes in {}ms", peerPrefix, bytesRead, readDuration);
                                buffer.write(chunk, 0, bytesRead);
                                while (rawStream.available() > 0 && (bytesRead = rawStream.read(chunk)) != -1) {
                                    buffer.write(chunk, 0, bytesRead);
                                }
                            } else if (bytesRead == -1) {
                                log.debug("{} EOF after {}ms - sender closed", peerPrefix, readDuration);
                            } else {
                                log.debug("{} Zero bytes after {}ms", peerPrefix, readDuration);
                            }
                        } catch (java.net.SocketTimeoutException e) {
                            log.debug("{} TIMEOUT after 400ms - no data", peerPrefix);
                        } catch (Exception e) {
                            log.debug("{} ERROR: {} - {}", peerPrefix, e.getClass().getSimpleName(), e.getMessage());
                        }

                        byte[] data = buffer.toByteArray();
                        long totalTime = System.currentTimeMillis() - acceptTime;
                        log.debug("{} TOTAL: {} bytes in {}ms", peerPrefix, data.length, totalTime);

                        // Create InputStream from buffered data
                        java.io.ByteArrayInputStream bufferedStream = new java.io.ByteArrayInputStream(data);
                        NetworkInputIOJob ni = new NetworkInputIOJob(bufferedStream, s);  // Pass buffered stream + Socket
                        synchronized (cx.jobQueue) {
                            cx.jobQueue.add(ni);
                        }
                    } else {
                        log.info("[SocketWatcher] Connection blacklisted/timeout, closing");
                        s.close();
                    }
                }
                Thread.sleep(NodeConfig.ioSocketSleep);
            } catch (IOException | InterruptedException e) {
                log.error("SocketWatcher error", e);
            }
        }
    }
}
