/*
 * Copyright (c) 2021. Christopher Willett
 * All Rights Reserved
 */

package us.anvildevelopment.cxnet.network.services.messagex;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

public class MessageXContainer implements Serializable {
    public Map<String, Chat> chats = new HashMap<>();

    public Boolean transmitMessage(Message m) {
        //TODO implement message transmission
        return false;
    }
}
