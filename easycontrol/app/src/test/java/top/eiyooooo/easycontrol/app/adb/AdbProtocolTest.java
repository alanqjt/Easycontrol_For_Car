package top.eiyooooo.easycontrol.app.adb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class AdbProtocolTest {
    @Test
    public void connectAdvertisesCurrentVersionAndTcpWindow() throws Exception {
        AdbProtocol.AdbMessage message = parse(
                AdbProtocol.generateConnect(AdbProtocol.CONNECT_MAXDATA_TCP),
                AdbProtocol.CONNECT_VERSION, AdbProtocol.CONNECT_MAXDATA_TCP);

        assertEquals(AdbProtocol.CMD_CNXN, message.command);
        assertEquals(AdbProtocol.CONNECT_VERSION_SKIP_CHECKSUM, message.arg0);
        assertEquals(1024 * 1024, message.arg1);
    }

    @Test
    public void usbCanAdvertiseLegacyVersionForVendorCompatibility() throws Exception {
        AdbProtocol.AdbMessage message = parse(
                AdbProtocol.generateConnect(AdbProtocol.CONNECT_VERSION_MIN,
                        AdbProtocol.CONNECT_MAXDATA),
                AdbProtocol.CONNECT_VERSION_MIN, AdbProtocol.CONNECT_MAXDATA);

        assertEquals(AdbProtocol.CONNECT_VERSION_MIN, message.arg0);
        assertEquals(AdbProtocol.CONNECT_MAXDATA, message.arg1);
    }

    @Test
    public void rejectsInvalidMagic() throws Exception {
        byte[] packet = toArray(AdbProtocol.generateConnect());
        ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).putInt(20, 0);

        expectIOException(packet, AdbProtocol.CONNECT_VERSION, AdbProtocol.CONNECT_MAXDATA);
    }

    @Test
    public void verifiesChecksumForLegacyProtocol() throws Exception {
        byte[] packet = toArray(AdbProtocol.generateAuth(
                AdbProtocol.AUTH_TYPE_TOKEN, new byte[]{1, 2, 3, 4}));
        packet[packet.length - 1] ^= 0x01;

        expectIOException(packet, AdbProtocol.CONNECT_VERSION_MIN, 1024);
    }

    @Test
    public void permitsSkippedChecksumForModernProtocol() throws Exception {
        byte[] packet = toArray(AdbProtocol.generateAuth(
                AdbProtocol.AUTH_TYPE_TOKEN, new byte[]{1, 2, 3, 4}));
        ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).putInt(16, 0);

        AdbProtocol.AdbMessage message = parse(ByteBuffer.wrap(packet),
                AdbProtocol.CONNECT_VERSION_SKIP_CHECKSUM, 1024);
        assertNotNull(message.payload);
        assertEquals(4, message.payloadLength);
    }

    @Test
    public void enforcesNegotiatedInboundPayloadLimit() throws Exception {
        byte[] packet = toArray(AdbProtocol.generateWrite(1, 2, new byte[8]));
        expectIOException(packet, AdbProtocol.CONNECT_VERSION_SKIP_CHECKSUM, 4);
    }

    @Test
    public void generatesStlsControlPacket() throws Exception {
        AdbProtocol.AdbMessage message = parse(AdbProtocol.generateStls(),
                AdbProtocol.CONNECT_VERSION_SKIP_CHECKSUM, 1024);
        assertEquals(AdbProtocol.CMD_STLS, message.command);
        assertEquals(AdbProtocol.STLS_VERSION, message.arg0);
        assertEquals(0, message.payloadLength);
    }

    @Test
    public void ignoresChecksumFieldForEmptyVendorControlPacket() throws Exception {
        byte[] packet = toArray(AdbProtocol.generateOkay(1, 2));
        ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).putInt(16, 1234);

        AdbProtocol.AdbMessage message = parse(ByteBuffer.wrap(packet),
                AdbProtocol.CONNECT_VERSION_MIN, 1024);
        assertEquals(AdbProtocol.CMD_OKAY, message.command);
    }

    private static void expectIOException(byte[] packet, int version, int maxPayload)
            throws Exception {
        try {
            parse(ByteBuffer.wrap(packet), version, maxPayload);
            fail("Expected IOException");
        } catch (IOException expected) {
            // Expected.
        }
    }

    private static AdbProtocol.AdbMessage parse(ByteBuffer packet, int version, int maxPayload)
            throws Exception {
        return AdbProtocol.AdbMessage.parseAdbMessage(
                new ByteBufferChannel(toArray(packet)), version, maxPayload);
    }

    private static byte[] toArray(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.duplicate().get(bytes);
        return bytes;
    }

    private static final class ByteBufferChannel implements AdbChannel {
        private final ByteBuffer source;

        ByteBufferChannel(byte[] bytes) {
            source = ByteBuffer.wrap(bytes);
        }

        @Override
        public void write(ByteBuffer data) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void flush() {
        }

        @Override
        public ByteBuffer read(int size) throws IOException {
            if (size < 0 || source.remaining() < size) throw new IOException("test EOF");
            byte[] bytes = new byte[size];
            source.get(bytes);
            return ByteBuffer.wrap(bytes);
        }

        @Override
        public int available() {
            return source.remaining();
        }

        @Override
        public void close() {
        }
    }
}
