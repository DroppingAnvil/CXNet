package us.anvildevelopment.cxnet.io;

import us.anvildevelopment.cxnet.ConnectX;
import us.anvildevelopment.cxnet.network.nodemesh.NodeConfig;
import us.anvildevelopment.cxnet.network.nodemesh.NodeMesh;
import us.anvildevelopment.cxnet.utils.obj.BaseStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;

public class IOThread implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(IOThread.class);
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
                    log.error("Unexpected error processing IO job", e);
                    ioJob.success = false;
                    ioJob.doAfter(false);
                }
                ioJob.success = true;
                ioJob.doAfter(true);
            } else {
                try {
                    Thread.sleep(sleep);
                } catch (InterruptedException e) {
                    log.error("IOThread sleep interrupted", e);
                }
            }
        }
    }
    public static ByteArrayOutputStream reverse(InputStream is, Boolean closeAfter) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[NodeConfig.ioReverseByteBuffer];
        int bytesRead;
        while ((bytesRead = is.read(buffer)) > -1) {
            baos.write(buffer, 0, bytesRead);
        }
        if (closeAfter) is.close();
        return baos;
    }
    public static void write(InputStream is, OutputStream os, Boolean closeAfter) throws IOException {
        byte[] buffer = new byte[NodeConfig.ioWriteByteBuffer];
        int bytesRead;
        while ((bytesRead = is.read(buffer)) > -1) {
            os.write(buffer, 0, bytesRead);
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
                    ioJob.o = reverse(ioJob.is, ioJob.closeAfter);
                    break;
                case NETWORK_READ:
                    NetworkInputIOJob ioj = (NetworkInputIOJob) ioJob;
                    nodeMesh.processNetworkInput(ioJob.is, ioj.s);
                    break;
                case SIGN_OBJECT:
                    break;
                case BUILD_OUTPUT:
                    ((ConnectX.EventBuilder) ioJob.o).execute();
                    break;
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
            log.error("Unexpected error in processJob", e);
            ioJob.success = false;
            ioJob.doAfter(false);
            return false;
        }
    }
}
