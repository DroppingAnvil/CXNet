package dev.droppinganvil.v3.network.remotedirectory;

import dev.droppinganvil.v3.Configuration;
import dev.droppinganvil.v3.ConnectX;
import dev.droppinganvil.v3.io.IOJob;
import dev.droppinganvil.v3.io.IOThread;
import dev.droppinganvil.v3.network.CXNetwork;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import us.anvildevelopment.util.tools.database.FileConnector;
import us.anvildevelopment.util.tools.permissions.BasicPermissionContainer;

import java.io.*;

/**
 * Object for accessing files stored remotely or locally
 */
public class RemoteDirectory {
    public static OkHttpClient httpClient = new OkHttpClient();

    public boolean readOnly;
    /**
     * Location of file in server scope
     */
    public String serverID;
    /**
     * Location of directory in machine scope
     */
    public String machinePath;
    /**
     * Remote URL for HTTP access
     */
    public String url;
    /**
     * Permission container for directory
     */
    public BasicPermissionContainer directoryPermissions;
    public Boolean useDepthIfAvailable = false;
    private String auth;
    private ConnectX cx;

    public RemoteDirectory(String address, String directoryPath, String auth, ConnectX cx) {
        this.auth = auth;
        this.cx = cx;
        machinePath = directoryPath;
        this.url = address;
        if (isLocal()) {

        }
    }

    public void copyFile(String path, OutputStream os, Boolean sync) throws IOException {
        InputStream is;
        //TODO Validity checks
        if (isLocal()) {
            is = new FileInputStream(path);
        } else {
            Response r = httpClient.newCall(new Request.Builder().url(url + path).build()).execute();
            ResponseBody rb = r.body();
            if (rb == null) throw new FileNotFoundException();
            is = rb.byteStream();
        }
        IOJob ioJob = new IOJob(is, os, true);
        synchronized (cx.jobQueue) {
            cx.jobQueue.add(ioJob);
        }
    }

    public void loadPermissions() {
        if (isLocal()) {
            // Local directory: load permissions from file using FileConnector
            File permissionFile = new File(machinePath, ".permissions");
            try {
                FileConnector fc = new FileConnector("permissions", BasicPermissionContainer.class, new File(machinePath));
                if (!permissionFile.exists()) {
                    // Create default permissions if not available
                    directoryPermissions = new BasicPermissionContainer();
                    fc.saveData(directoryPermissions, ".permissions", "default");
                } else {
                    // Load existing permissions
                    directoryPermissions = (BasicPermissionContainer) fc.getObject(".permissions", "default", BasicPermissionContainer.class);
                }
            } catch (Exception e) {
                e.printStackTrace();
                // Fallback to empty permissions
                directoryPermissions = new BasicPermissionContainer();
            }
        } else {
            // Remote directory: fetch permissions from network blockchain via CXNode
            CXNetwork network = ConnectX.getNetwork(serverID);
            if (network != null) {
                // TODO: Query blockchain (c2 - resources chain) for directory permissions at machinePath
                // For now, use network-level permissions as fallback
                directoryPermissions = network.networkPermissions;
            } else {
                // Network not found, use empty permissions
                directoryPermissions = new BasicPermissionContainer();
            }
        }
    }

    public boolean uploadFile(File f) {
        //TODO implement file upload
        return false;
    }

    public boolean uploadFile(FileOutputStream fos) {
        //TODO implement file upload from stream
        return false;
    }

    public boolean isLocal() {
        // Directory is local if no serverID specified, or if it matches this node's ID
        return serverID == null || serverID.equals(cx.getOwnID());
    }


}
