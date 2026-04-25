import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
// import com.dampcake.bencode.Bencode; - available if you need it!

public class Main {
  private static final Gson gson = new Gson();

  public static void main(String[] args) throws Exception {
    
    String command = args[0];
    if("decode".equals(command)) {
       String bencodedValue = args[1];
       Object decoded;
       try {
         decoded = decodeBencode(bencodedValue);
       } catch(RuntimeException e) {
         System.out.println(e.getMessage());
         return;
       }
       System.out.println(gson.toJson(decoded));

    } else if ("info".equals(command)) {
      byte[] data = Files.readAllBytes(Path.of(args[1]));

      // Parse full torrent for announce/length
      int[] index = {0};
      @SuppressWarnings("unchecked")
      Map<String, Object> torrent = (Map<String, Object>) decodeBytes(data, index);
      String announce = new String((byte[]) torrent.get("announce"), StandardCharsets.UTF_8);
      @SuppressWarnings("unchecked")
      Map<String, Object> info = (Map<String, Object>) torrent.get("info");
      long length = (Long) info.get("length");

      // Find the raw bytes of the info value by re-scanning the outer dict
      int[] idx = {0};
      idx[0]++; // skip outer 'd'
      int infoStart = -1, infoEnd = -1;
      while (data[idx[0]] != 'e') {
        byte[] keyBytes = (byte[]) decodeBytes(data, idx);
        String key = new String(keyBytes, StandardCharsets.UTF_8);
        int valueStart = idx[0];
        decodeBytes(data, idx);
        if ("info".equals(key)) {
          infoStart = valueStart;
          infoEnd = idx[0];
        }
      }
      byte[] infoBytes = Arrays.copyOfRange(data, infoStart, infoEnd);
      MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
      byte[] hash = sha1.digest(infoBytes);
      StringBuilder hex = new StringBuilder();
      for (byte b : hash) hex.append(String.format("%02x", b));

      System.out.println("Tracker URL: " + announce);
      System.out.println("Length: " + length);
      System.out.println("Info Hash: " + hex);
    } else {
      System.out.println("Unknown command: " + command);
    }

  }

  static Object decodeBencode(String bencodedString) {
    int[] index = {0};
    return decode(bencodedString, index);
  }

  // Byte-array based decoder — returns byte[] for bencoded strings (preserves binary data)
  static Object decodeBytes(byte[] data, int[] index) {
    byte c = data[index[0]];
    if (c == 'i') {
      index[0]++;
      int end = index[0];
      while (data[end] != 'e') end++;
      long val = Long.parseLong(new String(data, index[0], end - index[0], StandardCharsets.US_ASCII));
      index[0] = end + 1;
      return val;
    } else if (c == 'l') {
      index[0]++;
      List<Object> list = new ArrayList<>();
      while (data[index[0]] != 'e') {
        list.add(decodeBytes(data, index));
      }
      index[0]++;
      return list;
    } else if (c == 'd') {
      index[0]++;
      Map<String, Object> map = new LinkedHashMap<>();
      while (data[index[0]] != 'e') {
        byte[] keyBytes = (byte[]) decodeBytes(data, index);
        String key = new String(keyBytes, StandardCharsets.UTF_8);
        Object value = decodeBytes(data, index);
        map.put(key, value);
      }
      index[0]++;
      return map;
    } else if (c >= '0' && c <= '9') {
      int colon = index[0];
      while (data[colon] != ':') colon++;
      int length = Integer.parseInt(new String(data, index[0], colon - index[0], StandardCharsets.US_ASCII));
      index[0] = colon + 1;
      byte[] bytes = Arrays.copyOfRange(data, index[0], index[0] + length);
      index[0] += length;
      return bytes;
    } else {
      throw new RuntimeException("Unexpected token at index " + index[0]);
    }
  }

  static Object decode(String s, int[] index) {
    char c = s.charAt(index[0]);
    if (c == 'i') {
      index[0]++; // skip 'i'
      int end = s.indexOf('e', index[0]);
      long val = Long.parseLong(s.substring(index[0], end));
      index[0] = end + 1; // skip past 'e'
      return val;
    } else if (c == 'l') {
      index[0]++; // skip 'l'
      List<Object> list = new ArrayList<>();
      while (s.charAt(index[0]) != 'e') {
        list.add(decode(s, index));
      }
      index[0]++; // skip 'e'
      return list;
    } else if (c == 'd') {
      index[0]++; // skip 'd'
      Map<String, Object> map = new LinkedHashMap<>();
      while (s.charAt(index[0]) != 'e') {
        String key = (String) decode(s, index);
        Object value = decode(s, index);
        map.put(key, value);
      }
      index[0]++; // skip 'e'
      return map;
    } else if (Character.isDigit(c)) {
      int colon = s.indexOf(':', index[0]);
      int length = Integer.parseInt(s.substring(index[0], colon));
      index[0] = colon + 1;
      String str = s.substring(index[0], index[0] + length);
      index[0] += length;
      return str;
    } else {
      throw new RuntimeException("Only strings are supported at the moment");
    }
  }
  
}
