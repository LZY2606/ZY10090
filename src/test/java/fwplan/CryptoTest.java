package fwplan;

import java.security.KeyPair;
import java.security.KeyPairGenerator;

import fwplan.crypto.Hashes;
import fwplan.crypto.Signing;

public final class CryptoTest {

    public static void run() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair pair = gen.generateKeyPair();
        String sha = Hashes.sha256Hex("binary");
        String signature = Signing.signSha256(pair.getPrivate(), sha);
        Test.check(Signing.verifySha256(pair.getPublic(), sha, signature), "合法签名必须通过");
        Test.check(!Signing.verifySha256(pair.getPublic(), Hashes.sha256Hex("tampered"), signature),
                "哈希被改必须失败");
        Test.check(!Signing.verifySha256(pair.getPublic(), sha, signature + "AA"),
                "签名被改必须失败");
        Test.check(Hashes.isSha256Hex(sha), "哈希格式识别");
    }
}
