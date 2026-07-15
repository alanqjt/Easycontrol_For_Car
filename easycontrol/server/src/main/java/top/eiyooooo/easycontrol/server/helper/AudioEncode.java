package top.eiyooooo.easycontrol.server.helper;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;

import top.eiyooooo.easycontrol.server.Scrcpy;
import top.eiyooooo.easycontrol.server.entity.Device;
import top.eiyooooo.easycontrol.server.entity.Options;
import top.eiyooooo.easycontrol.server.utils.L;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 服务端音频编码协调器。
 * 应用流转使用单个 UID 管线；直接投屏使用导航、媒体两个互斥 AudioMix 管线。
 */
public final class AudioEncode {
    public static final int ROLE_MEDIA = AudioRoleResolver.ROLE_MEDIA;
    public static final int ROLE_NAVIGATION = AudioRoleResolver.ROLE_NAVIGATION;
    private static final int AUDIO_PROTOCOL_TAGGED = 2;
    private static final int FRAME_SIZE = AudioCapture.millisToBytes(20);
    private static final int AUDIO_LOG_INTERVAL_FRAMES = 100;
    private static final byte[] OPUS_HEADER_ID = {'A', 'O', 'P', 'U', 'S', 'H', 'D', 'R'};
    private static final List<AudioPipeline> pipelines = new ArrayList<>();
    private static boolean useOpus;
    private static boolean taggedAudio;
    private static Thread roleMonitorThread;

    private AudioEncode() {
    }

