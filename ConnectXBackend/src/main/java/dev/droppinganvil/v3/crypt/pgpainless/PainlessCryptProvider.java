package dev.droppinganvil.v3.crypt.pgpainless;

import dev.droppinganvil.v3.crypt.core.CryptProvider;
import dev.droppinganvil.v3.crypt.core.exceptions.DecryptionFailureException;
import dev.droppinganvil.v3.crypt.core.exceptions.EncryptionFailureException;
import dev.droppinganvil.v3.network.nodemesh.Node;
import dev.droppinganvil.v3.network.nodemesh.PeerDirectory;
import org.bouncycastle.openpgp.*;
import org.bouncycastle.util.io.Streams;
import org.pgpainless.PGPainless;
import org.pgpainless.algorithm.DocumentSignatureType;
import org.pgpainless.decryption_verification.ConsumerOptions;
import org.pgpainless.decryption_verification.DecryptionStream;
import org.pgpainless.encryption_signing.EncryptionOptions;
import org.pgpainless.encryption_signing.EncryptionStream;
import org.pgpainless.encryption_signing.ProducerOptions;
import org.pgpainless.encryption_signing.SigningOptions;
import org.pgpainless.key.protection.SecretKeyRingProtector;
import org.pgpainless.key.util.KeyRingUtils;
import org.pgpainless.util.Passphrase;

import java.io.*;
import java.util.concurrent.ConcurrentHashMap;

public class PainlessCryptProvider extends CryptProvider {

    public PainlessCryptProvider() {
        super("Encryption Layer", "Core");
    }

    /**
     * On board secret key
     */
    private PGPSecretKeyRing secretKey;
    /**
     * Network public key cache
     */
    public static ConcurrentHashMap<String, PGPPublicKeyRing> certCache = new ConcurrentHashMap<>();
    /**
     * On board public key
     */
    private PGPPublicKeyRing publicKey;
    /**
     * NMI Public key
     */
    public PGPPublicKeyRing nmipubkey;
    private SecretKeyRingProtector protector;

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
        if (cacheCert(cxID, false, true)) {
            try {
                DecryptionStream decryptionStream = PGPainless.decryptAndOrVerify()
                        .onInputStream(is)
                        .withOptions(new ConsumerOptions()
                                .addDecryptionKey(secretKey, protector)
                                .addVerificationCert(certCache.get(cxID))
                        );
                Streams.pipeAll(decryptionStream, os);
                decryptionStream.close();
                return decryptionStream.getResult().isVerified();
            } catch (Exception e) {
                e.printStackTrace();
                DecryptionFailureException dfe = new DecryptionFailureException();
                dfe.initCause(e);
                throw dfe;
            }
        }
        return false;
    }
    @Override
    public void encrypt(InputStream is, OutputStream os, String cxID) throws EncryptionFailureException {
        if (!cacheCert(cxID, false, true)) throw new EncryptionFailureException();
        if (is == null) {
            throw new EncryptionFailureException("InputStream cannot be null - no data to encrypt");
        }
        try {
            EncryptionStream encryptor = PGPainless.encryptAndOrSign()
                    .onOutputStream(os)
                    .withOptions(ProducerOptions.signAndEncrypt(
                            EncryptionOptions.encryptCommunications()
                                    .addRecipient(certCache.get(cxID))
                                    .addRecipient(publicKey),
                            new SigningOptions()
                                    .addInlineSignature(protector, secretKey, DocumentSignatureType.CANONICAL_TEXT_DOCUMENT)
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
            EncryptionStream encryptor = PGPainless.encryptAndOrSign()
                    .onOutputStream(os)
                    .withOptions(ProducerOptions.sign(new SigningOptions().addInlineSignature(protector, secretKey, DocumentSignatureType.CANONICAL_TEXT_DOCUMENT)
                            ).setAsciiArmor(false)
                    );
            // CRITICAL: Must pipe data and close stream to finalize signature
            Streams.pipeAll(is, encryptor);
            encryptor.close();
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
                    .withOptions(new ConsumerOptions()
                            .addDecryptionKey(secretKey, protector)
                            .addVerificationCert(certCache.get(certCache.get(cxID)))
                    );
            Streams.pipeAll(decryptionStream, os);
            decryptionStream.close();
            return decryptionStream.getResult();
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
                    .withOptions(new ConsumerOptions()
                            .addDecryptionKey(secretKey, protector)
                    );
            Streams.pipeAll(decryptionStream, os);
            decryptionStream.close();
            return decryptionStream.getResult();
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
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DecryptionStream ds = PGPainless.decryptAndOrVerify().onInputStream(privateKeyFile.toURL().openStream()).withOptions(new ConsumerOptions()
            .addDecryptionPassphrase(Passphrase.fromPassword(s)));
            Streams.pipeAll(ds, baos);
            ds.close();
            secretKey = PGPainless.readKeyRing().secretKeyRing(baos.toByteArray());
        } else {
            secretKey = PGPainless.generateKeyRing()
                    .modernKeyRing(cxID, s);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            FileOutputStream fos = new FileOutputStream(privateKeyFile);
            secretKey.encode(baos);
            EncryptionStream es = PGPainless.encryptAndOrSign().onOutputStream(fos).withOptions(ProducerOptions.encrypt(new EncryptionOptions()
            .addPassphrase(Passphrase.fromPassword(s))));
            Streams.pipeAll(new ByteArrayInputStream(baos.toByteArray()), es);
            es.close();  // CRITICAL: Must close EncryptionStream to finalize the encrypted file
            fos.close(); // Close the file output stream
        }

        // Initialize protector with the passphrase AFTER loading secretKey
        protector = SecretKeyRingProtector.unlockAllKeysWith(Passphrase.fromPassword(s), secretKey);

        publicKey = KeyRingUtils.publicKeyRingFrom(secretKey);
        File nmipublicKeyFile = new File(dir, "cx.asc");
        if (nmipublicKeyFile.exists()) {
            nmipubkey = PGPainless.readKeyRing().publicKeyRing(nmipublicKeyFile.toURL().openStream());
        } else {
            throw new IOException();
        }
        //load keys
        ready = true;
    }
    @Override
    public boolean cacheCert(String cxID, boolean tryImport, boolean sync) {
        if (certCache.containsKey(cxID)) return true;
        try {
            Node n = PeerDirectory.lookup(cxID, tryImport, sync);
            if (n != null) {
                PGPPublicKeyRing cert = PGPainless.readKeyRing().publicKeyRing(n.publicKey);
                if (cert != null) {
                    certCache.put(cxID, cert);
                    return true;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }
}
