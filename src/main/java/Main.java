/**
 * Java implementation of BitTorrent.
 * From CodeCrafters.io build-your-own-bittorrent (Java).
 * 
 * @author DJ Leamen
 * @version 1.0
 * @since 2026-04
 */

import com.google.gson.Gson;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.dampcake.bencode.Bencode;

public class Main {
  private static final Gson gson = new Gson();

  public static void main(String[] args) throws Exception {

    String command = args[0];
    if ("decode".equals(command)) {
      String bencodedValue = args[1];
      Object decoded;
      try {
        decoded = decodeBencode(bencodedValue);
      } catch (RuntimeException e) {
        System.out.println(e.getMessage());
        return;
      }
      System.out.println(gson.toJson(decoded));

    } else if ("info".equals(command)) {
      byte[] data = Files.readAllBytes(Path.of(args[1]));
      int[] index = {0};
      @SuppressWarnings("unchecked")
      Map<String, Object> torrent = (Map<String, Object>) decodeBytes(data, index);
      String announce = new String((byte[]) torrent.get("announce"), StandardCharsets.UTF_8);
      @SuppressWarnings("unchecked")
      Map<String, Object> info = (Map<String, Object>) torrent.get("info");
      long length = (Long) info.get("length");

      byte[] infoBytes = extractInfoBytes(data);
      System.out.println("Tracker URL: " + announce);
      System.out.println("Length: " + length);
      System.out.println("Info Hash: " + toHex(sha1Hash(infoBytes)));
      long pieceLength = (Long) info.get("piece length");
      byte[] pieces = (byte[]) info.get("pieces");
      System.out.println("Piece Length: " + pieceLength);
      System.out.println("Piece Hashes:");
      for (int i = 0; i < pieces.length; i += 20) {
        StringBuilder pieceHex = new StringBuilder();
        for (int j = i; j < i + 20; j++) pieceHex.append(String.format("%02x", pieces[j]));
        System.out.println(pieceHex);
      }

    } else if ("peers".equals(command)) {
      byte[] data = Files.readAllBytes(Path.of(args[1]));
      int[] index = {0};
      @SuppressWarnings("unchecked")
      Map<String, Object> torrent = (Map<String, Object>) decodeBytes(data, index);
      String announce = new String((byte[]) torrent.get("announce"), StandardCharsets.UTF_8);
      @SuppressWarnings("unchecked")
      Map<String, Object> info = (Map<String, Object>) torrent.get("info");
      long length = (Long) info.get("length");

      byte[] infoHash = sha1Hash(extractInfoBytes(data));
      byte[] peers = queryTracker(announce, infoHash, length);
      for (int i = 0; i < peers.length; i += 6) {
        int ip = ByteBuffer.wrap(peers, i, 4).getInt();
        int port = ((peers[i + 4] & 0xFF) << 8) | (peers[i + 5] & 0xFF);
        System.out.printf("%d.%d.%d.%d:%d%n",
            (ip >> 24) & 0xFF, (ip >> 16) & 0xFF, (ip >> 8) & 0xFF, ip & 0xFF, port);
      }

    } else if ("handshake".equals(command)) {
      byte[] data = Files.readAllBytes(Path.of(args[1]));
      String[] hostPort = args[2].split(":");
      String host = hostPort[0];
      int port = Integer.parseInt(hostPort[1]);

      byte[] infoHash = sha1Hash(extractInfoBytes(data));
      byte[] peerId = new byte[20];
      new SecureRandom().nextBytes(peerId);

      try (Socket socket = new Socket(host, port)) {
        socket.getOutputStream().write(buildHandshake(infoHash, peerId, false));
        socket.getOutputStream().flush();
        byte[] response = readFully(socket.getInputStream(), 68);
        System.out.println("Peer ID: " + toHex(Arrays.copyOfRange(response, 48, 68)));
      }

    } else if ("download_piece".equals(command)) {
      String outputPath = args[2];
      byte[] data = Files.readAllBytes(Path.of(args[3]));
      int pieceIndex = Integer.parseInt(args[4]);

      int[] index = {0};
      @SuppressWarnings("unchecked")
      Map<String, Object> torrent = (Map<String, Object>) decodeBytes(data, index);
      String announce = new String((byte[]) torrent.get("announce"), StandardCharsets.UTF_8);
      @SuppressWarnings("unchecked")
      Map<String, Object> info = (Map<String, Object>) torrent.get("info");
      long totalLength = (Long) info.get("length");
      long pieceLength = (Long) info.get("piece length");
      byte[] pieces = (byte[]) info.get("pieces");

      byte[] infoHash = sha1Hash(extractInfoBytes(data));
      String[] peerAddr = firstPeerAddress(queryTracker(announce, infoHash, totalLength));

      byte[] myPeerId = new byte[20];
      new SecureRandom().nextBytes(myPeerId);

      int numPieces = pieces.length / 20;
      long actualPieceLen = (pieceIndex == numPieces - 1)
          ? totalLength - (long) pieceIndex * pieceLength
          : pieceLength;

      try (Socket socket = new Socket(peerAddr[0], Integer.parseInt(peerAddr[1]))) {
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();
        out.write(buildHandshake(infoHash, myPeerId, false));
        out.flush();
        readFully(in, 68);

        byte[] msg;
        do { msg = readPeerMessage(in); } while (msg == null);
        sendPeerMessage(out, 2, new byte[0]);
        do { msg = readPeerMessage(in); } while (msg == null || msg[0] != 1);

        byte[] pieceData = downloadPiece(in, out, pieceIndex, actualPieceLen);
        byte[] expectedHash = Arrays.copyOfRange(pieces, pieceIndex * 20, pieceIndex * 20 + 20);
        if (!Arrays.equals(expectedHash, sha1Hash(pieceData))) {
          throw new RuntimeException("Piece hash mismatch for piece " + pieceIndex);
        }
        Files.write(Path.of(outputPath), pieceData);
        System.out.println("Piece " + pieceIndex + " downloaded to " + outputPath + ".");
      }

    } else if ("download".equals(command)) {
      String outputPath = args[2];
      byte[] data = Files.readAllBytes(Path.of(args[3]));

      int[] index = {0};
      @SuppressWarnings("unchecked")
      Map<String, Object> torrent = (Map<String, Object>) decodeBytes(data, index);
      String announce = new String((byte[]) torrent.get("announce"), StandardCharsets.UTF_8);
      @SuppressWarnings("unchecked")
      Map<String, Object> info = (Map<String, Object>) torrent.get("info");
      long totalLength = (Long) info.get("length");
      long pieceLength = (Long) info.get("piece length");
      byte[] pieces = (byte[]) info.get("pieces");

      byte[] infoHash = sha1Hash(extractInfoBytes(data));
      String[] peerAddr = firstPeerAddress(queryTracker(announce, infoHash, totalLength));

      byte[] myPeerId = new byte[20];
      new SecureRandom().nextBytes(myPeerId);

      int numPieces = pieces.length / 20;
      byte[] fileData = new byte[(int) totalLength];

      try (Socket socket = new Socket(peerAddr[0], Integer.parseInt(peerAddr[1]))) {
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();
        out.write(buildHandshake(infoHash, myPeerId, false));
        out.flush();
        readFully(in, 68);

        byte[] msg;
        do { msg = readPeerMessage(in); } while (msg == null);
        sendPeerMessage(out, 2, new byte[0]);
        do { msg = readPeerMessage(in); } while (msg == null || msg[0] != 1);

        for (int pi = 0; pi < numPieces; pi++) {
          long actualPieceLen = (pi == numPieces - 1)
              ? totalLength - (long) pi * pieceLength
              : pieceLength;
          byte[] pieceData = downloadPiece(in, out, pi, actualPieceLen);
          byte[] expectedHash = Arrays.copyOfRange(pieces, pi * 20, pi * 20 + 20);
          if (!Arrays.equals(expectedHash, sha1Hash(pieceData))) {
            throw new RuntimeException("Piece hash mismatch for piece " + pi);
          }
          System.arraycopy(pieceData, 0, fileData, (int) ((long) pi * pieceLength), (int) actualPieceLen);
        }
      }
      Files.write(Path.of(outputPath), fileData);
      System.out.println("Downloaded " + args[3] + " to " + outputPath + ".");

    } else if ("magnet_parse".equals(command)) {
      String[] parts = parseMagnetLink(args[1]);
      if (parts[1] != null) System.out.println("Tracker URL: " + parts[1]);
      if (parts[0] != null) System.out.println("Info Hash: " + parts[0]);

    } else if ("magnet_handshake".equals(command)) {
      String[] magnetParts = parseMagnetLink(args[1]);
      byte[] infoHash = hexToBytes(magnetParts[0]);
      String[] peerAddr = firstPeerAddress(queryTracker(magnetParts[1], infoHash, 999));

      byte[] myPeerId = new byte[20];
      new SecureRandom().nextBytes(myPeerId);

      try (Socket socket = new Socket(peerAddr[0], Integer.parseInt(peerAddr[1]))) {
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();
        out.write(buildHandshake(infoHash, myPeerId, true));
        out.flush();
        byte[] peerHandshake = readFully(in, 68);
        System.out.println("Peer ID: " + toHex(Arrays.copyOfRange(peerHandshake, 48, 68)));

        byte[] msg;
        do { msg = readPeerMessage(in); } while (msg == null || msg[0] != 5);

        if ((peerHandshake[25] & 0x10) != 0) {
          long utMetadataId = performExtensionHandshake(in, out);
          System.out.println("Peer Metadata Extension ID: " + utMetadataId);
        }
      }

    } else if ("magnet_info".equals(command)) {
      String[] magnetParts = parseMagnetLink(args[1]);
      byte[] infoHash = hexToBytes(magnetParts[0]);
      String[] peerAddr = firstPeerAddress(queryTracker(magnetParts[1], infoHash, 999));

      byte[] myPeerId = new byte[20];
      new SecureRandom().nextBytes(myPeerId);

      try (Socket socket = new Socket(peerAddr[0], Integer.parseInt(peerAddr[1]))) {
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();
        out.write(buildHandshake(infoHash, myPeerId, true));
        out.flush();
        byte[] peerHandshake = readFully(in, 68);

        byte[] msg;
        do { msg = readPeerMessage(in); } while (msg == null || msg[0] != 5);

        if ((peerHandshake[25] & 0x10) != 0) {
          long utMetadataId = performExtensionHandshake(in, out);
          byte[] infoBytes = fetchMetadata(in, out, utMetadataId);

          int[] infoIdx = {0};
          @SuppressWarnings("unchecked")
          Map<String, Object> info = (Map<String, Object>) decodeBytes(infoBytes, infoIdx);
          System.out.println("Tracker URL: " + magnetParts[1]);
          System.out.println("Length: " + info.get("length"));
          System.out.println("Info Hash: " + toHex(sha1Hash(infoBytes)));
          System.out.println("Piece Length: " + info.get("piece length"));
          System.out.println("Piece Hashes:");
          byte[] pieces = (byte[]) info.get("pieces");
          for (int i = 0; i < pieces.length; i += 20) {
            StringBuilder pieceHex = new StringBuilder();
            for (int j = i; j < i + 20; j++) pieceHex.append(String.format("%02x", pieces[j]));
            System.out.println(pieceHex);
          }
        }
      }

    } else if ("magnet_download_piece".equals(command)) {
      String outputPath = args[2];
      int pieceIndex = Integer.parseInt(args[4]);
      String[] magnetParts = parseMagnetLink(args[3]);
      byte[] infoHash = hexToBytes(magnetParts[0]);
      String[] peerAddr = firstPeerAddress(queryTracker(magnetParts[1], infoHash, 999));

      byte[] myPeerId = new byte[20];
      new SecureRandom().nextBytes(myPeerId);

      try (Socket socket = new Socket(peerAddr[0], Integer.parseInt(peerAddr[1]))) {
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();
        out.write(buildHandshake(infoHash, myPeerId, true));
        out.flush();
        byte[] peerHandshake = readFully(in, 68);

        byte[] msg;
        do { msg = readPeerMessage(in); } while (msg == null || msg[0] != 5);
        if ((peerHandshake[25] & 0x10) == 0) throw new RuntimeException("Peer does not support extensions");

        long utMetadataId = performExtensionHandshake(in, out);
        byte[] infoBytes = fetchMetadata(in, out, utMetadataId);

        int[] infoIdx = {0};
        @SuppressWarnings("unchecked")
        Map<String, Object> info = (Map<String, Object>) decodeBytes(infoBytes, infoIdx);
        long totalLength = (Long) info.get("length");
        long pieceLength = (Long) info.get("piece length");
        byte[] pieces = (byte[]) info.get("pieces");

        sendPeerMessage(out, 2, new byte[0]);
        do { msg = readPeerMessage(in); } while (msg == null || msg[0] != 1);

        int numPieces = pieces.length / 20;
        long actualPieceLen = (pieceIndex == numPieces - 1)
            ? totalLength - (long) pieceIndex * pieceLength
            : pieceLength;

        byte[] pieceData = downloadPiece(in, out, pieceIndex, actualPieceLen);
        byte[] expectedHash = Arrays.copyOfRange(pieces, pieceIndex * 20, pieceIndex * 20 + 20);
        if (!Arrays.equals(expectedHash, sha1Hash(pieceData))) {
          throw new RuntimeException("Piece hash mismatch for piece " + pieceIndex);
        }
        Files.write(Path.of(outputPath), pieceData);
        System.out.println("Piece " + pieceIndex + " downloaded to " + outputPath + ".");
      }

    } else if ("magnet_download".equals(command)) {
      String outputPath = args[2];
      String magnetLink = args[3];
      String[] magnetParts = parseMagnetLink(magnetLink);
      byte[] infoHash = hexToBytes(magnetParts[0]);
      String[] peerAddr = firstPeerAddress(queryTracker(magnetParts[1], infoHash, 999));

      byte[] myPeerId = new byte[20];
      new SecureRandom().nextBytes(myPeerId);

      try (Socket socket = new Socket(peerAddr[0], Integer.parseInt(peerAddr[1]))) {
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();
        out.write(buildHandshake(infoHash, myPeerId, true));
        out.flush();
        byte[] peerHandshake = readFully(in, 68);

        byte[] msg;
        do { msg = readPeerMessage(in); } while (msg == null || msg[0] != 5);
        if ((peerHandshake[25] & 0x10) == 0) throw new RuntimeException("Peer does not support extensions");

        long utMetadataId = performExtensionHandshake(in, out);
        byte[] infoBytes = fetchMetadata(in, out, utMetadataId);

        int[] infoIdx = {0};
        @SuppressWarnings("unchecked")
        Map<String, Object> info = (Map<String, Object>) decodeBytes(infoBytes, infoIdx);
        long totalLength = (Long) info.get("length");
        long pieceLength = (Long) info.get("piece length");
        byte[] pieces = (byte[]) info.get("pieces");

        sendPeerMessage(out, 2, new byte[0]);
        do { msg = readPeerMessage(in); } while (msg == null || msg[0] != 1);

        int numPieces = pieces.length / 20;
        byte[] fileData = new byte[(int) totalLength];

        for (int pi = 0; pi < numPieces; pi++) {
          long actualPieceLen = (pi == numPieces - 1)
              ? totalLength - (long) pi * pieceLength
              : pieceLength;
          byte[] pieceData = downloadPiece(in, out, pi, actualPieceLen);
          byte[] expectedHash = Arrays.copyOfRange(pieces, pi * 20, pi * 20 + 20);
          if (!Arrays.equals(expectedHash, sha1Hash(pieceData))) {
            throw new RuntimeException("Piece hash mismatch for piece " + pi);
          }
          System.arraycopy(pieceData, 0, fileData, (int) ((long) pi * pieceLength), (int) actualPieceLen);
        }
        Files.write(Path.of(outputPath), fileData);
        System.out.println("Downloaded " + magnetLink + " to " + outputPath + ".");
      }

    } else {
      System.out.println("Unknown command: " + command);
    }
  }

