/*
 * Copyright (c) 2022. Christopher Willett
 * All Rights Reserved
 */

package us.anvildevelopment.cxnet.io.strings;

import java.io.InputStream;
import java.io.OutputStream;

public interface SerializationProvider {
    public String getString(Object object) throws Exception;
    public void writeToStream(OutputStream os, Object object) throws Exception;
    public Object getObject(String string, Class<?> clazz) throws Exception;
    public Object getObject(InputStream is, Class<?> clazz) throws Exception;
}
