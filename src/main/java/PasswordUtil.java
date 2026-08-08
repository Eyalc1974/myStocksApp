import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

public class PasswordUtil {
    
    /**
     * Hash a password using SHA-256
     */
    public static String hashPassword(String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(password.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not found", e);
        }
    }
    
    /**
     * Verify a password against a hash
     */
    public static boolean verifyPassword(String password, String hash) {
        String computedHash = hashPassword(password);
        return computedHash.equals(hash);
    }
    
    /**
     * Generate hash for admin password
     */
    public static void main(String[] args) {
        String adminPassword = "Cohen1974Yhv";
        String hash = hashPassword(adminPassword);
        System.out.println("Admin password hash: " + hash);
    }
}
