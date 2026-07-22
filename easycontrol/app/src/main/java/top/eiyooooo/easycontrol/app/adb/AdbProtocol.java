package top.eiyooooo.easycontrol.app.adb;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
/**
 * 类 AdbProtocol
 * 说明：该类负责 AdbProtocol 相关功能。
 */

public class AdbProtocol {
  public static final int ADB_HEADER_LENGTH = 24;
  public static final int MAX_ADB_PAYLOAD_LENGTH = 16 * 1024 * 1024;

  public static final int AUTH_TYPE_TOKEN = 1;
  public static final int AUTH_TYPE_SIGNATURE = 2;
  public static final int AUTH_TYPE_RSA_PUBLIC = 3;

  public static final int CMD_AUTH = 0x48545541;
  public static final int CMD_CNXN = 0x4e584e43;
  public static final int CMD_SYNC = 0x434e5953;
  public static final int CMD_OPEN = 0x4e45504f;
  public static final int CMD_OKAY = 0x59414b4f;
  public static final int CMD_CLSE = 0x45534c43;
  public static final int CMD_WRTE = 0x45545257;
  public static final int CMD_STLS = 0x534c5453;

  public static final int CONNECT_VERSION_MIN = 0x01000000;
  public static final int CONNECT_VERSION_SKIP_CHECKSUM = 0x01000001;
  public static final int CONNECT_VERSION = CONNECT_VERSION_SKIP_CHECKSUM;
  public static final int STLS_VERSION = 0x01000000;
  public static final int CONNECT_MAXDATA = 15 * 1024;
  public static final int CONNECT_MAXDATA_TCP = 1024 * 1024;

  public static final byte[] CONNECT_PAYLOAD = "host::\0".getBytes(StandardCharsets.UTF_8);

  public static ByteBuffer generateConnect() {
    return generateConnect(CONNECT_MAXDATA);
  }

  public static ByteBuffer generateConnect(int maxData) {
    return generateConnect(CONNECT_VERSION, maxData);
  }

  public static ByteBuffer generateConnect(int version, int maxData) {
    if (version < CONNECT_VERSION_MIN || version > CONNECT_VERSION) {
      throw new IllegalArgumentException("invalid ADB connect version: 0x"
              + Integer.toHexString(version));
    }
    if (maxData <= 128 || maxData > MAX_ADB_PAYLOAD_LENGTH) {
      throw new IllegalArgumentException("invalid ADB connect maxData: " + maxData);
    }
    return generateMessage(CMD_CNXN, version, maxData, CONNECT_PAYLOAD);
  }

  public static ByteBuffer generateAuth(int type, byte[] data) {
    return generateMessage(CMD_AUTH, type, 0, data);
  }

  public static ByteBuffer generateStls() {
    return generateMessage(CMD_STLS, STLS_VERSION, 0, null);
  }

  public static ByteBuffer generateOpen(int localId, String dest) {
    ByteBuffer bbuf = ByteBuffer.allocate(dest.length() + 1);
    bbuf.put(dest.getBytes(StandardCharsets.UTF_8));
    bbuf.put((byte) 0);
    return generateMessage(CMD_OPEN, localId, 0, bbuf.array());
  }

  public static ByteBuffer generateWrite(int localId, int remoteId, byte[] data) {
    return generateMessage(CMD_WRTE, localId, remoteId, data);
  }

  public static ByteBuffer generateClose(int localId, int remoteId) {
    return generateMessage(CMD_CLSE, localId, remoteId, null);
  }

  public static ByteBuffer generateOkay(int localId, int remoteId) {
    return generateMessage(CMD_OKAY, localId, remoteId, null);
  }

  private static ByteBuffer generateMessage(int cmd, int arg0, int arg1, byte[] payload) {

    int size = payload == null ? ADB_HEADER_LENGTH : (ADB_HEADER_LENGTH + payload.length);
    ByteBuffer buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);

    buffer.putInt(cmd);
    buffer.putInt(arg0);
    buffer.putInt(arg1);

