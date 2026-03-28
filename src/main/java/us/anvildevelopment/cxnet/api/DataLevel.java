/*
 * Copyright (c) 2026. Christopher Willett
 * All Rights Reserved
 */

package us.anvildevelopment.cxnet.api;

import us.anvildevelopment.cxnet.network.InputBundle;
import us.anvildevelopment.cxnet.network.events.NetworkEvent;

/**
 * Controls what data is passed to {@link CXPlugin#handleEvent(Object)}.
 *
 * <ul>
 *   <li>{@link #NETWORK_EVENT} — raw {@link NetworkEvent}</li>
 *   <li>{@link #INPUT_BUNDLE} — the full {@link InputBundle},
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
