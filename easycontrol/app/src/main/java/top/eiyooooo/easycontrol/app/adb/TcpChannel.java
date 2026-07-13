package top.eiyooooo.easycontrol.app.adb;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
/**
 * 类 TcpChannel
 * 说明：该类负责 TcpChannel 相关功能。
 */

public class TcpChannel implements AdbChannel {
  private final Socket socket = new Socket();
  private final InputStream inputStream;
  private final OutputStream outputStream;

  public TcpChannel(String host, int port, boolean test) throws IOException {
    socket.setTcpNoDelay(true);
    socket.connect(new InetSocketAddress(host, port), 5000);
    if (test) socket.setSoTimeout(2200);
    inputStream = socket.getInputStream();
    outputStream = socket.getOutputStream();
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
      socket.close();
    } catch (Exception ignored) {
    }
  }
}
