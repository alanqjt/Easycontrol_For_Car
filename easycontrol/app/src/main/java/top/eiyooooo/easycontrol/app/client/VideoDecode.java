package top.eiyooooo.easycontrol.app.client;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
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
  private static final String TAG = "EasycontrolDisplay";
  private static final int MAX_PENDING_VIDEO_FRAMES = 4;
  private static final long STATS_INTERVAL_MS = 2_000L;
  private final Object codecLock = new Object();
  private final Object statsLock = new Object();
  private volatile boolean released = false;
  private String codecDescription = "pending";
  private long statsStartedAtMs = SystemClock.elapsedRealtime();
  private long statsInputFrames;
  private long statsInputBytes;
  private long statsRenderedFrames;
  private long statsDroppedFrames;
  private long statsRejectedFrames;
  private int statsMaxPendingFrames;
  // MediaCodec 解码器实例。
  private MediaCodec decodec;
  // 异步回调，负责处理输入和输出缓冲区。
  private final MediaCodec.Callback callback = new MediaCodec.Callback() {
    @Override
    public void onInputBufferAvailable(MediaCodec mediaCodec, int inIndex) {
      if (released) return;
      // 有空闲输入缓冲区时，先记下来，再尝试喂数据。
      intputBufferQueue.offer(inIndex);
      checkDecode();
    }

    @Override
    public void onOutputBufferAvailable(@NonNull MediaCodec mediaCodec, int outIndex, @NonNull MediaCodec.BufferInfo bufferInfo) {
      synchronized (codecLock) {
        if (released) return;
        try {
          // 手机 PTS 属于远端时间轴，不能直接当作车机的纳秒绝对渲染时间。
          // 完整模式只保证输入顺序，不丢帧；输出仍应收到即显示，避免错误排队造成延迟。
          mediaCodec.releaseOutputBuffer(outIndex, true);
          recordRenderedFrame();
        } catch (RuntimeException e) {
          Log.w(TAG, "release video output buffer failed", e);
        }
      }
      reportStatsIfNeeded();
    }

    @Override
    public void onError(@NonNull MediaCodec mediaCodec, @NonNull MediaCodec.CodecException e) {
      Log.e(TAG, "video decoder error, codec=" + codecDescription, e);
    }

    @Override
    public void onOutputFormatChanged(MediaCodec mediaCodec, MediaFormat format) {
      Log.i(TAG, "video decoder output format changed, format=" + format);
    }
  };

  public VideoDecode(Pair<Integer, Integer> videoSize, Surface surface, Pair<byte[], Long> csd0, Pair<byte[], Long> csd1, Handler handler) throws IOException {
    // 初始化解码器并绑定渲染 Surface。
    setVideoDecodec(videoSize, surface, csd0, csd1, handler);
  }

  public void release() {
    synchronized (codecLock) {
      if (released) return;
      released = true;
      intputDataQueue.clear();
      intputBufferQueue.clear();
    }
    try {
      if (decodec != null) decodec.stop();
    } catch (Exception ignored) {
    }
    try {
      if (decodec != null) decodec.release();
    } catch (Exception ignored) {
    }
  }

  private final LinkedBlockingQueue<Pair<byte[], Long>> intputDataQueue = new LinkedBlockingQueue<>();
  private final LinkedBlockingQueue<Integer> intputBufferQueue = new LinkedBlockingQueue<>();

  public void decodeIn(byte[] data, long pts) {
    if (released) return;
    int droppedFrames = 0;
    if (AppData.setting.getAllowVideoFrameDrop()) {
      while (intputDataQueue.size() >= MAX_PENDING_VIDEO_FRAMES) {
        if (intputDataQueue.poll() != null) droppedFrames++;
      }
    }
    // 视频帧会带时间戳，保证播放顺序和同步。
    intputDataQueue.offer(new Pair<>(data, pts));
    recordInputFrame(data.length, droppedFrames, intputDataQueue.size());
    checkDecode();
    reportStatsIfNeeded();
  }

  public int getPendingFrameCount() {
    return intputDataQueue.size();
  }

  private void checkDecode() {
    synchronized (codecLock) {
      if (released) return;
      try {
        while (!intputDataQueue.isEmpty() && !intputBufferQueue.isEmpty()) {
          Integer inIndex = intputBufferQueue.poll();
          Pair<byte[], Long> data = intputDataQueue.poll();
          if (inIndex == null || data == null) return;
          ByteBuffer inputBuffer = decodec.getInputBuffer(inIndex);
          if (inputBuffer != null) inputBuffer.clear();
          if (inputBuffer == null || inputBuffer.remaining() < data.first.length) {
            decodec.queueInputBuffer(inIndex, 0, 0, data.second, 0);
            recordRejectedFrame();
            continue;
          }
          inputBuffer.put(data.first);
          decodec.queueInputBuffer(inIndex, 0, data.first.length, data.second, 0);
        }
      } catch (RuntimeException ignored) {
        // Codec errors or shutdown may invalidate a buffer asynchronously.
      }
    }
  }

  // 创建解码器。
  private void setVideoDecodec(Pair<Integer, Integer> videoSize, Surface surface, Pair<byte[], Long> csd0, Pair<byte[], Long> csd1, Handler handler) throws IOException {
    // 如果 csd1 为空，说明更可能是 H.265；否则按 H.264 处理。
    boolean isH265Support = csd1 == null;
    // 创建对应编码格式的解码器。
    String codecMime = isH265Support ? MediaFormat.MIMETYPE_VIDEO_HEVC : MediaFormat.MIMETYPE_VIDEO_AVC;
    decodec = MediaCodec.createDecoderByType(codecMime);
    boolean hardwareAccelerated = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            && decodec.getCodecInfo().isHardwareAccelerated();
    boolean softwareOnly = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            && decodec.getCodecInfo().isSoftwareOnly();
    codecDescription = decodec.getName() + "/" + codecMime
            + ", hw=" + hardwareAccelerated + ", sw=" + softwareOnly;
    MediaFormat decodecFormat = MediaFormat.createVideoFormat(codecMime, videoSize.first, videoSize.second);
    boolean adaptivePlayback = decodec.getCodecInfo().getCapabilitiesForType(codecMime)
            .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_AdaptivePlayback);
    if (adaptivePlayback) {
      // 虚拟屏只会改变横竖比例，最长边保持不变；方形上限可覆盖分屏和全屏切换。
      int adaptiveEdge = Math.max(videoSize.first, videoSize.second);
      decodecFormat.setInteger(MediaFormat.KEY_MAX_WIDTH, adaptiveEdge);
      decodecFormat.setInteger(MediaFormat.KEY_MAX_HEIGHT, adaptiveEdge);
      Log.i(TAG, "video decoder adaptive playback enabled"
              + ", codec=" + decodec.getName()
              + ", initial=" + videoSize.first + "x" + videoSize.second
              + ", envelope=" + adaptiveEdge + "x" + adaptiveEdge);
    } else {
      Log.w(TAG, "video decoder does not support adaptive playback"
              + ", codec=" + decodec.getName()
              + ", initial=" + videoSize.first + "x" + videoSize.second);
    }
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
    Log.i(TAG, "video decoder started, codec=" + codecDescription
            + ", size=" + videoSize.first + "x" + videoSize.second
            + ", drop=" + AppData.setting.getAllowVideoFrameDrop());
    // 把首个 codec config 帧也喂进去，避免刚开始黑屏。
    decodeIn(csd0.first, csd0.second);
    if (!isH265Support) decodeIn(csd1.first, csd1.second);
  }

  private void recordInputFrame(int bytes, int droppedFrames, int pendingFrames) {
    synchronized (statsLock) {
      statsInputFrames++;
      statsInputBytes += bytes;
      statsDroppedFrames += droppedFrames;
      statsMaxPendingFrames = Math.max(statsMaxPendingFrames, pendingFrames);
    }
  }

  private void recordRenderedFrame() {
    synchronized (statsLock) {
      statsRenderedFrames++;
    }
  }

  private void recordRejectedFrame() {
    synchronized (statsLock) {
      statsRejectedFrames++;
    }
  }

  private void reportStatsIfNeeded() {
    long now = SystemClock.elapsedRealtime();
    long elapsedMs;
    long inputFrames;
    long inputBytes;
    long renderedFrames;
    long droppedFrames;
    long rejectedFrames;
    int maxPendingFrames;
    synchronized (statsLock) {
      elapsedMs = now - statsStartedAtMs;
      if (elapsedMs < STATS_INTERVAL_MS) return;
      inputFrames = statsInputFrames;
      inputBytes = statsInputBytes;
      renderedFrames = statsRenderedFrames;
      droppedFrames = statsDroppedFrames;
      rejectedFrames = statsRejectedFrames;
      maxPendingFrames = statsMaxPendingFrames;
      statsStartedAtMs = now;
      statsInputFrames = 0;
      statsInputBytes = 0;
      statsRenderedFrames = 0;
      statsDroppedFrames = 0;
      statsRejectedFrames = 0;
      statsMaxPendingFrames = intputDataQueue.size();
    }

    float inputFps = inputFrames * 1_000f / elapsedMs;
    float renderedFps = renderedFrames * 1_000f / elapsedMs;
    float inputMbps = inputBytes * 8f / elapsedMs / 1_000f;
    String message = String.format(java.util.Locale.US,
            "video decode stats codec=%s, in=%.1ffps/%.2fMbps, render=%.1ffps, pending=%d, maxPending=%d, dropped=%d, rejected=%d, mode=%s",
            codecDescription, inputFps, inputMbps, renderedFps, intputDataQueue.size(), maxPendingFrames,
            droppedFrames, rejectedFrames,
            AppData.setting.getAllowVideoFrameDrop() ? "drop-old" : "complete-immediate");
    if (maxPendingFrames >= 8 || rejectedFrames > 0) Log.w(TAG, message);
    else Log.i(TAG, message);
  }

}
