package fwplan.crypto;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** 签名原语：SHA256-with-RSA over 固件 sha256 文本。 */
public final class Signing {

    public static final String ALG = "SHA256withRSA";

    private Signing() {}

    public static String signSha256(PrivateKey key, String sha256Hex) {
        try {
            Signature sig = Signature.getInstance(ALG);
            sig.initSign(key);
            sig.update(sha256Hex.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(sig.sign());
        } catch (Exception e) {
            throw new IllegalStateException("signing failed", e);
        }
    }

    public static boolean verifySha256(PublicKey key, String sha256Hex, String signatureBase64) {
        try {
            Signature sig = Signature.getInstance(ALG);
            sig.initVerify(key);
            sig.update(sha256Hex.getBytes(StandardCharsets.UTF_8));
            return sig.verify(Base64.getDecoder().decode(signatureBase64));
        } catch (Exception e) {
            return false;
        }
    }

    public static PublicKey decodePublicX509Base64(String base64) {
        try {
            byte[] der = Base64.getDecoder().decode(base64);
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid public key", e);
        }
    }

    public static PrivateKey decodePrivatePkcs8Base64(String base64) {
        try {
            byte[] der = Base64.getDecoder().decode(base64);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid private key", e);
        }
    }

    public static String encodePublicBase64(PublicKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    public static String encodePrivateBase64(PrivateKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }
}
