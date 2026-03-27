package us.anvildevelopment.cxnet.crypt.pgpainless;

import us.anvildevelopment.cxnet.ConnectX;
import us.anvildevelopment.cxnet.crypt.core.CryptProvider;
import us.anvildevelopment.cxnet.crypt.core.exceptions.DecryptionFailureException;
import us.anvildevelopment.cxnet.crypt.core.exceptions.EncryptionFailureException;
import us.anvildevelopment.cxnet.network.nodemesh.Node;
import us.anvildevelopment.cxnet.network.nodemesh.NodeConfig;
import org.bouncycastle.openpgp.*;
import org.bouncycastle.openpgp.api.OpenPGPKey;
import org.bouncycastle.util.io.Streams;
import org.pgpainless.PGPainless;
import org.pgpainless.algorithm.DocumentSignatureType;
import org.pgpainless.algorithm.HashAlgorithm;
import org.pgpainless.decryption_verification.ConsumerOptions;
import org.pgpainless.decryption_verification.DecryptionStream;
import org.pgpainless.encryption_signing.EncryptionOptions;
import org.pgpainless.encryption_signing.EncryptionStream;
import org.pgpainless.encryption_signing.ProducerOptions;
import org.pgpainless.encryption_signing.SigningOptions;
import org.pgpainless.key.info.KeyRingInfo;
import org.pgpainless.key.protection.SecretKeyRingProtector;
import org.pgpainless.key.util.KeyRingUtils;
import org.pgpainless.util.Passphrase;

import java.io.*;
import java.util.concurrent.ConcurrentHashMap;

public class PainlessCryptProvider extends CryptProvider {

    public PainlessCryptProvider(ConnectX connectX) {
        super("Encryption Layer", "Core", connectX);
    }

    /**
     * On board secret key
     */
    private PGPSecretKeyRing secretKey;
    /** Native OpenPGPKey wrapper - used for signing to avoid v4/v6 OPS mismatch */
    private OpenPGPKey secretKeyNative;
    /**
     * Network public key cache
     */
    public ConcurrentHashMap<String, PGPPublicKeyRing> certCache = new ConcurrentHashMap<>();
    /**
     * On board public key
     */
    private PGPPublicKeyRing publicKey;
    /**
     * NMI Public key
     */
    public PGPPublicKeyRing nmipubkey;
    private SecretKeyRingProtector protector;

    // TODO: Remove after HTTP bridge seed download is implemented
    private boolean epochMode = false; // Set true when this node IS the NMI (EPOCH)

    public void setEpochMode(boolean epochMode) {
        this.epochMode = epochMode;
    }

