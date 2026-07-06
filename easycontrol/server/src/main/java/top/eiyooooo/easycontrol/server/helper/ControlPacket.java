package top.eiyooooo.easycontrol.server.helper;

import android.system.ErrnoException;
import top.eiyooooo.easycontrol.server.Scrcpy;
import top.eiyooooo.easycontrol.server.entity.Device;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
/**
 * 控制和媒体数据打包器。
 * 这个类负责把触摸、按键、剪贴板、心跳、音视频帧等内容
 * 按协议打成二进制包，再交给 Scrcpy 的 socket 发送出去。
 */
public final class ControlPacket {

    // 回写函数，由外部注入，决定最终把控制包写到哪里。
    public static void sendVideoEvent(long pts, ByteBuffer data) throws IOException, ErrnoException {
        // 视频包格式：长度 + 视频数据 + 时间戳。
        int size = data.remaining();
        if (size < 0) return;
        ByteBuffer byteBuffer = ByteBuffer.allocate(12 + size);
        byteBuffer.putInt(size);
        byteBuffer.put(data);
        byteBuffer.putLong(pts);
        byteBuffer.flip();
        Scrcpy.writeVideo(byteBuffer);
    }

    public static void sendAudioEvent(ByteBuffer data) throws IOException, ErrnoException {
        // 音频包格式：类型标记 + 长度 + 数据。
        int size = data.remaining();
        if (size < 0) return;
        ByteBuffer byteBuffer = ByteBuffer.allocate(5 + size);
        byteBuffer.put((byte) 2);
        byteBuffer.putInt(size);
        byteBuffer.put(data);
        byteBuffer.flip();
        Scrcpy.writeMain(byteBuffer);
    }

    public static void sendClipboardEvent(String newClipboardText) {
        // 剪贴板内容过小或过大都不发，避免无意义同步。
        byte[] tmpTextByte = newClipboardText.getBytes(StandardCharsets.UTF_8);
        if (tmpTextByte.length == 0 || tmpTextByte.length > 5000) return;
        ByteBuffer byteBuffer = ByteBuffer.allocate(5 + tmpTextByte.length);
        byteBuffer.put((byte) 3);
        byteBuffer.putInt(tmpTextByte.length);
        byteBuffer.put(tmpTextByte);
        byteBuffer.flip();
        try {
            Scrcpy.writeMain(byteBuffer);
        } catch (IOException | ErrnoException e) {
            Scrcpy.errorClose(e);
        }
    }

    public static void sendVideoSizeEvent() throws IOException, ErrnoException {
        // 通知客户端视频尺寸，方便它创建对应大小的解码器。
        ByteBuffer byteBuffer = ByteBuffer.allocate(9);
        byteBuffer.put((byte) 4);
        byteBuffer.putInt(Device.videoSize.first);
        byteBuffer.putInt(Device.videoSize.second);
        byteBuffer.flip();
        Scrcpy.writeMain(byteBuffer);
    }

    public static void sendKeepAlive() throws IOException, ErrnoException {
        // 心跳包只有一个字节。
        Scrcpy.writeMain(ByteBuffer.wrap(new byte[]{5}));
    }

    public static void handleTouchEvent() throws IOException {
        // 读取触摸事件参数并转发给 Device。
        int action = Scrcpy.inputStream.readByte();
        int pointerId = Scrcpy.inputStream.readByte();
        float x = Scrcpy.inputStream.readFloat();
        float y = Scrcpy.inputStream.readFloat();
        int offsetTime = Scrcpy.inputStream.readInt();
        Device.touchEvent(action, x, y, pointerId, offsetTime);
    }

    public static void handleKeyEvent() throws IOException {
        // 读取按键码、元修饰键和目标显示器。
        int keyCode = Scrcpy.inputStream.readInt();
        int meta = Scrcpy.inputStream.readInt();
        int displayIdToInject = Scrcpy.inputStream.readInt();
        if (displayIdToInject == -1)
            Device.keyEvent(keyCode, meta, Device.displayId);
        else
            Device.keyEvent(keyCode, meta, displayIdToInject);
    }

    public static void handleClipboardEvent() throws IOException {
        // 读取客户端传来的文本剪贴板内容。
        int size = Scrcpy.inputStream.readInt();
        byte[] textBytes = new byte[size];
        Scrcpy.inputStream.readFully(textBytes);
        String text = new String(textBytes, StandardCharsets.UTF_8);
        Device.setClipboardText(text);
    }

}
