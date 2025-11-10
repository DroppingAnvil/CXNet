/*
 * Copyright (c) 2022. Christopher Willett
 * All Rights Reserved
 */

package dev.droppinganvil.v3.resourcecore;

import dev.droppinganvil.v3.ConnectX;
import dev.droppinganvil.v3.Permission;
import dev.droppinganvil.v3.State;
import dev.droppinganvil.v3.network.CXNetwork;
import dev.droppinganvil.v3.network.CXPath;
import dev.droppinganvil.v3.network.nodemesh.Node;
import dev.droppinganvil.v3.network.nodemesh.PeerDirectory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.Serializable;
import java.util.List;

public class Resource implements Serializable {
    public CXPath p;
    /**
     * Resource version
     */
    public Long rV;
    /**
     * Resource owner ID
     */
    public String oID;
    public ResourceType rt = null;
    public Availability a = null;
    /**
     * for later implementation
     */
    private String rL;
    public List<CXPath> l;
    public String hash;
    /**
     * Should reflect if the resource is a reference to the data or presently contains the data
     * true reflects a reference
     * false reflects data carrier
     */
    public boolean ref = true;
    private Object o;
    /**
     * Method used for persistence
     */
    public String m;
    /**
     * Original
     */
    public byte[] or;
    /**
     * Resource data if contained
     */
    public byte[] rD;
    /**
     * ConnectX instance for crypto and file operations
     */
    private transient ConnectX cx;

    public void setConnectX(ConnectX cx) {
        this.cx = cx;
    }

    public Object getObject(Class<?> clazz, Boolean sync, boolean tryImports) throws Exception {
        if (o != null) return o;
        Node owner = PeerDirectory.lookup(oID, tryImports, true, cx.cxRoot, cx);
        if (rD != null & !ref) {
            ByteArrayInputStream bais = new ByteArrayInputStream(rD);
            o = cx.getSignedObject(oID, bais, clazz, m);
            return o;
        }
        File f = null;
        //TODO futureproof
        switch (p.getScope()) {
            case CXN: f = new File(cx.resources, p.network);
            break;
            case CXS: f = new File(cx.resources, p.cxID);
            break;
        }
        if (f!=null & !f.exists()) f.mkdir();
        File resource = new File(f, p.resourceID);
        if (resource.exists()) {
            if (ref) {
                File signedObject = new File(resource, "cobj.cx");
                if (signedObject.exists()) {
                    o = cx.getSignedObject(oID, signedObject.toURL().openStream(), clazz, "cxJSON1");
                    return o;
                } else if (tryImports) {
                    //TODO implement resource import from network
                }
            } else {
                return null;
            }
        }
        return null;
    }

    public static void importResource(Resource r, ConnectX cx) {
        // Determine resource directory based on CXPath scope
        File resourceDir = cx.locateResourceDIR(r);
        if (resourceDir == null) return;

        File resourceFile = new File(resourceDir, r.p.resourceID);

        // If resource already exists locally, no need to add reference (avoid network slowdown)
        if (resourceFile.exists()) {
            return;
        }

        // Create directory structure if needed
        if (!resourceDir.exists()) {
            resourceDir.mkdirs();
        }

        //TODO: Fetch resource data from network if not local
        //TODO: Verify network permissions will accept this resource before adding to c2
        //TODO: Write resource to local storage
        //TODO: Add reference to blockchain c2 (resources chain) only if new
    }

    public Resource publish(CXNetwork cxnet, ResourceType type, Availability availability, String hash, String resourceLocation, Object o) throws IllegalAccessException {
        assert l == null;
        assert cxnet.c2 != null;
        assert cxnet.networkDictionary != null;
        assert cxnet.networkState == State.READY;
        switch (availability) {
            case PRIVATE_SYSTEM:
                if (!cxnet.configuration.unlimitedUpload) {
                    Integer w = cxnet.getVariableNetworkPermission(cx.getOwnID(), Permission.AddResource.name());
                    if (w!=null && w!=0) {
                        if (cx.locateResourceDIR(this).listFiles().length < w) {
                            //TODO implement resource publishing
                        } else {
                            throw new IllegalAccessException();
                        }
                    } else {
                        throw new IllegalAccessException();
                    }
                }
        }
        return null;
    }
    //TODO resourceID validate
}
