import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Decodes bencoded data from strings or byte arrays. */
public class BencodeDecoder {

  public static Object decodeBencode(String bencodedString) {
    int[] index = {0};
    return decode(bencodedString, index);
  }

  public static Object decodeBytes(byte[] data, int[] index) {
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

  public static Object decode(String s, int[] index) {
    char c = s.charAt(index[0]);
    if (c == 'i') {
      index[0]++;
      int end = s.indexOf('e', index[0]);
      long val = Long.parseLong(s.substring(index[0], end));
      index[0] = end + 1;
      return val;
    } else if (c == 'l') {
      index[0]++;
      List<Object> list = new ArrayList<>();
      while (s.charAt(index[0]) != 'e') {
        list.add(decode(s, index));
      }
      index[0]++;
      return list;
    } else if (c == 'd') {
      index[0]++;
      Map<String, Object> map = new LinkedHashMap<>();
      while (s.charAt(index[0]) != 'e') {
        String key = (String) decode(s, index);
        Object value = decode(s, index);
        map.put(key, value);
      }
      index[0]++;
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
