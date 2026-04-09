/*
 * Copyright (c) 2026. Christopher Willett
 * All Rights Reserved
 */

package us.anvildevelopment.cxnet.network.stream;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AES-GCM-256 stream cipher - default implementation of {@link CXStreamCipher}.
 *
 * <p>Key: 32 bytes (AES-256).
 * IV: 12 bytes, derived per chunk as {@code baseIV XOR counter} where the 64-bit
 * counter occupies the last 8 bytes of the IV. This guarantees a unique nonce for
 * every chunk without transmitting the IV in the frame.
 *
 * <p>GCM tag is 128 bits (16 bytes), appended to ciphertext by the JCE provider.
 * Decryption will throw if the tag does not verify, so any tampered frame is
 * rejected before the plaintext is delivered.
 *
 * <p>Custom cipher implementations can be registered via {@link #register}:
 * <pre>
 *   AESGCMStreamCipher.register("MY-CIPHER-1", (key, iv) -> new MyCipher(key, iv));
 * </pre>
 */
public class AESGCMStreamCipher implements CXStreamCipher {

    public static final String CIPHER_NAME = "AES-GCM-256";
    private static final int GCM_TAG_BITS = 128;
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;

    /** Factory interface for pluggable cipher implementations. */
    @FunctionalInterface
    public interface CXStreamCipherFactory {
        CXStreamCipher create(byte[] keyBytes, byte[] baseIV);
    }

    private static final ConcurrentHashMap<String, CXStreamCipherFactory> registry = new ConcurrentHashMap<>();

    static {
        registry.put(CIPHER_NAME, AESGCMStreamCipher::new);
    }

    /**
     * Register a custom cipher factory under the given identifier.
     * The identifier is used in {@link us.anvildevelopment.cxnet.network.events.CXStream#cipher}.
     */
    public static void register(String cipherName, CXStreamCipherFactory factory) {
        registry.put(cipherName, factory);
    }

    /**
     * Instantiate a cipher by name.
     *
     * @throws IllegalArgumentException if the cipher name is not registered
     */
    public static CXStreamCipher create(String cipherName, byte[] keyBytes, byte[] baseIV) {
        CXStreamCipherFactory factory = registry.get(cipherName);
        if (factory == null) throw new IllegalArgumentException("Unknown stream cipher: " + cipherName);
        return factory.create(keyBytes, baseIV);
    }

    // --- Instance ---

    private final SecretKeySpec key;
    private final byte[] baseIV;

    public AESGCMStreamCipher(byte[] keyBytes, byte[] baseIV) {
        if (keyBytes.length != KEY_BYTES)
            throw new IllegalArgumentException("AES-GCM-256 requires a 32-byte key, got " + keyBytes.length);
        if (baseIV.length != IV_BYTES)
            throw new IllegalArgumentException("AES-GCM requires a 12-byte IV, got " + baseIV.length);
        this.key = new SecretKeySpec(keyBytes, "AES");
        this.baseIV = baseIV.clone();
    }

    /**
     * Derive per-chunk IV: baseIV XOR (counter in last 8 bytes, big-endian).
     * The first 4 bytes of baseIV are preserved as a session discriminator.
     */
    private byte[] deriveIV(long counter) {
        byte[] iv = baseIV.clone();
        for (int i = 0; i < 8; i++) {
            iv[4 + i] ^= (byte) (counter >>> (56 - i * 8));
        }
        return iv;
    }

    @Override
    public byte[] encrypt(byte[] plaintext, long chunkCounter) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, deriveIV(chunkCounter)));
        return cipher.doFinal(plaintext);
    }

    @Override
    public byte[] decrypt(byte[] ciphertext, long chunkCounter) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, deriveIV(chunkCounter)));
        return cipher.doFinal(ciphertext);
    }

    @Override
    public String getCipherName() {
        return CIPHER_NAME;
    }
}