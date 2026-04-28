import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.Map;

/** Queries the BitTorrent tracker and extracts peer addresses. */
public class TrackerClient {

  public static byte[] queryTracker(String trackerUrl, byte[] infoHash, long left) throws Exception {
    String peerId = "-TR2940-k8hj0wgej6ch";
    String url = trackerUrl
        + "?info_hash=" + ByteUtils.urlEncodeBytes(infoHash)
        + "&peer_id=" + peerId
        + "&port=6881&uploaded=0&downloaded=0&left=" + left + "&compact=1";
    HttpClient client = HttpClient.newHttpClient();
    HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
    HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
    int[] ri = {0};
    @SuppressWarnings("unchecked")
    Map<String, Object> trackerResp = (Map<String, Object>) BencodeDecoder.decodeBytes(resp.body(), ri);
    return (byte[]) trackerResp.get("peers");
  }

  /** Returns {host, port} of the first peer in a compact peers list. */
  public static String[] firstPeerAddress(byte[] peersBytes) {
    int peerIpInt = ByteBuffer.wrap(peersBytes, 0, 4).getInt();
    int peerPort = ((peersBytes[4] & 0xFF) << 8) | (peersBytes[5] & 0xFF);
    String peerHost = String.format("%d.%d.%d.%d",
        (peerIpInt >> 24) & 0xFF, (peerIpInt >> 16) & 0xFF,
        (peerIpInt >> 8) & 0xFF, peerIpInt & 0xFF);
    return new String[]{peerHost, String.valueOf(peerPort)};
  }
}
