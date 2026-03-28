package us.anvildevelopment.cxnet.crypt.core.exceptions;

public class EncryptionFailureException extends CryptException {
    public EncryptionFailureException() {
        super();
    }

    public EncryptionFailureException(String message) {
        super(message);
    }
}
