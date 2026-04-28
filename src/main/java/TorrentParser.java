import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Parses .torrent files and magnet links. */
public class TorrentParser {

  public static byte[] extractInfoBytes(byte[] torrentData) {
    int[] idx = {1}; // skip opening 'd'
    int infoStart = -1, infoEnd = -1;
    while (torrentData[idx[0]] != 'e') {
      byte[] keyBytes = (byte[]) BencodeDecoder.decodeBytes(torrentData, idx);
      String key = new String(keyBytes, StandardCharsets.UTF_8);
      int valueStart = idx[0];
      BencodeDecoder.decodeBytes(torrentData, idx);
      if ("info".equals(key)) {
        infoStart = valueStart;
        infoEnd = idx[0];
      }
    }
    return Arrays.copyOfRange(torrentData, infoStart, infoEnd);
  }

  /** Returns {infoHashHex, trackerUrl}. */
  public static String[] parseMagnetLink(String magnetLink) throws Exception {
    String query = magnetLink.startsWith("magnet:?") ? magnetLink.substring(8) : magnetLink;
    String infoHashHex = null, trackerUrl = null;
    for (String param : query.split("&")) {
      int eq = param.indexOf('=');
      if (eq == -1) continue;
      String key = param.substring(0, eq);
      String value = java.net.URLDecoder.decode(param.substring(eq + 1), StandardCharsets.UTF_8);
      if ("xt".equals(key) && value.startsWith("urn:btih:")) {
        infoHashHex = value.substring("urn:btih:".length());
      } else if ("tr".equals(key)) {
        trackerUrl = value;
      }
    }
    return new String[]{infoHashHex, trackerUrl};
  }
}
