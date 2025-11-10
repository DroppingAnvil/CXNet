/*
 * Copyright (c) 2022. Christopher Willett
 * All Rights Reserved
 */

package dev.droppinganvil.v3.io.strings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import dev.droppinganvil.v3.io.strings.SerializationProvider;

import java.io.InputStream;
import java.io.OutputStream;

public class JacksonProvider implements SerializationProvider {
    private ObjectMapper mapper;

    public JacksonProvider() {
        mapper = new ObjectMapper();
        // Enable polymorphic type handling for abstract classes like Entry
        PolymorphicTypeValidator ptv = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("us.anvildevelopment.util.tools.permissions.")
                .allowIfSubType("dev.droppinganvil.")
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
