package us.anvildevelopment.cxnet.utils.obj;

public class IPXLock {
    /**
     * Used for application specific use case
     */
    public Object o;
    /**
     * Represents locked state of containing Object
     * a1 should represent the ability to safely edit the container object
     * a2 should represent the lock on this object and should represent the lock on this object, more specifically the timestamp
     */
    private Dualean lock = new Dualean(false, false);
    /**
     * Timestamp for edits to lock status
     */
    private Long timestamp;
    /**
     * Some form of identification for lock usages, should be null when a lock is not active
     */
    public String lockerID;

    public IPXLock() {
        timestamp = System.nanoTime();
    }

    /**
     * Lock container object
     * @param lockerID A String representation of the process that locked the object Ex. EDIT_THREAD_1
     * @throws LockedStateException
     */
    public void lock(String lockerID) throws LockedStateException, UnlockedStateException {
        verifyUnlocked();
        this.lockerID = lockerID;
        lock.a1 = true;
        generateLockTimeStamp();
    }

    /**
     * Unlock container object
     * @param lockerID A String representation of the process that locked the object Ex. EDIT_THREAD_1
     * @throws UnlockedStateException
     * @throws LockOwnerException
     */
    public void unlock(String lockerID) throws UnlockedStateException, LockOwnerException {
        verifyFullLocked();
        verifyOwner(lockerID);
        generateUnlockTimeStamp(lockerID);
        this.lockerID = null;
        lock.a1 = false;
    }

    private void generateLockTimeStamp() throws LockedStateException, UnlockedStateException {
        verifyLocked();
        verifyTimeStampUnlocked();
        lock.a2 = true;
        timestamp = System.nanoTime();
    }
    private void generateUnlockTimeStamp(String lockerID) throws UnlockedStateException, LockOwnerException {
        verifyFullLocked();
        verifyOwner(lockerID);
        lock.a2 = false;
        timestamp = System.nanoTime();
    }



    private void verifyOwner(String providedLID) throws LockOwnerException {
        if (!lockerID.equals(providedLID)) throw new LockOwnerException();
    }

    public void verifyUnlocked() throws LockedStateException {
        if (lock.a1 || lock.a2) throw new LockedStateException();
    }

    public void verifyLocked() throws UnlockedStateException {
        if (!lock.a1) throw new UnlockedStateException();
    }

    public void verifyFullLocked() throws UnlockedStateException {
        verifyLocked();
        verifyTimeStampLocked();
    }

    public void verifyTimeStampLocked() throws UnlockedStateException {
        if (!lock.a2) throw new UnlockedStateException();
    }

    public void verifyTimeStampUnlocked() throws LockedStateException {
        if (lock.a2) throw new LockedStateException();
    }

    public void verifyLockState() throws LockedStateException {
        if (lock.a2 && !lock.a1) throw new LockedStateException();
    }

    public boolean canEdit() {
        return (lock.a1 || lock.a2);
    }


}
