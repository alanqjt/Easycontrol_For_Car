package top.eiyooooo.easycontrol.server.helper;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.os.SystemClock;

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
import java.util.Locale;

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
    private static final long AUDIO_GAP_WARNING_MS = 80;
    private static final long AUDIO_SEND_WARNING_MS = 40;
    private static final long AUDIO_WARNING_RATE_LIMIT_MS = 1000;
    private static final int PCM_CLIP_THRESHOLD = 32760;
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
        private final Object sourceStatsLock = new Object();
        private final Object transportStatsLock = new Object();
        private Thread inputThread;
        private Thread outputThread;
        private long inputPresentationTimeUs;
        private long inputFrameCount;
        private long outputFrameCount;
        private long lastCaptureStartedNs;
        private long lastEncodedOutputNs;
        private long sourceWindowStartedMs = SystemClock.elapsedRealtime();
        private long transportWindowStartedMs = SystemClock.elapsedRealtime();
        private long sourceFrames;
        private long sourceBytes;
        private long sourceSamples;
        private long sourceClippedSamples;
        private long sourceSquareSum;
        private long sourceMaxGapMs;
        private long sourceMaxReadMs;
        private long sourceShortReads;
        private long sourceEmptyReads;
        private int sourcePeak;
        private long transportFrames;
        private long transportBytes;
        private long transportMaxOutputGapMs;
        private long transportMaxSendMs;
        private long transportSlowSends;
        private long lastCaptureWarningMs;
        private long lastOutputWarningMs;
        private long lastSendWarningMs;
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
                    long readStartedNs = SystemClock.elapsedRealtimeNanos();
                    long captureGapMs = lastCaptureStartedNs == 0
                            ? 0 : nanosToRoundedUpMillis(readStartedNs - lastCaptureStartedNs);
                    lastCaptureStartedNs = readStartedNs;
                    int read = capture.read(buffer, requested);
                    long readMs = nanosToRoundedUpMillis(SystemClock.elapsedRealtimeNanos() - readStartedNs);
                    if (read <= 0) {
                        recordCaptureReadFailure(read, requested, captureGapMs, readMs);
                        encoder.queueInputBuffer(inputIndex, 0, 0, inputPresentationTimeUs, 0);
                        continue;
                    }
                    inputFrameCount++;
                    int framePeak = recordCapturedPcm(buffer, read, requested, captureGapMs, readMs);
                    if (inputFrameCount <= 3) {
                        L.i("audio capture role=" + roleName(role)
                                + ", frame=" + inputFrameCount
                                + ", bytes=" + read
                                + ", requested=" + requested
                                + ", gapMs=" + captureGapMs
                                + ", readMs=" + readMs
                                + ", pcmPeak=" + framePeak);
                    }
                    if (inputFrameCount % AUDIO_LOG_INTERVAL_FRAMES == 0) {
                        logSourceStats("periodic");
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
                    long outputNowNs = SystemClock.elapsedRealtimeNanos();
                    long outputGapMs = lastEncodedOutputNs == 0
                            ? 0 : nanosToRoundedUpMillis(outputNowNs - lastEncodedOutputNs);
                    lastEncodedOutputNs = outputNowNs;
                    int sendBytes = data.remaining();
                    if (codecConfig || outputFrameCount <= 3) {
                        L.i("audio encoded role=" + roleName(role)
                                + ", frame=" + outputFrameCount
                                + ", config=" + codecConfig
                                + ", rawBytes=" + rawSize
                                + ", sendBytes=" + sendBytes
                                + ", outputGapMs=" + outputGapMs
                                + ", head=" + hexPrefix(data, 12));
                    }
                    long sendStartedNs = SystemClock.elapsedRealtimeNanos();
                    ControlPacket.sendAudioEvent(role, taggedAudio, data);
                    long sendMs = nanosToRoundedUpMillis(SystemClock.elapsedRealtimeNanos() - sendStartedNs);
                    recordEncodedTransport(sendBytes, outputGapMs, sendMs);
                    if (outputFrameCount % AUDIO_LOG_INTERVAL_FRAMES == 0) {
                        logTransportStats("periodic");
                    }
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

        private int recordCapturedPcm(ByteBuffer buffer, int size, int requested, long gapMs, long readMs) {
            int framePeak = 0;
            long frameSamples = 0;
            long frameClippedSamples = 0;
            long frameSquareSum = 0;
            int sampleBytes = size - (size & 1);
            for (int i = 0; i < sampleBytes; i += 2) {
                int sample = (short) ((buffer.get(i) & 0xff) | (buffer.get(i + 1) << 8));
                int absolute = Math.abs(sample);
                framePeak = Math.max(framePeak, absolute);
                frameSamples++;
                frameSquareSum += sample * (long) sample;
                if (absolute >= PCM_CLIP_THRESHOLD) frameClippedSamples++;
            }

            long nowMs = SystemClock.elapsedRealtime();
            boolean warn;
            synchronized (sourceStatsLock) {
                sourceFrames++;
                sourceBytes += size;
                sourceSamples += frameSamples;
                sourceClippedSamples += frameClippedSamples;
                sourceSquareSum += frameSquareSum;
                sourcePeak = Math.max(sourcePeak, framePeak);
                sourceMaxGapMs = Math.max(sourceMaxGapMs, gapMs);
                sourceMaxReadMs = Math.max(sourceMaxReadMs, readMs);
                if (size < requested) sourceShortReads++;
                warn = (gapMs >= AUDIO_GAP_WARNING_MS || readMs >= AUDIO_GAP_WARNING_MS)
                        && nowMs - lastCaptureWarningMs >= AUDIO_WARNING_RATE_LIMIT_MS;
                if (warn) lastCaptureWarningMs = nowMs;
            }
            if (warn) {
                L.w("audio capture delayed role=" + roleName(role)
                        + ", gapMs=" + gapMs
                        + ", readMs=" + readMs
                        + ", requested=" + requested
                        + ", read=" + size);
            }
            return framePeak;
        }

        private void recordCaptureReadFailure(int result, int requested, long gapMs, long readMs) {
            long nowMs = SystemClock.elapsedRealtime();
            boolean warn;
            synchronized (sourceStatsLock) {
                sourceEmptyReads++;
                sourceMaxGapMs = Math.max(sourceMaxGapMs, gapMs);
                sourceMaxReadMs = Math.max(sourceMaxReadMs, readMs);
                warn = nowMs - lastCaptureWarningMs >= AUDIO_WARNING_RATE_LIMIT_MS;
                if (warn) lastCaptureWarningMs = nowMs;
            }
            if (warn) {
                L.w("audio capture empty role=" + roleName(role)
                        + ", result=" + result
                        + ", requested=" + requested
                        + ", gapMs=" + gapMs
                        + ", readMs=" + readMs);
            }
        }

        private void logSourceStats(String reason) {
            String message;
            synchronized (sourceStatsLock) {
                if (sourceFrames == 0 && sourceEmptyReads == 0) return;
                long nowMs = SystemClock.elapsedRealtime();
                long windowMs = Math.max(1, nowMs - sourceWindowStartedMs);
                double pcmKbps = sourceBytes * 8.0 / windowMs;
                double clipPercent = sourceSamples == 0
                        ? 0.0 : sourceClippedSamples * 100.0 / sourceSamples;
                double rms = sourceSamples == 0
                        ? 0.0 : Math.sqrt(sourceSquareSum / (double) sourceSamples);
                double rmsDbfs = rms <= 0.0 ? -120.0 : 20.0 * Math.log10(rms / 32768.0);
                message = String.format(Locale.US,
                        "audio source stats role=%s, reason=%s, windowMs=%d"
                                + ", frames=%d, pcmKB=%d, pcmKbps=%.1f"
                                + ", captureGapMaxMs=%d, readMaxMs=%d, shortReads=%d, emptyReads=%d"
                                + ", peak=%d, rmsDbfs=%.1f, clipped=%d/%d(%.3f%%)",
                        roleName(role), reason, windowMs,
                        sourceFrames, sourceBytes / 1024, pcmKbps,
                        sourceMaxGapMs, sourceMaxReadMs, sourceShortReads, sourceEmptyReads,
                        sourcePeak, rmsDbfs, sourceClippedSamples, sourceSamples, clipPercent);
                sourceWindowStartedMs = nowMs;
                sourceFrames = 0;
                sourceBytes = 0;
                sourceSamples = 0;
                sourceClippedSamples = 0;
                sourceSquareSum = 0;
                sourceMaxGapMs = 0;
                sourceMaxReadMs = 0;
                sourceShortReads = 0;
                sourceEmptyReads = 0;
                sourcePeak = 0;
            }
            L.i(message);
        }

        private void recordEncodedTransport(int bytes, long outputGapMs, long sendMs) {
            long nowMs = SystemClock.elapsedRealtime();
            boolean warnOutput;
            boolean warnSend;
            synchronized (transportStatsLock) {
                transportFrames++;
                transportBytes += bytes;
                transportMaxOutputGapMs = Math.max(transportMaxOutputGapMs, outputGapMs);
                transportMaxSendMs = Math.max(transportMaxSendMs, sendMs);
                if (sendMs >= AUDIO_SEND_WARNING_MS) transportSlowSends++;
                warnOutput = outputGapMs >= AUDIO_GAP_WARNING_MS
                        && nowMs - lastOutputWarningMs >= AUDIO_WARNING_RATE_LIMIT_MS;
                warnSend = sendMs >= AUDIO_SEND_WARNING_MS
                        && nowMs - lastSendWarningMs >= AUDIO_WARNING_RATE_LIMIT_MS;
                if (warnOutput) lastOutputWarningMs = nowMs;
                if (warnSend) lastSendWarningMs = nowMs;
            }
            if (warnOutput) {
                L.w("audio encoder output gap role=" + roleName(role)
                        + ", gapMs=" + outputGapMs
                        + ", bytes=" + bytes);
            }
            if (warnSend) {
                L.w("audio socket send delayed role=" + roleName(role)
                        + ", sendMs=" + sendMs
                        + ", bytes=" + bytes
                        + ", tagged=" + taggedAudio);
            }
        }

        private void logTransportStats(String reason) {
            String message;
            synchronized (transportStatsLock) {
                if (transportFrames == 0) return;
                long nowMs = SystemClock.elapsedRealtime();
                long windowMs = Math.max(1, nowMs - transportWindowStartedMs);
                double encodedKbps = transportBytes * 8.0 / windowMs;
                message = String.format(Locale.US,
                        "audio transport stats role=%s, reason=%s, windowMs=%d"
                                + ", frames=%d, encodedKB=%d, encodedKbps=%.1f"
                                + ", encoderGapMaxMs=%d, sendMaxMs=%d, slowSends=%d, tagged=%s",
                        roleName(role), reason, windowMs,
                        transportFrames, transportBytes / 1024, encodedKbps,
                        transportMaxOutputGapMs, transportMaxSendMs, transportSlowSends, taggedAudio);
                transportWindowStartedMs = nowMs;
                transportFrames = 0;
                transportBytes = 0;
                transportMaxOutputGapMs = 0;
                transportMaxSendMs = 0;
                transportSlowSends = 0;
            }
            L.i(message);
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

        private long nanosToRoundedUpMillis(long nanos) {
            return nanos <= 0 ? 0 : (nanos + 999_999L) / 1_000_000L;
        }

        private void release() {
            if (released) return;
            released = true;
            if (inputThread != null) inputThread.interrupt();
            if (outputThread != null) outputThread.interrupt();
            logSourceStats("release");
            logTransportStats("release");
            capture.release();
            try {
                encoder.stop();
            } catch (Exception ignored) {
            }
            try {
                encoder.release();
            } catch (Exception ignored) {
            }
            L.i("audio pipeline released role=" + roleName(role));
        }
    }
}
