/*
 * Copyright (c) 2026. Christopher Willett
 * All Rights Reserved
 */

package dev.droppinganvil.v3.api;

/**
 * Controls what data is passed to {@link CXPlugin#handleEvent(Object)}.
 *
 * <ul>
 *   <li>{@link #NETWORK_EVENT} — raw {@link dev.droppinganvil.v3.network.events.NetworkEvent}</li>
 *   <li>{@link #INPUT_BUNDLE} — the full {@link dev.droppinganvil.v3.network.InputBundle},
 *       including signed bytes, verified bytes, and network container</li>
 *   <li>{@link #OBJECT} — payload deserialized into {@link CXPlugin#type} using the
 *       serialization method from the network container (nc.se)</li>
 * </ul>
 */
public enum DataLevel {
    NETWORK_EVENT,
    INPUT_BUNDLE,
    OBJECT,
}
