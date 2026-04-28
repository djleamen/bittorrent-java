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
  /** Gson instance used for serializing decoded bencode values to JSON output. */
  private static final Gson gson = new Gson();

  /** 
   * The main method serves as the entry point for the application. 
   * 
   * @param args The command-line arguments. The first argument is the command to execute, followed by command-specific parameters.
   * @throws Exception if any error occurs during execution, such as network errors, file I/O errors, or malformed input.
   */
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

  /**
   * Extracts the raw bencoded info dictionary bytes from the torrent file data. 
   * 
   * @param torrentData The raw bytes of the torrent file. This should be the entire content of the .torrent file read as a byte array.
   * @return byte[] The raw bencoded info dictionary bytes, which can be used to compute the info hash or to parse the info dictionary fields.
   */
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

  /** 
   * Parses a magnet link and returns {infoHashHex, trackerUrl}. 
   * 
   * @param magnetLink The magnet link to parse. It should be in the format "magnet:?xt=urn:btih:<infoHashHex>&tr=<trackerUrl>".
   * @return String[] A string array where the first element is the info hash in hexadecimal and the second element is the tracker URL. If a component is missing, its value will be null.
   * */
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

  /** 
   * Queries the tracker and returns the compact peers bytes. 
   * 
   * @param trackerUrl The URL of the tracker to query.
   * @param infoHash The info hash of the torrent.
   * @param left The number of bytes left to download.
   * @return byte[] The compact peers bytes returned by the tracker.
   * @throws Exception If an error occurs while querying the tracker.
   */
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

  /** 
   * Returns {host, port} of the first peer in a compact peers list.
   * 
   * @param peersBytes The compact peers bytes returned by the tracker.
   * @return String[] A string array where the first element is the host and the second element is the port.
   */
  static String[] firstPeerAddress(byte[] peersBytes) {
    int peerIpInt = ByteBuffer.wrap(peersBytes, 0, 4).getInt();
    int peerPort = ((peersBytes[4] & 0xFF) << 8) | (peersBytes[5] & 0xFF);
    String peerHost = String.format("%d.%d.%d.%d",
        (peerIpInt >> 24) & 0xFF, (peerIpInt >> 16) & 0xFF,
        (peerIpInt >> 8) & 0xFF, peerIpInt & 0xFF);
    return new String[]{peerHost, String.valueOf(peerPort)};
  }

  /** 
   * Converts a hex string to a byte array. 
   * 
   * @param hex The hex string to convert.
   * @return byte[] The byte array representation of the hex string.
   */
  static byte[] hexToBytes(String hex) {
    byte[] bytes = new byte[hex.length() / 2];
    for (int i = 0; i < bytes.length; i++) {
      bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
    }
    return bytes;
  }

  /** 
   * Builds a 68-byte BitTorrent handshake. Set extension=true to advertise BEP-10 support.
   * 
   * @param infoHash The info hash of the torrent.
   * @param peerId The peer ID to use in the handshake.
   * @param extension Whether to advertise BEP-10 support.
   * @return byte[] The 68-byte BitTorrent handshake message.
   */
  static byte[] buildHandshake(byte[] infoHash, byte[] peerId, boolean extension) {
    byte[] msg = new byte[68];
    msg[0] = 19;
    System.arraycopy("BitTorrent protocol".getBytes(StandardCharsets.US_ASCII), 0, msg, 1, 19);
    if (extension) msg[25] = 0x10; // reserved byte 5, bit 4 → BEP-10
    System.arraycopy(infoHash, 0, msg, 28, 20);
    System.arraycopy(peerId, 0, msg, 48, 20);
    return msg;
  }

  /** 
   * Performs the BEP-10 extension handshake and returns the peer's ut_metadata extension ID.
   * 
   * @param in The input stream to read the extension handshake from.
   * @param out The output stream to send the extension handshake to.
   * @return long The peer's ut_metadata extension ID.
   * @throws Exception If an error occurs during the extension handshake.
   */
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

  /** 
   * Fetches the info dictionary bytes from the peer using the ut_metadata extension.
   * 
   * @param in The input stream to read the metadata from.
   * @param out The output stream to send the metadata request to.
   * @param utMetadataId The peer's ut_metadata extension ID.
   * @return byte[] The info dictionary bytes.
   * @throws Exception If an error occurs while fetching the metadata.
   */
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

  /** 
   * Downloads a single piece by requesting all its blocks sequentially.
   * 
   * @param in The input stream to read the piece data from.
   * @param out The output stream to send the piece requests to.
   * @param pieceIndex The index of the piece to download.
   * @param actualPieceLen The length of the piece in bytes.
   * @return byte[] The downloaded piece data.
   * @throws Exception If an error occurs while downloading the piece.
   */
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
   *
   * @param data The data to hash.
   * @return byte[] The SHA-1 hash of the data.
   * @throws Exception If an error occurs while computing the hash.
   */
  static byte[] sha1Hash(byte[] data) throws Exception {
    return MessageDigest.getInstance("SHA-1").digest(data);
  }

  /** 
   * Converts a byte array to a hex string. 
   * This is used for displaying the info hash and piece hashes in a human-readable format.
   *
   * @param bytes The byte array to convert.
   * @return String The hex string representation of the byte array.
   */
  static String toHex(byte[] bytes) {
    StringBuilder sb = new StringBuilder();
    for (byte b : bytes) sb.append(String.format("%02x", b));
    return sb.toString();
  }

  /** 
   * URL-encodes a byte array according to the BitTorrent specification. 
   * Alphanumeric characters and -_.~ are not percent-encoded, while all 
   * other bytes are encoded as %XX.
   * 
   * @param bytes The byte array to URL-encode.
   * @return String The URL-encoded string representation of the byte array.
   */
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

  /** 
   * Reads exactly n bytes from the input stream. If the stream ends before n bytes are read, throws an exception.
   * This is used to read the fixed-length handshake and peer messages from the socket.
   * 
   * @param in The input stream to read from.
   * @param n The number of bytes to read.
   * @return byte[] A byte array containing the n bytes read from the input stream.
   * @throws Exception If an error occurs while reading from the input stream or if the end of the stream is reached before n bytes are read.
   */
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

  /** 
   * Reads a peer message from the input stream. First reads 4 bytes to determine the message length, then reads the full message. If the length is 0, returns null to indicate a keep-alive message.
   * This is used to read messages from the peer after the handshake.
   * 
   * @param in The input stream to read from.
   * @return byte[] A byte array containing the peer message, or null if it's a keep-alive message.
   * @throws Exception If an error occurs while reading from the input stream or if the end of the stream is reached unexpectedly.
   */
  static byte[] readPeerMessage(InputStream in) throws Exception {
    byte[] lenBuf = readFully(in, 4);
    int length = ByteBuffer.wrap(lenBuf).getInt();
    if (length == 0) return null; // keep-alive
    return readFully(in, length);
  }

  /** 
   * Sends a peer message with the given ID and payload. The message is prefixed with a 4-byte length and a 1-byte message ID, followed by the payload.
   * This is used to send messages to the peer after the handshake.
   * 
   * @param out The output stream to write the message to.
   * @param id The message ID to send. This should be a value between 0 and 255 that identifies the type of message being sent.
   * @param payload The payload of the message as a byte array. This can be null or empty if the message has no payload.
   * @throws Exception If an error occurs while writing to the output stream.
   */
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
   * Decodes a bencoded string and returns the corresponding Java object.
   * This is a convenience wrapper around {@link #decode(String, int[])} that initializes the index.
   *
   * @param bencodedString The bencoded string to decode.
   * @return Object The decoded Java object (Long for integers, List for lists, Map for dictionaries, String for byte strings).
   */
  static Object decodeBencode(String bencodedString) {
    int[] index = {0};
    return decode(bencodedString, index);
  }

  /**
   * Decodes a bencoded byte array starting from the given index.
   * Returns the decoded object and updates the index to the position after the parsed value.
   * Supports integers, lists, dictionaries, and byte strings. For dictionaries, keys are decoded as UTF-8 strings.
   *
   * @param data  The bencoded byte array.
   * @param index The current index in the byte array; updated to the position after the parsed value.
   * @return Object The decoded object (Long for integers, List for lists, Map for dictionaries, byte[] for byte strings).
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
   * Decodes a bencoded string starting from the given index.
   * Returns the decoded object and updates the index to the position after the parsed value.
   * Supports integers, lists, dictionaries, and byte strings. For dictionaries, keys are decoded as strings.
   *
   * @param s     The bencoded string to decode.
   * @param index The current index in the string; updated to the position after the parsed value.
   * @return Object The decoded Java object (Long for integers, List for lists, Map for dictionaries, String for byte strings).
   * @throws RuntimeException If the bencoded string contains an unsupported type or is malformed.
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