    /**
     * CXNET NMI Public Key (cx.asc) - HARDCODED for security
     * This is the official CXNET Network Master Identity public key.
     * All CXNET seeds MUST be signed by this key.
     *
     * Security Model:
     * 1. This key is hardcoded in the application (certificate pinning)
     * 2. During bootstrap, compare with seed's nmiPub field
     * 3. If different: contact multiple peers for verification
     * 4. If consensus doesn't match: REFUSE and prompt user
     * 5. User can manually trust new key (key rotation scenario)
     */
    private static final String HARDCODED_CXNET_NMI_PUBLIC_KEY =
        "mDMEaReGrRYJKwYBBAHaRw8BAQdAYJ9WTY6LY5gnOu6hX4+hPIq87rHghM6IFIoUQ3EsVHu0JDAwMDAwMDAwLTAwMDAtMDAwMC0wMDAwLTAwMDAwMDAwMDAwMYh4BBMWCgAgBQJpF4atAhsBBRYCAwEABAsJCAcFFQoJCAsCHgECGQEACgkQZ7PbdlbrnH6L1gEAsHp5sU18rfsuR5+LqpEzLmGD54RO7+rOuLvAIZC5378A/2H+OoAhnz18RmtHcZ/+0qNZKNxcSLqIRWJpTwzzLsgOuDgEaReGrRIKKwYBBAGXVQEFAQEHQOqHVj6aFo0yUi5e3RmBg/bYMsGTJk0DX1ql73Z0YdYsAwEIB4h1BBgWCgAdBQJpF4atAhsMBRYCAwEABAsJCAcFFQoJCAsCHgEACgkQZ7PbdlbrnH7iwQEAk1oh9aK73s/gKVaIoA8JfMKyruKfOgDHZzXksNqnzEcBALu1LH+st6D+6jk+3lQDrzXeRMecWecEUcwd2c+Azg8HuDMEaReGrRYJKwYBBAHaRw8BAQdAx58p+H95rzrJ/lrTbql4qW61UpZY3XA4yo1aBvqcnd6I1QQYFgoAfQUCaReGrQIbAgUWAgMBAAQLCQgHBRUKCQgLAh4BXyAEGRYKAAYFAmkXhq0ACgkQaxHYF824w+HtCwEAnmOkdXlwi+z37mcEIP2Uy/E+wymNKz7e0FNmA+rz8sEA/3QX7zFWjwe4DHaCGGPKYVV0mbDPgLxXaleuz9oVeu4CAAoJEGez23ZW65x+xikA/2+dI9NbgEiBVQb6ZvvyuqzTTcgydrTU5fAye+dWMc8KAP9DpMVf4oI8gsyf8MSYJBTEEBxO69j6foOMl7t1ESURBg==";

