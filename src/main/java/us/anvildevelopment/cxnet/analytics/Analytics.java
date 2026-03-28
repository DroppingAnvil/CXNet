/*
 * Copyright (c) 2021 Christopher Willett
 * All Rights Reserved.
 */

package us.anvildevelopment.cxnet.analytics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;

public class Analytics {
    private static final Logger log = LoggerFactory.getLogger(Analytics.class);

    public static HashMap<AnalyticData, String> analyticData = new HashMap<>();
    public static HashMap<AnalyticData, HashSet<String>> aExMap = new HashMap<>();
    public static void addData(AnalyticData ad, Object o) {
        if (ad == AnalyticData.InternalError) {
            log.error("Unexpected error", (Exception) o);
        }
        if (aExMap.containsKey(ad)) {
            if (o instanceof Exception) {
                aExMap.get(ad).add(o.toString());
            }
        }
        if (analyticData.containsKey(ad)) {
            analyticData.replace(ad, analyticData.get(ad), analyticData.get(ad) + "1");
            //todo Analytics
            } else {
            analyticData.put(ad, "1");
        }
        }
}
