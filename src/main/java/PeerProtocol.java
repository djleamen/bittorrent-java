import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

/** BitTorrent peer wire protocol: handshake, messages, and piece download. */
public class PeerProtocol {

  /** Builds a 68-byte handshake. Set {@code extension=true} to advertise BEP-10 support. */
  public static byte[] buildHandshake(byte[] infoHash, byte[] peerId, boolean extension) {
    byte[] msg = new byte[68];
    msg[0] = 19;
    System.arraycopy("BitTorrent protocol".getBytes(StandardCharsets.US_ASCII), 0, msg, 1, 19);
    if (extension) msg[25] = 0x10; // reserved byte 5, bit 4 → BEP-10
    System.arraycopy(infoHash, 0, msg, 28, 20);
    System.arraycopy(peerId, 0, msg, 48, 20);
    return msg;
  }

  /** Performs the BEP-10 extension handshake and returns the peer's ut_metadata extension ID. */
  public static long performExtensionHandshake(InputStream in, OutputStream out) throws Exception {
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
    Map<String, Object> extHandshake = (Map<String, Object>) BencodeDecoder.decodeBytes(
        Arrays.copyOfRange(extMsg, 2, extMsg.length), ei);
    @SuppressWarnings("unchecked")
    Map<String, Object> mDict = (Map<String, Object>) extHandshake.get("m");
    return ((Number) mDict.get("ut_metadata")).longValue();
  }

  /** Fetches the info dictionary bytes via the ut_metadata extension. */
  public static byte[] fetchMetadata(InputStream in, OutputStream out, long utMetadataId) throws Exception {
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

    // Skip the bencoded response dict header to reach the raw info bytes
    int[] metaIdx = {2};
    BencodeDecoder.decodeBytes(metaDataMsg, metaIdx);
    return Arrays.copyOfRange(metaDataMsg, metaIdx[0], metaDataMsg.length);
  }

  /** Downloads a single piece by requesting all its blocks sequentially. */
  public static byte[] downloadPiece(InputStream in, OutputStream out, int pieceIndex, long actualPieceLen) throws Exception {
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

  /** Reads a length-prefixed peer message. Returns null for keep-alive (length 0). */
  public static byte[] readPeerMessage(InputStream in) throws Exception {
    byte[] lenBuf = ByteUtils.readFully(in, 4);
    int length = ByteBuffer.wrap(lenBuf).getInt();
    if (length == 0) return null;
    return ByteUtils.readFully(in, length);
  }

  /** Sends a length-prefixed peer message with the given ID and payload. */
  public static void sendPeerMessage(OutputStream out, int id, byte[] payload) throws Exception {
    int payloadLen = payload != null ? payload.length : 0;
    ByteBuffer buf = ByteBuffer.allocate(4 + 1 + payloadLen);
    buf.putInt(1 + payloadLen);
    buf.put((byte) id);
    if (payloadLen > 0) buf.put(payload);
    out.write(buf.array());
    out.flush();
  }
}
