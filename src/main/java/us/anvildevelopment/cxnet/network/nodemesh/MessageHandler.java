/*
 * Copyright (c) 2022. Christopher Willett
 * All Rights Reserved
 */

package us.anvildevelopment.cxnet.network.nodemesh;

public interface MessageHandler {
    void inputMessage(String message, Node node);
}
