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
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;

public class Main {
  private static final Gson gson = new Gson();

  public static void main(String[] args) throws Exception {
    String command = args[0];

    if ("decode".equals(command)) {
      Object decoded;
      try {
        decoded = BencodeDecoder.decodeBencode(args[1]);
      } catch (RuntimeException e) {
        System.out.println(e.getMessage());
        return;
      }
      System.out.println(gson.toJson(decoded));

    } else if ("info".equals(command)) {
      byte[] data = Files.readAllBytes(Path.of(args[1]));
      int[] index = {0};
      @SuppressWarnings("unchecked")
      Map<String, Object> torrent = (Map<String, Object>) BencodeDecoder.decodeBytes(data, index);
      String announce = new String((byte[]) torrent.get("announce"), StandardCharsets.UTF_8);
      @SuppressWarnings("unchecked")
      Map<String, Object> info = (Map<String, Object>) torrent.get("info");
      long length = (Long) info.get("length");
      byte[] infoBytes = TorrentParser.extractInfoBytes(data);
      System.out.println("Tracker URL: " + announce);
      System.out.println("Length: " + length);
      System.out.println("Info Hash: " + ByteUtils.toHex(ByteUtils.sha1Hash(infoBytes)));
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
      Map<String, Object> torrent = (Map<String, Object>) BencodeDecoder.decodeBytes(data, index);
      String announce = new String((byte[]) torrent.get("announce"), StandardCharsets.UTF_8);
      @SuppressWarnings("unchecked")
      Map<String, Object> info = (Map<String, Object>) torrent.get("info");
      long length = (Long) info.get("length");
      byte[] infoHash = ByteUtils.sha1Hash(TorrentParser.extractInfoBytes(data));
      byte[] peers = TrackerClient.queryTracker(announce, infoHash, length);
      for (int i = 0; i < peers.length; i += 6) {
        int ip = ByteBuffer.wrap(peers, i, 4).getInt();
        int port = ((peers[i + 4] & 0xFF) << 8) | (peers[i + 5] & 0xFF);
        System.out.printf("%d.%d.%d.%d:%d%n",
            (ip >> 24) & 0xFF, (ip >> 16) & 0xFF, (ip >> 8) & 0xFF, ip & 0xFF, port);
      }

    } else if ("handshake".equals(command)) {
      byte[] data = Files.readAllBytes(Path.of(args[1]));
      String[] hostPort = args[2].split(":");
      byte[] infoHash = ByteUtils.sha1Hash(TorrentParser.extractInfoBytes(data));
      byte[] peerId = new byte[20];
      new SecureRandom().nextBytes(peerId);
      try (Socket socket = new Socket(hostPort[0], Integer.parseInt(hostPort[1]))) {
        socket.getOutputStream().write(PeerProtocol.buildHandshake(infoHash, peerId, false));
        socket.getOutputStream().flush();
        byte[] response = ByteUtils.readFully(socket.getInputStream(), 68);
        System.out.println("Peer ID: " + ByteUtils.toHex(Arrays.copyOfRange(response, 48, 68)));
      }

    } else if ("download_piece".equals(command)) {
      String outputPath = args[2];
      byte[] data = Files.readAllBytes(Path.of(args[3]));
      int pieceIndex = Integer.parseInt(args[4]);
      int[] index = {0};
      @SuppressWarnings("unchecked")
      Map<String, Object> torrent = (Map<String, Object>) BencodeDecoder.decodeBytes(data, index);
      String announce = new String((byte[]) torrent.get("announce"), StandardCharsets.UTF_8);
      @SuppressWarnings("unchecked")
      Map<String, Object> info = (Map<String, Object>) torrent.get("info");
      long totalLength = (Long) info.get("length");
      long pieceLength = (Long) info.get("piece length");
      byte[] pieces = (byte[]) info.get("pieces");
      byte[] infoHash = ByteUtils.sha1Hash(TorrentParser.extractInfoBytes(data));
      String[] peerAddr = TrackerClient.firstPeerAddress(TrackerClient.queryTracker(announce, infoHash, totalLength));
      byte[] myPeerId = new byte[20];
      new SecureRandom().nextBytes(myPeerId);
      int numPieces = pieces.length / 20;
      long actualPieceLen = (pieceIndex == numPieces - 1)
          ? totalLength - (long) pieceIndex * pieceLength : pieceLength;
      try (Socket socket = new Socket(peerAddr[0], Integer.parseInt(peerAddr[1]))) {
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();
        out.write(PeerProtocol.buildHandshake(infoHash, myPeerId, false));
        out.flush();
        ByteUtils.readFully(in, 68);
        byte[] msg;
        do { msg = PeerProtocol.readPeerMessage(in); } while (msg == null);
        PeerProtocol.sendPeerMessage(out, 2, new byte[0]);
        do { msg = PeerProtocol.readPeerMessage(in); } while (msg == null || msg[0] != 1);
        byte[] pieceData = PeerProtocol.downloadPiece(in, out, pieceIndex, actualPieceLen);
        byte[] expectedHash = Arrays.copyOfRange(pieces, pieceIndex * 20, pieceIndex * 20 + 20);
        if (!Arrays.equals(expectedHash, ByteUtils.sha1Hash(pieceData))) {
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
      Map<String, Object> torrent = (Map<String, Object>) BencodeDecoder.decodeBytes(data, index);
      String announce = new String((byte[]) torrent.get("announce"), StandardCharsets.UTF_8);
      @SuppressWarnings("unchecked")
      Map<String, Object> info = (Map<String, Object>) torrent.get("info");
      long totalLength = (Long) info.get("length");
      long pieceLength = (Long) info.get("piece length");
      byte[] pieces = (byte[]) info.get("pieces");
      byte[] infoHash = ByteUtils.sha1Hash(TorrentParser.extractInfoBytes(data));
      String[] peerAddr = TrackerClient.firstPeerAddress(TrackerClient.queryTracker(announce, infoHash, totalLength));
      byte[] myPeerId = new byte[20];
      new SecureRandom().nextBytes(myPeerId);
      int numPieces = pieces.length / 20;
      byte[] fileData = new byte[(int) totalLength];
      try (Socket socket = new Socket(peerAddr[0], Integer.parseInt(peerAddr[1]))) {
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();
        out.write(PeerProtocol.buildHandshake(infoHash, myPeerId, false));
        out.flush();
        ByteUtils.readFully(in, 68);
        byte[] msg;
        do { msg = PeerProtocol.readPeerMessage(in); } while (msg == null);
        PeerProtocol.sendPeerMessage(out, 2, new byte[0]);
        do { msg = PeerProtocol.readPeerMessage(in); } while (msg == null || msg[0] != 1);
        for (int pi = 0; pi < numPieces; pi++) {
          long actualPieceLen = (pi == numPieces - 1)
              ? totalLength - (long) pi * pieceLength : pieceLength;
          byte[] pieceData = PeerProtocol.downloadPiece(in, out, pi, actualPieceLen);
          byte[] expectedHash = Arrays.copyOfRange(pieces, pi * 20, pi * 20 + 20);
          if (!Arrays.equals(expectedHash, ByteUtils.sha1Hash(pieceData))) {
            throw new RuntimeException("Piece hash mismatch for piece " + pi);
          }
          System.arraycopy(pieceData, 0, fileData, (int) ((long) pi * pieceLength), (int) actualPieceLen);
        }
      }
      Files.write(Path.of(outputPath), fileData);
      System.out.println("Downloaded " + args[3] + " to " + outputPath + ".");

    } else if ("magnet_parse".equals(command)) {
      String[] parts = TorrentParser.parseMagnetLink(args[1]);
      if (parts[1] != null) System.out.println("Tracker URL: " + parts[1]);
      if (parts[0] != null) System.out.println("Info Hash: " + parts[0]);

    } else if ("magnet_handshake".equals(command)) {
      String[] magnetParts = TorrentParser.parseMagnetLink(args[1]);
      byte[] infoHash = ByteUtils.hexToBytes(magnetParts[0]);
      String[] peerAddr = TrackerClient.firstPeerAddress(TrackerClient.queryTracker(magnetParts[1], infoHash, 999));
      byte[] myPeerId = new byte[20];
      new SecureRandom().nextBytes(myPeerId);
      try (Socket socket = new Socket(peerAddr[0], Integer.parseInt(peerAddr[1]))) {
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();
        out.write(PeerProtocol.buildHandshake(infoHash, myPeerId, true));
        out.flush();
        byte[] peerHandshake = ByteUtils.readFully(in, 68);
        System.out.println("Peer ID: " + ByteUtils.toHex(Arrays.copyOfRange(peerHandshake, 48, 68)));
        byte[] msg;
        do { msg = PeerProtocol.readPeerMessage(in); } while (msg == null || msg[0] != 5);
        if ((peerHandshake[25] & 0x10) != 0) {
          long utMetadataId = PeerProtocol.performExtensionHandshake(in, out);
          System.out.println("Peer Metadata Extension ID: " + utMetadataId);
        }
      }

    } else if ("magnet_info".equals(command)) {
      String[] magnetParts = TorrentParser.parseMagnetLink(args[1]);
      byte[] infoHash = ByteUtils.hexToBytes(magnetParts[0]);
      String[] peerAddr = TrackerClient.firstPeerAddress(TrackerClient.queryTracker(magnetParts[1], infoHash, 999));
      byte[] myPeerId = new byte[20];
      new SecureRandom().nextBytes(myPeerId);
      try (Socket socket = new Socket(peerAddr[0], Integer.parseInt(peerAddr[1]))) {
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();
        out.write(PeerProtocol.buildHandshake(infoHash, myPeerId, true));
        out.flush();
        byte[] peerHandshake = ByteUtils.readFully(in, 68);
        byte[] msg;
        do { msg = PeerProtocol.readPeerMessage(in); } while (msg == null || msg[0] != 5);
        if ((peerHandshake[25] & 0x10) != 0) {
          long utMetadataId = PeerProtocol.performExtensionHandshake(in, out);
          byte[] infoBytes = PeerProtocol.fetchMetadata(in, out, utMetadataId);
          int[] infoIdx = {0};
          @SuppressWarnings("unchecked")
          Map<String, Object> info = (Map<String, Object>) BencodeDecoder.decodeBytes(infoBytes, infoIdx);
          System.out.println("Tracker URL: " + magnetParts[1]);
          System.out.println("Length: " + info.get("length"));
          System.out.println("Info Hash: " + ByteUtils.toHex(ByteUtils.sha1Hash(infoBytes)));
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
      String[] magnetParts = TorrentParser.parseMagnetLink(args[3]);
      byte[] infoHash = ByteUtils.hexToBytes(magnetParts[0]);
      String[] peerAddr = TrackerClient.firstPeerAddress(TrackerClient.queryTracker(magnetParts[1], infoHash, 999));
      byte[] myPeerId = new byte[20];
      new SecureRandom().nextBytes(myPeerId);
      try (Socket socket = new Socket(peerAddr[0], Integer.parseInt(peerAddr[1]))) {
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();
        out.write(PeerProtocol.buildHandshake(infoHash, myPeerId, true));
        out.flush();
        byte[] peerHandshake = ByteUtils.readFully(in, 68);
        byte[] msg;
        do { msg = PeerProtocol.readPeerMessage(in); } while (msg == null || msg[0] != 5);
        if ((peerHandshake[25] & 0x10) == 0) throw new RuntimeException("Peer does not support extensions");
        long utMetadataId = PeerProtocol.performExtensionHandshake(in, out);
        byte[] infoBytes = PeerProtocol.fetchMetadata(in, out, utMetadataId);
        int[] infoIdx = {0};
        @SuppressWarnings("unchecked")
        Map<String, Object> info = (Map<String, Object>) BencodeDecoder.decodeBytes(infoBytes, infoIdx);
        long totalLength = (Long) info.get("length");
        long pieceLength = (Long) info.get("piece length");
        byte[] pieces = (byte[]) info.get("pieces");
        PeerProtocol.sendPeerMessage(out, 2, new byte[0]);
        do { msg = PeerProtocol.readPeerMessage(in); } while (msg == null || msg[0] != 1);
        int numPieces = pieces.length / 20;
        long actualPieceLen = (pieceIndex == numPieces - 1)
            ? totalLength - (long) pieceIndex * pieceLength : pieceLength;
        byte[] pieceData = PeerProtocol.downloadPiece(in, out, pieceIndex, actualPieceLen);
        byte[] expectedHash = Arrays.copyOfRange(pieces, pieceIndex * 20, pieceIndex * 20 + 20);
        if (!Arrays.equals(expectedHash, ByteUtils.sha1Hash(pieceData))) {
          throw new RuntimeException("Piece hash mismatch for piece " + pieceIndex);
        }
        Files.write(Path.of(outputPath), pieceData);
        System.out.println("Piece " + pieceIndex + " downloaded to " + outputPath + ".");
      }

    } else if ("magnet_download".equals(command)) {
      String outputPath = args[2];
      String magnetLink = args[3];
      String[] magnetParts = TorrentParser.parseMagnetLink(magnetLink);
      byte[] infoHash = ByteUtils.hexToBytes(magnetParts[0]);
      String[] peerAddr = TrackerClient.firstPeerAddress(TrackerClient.queryTracker(magnetParts[1], infoHash, 999));
      byte[] myPeerId = new byte[20];
      new SecureRandom().nextBytes(myPeerId);
      try (Socket socket = new Socket(peerAddr[0], Integer.parseInt(peerAddr[1]))) {
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();
        out.write(PeerProtocol.buildHandshake(infoHash, myPeerId, true));
        out.flush();
        byte[] peerHandshake = ByteUtils.readFully(in, 68);
        byte[] msg;
        do { msg = PeerProtocol.readPeerMessage(in); } while (msg == null || msg[0] != 5);
        if ((peerHandshake[25] & 0x10) == 0) throw new RuntimeException("Peer does not support extensions");
        long utMetadataId = PeerProtocol.performExtensionHandshake(in, out);
        byte[] infoBytes = PeerProtocol.fetchMetadata(in, out, utMetadataId);
        int[] infoIdx = {0};
        @SuppressWarnings("unchecked")
        Map<String, Object> info = (Map<String, Object>) BencodeDecoder.decodeBytes(infoBytes, infoIdx);
        long totalLength = (Long) info.get("length");
        long pieceLength = (Long) info.get("piece length");
        byte[] pieces = (byte[]) info.get("pieces");
        PeerProtocol.sendPeerMessage(out, 2, new byte[0]);
        do { msg = PeerProtocol.readPeerMessage(in); } while (msg == null || msg[0] != 1);
        int numPieces = pieces.length / 20;
        byte[] fileData = new byte[(int) totalLength];
        for (int pi = 0; pi < numPieces; pi++) {
          long actualPieceLen = (pi == numPieces - 1)
              ? totalLength - (long) pi * pieceLength : pieceLength;
          byte[] pieceData = PeerProtocol.downloadPiece(in, out, pi, actualPieceLen);
          byte[] expectedHash = Arrays.copyOfRange(pieces, pi * 20, pi * 20 + 20);
          if (!Arrays.equals(expectedHash, ByteUtils.sha1Hash(pieceData))) {
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
}

