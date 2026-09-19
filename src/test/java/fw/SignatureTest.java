package fw;

import fw.crypto.SignatureVerifier;
import fw.json.Json;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SignatureTest {

    private static final String KEY = "unit-key";

    private Map<String, Object> sample() {
        return Json.parseObject("""
                {"package_id":"p","component":"main","version":"1.0","family":"f",
                 "sha256":"abc","signer":"team-a"}""");
    }

    @Test
    void validSignatureRoundTrips() {
        var verifier = new SignatureVerifier().registerKey(KEY, "secret");
        Map<String, Object> body = sample();
        body.put("signature", SignatureVerifier.sign(body, KEY, "secret"));
        SignatureVerifier.Result result = verifier.verify(body);
        assertTrue(result.ok(), result.detail());
        assertEquals("team-a", result.signer());
    }

    @Test
    void tamperedPayloadFails() {
        var verifier = new SignatureVerifier().registerKey(KEY, "secret");
        Map<String, Object> body = sample();
        body.put("signature", SignatureVerifier.sign(body, KEY, "secret"));
        body.put("version", "9.9");
        assertFalse(verifier.verify(body).ok());
    }

    @Test
    void unknownKeyAndMalformedSignatureFail() {
        var verifier = new SignatureVerifier().registerKey(KEY, "secret");
        Map<String, Object> body = sample();
        body.put("signature", "other-key:aaaa");
        assertFalse(verifier.verify(body).ok());
        body.put("signature", "garbage");
        assertFalse(verifier.verify(body).ok());
    }

    @Test
    void canonicalFormIgnoresKeyOrderAndWhitespace() {
        var a = SignatureVerifier.sign(Json.parseObject("""
                {"a":1,"b":[1,2],"c":"x"}"""), KEY, "s");
        var b = SignatureVerifier.sign(Json.parseObject("""
                { "c": "x", "b": [1, 2], "a": 1 }"""), KEY, "s");
        assertEquals(a.substring(a.indexOf(':')), b.substring(b.indexOf(':')));
    }
}