    @Override
    public String getPublicKey() {
        if (publicKey == null) return null;
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            publicKey.encode(baos);
            return java.util.Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    @Override
    public boolean verifyAndStrip(InputStream is, OutputStream os, String cxID) throws DecryptionFailureException {
        //TODO verify tryimport is best case
        if (cacheCert(cxID, true, true, super.connectX)) {
            try {
                DecryptionStream decryptionStream = PGPainless.decryptAndOrVerify()
                        .onInputStream(is)
                        .withOptions(ConsumerOptions.get()
                                .addDecryptionKey(secretKey, protector)
                                .addVerificationCert(certCache.get(cxID))
                        );
                Streams.pipeAll(decryptionStream, os);
                decryptionStream.close();
                org.pgpainless.decryption_verification.MessageMetadata meta = decryptionStream.getMetadata();
                boolean verified = meta.isVerifiedSigned();
                if (!verified) {
                    System.err.println("[VERIFY-FAIL] isVerifiedSigned=false for " + cxID.substring(0, 8));
                    System.err.println("[VERIFY-FAIL] verifiedInline=" + meta.getVerifiedInlineSignatures().size()
                        + " rejectedInline=" + meta.getRejectedInlineSignatures().size());
                    meta.getRejectedInlineSignatures().forEach(f ->
                        System.err.println("[VERIFY-FAIL] rejected: " + f));
                }
                return verified;
            } catch (Exception e) {
                e.printStackTrace();
                DecryptionFailureException dfe = new DecryptionFailureException();
                dfe.initCause(e);
                throw dfe;
            }
        }
        System.err.println("[VERIFY-FAIL] cacheCert returned false for " + cxID);
        return false;
    }
    @Override
    public void encrypt(InputStream is, OutputStream os, String cxID) throws EncryptionFailureException {
        if (!cacheCert(cxID, false, true, super.connectX)) throw new EncryptionFailureException();
        if (is == null) {
            throw new EncryptionFailureException("InputStream cannot be null - no data to encrypt");
        }
        try {
            EncryptionStream encryptor = PGPainless.encryptAndOrSign()
                    .onOutputStream(os)
                    .withOptions(ProducerOptions.signAndEncrypt(
                            EncryptionOptions.get()
                                    .addRecipient(certCache.get(cxID))
                                    .addRecipient(publicKey),
                            SigningOptions.get()
                                    .overrideHashAlgorithm(HashAlgorithm.SHA512)
                                    .addInlineSignature(protector, secretKeyNative, DocumentSignatureType.CANONICAL_TEXT_DOCUMENT)
                            ).setAsciiArmor(false)
                    );
            // CRITICAL: Must pipe data and close stream to finalize encryption
            Streams.pipeAll(is, encryptor);
            encryptor.close();
        } catch (Exception e) {
            EncryptionFailureException efe = new EncryptionFailureException();
            efe.initCause(e);
            throw efe;
        }
    }

    @Override
    public void encrypt(InputStream is, OutputStream os, java.util.List<String> recipientCxIDs) throws EncryptionFailureException {
        if (!ready) {
            throw new EncryptionFailureException("Encryption provider not initialized - call setup() first");
        }
        if (is == null) {
            throw new EncryptionFailureException("InputStream cannot be null - no data to encrypt");
        }
        if (recipientCxIDs == null || recipientCxIDs.isEmpty()) {
            throw new EncryptionFailureException("Must specify at least one recipient for E2E encryption");
        }

        // Cache all recipient certificates
        for (String cxID : recipientCxIDs) {
            if (!cacheCert(cxID, true, true, super.connectX)) {
                throw new EncryptionFailureException("Failed to cache certificate for recipient: " + cxID + " This generally indicates that a recipient peer has never been met");
            }
        }

        try {
            // Build encryption options with all recipients
            EncryptionOptions encryptionOptions = EncryptionOptions.get();

            // Add each recipient
            for (String cxID : recipientCxIDs) {
                encryptionOptions.addRecipient(certCache.get(cxID));
            }

            // Always add sender's own key so they can decrypt their own messages
            encryptionOptions.addRecipient(publicKey);

            // Create encryption stream
            EncryptionStream encryptor = PGPainless.encryptAndOrSign()
                    .onOutputStream(os)
                    .withOptions(ProducerOptions.signAndEncrypt(
                            encryptionOptions,
                            SigningOptions.get()
                                    .overrideHashAlgorithm(HashAlgorithm.SHA512)
                                    .addInlineSignature(protector, secretKeyNative, DocumentSignatureType.CANONICAL_TEXT_DOCUMENT)
                            ).setAsciiArmor(false)
                    );

            // CRITICAL: Must pipe data and close stream to finalize encryption
            Streams.pipeAll(is, encryptor);
            encryptor.close();
        } catch (Exception e) {
            EncryptionFailureException efe = new EncryptionFailureException();
            efe.initCause(e);
            throw efe;
        }
    }
    @Override
    public void sign(InputStream is, OutputStream os) throws EncryptionFailureException {
        if (!ready) {
            throw new EncryptionFailureException("Encryption provider not initialized - call setup() first");
        }
        if (is == null) {
            throw new EncryptionFailureException("InputStream cannot be null - no data to sign");
        }
        try {
            //Debug
            if (NodeConfig.DEBUG) {
                PGPPublicKeyRing publicRing = PGPainless.extractCertificate(secretKey);
                KeyRingInfo info = new KeyRingInfo(publicRing);

                System.out.println("Primary User ID: " + info.getPrimaryUserId());
                System.out.println("Public Keys: " + info.getPublicKeys());
                System.out.println("Algorithm: " + info.getAlgorithm());
                System.out.println("Signing Subkeys: " + info.getSigningSubkeys());
                System.out.println("Primary User ID (again): " + info.getPrimaryUserId());
                //System.out.println("Preferred Hash Algorithms: " + info.getPreferredHashAlgorithms());
            }
            // PGPainless 2.x generates v6 keys via modernKeyRing; raw BouncyCastle produces
            // v4 OPS packets which PGPainless 2.x rejects as "Incorrect OnePassSignature".
            // Use PGPainless native signing so the OPS version matches the key version.
            EncryptionStream signer = PGPainless.encryptAndOrSign()
                .onOutputStream(os)
                .withOptions(ProducerOptions.sign(
                    SigningOptions.get()
                        .addInlineSignature(protector, secretKeyNative, DocumentSignatureType.BINARY_DOCUMENT)
                ).setAsciiArmor(false));
            Streams.pipeAll(is, signer);
            signer.close();
        } catch (Exception e) {
            EncryptionFailureException efe = new EncryptionFailureException();
            efe.initCause(e);
            throw efe;
        }
    }
    @Override
    public Object decrypt(InputStream is, OutputStream os, String cxID, boolean tryImport) throws DecryptionFailureException {
        try {
            DecryptionStream decryptionStream = PGPainless.decryptAndOrVerify()
                    .onInputStream(is)
                    .withOptions(ConsumerOptions.get()
                            .addDecryptionKey(secretKey, protector)
                            .addVerificationCert(certCache.get(cxID))
                    );
            Streams.pipeAll(decryptionStream, os);
            decryptionStream.close();
            return decryptionStream.getMetadata();
        } catch (Exception e) {
            e.printStackTrace();
            DecryptionFailureException dfe = new DecryptionFailureException();
            dfe.initCause(e);
            throw dfe;
        }
    }
    @Override
    public Object decrypt(InputStream is, OutputStream os) throws DecryptionFailureException {
        try {
            DecryptionStream decryptionStream = PGPainless.decryptAndOrVerify()
                    .onInputStream(is)
                    .withOptions(ConsumerOptions.get()
                            .addDecryptionKey(secretKey, protector)
                    );
            Streams.pipeAll(decryptionStream, os);
            decryptionStream.close();
            return decryptionStream.getMetadata();
        } catch (Exception e) {
            e.printStackTrace();
            DecryptionFailureException dfe = new DecryptionFailureException();
            dfe.initCause(e);
            throw dfe;
        }
    }
    @Override
    public void setup(String cxID, String s, File dir) throws Exception {
        File privateKeyFile = new File(dir, "key.cx");
        if (privateKeyFile.exists()) {
            // Try loading as unencrypted .asc file first (exported from Kleopatra)
            try {
                secretKey = PGPainless.readKeyRing().secretKeyRing(privateKeyFile.toURL().openStream());
            } catch (Exception e) {
                // If that fails, try decrypting it (old format with password-protected file)
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                DecryptionStream ds = PGPainless.decryptAndOrVerify().onInputStream(privateKeyFile.toURL().openStream()).withOptions(ConsumerOptions.get()
                .addMessagePassphrase(Passphrase.fromPassword(s)));
                Streams.pipeAll(ds, baos);
                ds.close();
                secretKey = PGPainless.readKeyRing().secretKeyRing(baos.toByteArray());
            }
            secretKeyNative = new OpenPGPKey(secretKey);
        } else {
            OpenPGPKey generatedKey = PGPainless.generateKeyRing().modernKeyRing(cxID, s);
            secretKey = generatedKey.getPGPSecretKeyRing();
            secretKeyNative = generatedKey;
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            FileOutputStream fos = new FileOutputStream(privateKeyFile);
            secretKey.encode(baos);
            EncryptionStream es = PGPainless.encryptAndOrSign().onOutputStream(fos).withOptions(ProducerOptions.encrypt(EncryptionOptions.get()
            .addPassphrase(Passphrase.fromPassword(s))));
            Streams.pipeAll(new ByteArrayInputStream(baos.toByteArray()), es);
            es.close();  // CRITICAL: Must close EncryptionStream to finalize the encrypted file
            fos.close(); // Close the file output stream
        }

        // Initialize protector with the passphrase AFTER loading secretKey
        // For keys exported from Kleopatra, try empty passphrase first, then unprotected
        if (s == null || s.isEmpty()) {
            // If no password provided, key is either unprotected or has empty passphrase
            // Try empty passphrase first (most common for Kleopatra exported keys)
            protector = SecretKeyRingProtector.unlockEachKeyWith(Passphrase.emptyPassphrase(), secretKey);
        } else {
            // Use provided passphrase
            protector = SecretKeyRingProtector.unlockEachKeyWith(Passphrase.fromPassword(s), secretKey);
        }

        publicKey = KeyRingUtils.publicKeyRingFrom(secretKey);

        // Load CXNET NMI Public Key (cx.asc)
        if (epochMode) {
            // EPOCH mode: This node IS the NMI, use own key as network master key
            nmipubkey = publicKey;
            System.out.println("[Crypto] EPOCH mode: Using own key as CXNET NMI");
        } else {
            // Standard mode: Use hardcoded CXNET NMI public key
            // This provides certificate pinning security against MITM attacks
            try {
                nmipubkey = PGPainless.readKeyRing().publicKeyRing(
                    new ByteArrayInputStream(java.util.Base64.getDecoder().decode(HARDCODED_CXNET_NMI_PUBLIC_KEY))
                );
                System.out.println("[Crypto] Loaded hardcoded CXNET NMI public key");
            } catch (Exception e) {
                throw new IOException("Failed to load hardcoded CXNET NMI public key: " + e.getMessage(), e);
            }

            // Optional: Check if local cx.asc file exists and compare
            File nmipublicKeyFile = new File(dir, "cx.asc");
            if (nmipublicKeyFile.exists()) {
                try {
                    byte[] raw = java.nio.file.Files.readAllBytes(nmipublicKeyFile.toPath());
                    PGPPublicKeyRing localKey = PGPainless.readKeyRing().publicKeyRing(
                        new ByteArrayInputStream(java.util.Base64.getDecoder().decode(raw))
                    );
                    if (!localKey.equals(nmipubkey)) {
                        System.err.println("[SECURITY WARNING] Local cx.asc does NOT match hardcoded CXNET NMI key!");
                        System.err.println("[SECURITY WARNING] Using hardcoded key for security.");
                        System.err.println("[SECURITY WARNING] TODO: Implement multi-peer verification");
                    }
                } catch (Exception e) {
                    System.err.println("[Crypto] Warning: Could not compare local cx.asc: " + e.getMessage());
                }
            }
        }
        //load keys
        ready = true;
    }
    @Override
    public boolean cacheCert(String cxID, boolean tryImport, boolean sync, ConnectX connectX) {
        try {
            if (certCache.containsKey(cxID)) return true;
        } catch (Exception ignored) {

        }
        try {
            Node n = connectX.nodeMesh.peerDirectory.lookup(cxID, tryImport, sync);
            System.out.println(n);
            if (n != null) {
                PGPPublicKeyRing cert = PGPainless.readKeyRing()
                        .publicKeyRing(
                                new ByteArrayInputStream(
                                        java.util.Base64.getDecoder().decode(n.publicKey)
                                )
                        );
                if (cert != null) {
                    certCache.putIfAbsent(cxID, cert);
                    return true;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    @Override
    public void stripSignature(InputStream is, OutputStream os) throws DecryptionFailureException {
        try {
            // Strip signature WITHOUT verification - used for NewNode peeking
            // NOTE: NetworkEvent is SIGNED ONLY (not encrypted), so we don't add decryption keys
            DecryptionStream decryptionStream = PGPainless.decryptAndOrVerify()
                    .onInputStream(is)
                    .withOptions(ConsumerOptions.get()
                            // NOTE: No decryption key - data is signed only, not encrypted
                            // NOTE: No verification cert - this strips without verifying
                    );
            Streams.pipeAll(decryptionStream, os);
            decryptionStream.close();
        } catch (Exception e) {
            e.printStackTrace();
            DecryptionFailureException dfe = new DecryptionFailureException();
            dfe.initCause(e);
            throw dfe;
        }
    }
}
