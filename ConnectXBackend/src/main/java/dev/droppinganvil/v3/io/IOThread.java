package dev.droppinganvil.v3.io;

import dev.droppinganvil.v3.ConnectX;
import dev.droppinganvil.v3.analytics.AnalyticData;
import dev.droppinganvil.v3.analytics.Analytics;
import dev.droppinganvil.v3.crypt.core.exceptions.DecryptionFailureException;
import dev.droppinganvil.v3.network.InputBundle;
import dev.droppinganvil.v3.network.UnauthorizedNetworkConnectivityException;
import dev.droppinganvil.v3.network.events.EventType;
import dev.droppinganvil.v3.network.events.NetworkContainer;
import dev.droppinganvil.v3.network.nodemesh.NodeConfig;
import dev.droppinganvil.v3.network.nodemesh.NodeMesh;
import dev.droppinganvil.v3.network.events.NetworkEvent;
import dev.droppinganvil.v3.utils.obj.BaseStatus;

import java.io.*;
import java.net.Socket;

public class IOThread implements Runnable {
    public boolean run = false;
    public Long sleep;
    public BaseStatus status;
    public ConnectX cx;
    private NodeMesh nodeMesh;

    public IOThread(Long sleep, ConnectX cx, NodeMesh nodeMesh) {
        this.cx = cx;
        this.nodeMesh = nodeMesh;
        assert sleep != null;
        this.sleep = sleep;
    }

    public IOThread(Long sleep, BaseStatus bs, ConnectX cx, NodeMesh nodeMesh) {
        this.cx = cx;
        this.nodeMesh = nodeMesh;
        assert sleep != null;
        this.sleep = sleep;
        status = bs;
    }
    @Override
    public void run() {
        while (run) {
            IOJob ioJob;
            synchronized (cx.jobQueue) {
                ioJob = cx.jobQueue.poll();
            }
            if (ioJob != null) {
                try {
                    processJob(ioJob, true);
                } catch (Exception e) {
                    e.printStackTrace();
                    ioJob.success = false;
                    ioJob.doAfter(false);
                }
                ioJob.success = true;
                ioJob.doAfter(true);
            } else {
                try {
                    Thread.sleep(sleep);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
    }
    public static ByteArrayOutputStream reverse(InputStream is, Boolean closeAfter) throws IOException {
        //TODO
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[NodeConfig.ioReverseByteBuffer];
        while(is.read(buffer) > -1) {
            baos.write(buffer);
        }
        if (closeAfter) is.close();
        return baos;
    }
    public static void write(InputStream is, OutputStream os, Boolean closeAfter) throws IOException {
        //TODO
        byte[] buffer = new byte[NodeConfig.ioWriteByteBuffer];
        while(is.read(buffer) > -1) {
            os.write(buffer);
        }
        if (closeAfter) {
            is.close();
            os.close();
        }
    }
    public void signObject(IOJob ioJob) throws Exception {
        if (ioJob.is == null) {
            OutputStream oss = new ByteArrayOutputStream();
            cx.encryptionProvider.sign(null, oss);
            ConnectX.serialize(String.valueOf(ioJob.o1), ioJob.o, oss);
        } else {

        }
    }
    public boolean processJob(IOJob ioJob, boolean root) throws IOException {
        try {
            switch (ioJob.jt) {
                case WRITE:
                    write(ioJob.is, ioJob.os, ioJob.closeAfter);
                    break;
                case REVERSE:
                    reverse(ioJob.is, ioJob.closeAfter);
                    break;
                case NETWORK_READ:
                    NetworkInputIOJob ioj = (NetworkInputIOJob) ioJob;
                    nodeMesh.processNetworkInput(ioJob.is, ioj.s);
                    break;
                case SIGN_OBJECT:
            }
            if (ioJob.next != null) {
                processJob(ioJob.next, false);
            }
            if (!root) {
                ioJob.success = true;
                ioJob.doAfter(true);
            }
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            ioJob.success = false;
            ioJob.doAfter(false);
            return false;
        }
    }
}
