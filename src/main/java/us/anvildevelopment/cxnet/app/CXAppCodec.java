/*
 * Copyright (c) 2026. Christopher Willett
 * All Rights Reserved
 */

package us.anvildevelopment.cxnet.app;

import us.anvildevelopment.cxnet.ConnectX;

/**
 * The single definition of how a CXApp value is written to, and read from, the wire.
 *
 * CXAppServer and CXAppClient each held their own copy of this conversion, and the copies did not
 * agree: the server wrote every value with {@code String.valueOf} while both sides read non-scalars
 * as cxJSON1, and the client's reader had no cxJSON1 branch at all, so a Map field went out as
 * {@code {a=b}} and came back as null. Two copies of a symmetric rule is what allowed one half to
 * be changed without the other, so there is now one copy and both classes delegate to it.
 *
 * The rule: a scalar travels as its plain text form, everything else travels as cxJSON1.
 */
final class CXAppCodec {

    private CXAppCodec() {}

    /**
     * Types carried as plain text rather than cxJSON1.
     *
     * Tested against a declared field or parameter type when reading, which may be primitive, and
     * against a runtime class when writing, which never is, so both spellings of each scalar are
     * listed.
     */
    static boolean isScalar(Class<?> type) {
        return type == String.class
            || type == int.class     || type == Integer.class
            || type == long.class    || type == Long.class
            || type == boolean.class || type == Boolean.class
            || type == double.class  || type == Double.class
            || type == float.class   || type == Float.class;
    }

    /**
     * Render a value for the wire, in the form {@link #coerce} reads it back.
     *
     * Throws rather than falling back to {@code toString} on a value it cannot serialize. Emitting
     * an unparseable string alongside a successful response is the failure this class exists to
     * prevent, so it is not reintroduced as a fallback.
     */
    static String stringify(Object value) throws Exception {
        if (value == null) return "";
        if (isScalar(value.getClass())) return String.valueOf(value);
        return ConnectX.serialize("cxJSON1", value);
    }

    /** Parse a wire value into the declared type, in the form {@link #stringify} wrote it. */
    static Object coerce(String value, Class<?> type) throws Exception {
        if (value == null)                                        return null;
        if (type == String.class)                                 return value;
        if (type == int.class     || type == Integer.class)       return Integer.parseInt(value);
        if (type == long.class    || type == Long.class)          return Long.parseLong(value);
        if (type == boolean.class || type == Boolean.class)       return Boolean.parseBoolean(value);
        if (type == double.class  || type == Double.class)        return Double.parseDouble(value);
        if (type == float.class   || type == Float.class)         return Float.parseFloat(value);
        return ConnectX.deserialize("cxJSON1", value, type);
    }
}
