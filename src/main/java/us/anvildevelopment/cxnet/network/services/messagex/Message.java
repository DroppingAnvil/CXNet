/*
 * Copyright (c) 2021. Christopher Willett
 * All Rights Reserved
 */

package us.anvildevelopment.cxnet.network.services.messagex;

import java.io.Serializable;
import java.util.List;

public class Message implements Serializable {
    /**
     * E2E Encrypted message.
     * Not Accessible.
     */
    public String pgpMessage;
    /**
     * List of network account ids to deposit the message.
     * Accessible
     */
    public List<String> recipients;
    /**
     * Accessible
     */
    public String chatID;
}
