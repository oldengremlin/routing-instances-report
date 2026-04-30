package net.ukrhub.routing.instances.report;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Computes the deduplication key used to merge routing instances that appear
 * on multiple routers into a single report row.
 *
 * <p>The key format is intentionally compatible with the original Perl
 * implementation:</p>
 * <pre>
 *   $padded . ":" . md5_hex($padded . $type) . ":" . sha1_hex($padded . $type)
 * </pre>
 * <p>where {@code $padded} is the instance name left-justified in a 50-character
 * field. The same VRF present on several routers produces the same key and
 * therefore collapses into one row listing all routers.</p>
 */
public class HashUtils {

    private HashUtils() {}

    /**
     * Builds the composite deduplication key for a routing instance.
     *
     * @param paddedName instance name padded to exactly 50 characters
     * @param type       instance type string (e.g. {@code "vrf"}, {@code "vpls"})
     * @return           {@code paddedName:md5hex:sha1hex}
     */
    public static String computeKey(String paddedName, String type) {
        String input = paddedName + type;
        return paddedName + ":" + hexDigest("MD5", input) + ":" + hexDigest("SHA-1", input);
    }

    /**
     * Returns the lowercase hex-encoded digest of {@code input} using the given algorithm.
     *
     * @param algorithm JCA algorithm name ({@code "MD5"} or {@code "SHA-1"})
     * @param input     string to digest (UTF-8 encoded)
     * @return          hex string
     */
    private static String hexDigest(String algorithm, String input) {
        try {
            byte[] bytes = MessageDigest.getInstance(algorithm)
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
