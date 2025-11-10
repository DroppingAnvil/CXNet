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

    public SocketWatcher(ConnectX cx, NodeMesh nodeMesh) {
        this.cx = cx;
        this.nodeMesh = nodeMesh;
    }
    @Override
    public void run() {
        while (active) {
            try {
                Socket s = InConnectionManager.serverSocket.accept();
                if (s != null) {
                    if (!NodeMesh.blacklist.containsKey(s.getInetAddress().getHostAddress()) & !NodeMesh.timeout.containsKey(s.getInetAddress().getHostAddress())) {
                        NetworkInputIOJob ni = new NetworkInputIOJob(s.getInputStream(), s.getInetAddress().getHostAddress());
                        synchronized (cx.jobQueue) {
                            cx.jobQueue.add(ni);
                        }
                    } else {
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
