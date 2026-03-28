package us.anvildevelopment.cxnet.io;

import java.io.InputStream;
import java.io.OutputStream;
import us.anvildevelopment.cxnet.ConnectX;

public class IOJob {
    public InputStream is;
    public OutputStream os;
    public Boolean closeAfter;
    public JobType jt;
    /**
     * Represents the success of the IO operation this job should undertake, will not become true on root object until all jobs and doAfter s have completed
     */
    public boolean success;
    /**
     * Use when multiple IO operations are required for one operation.
     * Will be null unless set
     */
    public IOJob next;
    /**
     * For extra job specific data
     */
    public Object o;
    public Object o1;

    public IOJob(InputStream is, OutputStream os, Boolean closeAfter) {
        this.is = is;
        this.os = os;
        this.jt = JobType.WRITE;
        this.closeAfter = closeAfter;
    }

    public IOJob(InputStream is, Boolean closeAfter) {
        this.is = is;
        this.jt = JobType.REVERSE;
        this.closeAfter = closeAfter;
    }

    public IOJob(ConnectX.EventBuilder builder) {
        this.jt = JobType.BUILD_OUTPUT;
        this.o = builder;
    }

    /**
     * This method will be fired after an IO operation on an IOJob unless it has a next IOJob, in this case it will be fired after all jobs contained are completed.
     * Fires on completion of all IO operations in an IOJob in their respective order
     * @param success true unless an exception has occurred
     */
    public void doAfter(boolean success) {

    }
}
