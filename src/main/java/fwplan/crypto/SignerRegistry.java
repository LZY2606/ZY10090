package fwplan.crypto;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.LinkedHashMap;
import java.util.Map;

import fwplan.util.Json;

/**
 * 受信签名者注册表（signer 名称 -> RSA 公钥）。
 * 注册表以原始输入形式保存于 data/raw/trusted_signers.json。
 */
public final class SignerRegistry {

    private final Map<String, PublicKey> keys = new LinkedHashMap<>();

    public static SignerRegistry load(Path file) {
        SignerRegistry registry = new SignerRegistry();
        if (Files.exists(file)) {
            String body;
            try { body = Files.readString(file); }
            catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
            Map<String, Object> map = Json.parseObject(body);
            for (Map.Entry<String, Object> e : map.entrySet()) {
                registry.keys.put(e.getKey(), Signing.decodePublicX509Base64(String.valueOf(e.getValue())));
            }
        }
        return registry;
    }

    public boolean isTrusted(String signer) { return keys.containsKey(signer); }

    public void register(String signer, PublicKey key) { keys.put(signer, key); }

    public boolean isEmpty() { return keys.isEmpty(); }

    public PublicKey key(String signer) { return keys.get(signer); }

    public Map<String, String> describe() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, PublicKey> e : keys.entrySet()) {
            out.put(e.getKey(), Signing.encodePublicBase64(e.getValue()));
        }
        return out;
    }
}
