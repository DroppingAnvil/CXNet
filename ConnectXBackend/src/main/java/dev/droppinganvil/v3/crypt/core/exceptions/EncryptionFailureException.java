package dev.droppinganvil.v3.crypt.core.exceptions;

public class EncryptionFailureException extends CryptException {
    public EncryptionFailureException() {
        super();
    }

    public EncryptionFailureException(String message) {
        super(message);
    }
}