    if (payload == null) {
      buffer.putInt(0);
      buffer.putInt(0);
    } else {
      buffer.putInt(payload.length);
      buffer.putInt(payloadChecksum(payload));
    }

    buffer.putInt(~cmd);
    if (payload != null) buffer.put(payload);
    buffer.flip();

    return buffer;
  }

  public static ByteBuffer generateSyncHeader(String id, int arg) {
    ByteBuffer tmpBuffer = ByteBuffer.allocate(8);
    tmpBuffer.order(ByteOrder.LITTLE_ENDIAN);
    tmpBuffer.clear();
    tmpBuffer.put(id.getBytes(StandardCharsets.UTF_8));
    tmpBuffer.putInt(arg);
    tmpBuffer.flip();
    return tmpBuffer;
  }

  static int payloadChecksum(byte[] payload) {
    int checksum = 0;
    for (byte b : payload) checksum += (b & 0xFF);
    return checksum;
  }

  final static class AdbMessage {
    public int command;
    public int arg0;
    public int arg1;
    public int payloadLength;
    public int dataCheck;
    public int magic;
    public ByteBuffer payload = null;

    public static AdbMessage parseAdbMessage(AdbChannel channel) throws IOException, InterruptedException {
      return parseAdbMessage(channel, CONNECT_VERSION_MIN, MAX_ADB_PAYLOAD_LENGTH);
    }

    public static AdbMessage parseAdbMessage(AdbChannel channel, int protocolVersion,
                                             int maxPayloadLength)
            throws IOException, InterruptedException {
      AdbMessage msg = new AdbMessage();
      ByteBuffer buffer = channel.read(ADB_HEADER_LENGTH).order(ByteOrder.LITTLE_ENDIAN);

      if (buffer.remaining() != ADB_HEADER_LENGTH) {
        throw new IOException("incomplete ADB header: " + buffer.remaining());
      }

      msg.command = buffer.getInt();
      msg.arg0 = buffer.getInt();
      msg.arg1 = buffer.getInt();
      msg.payloadLength = buffer.getInt();
      msg.dataCheck = buffer.getInt();
      msg.magic = buffer.getInt();

      if (msg.magic != ~msg.command) {
        throw new IOException("invalid ADB command magic: command=0x"
                + Integer.toHexString(msg.command) + ", magic=0x"
                + Integer.toHexString(msg.magic));
      }
      if (!isKnownCommand(msg.command)) {
        throw new IOException("invalid ADB command: 0x" + Integer.toHexString(msg.command));
      }
      int payloadLimit = Math.min(MAX_ADB_PAYLOAD_LENGTH, maxPayloadLength);
      if (msg.payloadLength < 0 || msg.payloadLength > payloadLimit) {
        throw new IOException("invalid ADB payload length: " + msg.payloadLength
                + ", limit=" + payloadLimit);
      }
      if (msg.payloadLength == 0) {
        return msg;
      }

      msg.payload = channel.read(msg.payloadLength);
      if (msg.payload.remaining() != msg.payloadLength) {
        throw new IOException("incomplete ADB payload: expected=" + msg.payloadLength
                + ", actual=" + msg.payload.remaining());
      }
      boolean checksumRequired = protocolVersion <= CONNECT_VERSION_MIN
              || (msg.command == CMD_CNXN && msg.arg0 <= CONNECT_VERSION_MIN);
      if (checksumRequired) {
        byte[] payload = new byte[msg.payload.remaining()];
        msg.payload.duplicate().get(payload);
        int actualChecksum = payloadChecksum(payload);
        if (actualChecksum != msg.dataCheck) {
          throw new IOException("invalid ADB payload checksum: expected=" + msg.dataCheck
                  + ", actual=" + actualChecksum);
        }
      }

      return msg;
    }

    private static boolean isKnownCommand(int command) {
      return command == CMD_SYNC || command == CMD_CNXN || command == CMD_OPEN
              || command == CMD_OKAY || command == CMD_CLSE || command == CMD_WRTE
              || command == CMD_AUTH || command == CMD_STLS;
    }
  }
}
