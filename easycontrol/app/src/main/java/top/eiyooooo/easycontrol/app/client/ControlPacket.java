package top.eiyooooo.easycontrol.app.client;

import android.content.ClipData;
import android.view.MotionEvent;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import top.eiyooooo.easycontrol.app.entity.AppData;
import top.eiyooooo.easycontrol.app.buffer.BufferStream;

/**
 * 客户端控制包编解码器。
 * 负责把触摸、按键、剪贴板、心跳和系统状态变化等动作，
 * 打成协议包发送给服务端。
 */
public class ControlPacket {
  // 外部传入的写出函数，最终把控制包送到连接里的 BufferStream。
  private final MyFunctionByteBuffer write;

  public ControlPacket(MyFunctionByteBuffer write) {
    this.write = write;
  }

  public byte[] readFrame(BufferStream bufferStream) throws IOException, InterruptedException {
    // 帧格式一般是：长度 + 数据，所以先读长度再读内容。
    return bufferStream.readByteArray(bufferStream.readInt()).array();
  }

  // 当前缓存的剪贴板文本，用于去重发送。
  public String nowClipboardText = "";

  public void checkClipBoard() {
    // 读取系统剪贴板，只有内容真正变化时才发送。
    ClipData clipBoard = AppData.clipBoard.getPrimaryClip();
    if (clipBoard != null && clipBoard.getItemCount() > 0) {
      String newClipBoardText = String.valueOf(clipBoard.getItemAt(0).getText());
      if (!Objects.equals(nowClipboardText, newClipBoardText)) {
        nowClipboardText = newClipBoardText;
        sendClipboardEvent();
      }
    }
  }

  // 发送触摸事件。
  public void sendTouchEvent(int action, int p, float x, float y, int offsetTime) {
    // 坐标越界时，强制按抬起处理，避免误触继续拖动。
    if (x < 0 || x > 1 || y < 0 || y > 1) {
      if (x < 0) x = 0;
      if (x > 1) x = 1;
      if (y < 0) y = 0;
      if (y > 1) y = 1;
      action = MotionEvent.ACTION_UP;
    }
    ByteBuffer byteBuffer = ByteBuffer.allocate(15);
    // 协议字节 1 表示触摸事件。
    byteBuffer.put((byte) 1);
    // action 是 MotionEvent 的触摸类型。
    byteBuffer.put((byte) action);
    // pointerId 用来区分多指。
    byteBuffer.put((byte) p);
    // 归一化坐标。
    byteBuffer.putFloat(x);
    byteBuffer.putFloat(y);
    // 事件时间偏移。
    byteBuffer.putInt(offsetTime);
    byteBuffer.flip();
    write.run(byteBuffer);
  }

  // 发送按键事件。
  public void sendKeyEvent(int key, int meta, int displayIdToInject) {
    // 协议字节 2 表示按键事件。
    ByteBuffer byteBuffer = ByteBuffer.allocate(13);
    byteBuffer.put((byte) 2);
    byteBuffer.putInt(key);
    byteBuffer.putInt(meta);
    byteBuffer.putInt(displayIdToInject);
    byteBuffer.flip();
    write.run(byteBuffer);
  }

  // 发送剪贴板事件。
  private void sendClipboardEvent() {
    byte[] tmpTextByte = nowClipboardText.getBytes(StandardCharsets.UTF_8);
    if (tmpTextByte.length == 0 || tmpTextByte.length > 5000) return;
    ByteBuffer byteBuffer = ByteBuffer.allocate(5 + tmpTextByte.length);
    byteBuffer.put((byte) 3);
    byteBuffer.putInt(tmpTextByte.length);
    byteBuffer.put(tmpTextByte);
    byteBuffer.flip();
    write.run(byteBuffer);
  }

  // 发送心跳包。
  public void sendKeepAlive() {
    write.run(ByteBuffer.wrap(new byte[]{4}));
  }

  // 发送配置变化事件。
  public void sendConfigChangedEvent(int mode) {
    ByteBuffer byteBuffer = ByteBuffer.allocate(5);
    byteBuffer.put((byte) 5);
    byteBuffer.putInt(mode);
    byteBuffer.flip();
    write.run(byteBuffer);
  }

  // 发送旋转请求事件。
  public void sendRotateEvent() {
    sendRotateEvent(-1);
  }

  public void sendRotateEvent(int rotation) {
    ByteBuffer byteBuffer = ByteBuffer.allocate(5);
    byteBuffer.put((byte) 6);
    byteBuffer.putInt(rotation);
    byteBuffer.flip();
    write.run(byteBuffer);
  }

  // 发送背光控制事件。
  public void sendLightEvent(int mode) {
    write.run(ByteBuffer.wrap(new byte[]{7, (byte) mode}));
  }

  // 发送电源键事件。
  public void sendPowerEvent() {
    write.run(ByteBuffer.wrap(new byte[]{8}));
  }

  // 发送夜间模式事件。
  public void sendNightModeEvent(int mode) {
      ByteBuffer byteBuffer = ByteBuffer.allocate(2);
      byteBuffer.put((byte) 9);
      byteBuffer.put((byte) mode);
      byteBuffer.flip();
      write.run(byteBuffer);
  }

  public interface MyFunctionByteBuffer {
    void run(ByteBuffer byteBuffer);
  }

}
