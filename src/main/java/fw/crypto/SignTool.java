package fw.crypto;

import fw.json.Json;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Command line helper: signs a metadata JSON file.
 *
 * <p>Usage: {@code SignTool <input.json> <keyId> <secret>}. Prints the
 * signed JSON document to stdout.</p>
 */
public final class SignTool {

    private SignTool() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: SignTool <metadata.json> <keyId> <secret>");
            System.exit(2);
        }
        Path file = Path.of(args[0]);
        String keyId = args[1];
        String secret = args[2];
        Map<String, Object> raw = Json.parseObject(Files.readString(file, StandardCharsets.UTF_8));
        raw.remove("signature");
        String tag = SignatureVerifier.sign(raw, keyId, secret);
        raw.put("signature", tag);
        System.out.println(Json.writePretty(raw));
    }
}
