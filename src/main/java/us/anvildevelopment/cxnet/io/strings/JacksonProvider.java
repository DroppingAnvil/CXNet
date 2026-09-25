/*
 * Copyright (c) 2022. Christopher Willett
 * All Rights Reserved
 */

package us.anvildevelopment.cxnet.io.strings;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;

import java.io.InputStream;
import java.io.OutputStream;

public class JacksonProvider implements SerializationProvider {
    private ObjectMapper mapper;

    public JacksonProvider() {
        mapper = new ObjectMapper();
        // Ignore unknown properties during deserialization to handle schema evolution
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        // Enable polymorphic type handling for abstract classes like Entry
        //AD.target.PACKAGE/REFACTOR | Any time packages are changed or come from out of the CXNET package none will be accepted unless adding here
        //TODO add subtype from added plugins
        PolymorphicTypeValidator ptv = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("us.anvildevelopment.cxnet.")
                .allowIfSubType("us.anvildevelopment.util.tools.permissions.")
                // The bare "us.anvildevelopment." prefix was removed. It subsumed both entries
                // above and widened the reachable set to the entire organisation namespace,
                // including all of the Util dependency, which is a binary artifact outside this
                // source tree. Every Util class this project actually serializes lives under
                // tools.permissions, which is still allowed. A type outside these prefixes now
                // fails to deserialize rather than being instantiated on a remote peer's say-so.
                .allowIfSubType("java.util.")
                .allowIfSubType("java.lang.")
                .build();
        mapper.activateDefaultTyping(ptv, ObjectMapper.DefaultTyping.NON_FINAL);
    }

    @Override
    public String getString(Object object) throws Exception {
        return mapper.writeValueAsString(object);
    }

    @Override
    public void writeToStream(OutputStream os, Object object) throws Exception {
        mapper.writeValue(os, object);
    }

    @Override
    public Object getObject(String string, Class<?> clazz) throws Exception {
        return mapper.readValue(string, clazz);
    }

    @Override
    public Object getObject(InputStream is, Class<?> clazz) throws Exception {
        return mapper.readValue(is, clazz);
    }

}