  // ── Shared helpers ────────────────────────────────────────────────────────

  /** Walks the outer bencoded dictionary to find and return the raw bytes of the "info" value. */
  static byte[] extractInfoBytes(byte[] torrentData) {
    int[] idx = {1}; // skip opening 'd'
    int infoStart = -1, infoEnd = -1;
    while (torrentData[idx[0]] != 'e') {
      byte[] keyBytes = (byte[]) decodeBytes(torrentData, idx);
      String key = new String(keyBytes, StandardCharsets.UTF_8);
      int valueStart = idx[0];
      decodeBytes(torrentData, idx);
      if ("info".equals(key)) {
        infoStart = valueStart;
        infoEnd = idx[0];
      }
    }
    return Arrays.copyOfRange(torrentData, infoStart, infoEnd);
  }

  /** Parses a magnet link and returns {infoHashHex, trackerUrl}. */
  static String[] parseMagnetLink(String magnetLink) throws Exception {
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

  /** Queries the tracker and returns the compact peers bytes. */
  static byte[] queryTracker(String trackerUrl, byte[] infoHash, long left) throws Exception {
    String peerId = "-TR2940-k8hj0wgej6ch";
    String url = trackerUrl
        + "?info_hash=" + urlEncodeBytes(infoHash)
        + "&peer_id=" + peerId
        + "&port=6881&uploaded=0&downloaded=0&left=" + left + "&compact=1";
    HttpClient client = HttpClient.newHttpClient();
    HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
    HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
    int[] ri = {0};
    @SuppressWarnings("unchecked")
    Map<String, Object> trackerResp = (Map<String, Object>) decodeBytes(resp.body(), ri);
    return (byte[]) trackerResp.get("peers");
  }

  /** Returns {host, port} of the first peer in a compact peers list. */
  static String[] firstPeerAddress(byte[] peersBytes) {
    int peerIpInt = ByteBuffer.wrap(peersBytes, 0, 4).getInt();
    int peerPort = ((peersBytes[4] & 0xFF) << 8) | (peersBytes[5] & 0xFF);
    String peerHost = String.format("%d.%d.%d.%d",
        (peerIpInt >> 24) & 0xFF, (peerIpInt >> 16) & 0xFF,
        (peerIpInt >> 8) & 0xFF, peerIpInt & 0xFF);
    return new String[]{peerHost, String.valueOf(peerPort)};
  }

  /** Converts a hex string to a byte array. */
  static byte[] hexToBytes(String hex) {
    byte[] bytes = new byte[hex.length() / 2];
    for (int i = 0; i < bytes.length; i++) {
      bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
    }
    return bytes;
  }

  /** Builds a 68-byte BitTorrent handshake. Set extension=true to advertise BEP-10 support. */
  static byte[] buildHandshake(byte[] infoHash, byte[] peerId, boolean extension) {
    byte[] msg = new byte[68];
    msg[0] = 19;
    System.arraycopy("BitTorrent protocol".getBytes(StandardCharsets.US_ASCII), 0, msg, 1, 19);
    if (extension) msg[25] = 0x10; // reserved byte 5, bit 4 → BEP-10
    System.arraycopy(infoHash, 0, msg, 28, 20);
    System.arraycopy(peerId, 0, msg, 48, 20);
    return msg;
  }

  /** Performs the BEP-10 extension handshake and returns the peer's ut_metadata extension ID. */
  static long performExtensionHandshake(InputStream in, OutputStream out) throws Exception {
    byte[] extDict = "d1:md11:ut_metadatai1eee".getBytes(StandardCharsets.US_ASCII);
    ByteBuffer extBuf = ByteBuffer.allocate(4 + 1 + 1 + extDict.length);
    extBuf.putInt(1 + 1 + extDict.length);
    extBuf.put((byte) 20); // msg id = 20 (extension)
    extBuf.put((byte) 0);  // ext msg id = 0 (handshake)
    extBuf.put(extDict);
    out.write(extBuf.array());
    out.flush();

    byte[] extMsg;
    do { extMsg = readPeerMessage(in); } while (extMsg == null || extMsg[0] != 20);

    int[] ei = {0};
    @SuppressWarnings("unchecked")
    Map<String, Object> extHandshake = (Map<String, Object>) decodeBytes(
        Arrays.copyOfRange(extMsg, 2, extMsg.length), ei);
    @SuppressWarnings("unchecked")
    Map<String, Object> mDict = (Map<String, Object>) extHandshake.get("m");
    return ((Number) mDict.get("ut_metadata")).longValue();
  }

  /** Fetches the info dictionary bytes from the peer using the ut_metadata extension. */
  static byte[] fetchMetadata(InputStream in, OutputStream out, long utMetadataId) throws Exception {
    byte[] metaReqDict = "d8:msg_typei0e5:piecei0ee".getBytes(StandardCharsets.US_ASCII);
    ByteBuffer metaReqBuf = ByteBuffer.allocate(4 + 1 + 1 + metaReqDict.length);
    metaReqBuf.putInt(1 + 1 + metaReqDict.length);
    metaReqBuf.put((byte) 20);
    metaReqBuf.put((byte) utMetadataId);
    metaReqBuf.put(metaReqDict);
    out.write(metaReqBuf.array());
    out.flush();

    byte[] metaDataMsg;
    do { metaDataMsg = readPeerMessage(in); } while (metaDataMsg == null || metaDataMsg[0] != 20 || metaDataMsg[1] != 1);

    // Skip the bencoded response dict header to get to the raw info bytes
    int[] metaIdx = {2};
    decodeBytes(metaDataMsg, metaIdx);
    return Arrays.copyOfRange(metaDataMsg, metaIdx[0], metaDataMsg.length);
  }

  /** Downloads a single piece by requesting all its blocks sequentially. */
  static byte[] downloadPiece(InputStream in, OutputStream out, int pieceIndex, long actualPieceLen) throws Exception {
    byte[] pieceData = new byte[(int) actualPieceLen];
    int blockSize = 16 * 1024;
    int numBlocks = (int) ((actualPieceLen + blockSize - 1) / blockSize);
    for (int blockIdx = 0; blockIdx < numBlocks; blockIdx++) {
      int begin = blockIdx * blockSize;
      int blockLen = (int) Math.min(blockSize, actualPieceLen - begin);
      ByteBuffer reqPayload = ByteBuffer.allocate(12);
      reqPayload.putInt(pieceIndex);
      reqPayload.putInt(begin);
      reqPayload.putInt(blockLen);
      sendPeerMessage(out, 6, reqPayload.array());

      byte[] pieceMsg;
      do { pieceMsg = readPeerMessage(in); } while (pieceMsg == null || pieceMsg[0] != 7);

      int dataOffset = ByteBuffer.wrap(pieceMsg, 5, 4).getInt();
      System.arraycopy(pieceMsg, 9, pieceData, dataOffset, pieceMsg.length - 9);
    }
    return pieceData;
  }

  /** 
   * Computes the SHA-1 hash of the given data.
   * This is used to compute the info hash from the raw bencoded info dictionary.
   * @param data The data to hash.
   * @return The SHA-1 hash of the data.
   */
  static byte[] sha1Hash(byte[] data) throws Exception {
    return MessageDigest.getInstance("SHA-1").digest(data);
  }

  static String toHex(byte[] bytes) {
    StringBuilder sb = new StringBuilder();
    for (byte b : bytes) sb.append(String.format("%02x", b));
    return sb.toString();
  }

  static String urlEncodeBytes(byte[] bytes) {
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

  static byte[] readFully(InputStream in, int n) throws Exception {
    byte[] buf = new byte[n];
    int read = 0;
    while (read < n) {
      int r = in.read(buf, read, n - read);
      if (r == -1) throw new RuntimeException("Unexpected end of stream");
      read += r;
    }
    return buf;
  }

  static byte[] readPeerMessage(InputStream in) throws Exception {
    byte[] lenBuf = readFully(in, 4);
    int length = ByteBuffer.wrap(lenBuf).getInt();
    if (length == 0) return null; // keep-alive
    return readFully(in, length);
  }

  static void sendPeerMessage(OutputStream out, int id, byte[] payload) throws Exception {
    int payloadLen = payload != null ? payload.length : 0;
    ByteBuffer buf = ByteBuffer.allocate(4 + 1 + payloadLen);
    buf.putInt(1 + payloadLen);
    buf.put((byte) id);
    if (payloadLen > 0) buf.put(payload);
    out.write(buf.array());
    out.flush();
  }

  /**
   * Decodes a bencoded string and returns the corresponding Java object. This is a wrapper around the decode method that initializes the index.
   * @param bencodedString The bencoded string to decode.
   * @return The decoded Java object.
   */
  static Object decodeBencode(String bencodedString) {
    int[] index = {0};
    return decode(bencodedString, index);
  }

  /** 
   * Decodes a bencoded byte array starting from the given index. Returns the decoded object and updates the index to the position after the parsed value.
   * Supports integers, lists, dictionaries, and byte strings. For dictionaries, keys are decoded as UTF-8 strings.
   * @param data The bencoded byte array.
   * @param index The current index in the byte array. This will be updated to the position after the parsed value.
   * @return The decoded object.
   */
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

  /** 
   * Decodes a bencoded string starting from the given index. Returns the decoded object and updates the index to the position after the parsed value.
   * Supports integers, lists, dictionaries, and byte strings. For dictionaries, keys are decoded
   * @param s The bencoded string to decode.
   * @param index The current index in the string. This will be updated to the position after the parsed value.
   * @return Object The decoded Java object corresponding to the bencoded value at the given index. This can be a Long for integers, a List for lists, a Map for dictionaries, or a String for byte strings.
   * @throws RuntimeException if the bencoded string contains an unsupported type or is malformed.
   */
  static Object decode(String s, int[] index) {
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
