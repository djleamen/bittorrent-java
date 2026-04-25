import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.List;
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

    } else {
      System.out.println("Unknown command: " + command);
    }

  }

  static Object decodeBencode(String bencodedString) {
    int[] index = {0};
    return decode(bencodedString, index);
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
