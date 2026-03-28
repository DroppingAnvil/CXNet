/*
 * Copyright (c) 2022. Christopher Willett
 * All Rights Reserved
 */

package us.anvildevelopment.cxnet.utils.obj;

import java.io.IOException;

public interface RequiresLogin {
    boolean attemptLogin(String id, String auth) throws IOException;
}
