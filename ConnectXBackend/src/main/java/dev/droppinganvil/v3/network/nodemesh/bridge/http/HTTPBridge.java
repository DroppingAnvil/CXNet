/*
 * Copyright (c) 2022. Christopher Willett
 * All Rights Reserved
 */

package dev.droppinganvil.v3.network.nodemesh.bridge.http;

import dev.droppinganvil.v3.network.CXPath;
import dev.droppinganvil.v3.network.nodemesh.OutputBundle;
import dev.droppinganvil.v3.network.nodemesh.bridge.CXBridge;
import okhttp3.*;

import java.net.Socket;

/**
 * Simple HTTP bridge for CX.
 * CXPath:
 *  Bridge: cxHTTP
 *  BridgeArg: HTTP Path
 */
public class HTTPBridge implements CXBridge {
    public static OkHttpClient httpClient;
    public static final MediaType jsonType = MediaType.get("application/json; charset=utf-8");

    @Override
    public String getProtocol() {
        return "cxHTTP";
    }

    @Override
    public Integer getVersion() {
        return 1;
    }

    @Override
    public void setup() {
        httpClient = new OkHttpClient();
    }

    @Override
    public Socket connect(CXPath path, byte[] data) {
        return null;
    }

    @Override
    public boolean getDirectSocket() {
        return false;
    }

    @Override
    public boolean transmitEvent(CXPath path, byte[] data) {
        try {
            RequestBody rb = RequestBody.create(data, jsonType);
            Request r = new Request.Builder().url(path.bridgeArg).post(rb).build();
            Response rr = httpClient.newCall(r).execute();
            //TODO handle response
            rr.close();
            return rr.isSuccessful();
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
}
