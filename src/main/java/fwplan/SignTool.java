package fwplan;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Map;

import fwplan.crypto.Hashes;
import fwplan.crypto.Signing;
import fwplan.util.Json;

/**
 * 命令行签名工具：
 *   keygen --out /path/key.json                         生成 RSA 2048 密钥对
 *   sign --key /path/key.json --meta input.json         给固件元数据补 signer/signature
 *
 * sign 子命令读取已有元数据 JSON（需含 sha256），用私钥对 sha256 签名并打印完整 JSON。
 * 公钥需要先加入可信签名者（演示模式自动信任 embedded-demo）。
 */
public final class SignTool {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("用法:");
            System.out.println("  SignTool keygen --out key.json");
            System.out.println("  SignTool sign --key key.json --signer name --meta firmware-meta.json");
            System.exit(2);
        }
        switch (args[0]) {
            case "keygen" -> keygen(valueOf(args, "--out"));
            case "sign" -> sign(valueOf(args, "--key"), valueOf(args, "--signer"),
                    valueOf(args, "--meta"));
            default -> {
                System.err.println("未知子命令: " + args[0]);
                System.exit(2);
            }
        }
    }

    private static String valueOf(String[] args, String name) {
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals(name) && i + 1 < args.length) return args[i + 1];
        }
        throw new IllegalArgumentException("缺少参数 " + name);
    }

    private static void keygen(String out) throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair pair = gen.generateKeyPair();
        Map<String, Object> doc = Map.of(
                "publicBase64", Signing.encodePublicBase64(pair.getPublic()),
                "privateBase64", Signing.encodePrivateBase64(pair.getPrivate()));
        Files.writeString(Path.of(out), Json.writePretty(doc), StandardCharsets.UTF_8);
        System.out.println("密钥对已写入 " + out + "（私钥务必离线保管）");
        System.out.println("公钥: " + Signing.encodePublicBase64(pair.getPublic()));
    }

    private static void sign(String keyFile, String signer, String metaFile) throws Exception {
        Map<String, Object> key = Json.parseObject(Files.readString(Path.of(keyFile)));
        var priv = Signing.decodePrivatePkcs8Base64(String.valueOf(key.get("privateBase64")));
        Map<String, Object> meta = Json.parseObject(Files.readString(Path.of(metaFile)));
        String sha = String.valueOf(meta.get("sha256"));
        if (!Hashes.isSha256Hex(sha)) throw new IllegalArgumentException("元数据缺少合法 sha256");
        meta.put("signer", signer);
        meta.put("signature", Signing.signSha256(priv, sha));
        System.out.println(Json.writePretty(meta));
    }
}
