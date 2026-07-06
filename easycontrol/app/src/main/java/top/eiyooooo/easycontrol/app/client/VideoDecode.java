package top.eiyooooo.easycontrol.app.client;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Handler;
import android.util.Pair;
import android.view.Surface;

import androidx.annotation.NonNull;

import top.eiyooooo.easycontrol.app.entity.AppData;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingQueue;
/**
 * 客户端视频解码器。
 * 负责把服务端发来的 H.264 / H.265 数据解码后渲染到 Surface。
 * 这里的输入队列和回调节奏，决定了画面是否流畅、首帧是否及时。
 */
public class VideoDecode {
  private static final int MAX_PENDING_VIDEO_FRAMES = 4;
  // MediaCodec 解码器实例。
  private MediaCodec decodec;
  // 异步回调，负责处理输入和输出缓冲区。
  private final MediaCodec.Callback callback = new MediaCodec.Callback() {
    @Override
    public void onInputBufferAvailable(MediaCodec mediaCodec, int inIndex) {
      // 有空闲输入缓冲区时，先记下来，再尝试喂数据。
      intputBufferQueue.offer(inIndex);
      checkDecode();
    }

    @Override
    public void onOutputBufferAvailable(@NonNull MediaCodec mediaCodec, int outIndex, @NonNull MediaCodec.BufferInfo bufferInfo) {
      if (AppData.setting.getAllowVideoFrameDrop()) {
        // 低延迟模式直接显示最新解码帧，配合输入侧丢弃旧帧。
        mediaCodec.releaseOutputBuffer(outIndex, true);
      } else {
        // 完整模式保持原始时间戳节奏，避免滑动/动画中间态被跳过。
        mediaCodec.releaseOutputBuffer(outIndex, bufferInfo.presentationTimeUs);
      }
    }

    @Override
    public void onError(@NonNull MediaCodec mediaCodec, @NonNull MediaCodec.CodecException e) {
    }

    @Override
    public void onOutputFormatChanged(MediaCodec mediaCodec, MediaFormat format) {
    }
  };

  public VideoDecode(Pair<Integer, Integer> videoSize, Surface surface, Pair<byte[], Long> csd0, Pair<byte[], Long> csd1, Handler handler) throws IOException {
    // 初始化解码器并绑定渲染 Surface。
    setVideoDecodec(videoSize, surface, csd0, csd1, handler);
  }

  public void release() {
    try {
      // 停止并释放解码器。
      decodec.stop();
      decodec.release();
    } catch (Exception ignored) {
    }
  }

  private final LinkedBlockingQueue<Pair<byte[], Long>> intputDataQueue = new LinkedBlockingQueue<>();
  private final LinkedBlockingQueue<Integer> intputBufferQueue = new LinkedBlockingQueue<>();

  public void decodeIn(byte[] data, long pts) {
    if (AppData.setting.getAllowVideoFrameDrop()) {
      while (intputDataQueue.size() >= MAX_PENDING_VIDEO_FRAMES) {
        intputDataQueue.poll();
      }
    }
    // 视频帧会带时间戳，保证播放顺序和同步。
    intputDataQueue.offer(new Pair<>(data, pts));
    checkDecode();
  }

  private synchronized void checkDecode() {
    // 输入数据和输入缓冲区都准备好后再推进。
    if (intputDataQueue.isEmpty() || intputBufferQueue.isEmpty()) return;
    Integer inIndex = intputBufferQueue.poll();
    Pair<byte[], Long> data = intputDataQueue.poll();
    // 把压缩视频帧塞进输入缓冲区。
    decodec.getInputBuffer(inIndex).put(data.first);
    // 提交给解码器，时间戳保持和服务端一致。
    decodec.queueInputBuffer(inIndex, 0, data.first.length, data.second, 0);
    checkDecode();
  }

  // 创建解码器。
  private void setVideoDecodec(Pair<Integer, Integer> videoSize, Surface surface, Pair<byte[], Long> csd0, Pair<byte[], Long> csd1, Handler handler) throws IOException {
    // 如果 csd1 为空，说明更可能是 H.265；否则按 H.264 处理。
    boolean isH265Support = csd1 == null;
    // 创建对应编码格式的解码器。
    String codecMime = isH265Support ? MediaFormat.MIMETYPE_VIDEO_HEVC : MediaFormat.MIMETYPE_VIDEO_AVC;
    decodec = MediaCodec.createDecoderByType(codecMime);
    MediaFormat decodecFormat = MediaFormat.createVideoFormat(codecMime, videoSize.first, videoSize.second);
    // 写入视频标识头，帮助解码器识别码流参数。
    decodecFormat.setByteBuffer("csd-0", ByteBuffer.wrap(csd0.first));
    if (!isH265Support) decodecFormat.setByteBuffer("csd-1", ByteBuffer.wrap(csd1.first));
    // 采用异步回调，降低主线程等待。
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      decodec.setCallback(callback, handler);
    } else decodec.setCallback(callback);
    // 绑定渲染 Surface。
    decodec.configure(decodecFormat, surface, null, 0);
    // 启动解码器。
    decodec.start();
    // 把首个 codec config 帧也喂进去，避免刚开始黑屏。
    decodeIn(csd0.first, csd0.second);
    if (!isH265Support) decodeIn(csd1.first, csd1.second);
  }

}
