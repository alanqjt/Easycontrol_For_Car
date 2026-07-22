package top.eiyooooo.easycontrol.app.adb;

import android.util.Log;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
/**
 * 类 TcpChannel
 * 说明：该类负责 TcpChannel 相关功能。
 */

public class TcpChannel implements AdbChannel {
  private static final String TRANSPORT_LOG_TAG = "EasycontrolTransport";
  private static final int TCP_BUFFER_SIZE = 1024 * 1024;

  private final Socket socket = new Socket();
  private final String host;
  private final int port;
  private volatile InputStream inputStream;
  private volatile OutputStream outputStream;
  private volatile SSLSocket tlsSocket;

  public TcpChannel(String host, int port, boolean test) throws IOException {
    this.host = host;
    this.port = port;
    socket.setReceiveBufferSize(TCP_BUFFER_SIZE);
    socket.setSendBufferSize(TCP_BUFFER_SIZE);
    socket.setTcpNoDelay(true);
    socket.setKeepAlive(true);
    socket.setPerformancePreferences(0, 2, 1);
    socket.connect(new InetSocketAddress(host, port), 5000);
    if (test) socket.setSoTimeout(2200);
    inputStream = socket.getInputStream();
    outputStream = socket.getOutputStream();
    Log.i(TRANSPORT_LOG_TAG, "ADB TCP connected address=" + host + ":" + port
            + ", receiveBuffer=" + socket.getReceiveBufferSize()
            + ", sendBuffer=" + socket.getSendBufferSize()
            + ", tcpNoDelay=" + socket.getTcpNoDelay());
  }

  /** Upgrades the already connected ADB socket after both peers exchange STLS. */
  public synchronized void upgradeToTls(AdbKeyPair keyPair) throws Exception {
    if (tlsSocket != null) return;
    SSLContext sslContext = AdbPairManager.createSslContext(keyPair);
    SSLSocket upgraded = (SSLSocket) sslContext.getSocketFactory()
            .createSocket(socket, host, port, true);
    upgraded.setUseClientMode(true);
    upgraded.startHandshake();
    inputStream = upgraded.getInputStream();
    outputStream = upgraded.getOutputStream();
    tlsSocket = upgraded;
    Log.i(TRANSPORT_LOG_TAG, "ADB TLS 1.3 established address=" + host + ":" + port
            + ", protocol=" + upgraded.getSession().getProtocol()
            + ", cipher=" + upgraded.getSession().getCipherSuite());
  }

  @Override
  public void write(ByteBuffer data) throws IOException {
    byte[] bytes = new byte[data.remaining()];
    data.get(bytes);
    outputStream.write(bytes);
  }

  @Override
  public void flush() throws IOException {
    outputStream.flush();
  }

  @Override
  public ByteBuffer read(int size) throws IOException {
    byte[] buffer = new byte[size];
    int bytesRead = 0;
    while (bytesRead < size) {
      int bytesRemaining = size - bytesRead;
      int read = inputStream.read(buffer, bytesRead, bytesRemaining);
      if (read == -1) throw new EOFException("ADB TCP connection closed after " + bytesRead + " of " + size + " bytes");
      bytesRead += read;
    }
    return ByteBuffer.wrap(buffer);
  }

  @Override
  public int available() throws IOException {
    return inputStream.available();
  }

  @Override
  public void close() {
    try {
      outputStream.close();
    } catch (Exception ignored) {
    }
    try {
      inputStream.close();
    } catch (Exception ignored) {
    }
    try {
      if (tlsSocket != null) tlsSocket.close();
    } catch (Exception ignored) {
    }
    try {
      socket.close();
    } catch (Exception ignored) {
    }
  }
}
