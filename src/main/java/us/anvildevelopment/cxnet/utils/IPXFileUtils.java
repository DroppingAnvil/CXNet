package us.anvildevelopment.cxnet.utils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;

public class IPXFileUtils {
    public static Boolean checkBasicIORights(File file) throws IOException {
        PosixFileAttributes pfa = Files.readAttributes(file.toPath(), PosixFileAttributes.class);
        boolean read = (pfa.permissions().contains(PosixFilePermission.OWNER_READ) || pfa.permissions().contains(PosixFilePermission.GROUP_READ) || pfa.permissions().contains(PosixFilePermission.OTHERS_READ));
        boolean write = (pfa.permissions().contains(PosixFilePermission.OWNER_WRITE) || pfa.permissions().contains(PosixFilePermission.GROUP_WRITE) || pfa.permissions().contains(PosixFilePermission.OTHERS_WRITE));
        return (read & write);
    }
    public static Boolean checkBasicIORights(PosixFileAttributes pfa) throws IOException {
        boolean read = (pfa.permissions().contains(PosixFilePermission.OWNER_READ) || pfa.permissions().contains(PosixFilePermission.GROUP_READ) || pfa.permissions().contains(PosixFilePermission.OTHERS_READ));
        boolean write = (pfa.permissions().contains(PosixFilePermission.OWNER_WRITE) || pfa.permissions().contains(PosixFilePermission.GROUP_WRITE) || pfa.permissions().contains(PosixFilePermission.OTHERS_WRITE));
        return (read & write);
    }
    public static Boolean checkExecutionRights(File file) throws IOException {
        PosixFileAttributes pfa = Files.readAttributes(file.toPath(), PosixFileAttributes.class);
        return checkExecutionRights(pfa);
    }
    public static Boolean checkExecutionRights(PosixFileAttributes pfa) {
        return (pfa.permissions().contains(PosixFilePermission.OWNER_EXECUTE) || pfa.permissions().contains(PosixFilePermission.GROUP_EXECUTE) || pfa.permissions().contains(PosixFilePermission.OTHERS_EXECUTE));
    }
    public static Boolean checkIORights(File file) throws IOException {
        PosixFileAttributes pfa = Files.readAttributes(file.toPath(), PosixFileAttributes.class);
        return (checkBasicIORights(pfa) & checkExecutionRights(pfa));
    }
}
