package fw.crypto;

import fw.json.Json;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Demo signature scheme: HMAC-SHA256 over the canonical JSON payload.
 *
 * <p>Production deployments would swap this for Ed25519 with a pinned
 * signer certificate; the rest of the system only depends on the
 * {@link Verifier} interface, so no planner / storage code changes.</p>
 */
public class SignatureVerifier {

    public static final String SCHEME = "hmac-sha256-demo";

    public interface Verifier {
        Result verify(Map<String, Object> rawMetadata);
    }

    public record Result(boolean ok, String scheme, String signer, String detail) {
        public static Result fail(String detail) {
            return new Result(false, SCHEME, null, detail);
        }
    }

    private final Map<String, byte[]> keys = new TreeMap<>();

    public SignatureVerifier registerKey(String keyId, String secret) {
        keys.put(keyId, secret.getBytes(StandardCharsets.UTF_8));
        return this;
    }

    public static String sign(Map<String, Object> payload, String keyId, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String canonical = Json.canonicalString(payload);
            byte[] tag = mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
            return keyId + ":" + Base64.getUrlEncoder().withoutPadding().encodeToString(tag);
        } catch (NoSuchAlgorithmException | InvalidKeyException ex) {
            throw new IllegalStateException("HMAC signing failure", ex);
        }
    }

    public Result verify(Map<String, Object> rawMetadata) {
        Object sigObj = rawMetadata.get("signature");
        if (!(sigObj instanceof String sig) || sig.isEmpty()) {
            return Result.fail("missing signature");
        }
        int idx = sig.indexOf(':');
        if (idx <= 0) {
            return Result.fail("signature must be '<keyId>:<base64mac>'");
        }
        String keyId = sig.substring(0, idx);
        String providedB64 = sig.substring(idx + 1);
        byte[] key = keys.get(keyId);
        if (key == null) {
            return Result.fail("unknown signing key '" + keyId + "'");
        }
        Map<String, Object> payload = new LinkedHashMap<>(rawMetadata);
        payload.remove("signature");
        String expected;
        byte[] providedTag;
        try {
            expected = sign(payload, keyId, new String(key, StandardCharsets.UTF_8));
            providedTag = Base64.getUrlDecoder().decode(providedB64);
        } catch (RuntimeException ex) {
            return Result.fail("malformed signature encoding: " + ex.getMessage());
        }
        String expectedB64 = expected.substring(expected.indexOf(':') + 1);
        byte[] expectedTag = Base64.getUrlDecoder().decode(expectedB64);
        String signer = rawMetadata.get("signer") instanceof String s && !s.isEmpty() ? s : keyId;
        if (!constantTimeEquals(providedTag, expectedTag)) {
            return new Result(false, SCHEME, signer, "HMAC mismatch over canonical payload");
        }
        return new Result(true, SCHEME, signer, "valid");
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        return MessageDigest.isEqual(a, b);
    }
}