    /** 初始化全部采集和编码资源，并写出音频握手。 */
    public static synchronized boolean init() {
        useOpus = Options.useOpus && Device.isEncoderSupport("opus");
        taggedAudio = Options.audioProtocol >= AUDIO_PROTOCOL_TAGGED;
        try {
            if (!Options.isAudio) throw new IllegalStateException("audio not enabled");
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                throw new UnsupportedOperationException("audio not supported");
            }

            if (Options.audioSplit && taggedAudio) initDirectSplit();
            else initSinglePipeline();
            if (pipelines.isEmpty()) throw new IllegalStateException("no audio pipeline created");

            Scrcpy.writeMain(ByteBuffer.wrap(new byte[]{(byte) (taggedAudio ? 2 : 1), (byte) (useOpus ? 1 : 0)}));
            for (AudioPipeline pipeline : pipelines) pipeline.start();
            return true;
        } catch (Exception e) {
            L.w("Cannot initialize audio", e);
            release();
            try {
                Scrcpy.writeMain(ByteBuffer.wrap(new byte[]{0}));
            } catch (Exception writeError) {
                L.w("Cannot send disabled audio handshake", writeError);
            }
            return false;
        }
    }

    private static void initSinglePipeline() throws Exception {
        int role = Options.audioRole == ROLE_NAVIGATION ? ROLE_NAVIGATION : ROLE_MEDIA;
        AudioCapture.Session capture = AudioCapture.init(Options.audioUid, Options.audioFallback);
        pipelines.add(new AudioPipeline(role, capture));
        L.i("audio pipeline ready role=" + roleName(role) + ", uid=" + Options.audioUid);
    }

    private static void initDirectSplit() throws Exception {
        AudioRoleResolver.NavigationTargets targets = AudioRoleResolver.findInstalledNavigationApps();
        try {
            AudioCapture.SplitSession split = AudioCapture.initSplit(targets.uids);
            try {
                pipelines.add(new AudioPipeline(ROLE_MEDIA, split.media));
                pipelines.add(new AudioPipeline(ROLE_NAVIGATION, split.navigation));
            } catch (Exception e) {
                split.release();
                throw e;
            }
            roleMonitorThread = AudioRoleResolver.startActivePlaybackMonitor(targets.uids);
            L.i("direct audio pipelines ready: media + navigation");
        } catch (Exception splitError) {
            L.w("Direct split audio unavailable, falling back to mixed media", splitError);
            releasePipelines();
            AudioCapture.Session fallback = AudioCapture.init(-1, true);
            pipelines.add(new AudioPipeline(ROLE_MEDIA, fallback));
            L.i("direct audio fallback pipeline ready role=media");
        }
    }

    public static synchronized void release() {
        if (roleMonitorThread != null) {
            roleMonitorThread.interrupt();
            roleMonitorThread = null;
        }
        releasePipelines();
    }

    private static void releasePipelines() {
        for (AudioPipeline pipeline : pipelines) pipeline.release();
        pipelines.clear();
    }

    private static String roleName(int role) {
        return role == ROLE_NAVIGATION ? "navigation" : "media";
    }

    private static final class AudioPipeline {
        private final int role;
        private final AudioCapture.Session capture;
        private final MediaCodec encoder;
        private final MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        private Thread inputThread;
        private Thread outputThread;
        private long inputPresentationTimeUs;
        private long inputFrameCount;
        private long outputFrameCount;
        private volatile boolean released;

        private AudioPipeline(int role, AudioCapture.Session capture) throws Exception {
            this.role = role == ROLE_NAVIGATION ? ROLE_NAVIGATION : ROLE_MEDIA;
            this.capture = capture;
            MediaCodec pendingEncoder = null;
            try {
                String codecMime = useOpus ? MediaFormat.MIMETYPE_AUDIO_OPUS : MediaFormat.MIMETYPE_AUDIO_AAC;
                pendingEncoder = MediaCodec.createEncoderByType(codecMime);
                boolean hardwareAccelerated = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                        && pendingEncoder.getCodecInfo().isHardwareAccelerated();
                boolean softwareOnly = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                        && pendingEncoder.getCodecInfo().isSoftwareOnly();
                L.i("audio encoder role=" + roleName(this.role)
                        + ", name=" + pendingEncoder.getName()
                        + ", mime=" + codecMime
                        + ", hw=" + hardwareAccelerated
                        + ", sw=" + softwareOnly);

                MediaFormat format = MediaFormat.createAudioFormat(codecMime, AudioCapture.SAMPLE_RATE, AudioCapture.CHANNELS);
                format.setInteger(MediaFormat.KEY_BIT_RATE, 96000);
                format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAME_SIZE);
                if (!useOpus) {
                    format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                }
                pendingEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                pendingEncoder.start();
                encoder = pendingEncoder;
            } catch (Exception e) {
                capture.release();
                if (pendingEncoder != null) {
                    try {
                        pendingEncoder.release();
                    } catch (Exception ignored) {
                    }
                }
                throw e;
            }
        }

        private void start() {
            inputThread = new Thread(this::encodeInputLoop, "easycontrol_audio_in_" + roleName(role));
            outputThread = new Thread(this::encodeOutputLoop, "easycontrol_audio_out_" + roleName(role));
            inputThread.setPriority(Thread.MAX_PRIORITY);
            outputThread.setPriority(Thread.MAX_PRIORITY);
            inputThread.start();
            outputThread.start();
        }

        private void encodeInputLoop() {
            while (!released && !Thread.currentThread().isInterrupted()) {
                try {
                    int inputIndex = encoder.dequeueInputBuffer(10_000);
                    if (inputIndex < 0) continue;
                    ByteBuffer buffer = encoder.getInputBuffer(inputIndex);
                    if (buffer == null) {
                        encoder.queueInputBuffer(inputIndex, 0, 0, inputPresentationTimeUs, 0);
                        continue;
                    }
                    buffer.clear();
                    int requested = Math.min(buffer.remaining(), FRAME_SIZE);
                    int read = capture.read(buffer, requested);
                    if (read <= 0) {
                        encoder.queueInputBuffer(inputIndex, 0, 0, inputPresentationTimeUs, 0);
                        continue;
                    }
                    inputFrameCount++;
                    if (shouldLogFrame(inputFrameCount)) {
                        L.i("audio capture role=" + roleName(role)
                                + ", frame=" + inputFrameCount
                                + ", bytes=" + read
                                + ", pcmPeak=" + calculatePcmPeak(buffer, read));
                    }
                    encoder.queueInputBuffer(inputIndex, 0, read, inputPresentationTimeUs, 0);
                    inputPresentationTimeUs += bytesToMicros(read);
                } catch (IllegalStateException e) {
                    if (!released) L.e("Audio input failed role=" + roleName(role), e);
                    return;
                }
            }
        }

        private void encodeOutputLoop() {
            while (!released && !Thread.currentThread().isInterrupted()) {
                int outputIndex = -1;
                try {
                    outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 10_000);
                    if (outputIndex < 0) continue;
                    ByteBuffer output = encoder.getOutputBuffer(outputIndex);
                    if (output == null) continue;

                    if (bufferInfo.size <= 0) {
                        if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            L.w("empty audio codec config role=" + roleName(role));
                        }
                        continue;
                    }

                    int dataStart = bufferInfo.offset;
                    long dataEndLong = (long) dataStart + bufferInfo.size;
                    if (dataStart < 0 || dataEndLong > output.capacity()) {
                        throw new IOException("Invalid audio output range role=" + roleName(role)
                                + ", offset=" + dataStart
                                + ", size=" + bufferInfo.size
                                + ", capacity=" + output.capacity());
                    }

                    // duplicate() 会把字节序重置为 BIG_ENDIAN；先只用它限定本帧有效数据范围。
                    ByteBuffer data = output.duplicate();
                    data.clear();
                    data.position(dataStart);
                    data.limit((int) dataEndLong);
                    int rawSize = data.remaining();
                    boolean codecConfig = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                    if (useOpus && codecConfig) {
                        data = extractOpusHeader(data, role);
                    }

                    outputFrameCount++;
                    if (codecConfig || shouldLogFrame(outputFrameCount)) {
                        L.i("audio encoded role=" + roleName(role)
                                + ", frame=" + outputFrameCount
                                + ", config=" + codecConfig
                                + ", rawBytes=" + rawSize
                                + ", sendBytes=" + data.remaining()
                                + ", head=" + hexPrefix(data, 12));
                    }
                    ControlPacket.sendAudioEvent(role, taggedAudio, data);
                } catch (Exception e) {
                    if (!released) Scrcpy.errorClose(e);
                    return;
                } finally {
                    if (outputIndex >= 0) {
                        try {
                            encoder.releaseOutputBuffer(outputIndex, false);
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        }

        private long bytesToMicros(int bytes) {
            return bytes * 1_000_000L
                    / (AudioCapture.SAMPLE_RATE * AudioCapture.CHANNELS * AudioCapture.BYTES_PER_SAMPLE);
        }

        private ByteBuffer extractOpusHeader(ByteBuffer source, int frameRole) throws IOException {
            if (source.remaining() < 16) {
                throw new IOException("Not enough data in Opus config role=" + roleName(frameRole)
                        + ", bytes=" + source.remaining());
            }

            byte[] headerId = new byte[OPUS_HEADER_ID.length];
            source.get(headerId);
            if (!Arrays.equals(headerId, OPUS_HEADER_ID)) {
                throw new IOException("AOPUSHDR not found role=" + roleName(frameRole)
                        + ", head=" + hexPrefix(source, 16));
            }

            // Android Opus CSD 的段长度使用本机字节序；Android 设备通常为 LITTLE_ENDIAN。
            source.order(ByteOrder.nativeOrder());
            long headerSizeLong = source.getLong();
            if (headerSizeLong <= 0 || headerSizeLong > Integer.MAX_VALUE) {
                throw new IOException("Invalid Opus header size role=" + roleName(frameRole)
                        + ", size=" + headerSizeLong);
            }
            int headerSize = (int) headerSizeLong;
            if (headerSize > source.remaining()) {
                throw new IOException("Incomplete Opus header role=" + roleName(frameRole)
                        + ", size=" + headerSize
                        + ", remaining=" + source.remaining());
            }
            source.limit(source.position() + headerSize);
            return source;
        }

        private int calculatePcmPeak(ByteBuffer buffer, int size) {
            int peak = 0;
            int sampleBytes = size - (size & 1);
            for (int i = 0; i < sampleBytes; i += 2) {
                int sample = (buffer.get(i) & 0xff) | (buffer.get(i + 1) << 8);
                peak = Math.max(peak, Math.abs((short) sample));
            }
            return peak;
        }

        private boolean shouldLogFrame(long frame) {
            return frame <= 3 || frame % AUDIO_LOG_INTERVAL_FRAMES == 0;
        }

        private String hexPrefix(ByteBuffer buffer, int maxBytes) {
            ByteBuffer preview = buffer.duplicate();
            int count = Math.min(preview.remaining(), maxBytes);
            StringBuilder result = new StringBuilder(count * 3);
            for (int i = 0; i < count; i++) {
                int value = preview.get() & 0xff;
                if (i > 0) result.append(' ');
                if (value < 0x10) result.append('0');
                result.append(Integer.toHexString(value));
            }
            return result.toString();
        }

        private void release() {
            if (released) return;
            released = true;
            if (inputThread != null) inputThread.interrupt();
            if (outputThread != null) outputThread.interrupt();
            capture.release();
            try {
                encoder.stop();
            } catch (Exception ignored) {
            }
            try {
                encoder.release();
            } catch (Exception ignored) {
            }
        }
    }
}
