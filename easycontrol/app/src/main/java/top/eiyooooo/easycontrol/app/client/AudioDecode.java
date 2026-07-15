package top.eiyooooo.easycontrol.app.client;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.audiofx.LoudnessEnhancer;
import android.os.Build;
import android.os.Handler;
import android.util.Log;

import androidx.annotation.NonNull;
import top.eiyooooo.easycontrol.app.entity.AppData;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingQueue;
/**
 * 音频解码与播放链路。
 * 负责把服务端送来的 AAC / OPUS 数据解码成 PCM，
 * 再尽量低延迟地写入 AudioTrack 播放。
 * 这里的缓冲区处理方式会直接影响导航播报是否会“吃字”或延迟。
 */
public class AudioDecode {
  private static final String TAG = "EasycontrolAudio";
  public static final int ROLE_MEDIA = 0;
  public static final int ROLE_NAVIGATION = 1;
  private static final int AUDIO_LOG_INTERVAL_FRAMES = 100;
  private final Object codecLock = new Object();
  private final int audioRole;
  private long inputFrameCount;
  private long outputFrameCount;
  private volatile boolean released = false;
  // 媒体允许最多积压约 480ms；导航不丢包，避免播报开头或结尾缺字。
  private static final int MAX_PENDING_AUDIO_FRAMES = 24;
  // 车机端音频线程和视频解码经常抢资源，播放缓冲放大到 4 倍比 2 倍更稳。
  private static final int AUDIO_TRACK_BUFFER_MULTIPLIER = 4;
  // MediaCodec 解码器实例，负责把压缩音频转成 PCM。
  public MediaCodec decodec;
  // 最终把 PCM 写入系统音频输出的播放器对象。
  public AudioTrack audioTrack;
  // 轻量音量增强器，让车机里导航提示音更容易听清。
  public LoudnessEnhancer loudnessEnhancer;
  // 当前这路音频是否允许真正播放；非 owner 暂停时仍可解码，但会丢弃 PCM，避免堵住 AudioTrack。
  private volatile boolean shouldPlay = false;
  // 异步回调：解码器有输入/输出缓冲区时会通过它通知我们。
  private final MediaCodec.Callback callback = new MediaCodec.Callback() {
    @Override
    public void onInputBufferAvailable(@NonNull MediaCodec mediaCodec, int inIndex) {
      if (released) return;
      // 先把可写入的输入缓冲区索引保存起来，等压缩音频到达时再配对。
      intputBufferQueue.offer(inIndex);
      // 输入缓冲区或数据一旦准备好，立刻尝试推进解码，减少排队时间。
      checkDecode();
    }

    @Override
    public void onOutputBufferAvailable(@NonNull MediaCodec mediaCodec, int outIndex, @NonNull MediaCodec.BufferInfo bufferInfo) {
      synchronized (codecLock) {
        try {
          if (released) return;
          // 取出本次输出缓冲区里的 PCM 数据。
          ByteBuffer outputBuffer = mediaCodec.getOutputBuffer(outIndex);
          int writtenBytes = 0;
          if (outputBuffer != null && bufferInfo.size > 0) {
            int dataStart = bufferInfo.offset;
            long dataEndLong = (long) dataStart + bufferInfo.size;
            if (dataStart < 0 || dataEndLong > outputBuffer.capacity()) {
              throw new IllegalArgumentException("invalid PCM range offset=" + dataStart
                      + ", size=" + bufferInfo.size
                      + ", capacity=" + outputBuffer.capacity());
            }

            ByteBuffer pcm = outputBuffer.duplicate();
            pcm.clear();
            pcm.position(dataStart);
            pcm.limit((int) dataEndLong);
            outputFrameCount++;
            // 只有当前连接是音频 owner 时才写入 AudioTrack，非 owner 仍持续解码避免阻塞。
            if (shouldPlay) {
              // 导航必须完整写入，避免缓冲区暂满时丢掉播报结尾；媒体保持低延迟非阻塞写入。
              safeStartAudioTrack();
              int writeMode = audioRole == ROLE_NAVIGATION ? AudioTrack.WRITE_BLOCKING : AudioTrack.WRITE_NON_BLOCKING;
              while (pcm.hasRemaining()) {
                int writeSize = audioTrack.write(pcm, pcm.remaining(), writeMode);
                if (writeSize <= 0) break;
                writtenBytes += writeSize;
              }
            }
            if (shouldLogFrame(outputFrameCount)) {
              Log.i(TAG, "decoded role=" + roleName(audioRole)
                      + ", frame=" + outputFrameCount
                      + ", pcmBytes=" + bufferInfo.size
                      + ", writtenBytes=" + writtenBytes
                      + ", shouldPlay=" + shouldPlay
                      + ", trackState=" + audioTrack.getState()
                      + ", playState=" + audioTrack.getPlayState());
            }
          }
        } catch (RuntimeException e) {
          Log.e(TAG, "audio output failed role=" + roleName(audioRole), e);
        } finally {
          try {
            mediaCodec.releaseOutputBuffer(outIndex, false);
          } catch (RuntimeException e) {
            if (!released) Log.e(TAG, "release output failed role=" + roleName(audioRole), e);
          }
        }
      }
    }

    @Override
    public void onError(@NonNull MediaCodec mediaCodec, @NonNull MediaCodec.CodecException e) {
      String errorCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
              ? "0x" + Integer.toHexString(e.getErrorCode()) : "unavailable-before-api-23";
      Log.e(TAG, "decoder error role=" + roleName(audioRole)
              + ", codec=" + codecName(mediaCodec)
              + ", code=" + errorCode
              + ", recoverable=" + e.isRecoverable()
              + ", transient=" + e.isTransient()
              + ", diagnostic=" + e.getDiagnosticInfo(), e);
    }

    @Override
    public void onOutputFormatChanged(@NonNull MediaCodec mediaCodec, @NonNull MediaFormat format) {
      Log.i(TAG, "output format role=" + roleName(audioRole) + ", format=" + format);
    }
  };

