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
  // MediaCodec 解码器实例，负责把压缩音频转成 PCM。
  public MediaCodec decodec;
  // 最终把 PCM 写入系统音频输出的播放器对象。
  public AudioTrack audioTrack;
  // 轻量音量增强器，让车机里导航提示音更容易听清。
  public LoudnessEnhancer loudnessEnhancer;
  // 异步回调：解码器有输入/输出缓冲区时会通过它通知我们。
  private final MediaCodec.Callback callback = new MediaCodec.Callback() {
    @Override
    public void onInputBufferAvailable(@NonNull MediaCodec mediaCodec, int inIndex) {
      // 先把可写入的输入缓冲区索引保存起来，等压缩音频到达时再配对。
      intputBufferQueue.offer(inIndex);
      // 输入缓冲区或数据一旦准备好，立刻尝试推进解码，减少排队时间。
      checkDecode();
    }

    @Override
    public void onOutputBufferAvailable(@NonNull MediaCodec mediaCodec, int outIndex, @NonNull MediaCodec.BufferInfo bufferInfo) {
      // 取出本次输出缓冲区里的 PCM 数据。
      ByteBuffer outputBuffer = decodec.getOutputBuffer(outIndex);
      // 只有缓冲区有效且这次确实有音频数据时，才写入 AudioTrack。
      if (outputBuffer != null && bufferInfo.size > 0) {
        // 输出缓冲区可能带有偏移，先移动到有效数据起点。
        outputBuffer.position(bufferInfo.offset);
        // 只允许读取本次帧的有效长度，避免把无效尾部一起写进去。
        outputBuffer.limit(bufferInfo.offset + bufferInfo.size);
        // 阻塞写入，保证音频顺序稳定，尽量减少首字被吞或播放抖动。
        audioTrack.write(outputBuffer, bufferInfo.size, AudioTrack.WRITE_BLOCKING);
      }
      // 当前输出缓冲区已经消费完毕，交还给解码器复用。
      decodec.releaseOutputBuffer(outIndex, false);
    }

    @Override
    public void onError(@NonNull MediaCodec mediaCodec, @NonNull MediaCodec.CodecException e) {
      // 这里暂不做额外处理，交给上层统一释放资源和处理重连。
    }

    @Override
    public void onOutputFormatChanged(@NonNull MediaCodec mediaCodec, @NonNull MediaFormat format) {
      // 输出格式在创建解码器时已经固定为 PCM，这里不需要额外处理。
    }
  };

  public AudioDecode(boolean useOpus, byte[] csd0, Handler handler) throws IOException {
    // 先创建解码器，把压缩音频流转成 PCM。
    setAudioDecodec(useOpus, csd0, handler);
    // 再创建 AudioTrack，让 PCM 可以尽快进入系统播放链路。
    setAudioTrack();
    // 最后创建音量增强器，避免车机环境下导航播报过小。
    setLoudnessEnhancer();
  }

  public void release() {
    try {
      // 先停止播放，避免后面继续消费旧数据。
      audioTrack.stop();
      // 释放播放器占用的底层音频资源。
      audioTrack.release();
      // 关闭音量增强器，避免残留音效处理链路。
      loudnessEnhancer.release();
      // 停止解码器，结束异步回调。
      decodec.stop();
      // 释放解码器实例。
      decodec.release();
    } catch (Exception ignored) {
      // 资源释放阶段允许忽略异常，防止退出流程被打断。
    }
  }

  public void playAudio(boolean play) {
    if (play) {
      // 恢复播放前先清空积压，避免切回播放时把旧音频一起播出来。
      audioTrack.flush();
      // 开始播放，让后续写入的 PCM 立即进入硬件链路。
      audioTrack.play();
    } else {
      // 暂停播放，但保留播放器对象，方便后续快速恢复。
      audioTrack.pause();
    }
  }

  // 压缩音频数据队列：保存从网络侧收到的 AAC / OPUS 包。
  private final LinkedBlockingQueue<byte[]> intputDataQueue = new LinkedBlockingQueue<>();
  // 解码器输入缓冲区队列：保存当前可写入的输入 buffer 下标。
  private final LinkedBlockingQueue<Integer> intputBufferQueue = new LinkedBlockingQueue<>();

  public void decodeIn(byte[] data) {
    // 收到一帧压缩音频后先入队，等有空闲输入缓冲区时再配对。
    intputDataQueue.offer(data);
    // 立即尝试喂给解码器，减少等待和排队延迟。
    checkDecode();
  }

  private synchronized void checkDecode() {
    // 任意一边还没准备好，就先等待下一次回调。
    if (intputDataQueue.isEmpty() || intputBufferQueue.isEmpty()) return;
    // 取出一个可用的输入缓冲区。
    Integer inIndex = intputBufferQueue.poll();
    // 取出与之配对的一帧压缩音频数据。
    byte[] data = intputDataQueue.poll();
    // 把压缩音频写进解码器输入缓冲区。
    decodec.getInputBuffer(inIndex).put(data);
    // 交回解码器开始解码，这里保持时间戳为 0。
    decodec.queueInputBuffer(inIndex, 0, data.length, 0, 0);
    // 如果还有成对的数据和缓冲区，就继续往前推进。
    checkDecode();
  }

  // 创建解码器。
  private void setAudioDecodec(boolean useOpus, byte[] csd0, Handler handler) throws IOException {
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
      ByteBuffer csd12ByteBuffer = ByteBuffer.wrap(new byte[]{0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00});
      decodecFormat.setByteBuffer("csd-1", csd12ByteBuffer);
      decodecFormat.setByteBuffer("csd-2", csd12ByteBuffer);
    }
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
    // 在系统建议的最小缓冲基础上再放大一点，兼顾低延迟和稳定性。
    int bufferSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT) * 2;
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
      // Android 6.0 及以上使用 Builder，便于精细控制播放属性。
      AudioTrack.Builder audioTrackBuild = new AudioTrack.Builder();
      // 声明这一路声音的用途，导航播报更贴近车机场景。
      AudioAttributes.Builder audioAttributesBulider = new AudioAttributes.Builder();
      audioAttributesBulider.setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE);
      audioAttributesBulider.setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN);
      // 允许用户通过设置覆盖 legacy stream type，兼容不同车机音频策略。
      int audioChannel = AppData.setting.getAudioChannel();
      if (audioChannel != 0) audioAttributesBulider.setLegacyStreamType(audioChannel);
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
