/*
 * Copyright (c) 2021. Christopher Willett
 * All Rights Reserved
 */

package us.anvildevelopment.cxnet.io;

import java.io.InputStream;
import java.net.Socket;

/**
 * Object for initially reading network inputs
 */
public class NetworkInputIOJob extends IOJob {
    public String ina;
    public Socket s;
    public NetworkInputIOJob(InputStream is, String ina) {
        super(is, null, true);
        jt = JobType.NETWORK_READ;
        this.ina = ina;
    }
    public NetworkInputIOJob(InputStream is, Socket socket) {
        super(is, null, true);
        jt = JobType.NETWORK_READ;
        this.ina = socket.getInetAddress().getHostAddress();
        this.s = socket;
    }

}
