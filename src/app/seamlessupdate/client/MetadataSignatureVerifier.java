package app.seamlessupdate.client;

import android.util.Log;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class MetadataSignatureVerifier {
    private static final String TAG = MetadataSignatureVerifier.class.getSimpleName();
    private static final String OTA_CERTS_ZIP_PATH = "/system/etc/security/otacerts.zip";
    @Nullable
    private static PublicKey releasePublicKey = getPubKeyFromOTACertsZip();
    /**
     * Determined by the script the generates keys: development/tools/make_key;
     * and the script used to generate metadata: script/generate_metadata.py
     **/
    private static final String SIGNING_ALGORITHM = "SHA256withRSA/PSS";

    @Nullable
    private static PublicKey getPubKeyFromOTACertsZip() {
        final ArrayList<X509Certificate> certificates = new ArrayList<>();
        try (final ZipFile otaCertsZip = new ZipFile(new File(OTA_CERTS_ZIP_PATH))) {
            final CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
            final Enumeration<? extends ZipEntry> entries = otaCertsZip.entries();
            while (entries.hasMoreElements()) {
                final ZipEntry entry = entries.nextElement();
                try (final InputStream inputStream = otaCertsZip.getInputStream(entry)) {
                    certificates.add((X509Certificate) certificateFactory.generateCertificate(inputStream));
                }
            }
        } catch (IOException | CertificateException e) {
            Log.w(TAG, "unable to get OTA certificates", e);
            return null;
        }
        if (certificates.isEmpty()) {
            Log.w(TAG, "unable to find any OTA certs");
            return null;
        }
        if (certificates.size() > 1) {
            Log.w(TAG, "more than one OTA cert found");
        }

        return certificates.get(0).getPublicKey();
    }

    public static void verifyMetadata(byte[] metadata, byte[] signature)
            throws GeneralSecurityException {
        if (releasePublicKey == null) {
            // Try again
            releasePublicKey = getPubKeyFromOTACertsZip();
            if (releasePublicKey == null) {
                throw new GeneralSecurityException("was unable to find public key");
            }
        }

        final Signature verifier;
        try {
            verifier = Signature.getInstance(SIGNING_ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            throw new GeneralSecurityException("failed to get an instance of " + SIGNING_ALGORITHM);
        }

        try {
            verifier.initVerify(releasePublicKey);
            verifier.update(metadata);
            if (!verifier.verify(signature)) {
                throw new GeneralSecurityException("verifier.verify returned false");
            }
        } catch (InvalidKeyException | SignatureException e) {
            throw new GeneralSecurityException("failed to verify signature, exception", e);
        }
    }
}
