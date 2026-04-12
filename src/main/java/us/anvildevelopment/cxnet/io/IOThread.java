package us.anvildevelopment.cxnet.io;

import us.anvildevelopment.cxnet.ConnectX;
import us.anvildevelopment.cxnet.network.nodemesh.NodeConfig;
import us.anvildevelopment.cxnet.network.nodemesh.NodeMesh;
import us.anvildevelopment.cxnet.network.stream.CXStreamSession;
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
                    byte[] buffered = ioJob.is.readAllBytes();
                    if (NodeConfig.streamMainPortMux && cx.streamManager != null
                            && buffered.length >= 4
                            && buffered[0] == CXStreamSession.STREAM_MAGIC[0]
                            && buffered[1] == CXStreamSession.STREAM_MAGIC[1]
                            && buffered[2] == CXStreamSession.STREAM_MAGIC[2]
                            && buffered[3] == CXStreamSession.STREAM_MAGIC[3]) {
                        try {
                            InputStream sock = ioj.s.getInputStream();
                            ioj.s.setSoTimeout(1000);
                            // Get the length byte (byte 4) -- read from socket if not yet buffered
                            int idLen = buffered.length >= 5 ? (buffered[4] & 0xFF) : (sock.read() & 0xFF);
                            // Allocate exactly the header size we need
                            byte[] header = new byte[5 + idLen];
                            int have = Math.min(buffered.length, header.length);
                            System.arraycopy(buffered, 0, header, 0, have);
                            // If idLen was read from the socket (not from buffered), store it in
                            // header[4] and advance 'have' -- otherwise readNBytes would try to
                            // read idLen + session ID bytes when only session ID bytes remain.
                            if (buffered.length < 5) {
                                header[4] = (byte) idLen;
                                have = 5;
                            }
                            if (have < header.length) sock.readNBytes(header, have, header.length - have);
                            String sessionID = new String(header, 5, idLen, java.nio.charset.StandardCharsets.UTF_8);
                            CXStreamSession streamSession = cx.streamManager.getSession(sessionID);
                            if (streamSession != null) {
                                log.info("[IOThread] STREAM mux routing to session {}", sessionID.substring(0, 8));
                                streamSession.attachSocket(ioj.s, header, header.length);
                            } else {
                                log.warn("[IOThread] STREAM mux unknown session {}", sessionID.substring(0, Math.min(8, sessionID.length())));
                                ioj.s.close();
                            }
                        } catch (Exception e) {
                            log.warn("[IOThread] STREAM mux header read failed: {}", e.getMessage());
                            try { ioj.s.close(); } catch (Exception ignored) {}
                        }
                        break;
                    }
                    nodeMesh.processNetworkInput(new ByteArrayInputStream(buffered), ioj.s);
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
