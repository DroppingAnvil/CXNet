/*
 * Copyright (c) 2026. Christopher Willett
 * All Rights Reserved
 */

package us.anvildevelopment.cxnet.app;

/**
 * Failure codes returned in {@link CXAppResponse#error}, each carrying a message suitable for
 * showing to a person in {@link CXAppResponse#message}.
 *
 * The constant name is the wire code and is what callers should branch on; the message is free to
 * be reworded. {@code BROWSER_NOT_ALLOWED} is named to match the token already documented in
 * CX-PROTOCOL.md and consumed by the browser extension, so it must not be renamed.
 *
 * FORBIDDEN deliberately covers three distinct situations: the app is not registered on this node,
 * the caller lacks permission, and the named field or method does not exist. Reporting those
 * separately would let any peer that can reach the node enumerate which apps are installed and
 * which fields and methods they expose, simply by comparing replies. The real reason is logged
 * locally so the node operator can still diagnose it.
 */
public enum CXAppError {

    /** App unknown, permission denied, or target not found. Deliberately ambiguous; see above. */
    FORBIDDEN("That app is unavailable, or you do not have permission"),

    /**
     * Field does not exist. Only ever returned to a caller holding
     * {@link CXAppServer#debugPermission()}; everyone else gets FORBIDDEN.
     */
    UNKNOWN_FIELD("No such field"),

    /**
     * Method does not exist. Only ever returned to a caller holding
     * {@link CXAppServer#debugPermission()}; everyone else gets FORBIDDEN.
     */
    UNKNOWN_METHOD("No such method"),

    /**
     * Target exists but the caller lacks its permission. Only ever returned to a caller holding
     * {@link CXAppServer#debugPermission()}; everyone else gets FORBIDDEN. Holding the debug
     * permission changes only what the failure says, never whether the operation is allowed.
     */
    PERMISSION_DENIED("You do not have permission to do that"),

    /** Request was null or carried no op. */
    MALFORMED_REQUEST("That request was malformed"),

    /** Op was not one of READ, WRITE, INVOKE, REFRESH. */
    UNKNOWN_OP("That operation is not supported"),

    /** Browser surface is disabled for this app via browserEnabled(). */
    BROWSER_NOT_ALLOWED("This app cannot be opened in a browser, open it in CXNexus"),

    /** Field exists and the caller is permitted, but it is not readable. */
    FIELD_NOT_READABLE("That field cannot be read"),

    /** Field exists and the caller is permitted, but it is not writable. */
    FIELD_NOT_WRITABLE("That field cannot be written"),

    /** A required argument was absent. */
    MISSING_ARGUMENT("A required argument was missing"),

    /** The app's lane is saturated; the request was not run. */
    BUSY("That app is busy, try again"),

    /** The handler threw. */
    HANDLER_ERROR("The app failed to handle that request");

    private final String message;

    CXAppError(String message) {
        this.message = message;
    }

    /** Stable wire code. Callers branch on this. */
    public String code() {
        return name();
    }

    /** Human-readable sentence. Safe to reword without breaking callers. */
    public String message() {
        return message;
    }
}
