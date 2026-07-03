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
     * 音频编码帮助类：
     * - 初始化音频编码器和音频捕获
     * - 将捕获到的 PCM 音频送入编码器进行编码
     * - 发送编码后的音频包到远端
     * 支持 AAC 与 OPUS（由 useOpus 决定）
     */
    public final class AudioEncode {
    // 编码器：负责将 PCM 转为编码格式（AAC/OPUS）
    private static MediaCodec encoder;
    // 音频捕获对象（AudioRecord 封装）
    private static AudioRecord audioCapture;
    // 是否使用 OPUS 编码（优先使用 user 设定且设备支持）
    private static boolean useOpus;

    /**
     * 初始化音频子系统：
     * - 根据配置与设备能力决定是否使用 OPUS
     * - 创建并启动编码器
     * - 初始化音频捕获
     * 返回是否成功初始化（若失败会发送空字节通知）
     */
    public static boolean init() throws IOException, ErrnoException {
        useOpus = Options.useOpus && Device.isEncoderSupport("opus");
        byte[] bytes = new byte[]{0};
        try {
            // 从安卓12开始支持音频
            if (!Options.isAudio) throw new Exception("audio not enabled");
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) throw new Exception("audio not supported");
            setAudioEncoder();
            encoder.start();
            audioCapture = AudioCapture.init();
        } catch (Exception e) {
            L.w(e);
            Scrcpy.writeMain(ByteBuffer.wrap(bytes));
            return false;
        }
        bytes[0] = 1;
        Scrcpy.writeMain(ByteBuffer.wrap(bytes));
        bytes[0] = (byte) (useOpus ? 1 : 0);
        Scrcpy.writeMain(ByteBuffer.wrap(bytes));
        return true;
    }

    /**
     * 配置并创建音频编码器（AAC 或 OPUS）。
     * 设置采样率、通道数、比特率和最大输入大小，若非 OPUS 则设置 AAC 配置。
     */
    private static void setAudioEncoder() throws IOException {
        String codecMime = useOpus ? MediaFormat.MIMETYPE_AUDIO_OPUS : MediaFormat.MIMETYPE_AUDIO_AAC;
        encoder = MediaCodec.createEncoderByType(codecMime);
        MediaFormat encoderFormat = MediaFormat.createAudioFormat(codecMime, AudioCapture.SAMPLE_RATE, AudioCapture.CHANNELS);
        encoderFormat.setInteger(MediaFormat.KEY_BIT_RATE, 96000);
        encoderFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, frameSize);
        if (!useOpus)
            encoderFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
    }

    private static final int frameSize = AudioCapture.millisToBytes(50);

    /**
     * 从音频捕获缓冲区读取一帧 PCM 数据并送入编码器的输入缓冲区。
     */
    public static void encodeIn() {
        try {
            int inIndex;
            do inIndex = encoder.dequeueInputBuffer(-1); while (inIndex < 0);
            ByteBuffer buffer = encoder.getInputBuffer(inIndex);
            if (buffer == null) return;
            int size = Math.min(buffer.remaining(), frameSize);
            audioCapture.read(buffer, size);
            encoder.queueInputBuffer(inIndex, 0, size, 0, 0);
        } catch (IllegalStateException e) {
            L.e("AudioEncode encodeIn error", e);
        }
    }

    private static final MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

    /**
     * 从编码器输出缓冲区取出编码后的数据，处理 OPUS 的 codec config，并通过 ControlPacket 发送音频事件。
     */
    public static void encodeOut() throws IOException, ErrnoException {
        try {
            // 找到已完成的输出缓冲区
            int outIndex;
            do outIndex = encoder.dequeueOutputBuffer(bufferInfo, -1); while (outIndex < 0);
            ByteBuffer buffer = encoder.getOutputBuffer(outIndex);
            if (buffer == null) return;
            if (useOpus) {
                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    buffer.getLong();
                    int size = (int) buffer.getLong();
                    buffer.limit(buffer.position() + size);
                }
            }
            ControlPacket.sendAudioEvent(buffer);
            encoder.releaseOutputBuffer(outIndex, false);
        } catch (IllegalStateException e) {
            L.e("AudioEncode encodeOut error", e);
        }
    }

    /**
     * 释放音频相关资源：停止并释放捕获与编码器。
     */
    public static void release() {
        try {
            audioCapture.stop();
            audioCapture.release();
            encoder.stop();
            encoder.release();
        } catch (Exception e) {
            L.e("AudioEncode release error", e);
        }
    }
}
