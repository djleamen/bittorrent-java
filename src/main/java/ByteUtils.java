import java.io.InputStream;
import java.security.MessageDigest;

/** Utility methods for hashing, hex encoding, URL encoding, and stream I/O. */
public class ByteUtils {

  public static byte[] sha1Hash(byte[] data) throws Exception {
    return MessageDigest.getInstance("SHA-1").digest(data);
  }

  public static String toHex(byte[] bytes) {
    StringBuilder sb = new StringBuilder();
    for (byte b : bytes) sb.append(String.format("%02x", b));
    return sb.toString();
  }

  public static String urlEncodeBytes(byte[] bytes) {
    StringBuilder sb = new StringBuilder();
    for (byte b : bytes) {
      if ((b >= 'A' && b <= 'Z') || (b >= 'a' && b <= 'z') || (b >= '0' && b <= '9')
          || b == '-' || b == '_' || b == '.' || b == '~') {
        sb.append((char) b);
      } else {
        sb.append(String.format("%%%02X", b & 0xFF));
      }
    }
    return sb.toString();
  }

  public static byte[] hexToBytes(String hex) {
    byte[] bytes = new byte[hex.length() / 2];
    for (int i = 0; i < bytes.length; i++) {
      bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
    }
    return bytes;
  }

  public static byte[] readFully(InputStream in, int n) throws Exception {
    byte[] buf = new byte[n];
    int read = 0;
    while (read < n) {
      int r = in.read(buf, read, n - read);
      if (r == -1) throw new RuntimeException("Unexpected end of stream");
      read += r;
    }
    return buf;
  }
}
