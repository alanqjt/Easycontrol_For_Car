package top.eiyooooo.easycontrol.app.adb;
/**
 * 类 AdbBase64
 * 说明：该类负责 AdbBase64 相关功能。
 */

public interface AdbBase64 {
  String encodeToString(byte[] data);

  byte[] decode(byte[] data);
}
