package top.eiyooooo.easycontrol.server.helper;

import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.system.ErrnoException;
import top.eiyooooo.easycontrol.server.Scrcpy;
import top.eiyooooo.easycontrol.server.entity.Device;
import top.eiyooooo.easycontrol.server.entity.Options;
import top.eiyooooo.easycontrol.server.utils.L;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * 服务器侧音频编码链路。
 * 负责从 AudioRecord 读取 PCM，编码为 AAC / OPUS 后发给客户端。
 * 这里的帧大小、编码器配置和 OPUS 包处理，都会直接影响播放延迟和首字完整性。
 */
public final class AudioEncode {
    // 编码器实例：负责把 PCM 转成 AAC / OPUS。
    private static MediaCodec encoder;
    // 音频捕获对象：对 AudioRecord 做了一层封装。
    private static AudioRecord audioCapture;
    // 是否使用 OPUS 编码，取决于用户配置和设备编码器能力。
    private static boolean useOpus;
    private static long inputPresentationTimeUs;

    /**
     * 初始化音频子系统：
     * 先判断是否启用音频和设备是否支持，再创建编码器和音频捕获。
     * 初始化成功后会向主链路回写状态字节，通知客户端准备就绪。
     */
    public static boolean init() throws IOException, ErrnoException {
        // 只有用户开启且设备确实支持时，才启用 OPUS。
        useOpus = Options.useOpus && Device.isEncoderSupport("opus");
        // 复用的状态字节：0 表示失败，1 表示音频已就绪，第二个字节表示编码格式。
        byte[] bytes = new byte[]{0};
        try {
            // 从 Android 12 开始才允许这条音频链路工作。
            if (!Options.isAudio) throw new Exception("audio not enabled");
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) throw new Exception("audio not supported");
            // 创建并配置编码器。
            setAudioEncoder();
            inputPresentationTimeUs = 0;
            // 启动编码器，让它开始接收 PCM 输入。
            encoder.start();
            // 初始化音频采集端。
            audioCapture = AudioCapture.init();
        } catch (Exception e) {
            // 任何初始化失败都记录一下，方便排查车机兼容性问题。
            L.w(e);
            // 失败时回写 0，告诉主流程音频没有起来。
            Scrcpy.writeMain(ByteBuffer.wrap(bytes));
            return false;
        }
        // 第一个成功标记：音频链路已启动。
        bytes[0] = 1;
        Scrcpy.writeMain(ByteBuffer.wrap(bytes));
        // 第二个字节告诉客户端本次使用的是 AAC 还是 OPUS。
        bytes[0] = (byte) (useOpus ? 1 : 0);
        Scrcpy.writeMain(ByteBuffer.wrap(bytes));
        return true;
    }

    /**
     * 配置并创建音频编码器（AAC 或 OPUS）。
     * 采样率、通道数、比特率和输入大小都要和解码端保持一致。
     */
    private static void setAudioEncoder() throws IOException {
        // 根据编码模式选择对应的 MIME 类型。
        String codecMime = useOpus ? MediaFormat.MIMETYPE_AUDIO_OPUS : MediaFormat.MIMETYPE_AUDIO_AAC;
        // 创建指定类型的编码器实例。
        encoder = MediaCodec.createEncoderByType(codecMime);
        // 使用和采集端一致的参数创建输入格式。
        MediaFormat encoderFormat = MediaFormat.createAudioFormat(codecMime, AudioCapture.SAMPLE_RATE, AudioCapture.CHANNELS);
        // 设定目标比特率，保证语音清晰度和带宽之间的平衡。
        encoderFormat.setInteger(MediaFormat.KEY_BIT_RATE, 96000);
        // 限制单次输入大小，和下面的 frameSize 保持一致。
        encoderFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, frameSize);
        // AAC 场景下指定 LC profile，兼容性更稳。
        if (!useOpus)
            encoderFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        // 把格式配置给编码器，并声明这是编码方向。
        encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
    }

    // 按 20ms 切一帧，降低导航播报从手机到车机的端到端延迟。
    private static final int frameSize = AudioCapture.millisToBytes(20);

    /**
     * 从音频捕获缓冲区读取一帧 PCM 数据，并送入编码器输入缓冲区。
     */
    public static void encodeIn() {
        try {
            // 阻塞等待一个可用的输入缓冲区，避免忙轮询。
            int inIndex;
            do inIndex = encoder.dequeueInputBuffer(-1); while (inIndex < 0);
            // 取出该输入缓冲区，准备写入 PCM。
            ByteBuffer buffer = encoder.getInputBuffer(inIndex);
            if (buffer == null) {
                encoder.queueInputBuffer(inIndex, 0, 0, inputPresentationTimeUs, 0);
                return;
            }
            buffer.clear();
            // 读取量不能超过缓冲区剩余空间，也不能超过我们定义的单帧大小。
            int size = Math.min(buffer.remaining(), frameSize);
            // 从采集端读取 PCM 到编码器输入缓冲区。
            int readSize = audioCapture.read(buffer, size);
            if (readSize <= 0) {
                encoder.queueInputBuffer(inIndex, 0, 0, inputPresentationTimeUs, 0);
                return;
            }
            // 把这一帧提交给编码器，后续由 encodeOut 取出编码结果。
            encoder.queueInputBuffer(inIndex, 0, readSize, inputPresentationTimeUs, 0);
            inputPresentationTimeUs += bytesToMicros(readSize);
        } catch (IllegalStateException e) {
            L.e("AudioEncode encodeIn error", e);
        }
    }

    private static long bytesToMicros(int bytes) {
        return bytes * 1_000_000L / (AudioCapture.SAMPLE_RATE * AudioCapture.CHANNELS * AudioCapture.BYTES_PER_SAMPLE);
    }

    private static final MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

    /**
     * 从编码器输出缓冲区取出编码结果，处理 OPUS 的 codec config 后发送到客户端。
     */
    public static void encodeOut() throws IOException, ErrnoException {
        try {
            // 阻塞等待一个已经完成编码的输出缓冲区。
            int outIndex;
            do outIndex = encoder.dequeueOutputBuffer(bufferInfo, -1); while (outIndex < 0);
            // 取出编码后的数据。
            ByteBuffer buffer = encoder.getOutputBuffer(outIndex);
            if (buffer == null) return;
            if (useOpus) {
                // OPUS 的首个特殊包可能是 codec config，不是普通音频帧。
                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    // 跳过前面的头部字段，读取真正的配置长度。
                    buffer.getLong();
                    int size = (int) buffer.getLong();
                    // 把 limit 收紧到有效配置数据末尾。
                    buffer.limit(buffer.position() + size);
                }
            }
            // 直接发送编码结果，不再因为包太小就丢弃，避免首字或短包被误吃掉。
            ControlPacket.sendAudioEvent(buffer);
            // 当前输出缓冲区已经处理完毕，交回编码器复用。
            encoder.releaseOutputBuffer(outIndex, false);
        } catch (IllegalStateException e) {
            L.e("AudioEncode encodeOut error", e);
        }
    }

    /**
     * 释放音频相关资源：先停采集，再停编码器。
     */
    public static void release() {
        try {
            // 停止并释放采集端。
            audioCapture.stop();
            audioCapture.release();
            // 停止并释放编码器。
            encoder.stop();
            encoder.release();
        } catch (Exception e) {
            L.e("AudioEncode release error", e);
        }
    }
}
