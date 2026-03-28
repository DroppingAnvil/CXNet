package us.anvildevelopment.cxnet.utils.obj;

import java.util.HashSet;

public class BaseStatus {
    /**
     * Provides framework for multithreading
     */
    public IPXLock lockout = new IPXLock();
    /**
     * Services being handled by the same api instance
     */
    public static HashSet<BaseStatus> services = new HashSet<>();
    /**
     * a1 should represent if the service has been started
     * a2 should represent current status
     */
    private Dualean active = new Dualean(false, false);
    /**
     * Represents if a VM restart would be required to restart service.
     * Cannot be changed from true.
     */
    private boolean vmRestart = false;
    /**
     * The service name to be displayed
     */
    public String serviceName;
    /**
     * Service category to be included in
     */
    public String serviceCategory;
    /**
     * Uses of the service per minute, the definition of 'use' is very loose and should be considered when writing a service
     */
    public Long usePerMinute = 0L;
    /**
     * Total errors for instance
     */
    public Integer failures = 0;
    /**
     * When a self test is ran the result is left here
     */
    public Boolean passTest = false;

    /**
     * Base constructor
     */
    public BaseStatus(String serviceName, String serviceCategory) {
        this.serviceCategory = serviceCategory;
        this.serviceName = serviceName;

    }

    public boolean getStarted() {
        return active.a1;
    }
    public boolean getRunning() {
        return active.a2;
    }
    public boolean isVMPair() {
        return vmRestart;
    }
    public void setVMPair() {
        vmRestart = true;
    }
    public void setRunning(boolean b) {
        active.a2 = b;
    }
    public void setStarted() {
        active.a1 = true;
    }

}