  public AudioDecode(boolean useOpus, byte[] csd0, Handler handler, int audioRole) throws IOException {
    this.audioRole = audioRole == ROLE_NAVIGATION ? ROLE_NAVIGATION : ROLE_MEDIA;
    // 先创建解码器，把压缩音频流转成 PCM。
    setAudioDecodec(useOpus, csd0, handler);
    // 再创建 AudioTrack，让 PCM 可以尽快进入系统播放链路。
    setAudioTrack();
    // 最后创建音量增强器，避免车机环境下导航播报过小。
    if (this.audioRole == ROLE_NAVIGATION) {
      try {
        setLoudnessEnhancer();
      } catch (RuntimeException ignored) {
        // Some vehicle systems do not provide the LoudnessEnhancer effect.
        // Audio playback must continue without it instead of closing the client.
        loudnessEnhancer = null;
      }
    }
  }

  public void release() {
    synchronized (codecLock) {
      if (released) return;
      released = true;
      shouldPlay = false;
      intputDataQueue.clear();
      intputBufferQueue.clear();
    }
    try {
      if (audioTrack != null && audioTrack.getState() == AudioTrack.STATE_INITIALIZED) audioTrack.stop();
    } catch (Exception ignored) {
    }
    try {
      if (audioTrack != null) audioTrack.release();
    } catch (Exception ignored) {
    }
    try {
      if (loudnessEnhancer != null) loudnessEnhancer.release();
    } catch (Exception ignored) {
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

  public void playAudio(boolean play) {
    synchronized (codecLock) {
      if (released) return;
      shouldPlay = play;
      if (play) {
        // 导航轨提前进入 PLAYING 并在两次播报之间保持常驻，首帧到达时无需重新启动音频路由。
        if (audioRole == ROLE_NAVIGATION) safeStartAudioTrack();
        return;
      }
      if (audioTrack == null || audioTrack.getState() != AudioTrack.STATE_INITIALIZED) return;
      try {
        if (audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) audioTrack.pause();
        audioTrack.flush();
      } catch (RuntimeException ignored) {
        // AudioTrack may be invalidated asynchronously by the system audio policy.
      }
    }
  }

  private void safeStartAudioTrack() {
    synchronized (codecLock) {
      if (released || !shouldPlay || audioTrack == null || audioTrack.getState() != AudioTrack.STATE_INITIALIZED) return;
      try {
        if (audioTrack.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) audioTrack.play();
      } catch (IllegalStateException ignored) {
        // AudioTrack may be invalidated asynchronously by a vehicle audio policy change.
      }
    }
  }

  // 压缩音频数据队列：保存从网络侧收到的 AAC / OPUS 包。
  private final LinkedBlockingQueue<byte[]> intputDataQueue = new LinkedBlockingQueue<>();
  // 解码器输入缓冲区队列：保存当前可写入的输入 buffer 下标。
  private final LinkedBlockingQueue<Integer> intputBufferQueue = new LinkedBlockingQueue<>();

  public void decodeIn(byte[] data) {
    if (released || data == null || data.length == 0) return;
    if (audioRole != ROLE_NAVIGATION) {
      while (intputDataQueue.size() >= MAX_PENDING_AUDIO_FRAMES) {
        intputDataQueue.poll();
      }
    }
    inputFrameCount++;
    if (shouldLogFrame(inputFrameCount)) {
      Log.i(TAG, "compressed input role=" + roleName(audioRole)
              + ", frame=" + inputFrameCount
              + ", bytes=" + data.length
              + ", head=" + hexPrefix(data, 12));
    }
    // 收到一帧压缩音频后先入队，等有空闲输入缓冲区时再配对。
    intputDataQueue.offer(data);
    // 立即尝试喂给解码器，减少等待和排队延迟。
    checkDecode();
  }

  private void checkDecode() {
    synchronized (codecLock) {
      if (released) return;
      try {
        while (!intputDataQueue.isEmpty() && !intputBufferQueue.isEmpty()) {
          Integer inIndex = intputBufferQueue.poll();
          byte[] data = intputDataQueue.poll();
          if (inIndex == null || data == null) return;
          ByteBuffer inputBuffer = decodec.getInputBuffer(inIndex);
          if (inputBuffer == null) {
            decodec.queueInputBuffer(inIndex, 0, 0, 0, 0);
            continue;
          }
          inputBuffer.clear();
          if (inputBuffer.remaining() < data.length) {
            Log.e(TAG, "compressed frame too large role=" + roleName(audioRole)
                    + ", bytes=" + data.length
                    + ", capacity=" + inputBuffer.remaining());
            decodec.queueInputBuffer(inIndex, 0, 0, 0, 0);
            continue;
          }
          inputBuffer.put(data);
          decodec.queueInputBuffer(inIndex, 0, data.length, 0, 0);
        }
      } catch (RuntimeException e) {
        if (!released) Log.e(TAG, "queue input failed role=" + roleName(audioRole), e);
      }
    }
  }

  // 创建解码器。
  private void setAudioDecodec(boolean useOpus, byte[] csd0, Handler handler) throws IOException {
    if (csd0 == null || csd0.length == 0) throw new IOException("empty audio csd-0");
    if (useOpus && !startsWith(csd0, "OpusHead")) {
      throw new IOException("invalid Opus csd-0: bytes=" + csd0.length + ", head=" + hexPrefix(csd0, 12));
    }
    // 根据配置决定解码 AAC 还是 OPUS。
    String codecMime = useOpus ? MediaFormat.MIMETYPE_AUDIO_OPUS : MediaFormat.MIMETYPE_AUDIO_AAC;
    // 创建对应类型的解码器实例。
    decodec = MediaCodec.createDecoderByType(codecMime);
    // 统一使用 48kHz、双声道，和服务端编码参数保持一致。
    int sampleRate = 48000;
    int channelCount = 2;
    int bitRate = 96000;
    // 创建解码器输入格式。
    MediaFormat decodecFormat = MediaFormat.createAudioFormat(codecMime, sampleRate, channelCount);
    // 设置比特率，和服务端发送的编码参数对应。
    decodecFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
    // 载入音频标识头，供解码器识别流参数。
    decodecFormat.setByteBuffer("csd-0", ByteBuffer.wrap(csd0));
    if (useOpus) {
      // OPUS 需要额外的 csd-1 / csd-2 占位数据，这里按固定空字节补齐。
      decodecFormat.setByteBuffer("csd-1", ByteBuffer.wrap(new byte[8]));
      decodecFormat.setByteBuffer("csd-2", ByteBuffer.wrap(new byte[8]));
    }
    Log.i(TAG, "decoder configure role=" + roleName(audioRole)
            + ", codec=" + codecName(decodec)
            + ", mime=" + codecMime
            + ", csdBytes=" + csd0.length
            + ", csdHead=" + hexPrefix(csd0, 12));
    // 使用异步回调方式解码，尽量减少主线程等待。
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      decodec.setCallback(callback, handler);
    } else {
      decodec.setCallback(callback);
    }
    // 把格式和回调都配置给解码器。
    decodec.configure(decodecFormat, null, null, 0);
    // 启动解码器，开始接收输入数据。
    decodec.start();
  }

  // 创建 AudioTrack。
  private void setAudioTrack() {
    // 播放端同样使用 48kHz，避免解码后还要做额外重采样。
    int sampleRate = 48000;
    // 在系统建议的最小缓冲基础上再放大，优先解决车机端 AudioTrack underrun 导致的卡顿/吞字。
    int minBufferSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
    if (minBufferSize <= 0) minBufferSize = getFallbackAudioTrackBufferSize(sampleRate);
    int bufferSize = minBufferSize * AUDIO_TRACK_BUFFER_MULTIPLIER;
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
      // Android 6.0 及以上使用 Builder，便于精细控制播放属性。
      AudioTrack.Builder audioTrackBuild = new AudioTrack.Builder();
      // 先兼容用户指定的 legacy stream，再用明确 usage 覆盖用途，保证导航和媒体进入不同车机通道。
      AudioAttributes.Builder audioAttributesBulider = new AudioAttributes.Builder();
      int audioChannel = AppData.setting.getAudioChannel();
      if (audioChannel != 0) audioAttributesBulider.setLegacyStreamType(audioChannel);
      if (audioRole == ROLE_NAVIGATION) {
        audioAttributesBulider.setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE);
        audioAttributesBulider.setContentType(AudioAttributes.CONTENT_TYPE_SPEECH);
      } else {
        audioAttributesBulider.setUsage(AudioAttributes.USAGE_MEDIA);
        audioAttributesBulider.setContentType(AudioAttributes.CONTENT_TYPE_MUSIC);
      }
      // 音频格式必须和解码后的 PCM 一致，避免播放端再做额外转换。
      AudioFormat.Builder audioFormat = new AudioFormat.Builder();
      audioFormat.setEncoding(AudioFormat.ENCODING_PCM_16BIT);
      audioFormat.setSampleRate(sampleRate);
      audioFormat.setChannelMask(AudioFormat.CHANNEL_OUT_STEREO);
      // 把缓冲区、属性和格式都配置给 AudioTrack。
      audioTrackBuild.setBufferSizeInBytes(bufferSize);
      audioTrackBuild.setAudioAttributes(audioAttributesBulider.build());
      audioTrackBuild.setAudioFormat(audioFormat.build());
      // 使用流式模式，和持续输入的导航语音更匹配。
      audioTrackBuild.setTransferMode(AudioTrack.MODE_STREAM);
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        // 低延迟模式尽量缩短从手机到车机喇叭的播放链路。
        audioTrackBuild.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
      }
      // 创建最终的播放器实例。
      audioTrack = audioTrackBuild.build();
    } else {
      // 老版本系统退回到传统构造方式，保持兼容性。
      audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT, bufferSize, AudioTrack.MODE_STREAM);
    }
    Log.i(TAG, "AudioTrack ready role=" + roleName(audioRole)
            + ", state=" + audioTrack.getState()
            + ", sessionId=" + audioTrack.getAudioSessionId()
            + ", bufferBytes=" + bufferSize);
  }

  private static boolean shouldLogFrame(long frame) {
    return frame <= 3 || frame % AUDIO_LOG_INTERVAL_FRAMES == 0;
  }

  private static String roleName(int role) {
    return role == ROLE_NAVIGATION ? "navigation" : "media";
  }

  private static String codecName(MediaCodec codec) {
    try {
      return codec == null ? "null" : codec.getName();
    } catch (RuntimeException ignored) {
      return "unavailable";
    }
  }

  private static boolean startsWith(byte[] data, String value) {
    byte[] prefix = value.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    if (data.length < prefix.length) return false;
    for (int i = 0; i < prefix.length; i++) {
      if (data[i] != prefix[i]) return false;
    }
    return true;
  }

  private static String hexPrefix(byte[] data, int maxBytes) {
    int count = Math.min(data.length, maxBytes);
    StringBuilder result = new StringBuilder(count * 3);
    for (int i = 0; i < count; i++) {
      int value = data[i] & 0xff;
      if (i > 0) result.append(' ');
      if (value < 0x10) result.append('0');
      result.append(Integer.toHexString(value));
    }
    return result.toString();
  }

  private int getFallbackAudioTrackBufferSize(int sampleRate) {
    // 兜底 200ms PCM：48kHz * 2 声道 * 16bit * 0.2s，避免 getMinBufferSize 异常时创建失败。
    return sampleRate * 2 * 2 / 5;
  }

  // 创建音量增强器。
  private void setLoudnessEnhancer() {
    // 基于 AudioTrack 的 sessionId 创建增强器，只作用于当前这路音频。
    loudnessEnhancer = new LoudnessEnhancer(audioTrack.getAudioSessionId());
    // 设置一个偏温和的增益，帮助车机里导航提示音更容易听清。
    loudnessEnhancer.setTargetGain(2000);
    // 立即启用增强器，让后续播放直接生效。
    loudnessEnhancer.setEnabled(true);
  }
}
