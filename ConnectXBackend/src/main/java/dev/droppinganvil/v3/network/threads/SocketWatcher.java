/*
 * Copyright (c) 2021. Christopher Willett
 * All Rights Reserved
 */

package dev.droppinganvil.v3.network.threads;

import dev.droppinganvil.v3.ConnectX;
import dev.droppinganvil.v3.io.IOThread;
import dev.droppinganvil.v3.io.NetworkInputIOJob;
import dev.droppinganvil.v3.network.nodemesh.InConnectionManager;
import dev.droppinganvil.v3.network.nodemesh.NodeConfig;
import dev.droppinganvil.v3.network.nodemesh.NodeMesh;

import java.io.IOException;
import java.net.Socket;

public class SocketWatcher implements Runnable {
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
                    System.out.println("[RX:" + localPort + "/" + cx.getOwnID().substring(0,8) + "] ACCEPT from " + remoteAddr + " (connected=" + s.isConnected() + ", closed=" + s.isClosed() + ")");

                    if (!NodeMesh.blacklist.containsKey(s.getInetAddress().getHostAddress()) & !NodeMesh.timeout.containsKey(s.getInetAddress().getHostAddress())) {
                        java.io.InputStream rawStream = s.getInputStream();
                        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();

                        long setupTime = System.currentTimeMillis();
                        int available = rawStream.available();
                        String peerPrefix = "[RX:" + localPort + "/" + cx.getOwnID().substring(0,8) + "]";
                        System.out.println(peerPrefix + " Setup " + (setupTime - acceptTime) + "ms, available=" + available + " bytes");

                        s.setSoTimeout(400); // 400ms timeout
                        byte[] chunk = new byte[8192];
                        int bytesRead;
                        try {
                            long readStart = System.currentTimeMillis();
                            bytesRead = rawStream.read(chunk);
                            long readDuration = System.currentTimeMillis() - readStart;

                            if (bytesRead > 0) {
                                System.out.println(peerPrefix + " Read " + bytesRead + " bytes in " + readDuration + "ms");
                                buffer.write(chunk, 0, bytesRead);
                                while (rawStream.available() > 0 && (bytesRead = rawStream.read(chunk)) != -1) {
                                    buffer.write(chunk, 0, bytesRead);
                                }
                            } else if (bytesRead == -1) {
                                System.out.println(peerPrefix + " EOF after " + readDuration + "ms - sender closed");
                            } else {
                                System.out.println(peerPrefix + " Zero bytes after " + readDuration + "ms");
                            }
                        } catch (java.net.SocketTimeoutException e) {
                            System.out.println(peerPrefix + " TIMEOUT after 400ms - no data");
                        } catch (Exception e) {
                            System.out.println(peerPrefix + " ERROR: " + e.getClass().getSimpleName() + " - " + e.getMessage());
                        }

                        byte[] data = buffer.toByteArray();
                        long totalTime = System.currentTimeMillis() - acceptTime;
                        System.out.println(peerPrefix + " TOTAL: " + data.length + " bytes in " + totalTime + "ms");

                        // Create InputStream from buffered data
                        java.io.ByteArrayInputStream bufferedStream = new java.io.ByteArrayInputStream(data);
                        NetworkInputIOJob ni = new NetworkInputIOJob(bufferedStream, s);  // Pass buffered stream + Socket
                        synchronized (cx.jobQueue) {
                            cx.jobQueue.add(ni);
                        }
                    } else {
                        System.out.println("[SocketWatcher] Connection blacklisted/timeout, closing");
                        s.close();
                    }
                }
                Thread.sleep(NodeConfig.ioSocketSleep);
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
            }
        }
    }
}
