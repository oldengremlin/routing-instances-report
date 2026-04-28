package net.ukrcom.routingreport;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class HashUtils {

    /**
     * Computes the composite hash key used to deduplicate instances across routers.
     * Matches the Perl: $padded.":".md5_hex($padded.$type).":".sha1_hex($padded.$type)
     */
    public static String computeKey(String paddedName, String type) {
        String input = paddedName + type;
        return paddedName + ":" + hexDigest("MD5", input) + ":" + hexDigest("SHA-1", input);
    }

    private static String hexDigest(String algorithm, String input) {
        try {
            byte[] bytes = MessageDigest.getInstance(algorithm)
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
