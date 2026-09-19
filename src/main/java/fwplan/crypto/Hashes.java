package fwplan.crypto;

import java.security.MessageDigest;

public final class Hashes {

    private Hashes() {}

    public static String sha256Hex(String input) {
        return sha256Hex(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public static String sha256Hex(byte[] input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean isSha256Hex(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }
}
