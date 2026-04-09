/*
 * Copyright (c) 2026. Christopher Willett
 * All Rights Reserved
 */

package us.anvildevelopment.cxnet.network.stream;

/**
 * Pluggable cipher for a {@link CXStreamSession} data channel.
 *
 * <p>Implementations receive a monotonically increasing {@code chunkCounter}
 * per call so they can derive a unique nonce/IV without transmitting it in the
 * frame (both sides independently derive the same value from the shared base IV
 * and the counter). The counter starts at 0 and increments by 1 per chunk on
 * each direction independently -- send and receive counters are separate.
 *
 * <p>The default implementation is {@link AESGCMStreamCipher} (AES-GCM-256).
 * Custom implementations must be registered via
 * {@link AESGCMStreamCipher#register(String, CXStreamCipherFactory)}.
 */
public interface CXStreamCipher {

    /** Cipher identifier string as used in {@link us.anvildevelopment.cxnet.network.events.CXStream#cipher}. */
    String getCipherName();

    /**
     * Encrypt one chunk of plaintext.
     *
     * @param plaintext    raw data bytes (up to negotiated buffer size)
     * @param chunkCounter monotonically increasing send counter for this session direction
     * @return ciphertext + authentication tag
     * @throws Exception on encryption failure
     */
    byte[] encrypt(byte[] plaintext, long chunkCounter) throws Exception;

    /**
     * Decrypt one chunk of ciphertext.
     *
     * @param ciphertext   ciphertext + authentication tag as returned by {@link #encrypt}
     * @param chunkCounter monotonically increasing receive counter for this session direction
     * @return plaintext
     * @throws Exception on decryption or authentication failure
     */
    byte[] decrypt(byte[] ciphertext, long chunkCounter) throws Exception;
}