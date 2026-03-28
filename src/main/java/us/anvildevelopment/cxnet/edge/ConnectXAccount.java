/*
 * Copyright (c) 2021. Christopher Willett
 * All Rights Reserved
 */

package us.anvildevelopment.cxnet.edge;

import us.anvildevelopment.cxnet.ConnectX;

/**
 * Represents a ConnectX client account/session
 */
public class ConnectXAccount {
    /**
     * ConnectX ID for this account
     */
    public String cxID;

    /**
     * Username/display name
     */
    public String username;

    /**
     * ConnectX instance for this account
     */
    public ConnectX cx;

    /**
     * Whether this account is currently authenticated
     */
    public boolean authenticated = false;

    public ConnectXAccount() {
    }

    public ConnectXAccount(String cxID, ConnectX cx) {
        this.cxID = cxID;
        this.cx = cx;
    }
}