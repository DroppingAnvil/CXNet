/*
 * Copyright (c) 2021. Christopher Willett
 * All Rights Reserved
 */

package dev.droppinganvil.v3.edge;

import dev.droppinganvil.v3.ConnectX;

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