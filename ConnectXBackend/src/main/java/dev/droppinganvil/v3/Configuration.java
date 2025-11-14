/*
 * Copyright (c) 2021 Christopher Willett
 * All Rights Reserved.
 */

package dev.droppinganvil.v3;

import java.io.Serializable;
import java.util.List;

public class Configuration implements Serializable {
    public String SDF_FORMAT = "S-m-H-a-EEE-F-M-y";
    public String netID;
    public String nmiPub;
    public List<String> backendSet;
    public Boolean active = true;
    public Boolean unlimitedUpload = false;

    /**
     * ID of the current official seed for this network
     * Seeds are stored in c2 (Resources chain) and locally in seeds/ directory
     * System automatically loads all resources from c2 when joining network
     * Used for versioned seed distribution and resource lookup
     */
    public String currentSeedID;

    /**
     * ID of the previous official seed (for rollback/version tracking)
     */
    public String lastSeedID;

}
