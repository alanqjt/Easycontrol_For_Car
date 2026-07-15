package top.eiyooooo.easycontrol.app.adb;

import java.io.IOException;
import java.nio.ByteBuffer;
/**
 * 类 AdbChannel
 * 说明：该类负责 AdbChannel 相关功能。
 */

public interface AdbChannel {
  void write(ByteBuffer data) throws IOException, InterruptedException;

  void flush() throws IOException;

  ByteBuffer read(int size) throws IOException, InterruptedException;

  int available() throws IOException;

  void close();

}
