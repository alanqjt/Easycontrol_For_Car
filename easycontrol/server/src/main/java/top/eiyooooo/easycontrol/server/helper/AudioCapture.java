package top.eiyooooo.easycontrol.server.helper;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Context;
import android.content.AttributionSource;
import android.media.*;
import android.os.Build;
import android.os.Looper;
import android.os.Parcel;
import top.eiyooooo.easycontrol.server.utils.L;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.Set;
/**
 * 音频捕获辅助类。
 * 作用是创建并初始化 AudioRecord，让服务器侧可以采到系统混音里的音频。
 * 先优先走公开 API；如果某些车机或系统版本不兼容，再走反射兜底方案。
 */
public final class AudioCapture {

    private static final int BUFFER_SIZE_MULTIPLIER = 4;
    // 采样率，48kHz 是目前比较常见且音质/性能平衡较好的配置。
    public static final int SAMPLE_RATE = 48000;
    // AudioFormat 需要的输入通道配置，这里使用立体声输入。
    public static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_STEREO;
    // 真实通道数，立体声就是 2 声道。
    public static final int CHANNELS = 2;
    // 左右声道掩码，反射兜底时要直接写入内部字段。
    public static final int CHANNEL_MASK = AudioFormat.CHANNEL_IN_LEFT | AudioFormat.CHANNEL_IN_RIGHT;
    // PCM 编码格式固定为 16 位。
    public static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    // 每个采样点占 2 字节，因为这里是 16bit PCM。
    public static final int BYTES_PER_SAMPLE = 2;
    public static final class Session {
        private final AudioRecord recorder;
        private final PolicyRegistration policyRegistration;
        private boolean released;

        private Session(AudioRecord recorder, PolicyRegistration policyRegistration) {
            this.recorder = recorder;
            this.policyRegistration = policyRegistration;
        }

        private void start() {
            recorder.startRecording();
        }

        public int read(ByteBuffer buffer, int size) {
            return recorder.read(buffer, size);
        }

        public synchronized void release() {
            if (released) return;
            released = true;
            try {
                recorder.stop();
            } catch (Exception ignored) {
            }
            try {
                recorder.release();
            } catch (Exception ignored) {
            }
            if (policyRegistration != null) policyRegistration.releaseReference();
        }
    }

    public static final class SplitSession {
        public final Session media;
        public final Session navigation;

        private SplitSession(Session media, Session navigation) {
            this.media = media;
            this.navigation = navigation;
        }

        public void release() {
            media.release();
            navigation.release();
        }
    }

    /** 两个 AudioRecord 可以共享同一个 AudioPolicy，最后一个释放时才注销策略。 */
    private static final class PolicyRegistration {
        private final Object audioPolicy;
        private final Class<?> audioPolicyClass;
        private int references;

        private PolicyRegistration(Object audioPolicy, Class<?> audioPolicyClass, int references) {
            this.audioPolicy = audioPolicy;
            this.audioPolicyClass = audioPolicyClass;
            this.references = references;
        }

        private synchronized void releaseReference() {
            if (references <= 0) return;
            references--;
            if (references == 0) unregisterAudioPolicy(audioPolicy, audioPolicyClass);
        }
    }

    /**
     * 初始化一个已经开始录音的 AudioRecord。
     * 这里会先尝试官方 Builder；如果失败，再切到反射实现。
     */
    public static Session init(int audioUid, boolean allowFallback) {
        AudioRecord recorder;
        if (audioUid > 0) {
            Session uidSession = null;
            try {
                uidSession = createUidAudioRecord(audioUid);
                uidSession.start();
                L.i("audio capture filtered by uid=" + audioUid);
                return uidSession;
            } catch (Exception e) {
                if (uidSession != null) uidSession.release();
                L.w("Cannot capture audio for uid=" + audioUid, e);
                if (!allowFallback) throw new RuntimeException("UID audio capture unavailable", e);
                L.w("Falling back to REMOTE_SUBMIX audio capture");
            }
        }
        try {
            // 正常情况下优先用公开 API，兼容性最好也最安全。
            recorder = createAudioRecord();
        } catch (RuntimeException e) {
            // 某些系统实现会在 Builder 里直接异常，这里走兜底逻辑。
            L.w("Cannot create AudioRecord, try workaround");
            recorder = createAudioRecordWorkaround();
        }
        // 录音对象创建成功后立刻开始采集。
        Session session = new Session(recorder, null);
        session.start();
        return session;
    }

    /**
     * Android 13+ 通过 AudioPolicy 创建只匹配指定 UID 的回环录音源。
     * 该实现沿用 scrcpy 的 playback capture 路径，但规则从 usage 改为 UID。
     */
    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    @SuppressLint({"PrivateApi", "DiscouragedPrivateApi"})
    private static Session createUidAudioRecord(int uid) throws Exception {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            throw new UnsupportedOperationException("UID audio capture requires Android 13+");
        }

        Class<?> audioMixingRuleClass = Class.forName("android.media.audiopolicy.AudioMixingRule");
        Class<?> audioMixingRuleBuilderClass = Class.forName("android.media.audiopolicy.AudioMixingRule$Builder");
        Object audioMixingRuleBuilder = audioMixingRuleBuilderClass.getConstructor().newInstance();

        int mixRolePlayers = audioMixingRuleClass.getField("MIX_ROLE_PLAYERS").getInt(null);
        audioMixingRuleBuilderClass.getMethod("setTargetMixRole", int.class).invoke(audioMixingRuleBuilder, mixRolePlayers);
        int ruleMatchUid = audioMixingRuleClass.getField("RULE_MATCH_UID").getInt(null);
        audioMixingRuleBuilderClass.getMethod("addMixRule", int.class, Object.class)
                .invoke(audioMixingRuleBuilder, ruleMatchUid, Integer.valueOf(uid));
        Object audioMixingRule = audioMixingRuleBuilderClass.getMethod("build").invoke(audioMixingRuleBuilder);

        Class<?> audioMixClass = Class.forName("android.media.audiopolicy.AudioMix");
        Object audioMix = createAudioMix(audioMixingRuleClass, audioMixClass, audioMixingRule);

        Class<?> audioPolicyClass = Class.forName("android.media.audiopolicy.AudioPolicy");
        Class<?> audioPolicyBuilderClass = Class.forName("android.media.audiopolicy.AudioPolicy$Builder");
        Object audioPolicyBuilder = audioPolicyBuilderClass.getConstructor(Context.class).newInstance(FakeContext.get());
        audioPolicyBuilderClass.getMethod("addMix", audioMixClass).invoke(audioPolicyBuilder, audioMix);
        Object audioPolicy = audioPolicyBuilderClass.getMethod("build").invoke(audioPolicyBuilder);
        registerAudioPolicy(audioPolicy, audioPolicyClass);
        try {
            AudioRecord recorder = createAudioRecordSink(audioPolicyClass, audioMixClass, audioPolicy, audioMix);
            return new Session(recorder, new PolicyRegistration(audioPolicy, audioPolicyClass, 1));
        } catch (Exception e) {
            unregisterAudioPolicy(audioPolicy, audioPolicyClass);
            throw e;
        }
    }

    /**
     * 直接投屏双路采集：导航路匹配导航 UID，媒体路排除这些 UID，两个集合互不重叠。
     * 找不到导航包时退化为按 Android navigation usage 拆分。
     */
    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    @SuppressLint({"PrivateApi", "DiscouragedPrivateApi"})
    public static SplitSession initSplit(Set<Integer> navigationUids) throws Exception {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            throw new UnsupportedOperationException("Split audio capture requires Android 13+");
        }

        Class<?> ruleClass = Class.forName("android.media.audiopolicy.AudioMixingRule");
        Class<?> ruleBuilderClass = Class.forName("android.media.audiopolicy.AudioMixingRule$Builder");
        int mixRolePlayers = ruleClass.getField("MIX_ROLE_PLAYERS").getInt(null);
        int ruleMatchUid = ruleClass.getField("RULE_MATCH_UID").getInt(null);
        int ruleMatchUsage = ruleClass.getField("RULE_MATCH_ATTRIBUTE_USAGE").getInt(null);
        Object navigationRuleBuilder = ruleBuilderClass.getConstructor().newInstance();
        Object mediaRuleBuilder = ruleBuilderClass.getConstructor().newInstance();
        ruleBuilderClass.getMethod("setTargetMixRole", int.class).invoke(navigationRuleBuilder, mixRolePlayers);
        ruleBuilderClass.getMethod("setTargetMixRole", int.class).invoke(mediaRuleBuilder, mixRolePlayers);

        if (!navigationUids.isEmpty()) {
            Method addMixRule = ruleBuilderClass.getMethod("addMixRule", int.class, Object.class);
            Method excludeMixRule = ruleBuilderClass.getMethod("excludeMixRule", int.class, Object.class);
            for (Integer uid : navigationUids) {
                addMixRule.invoke(navigationRuleBuilder, ruleMatchUid, uid);
                excludeMixRule.invoke(mediaRuleBuilder, ruleMatchUid, uid);
            }
        } else {
            AudioAttributes navigationAttributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .build();
            ruleBuilderClass.getMethod("addMixRule", int.class, Object.class)
                    .invoke(navigationRuleBuilder, ruleMatchUsage, navigationAttributes);
            ruleBuilderClass.getMethod("excludeMixRule", int.class, Object.class)
                    .invoke(mediaRuleBuilder, ruleMatchUsage, navigationAttributes);
        }

        Object navigationRule = ruleBuilderClass.getMethod("build").invoke(navigationRuleBuilder);
        Object mediaRule = ruleBuilderClass.getMethod("build").invoke(mediaRuleBuilder);
        Class<?> mixClass = Class.forName("android.media.audiopolicy.AudioMix");
        Object navigationMix = createAudioMix(ruleClass, mixClass, navigationRule);
        Object mediaMix = createAudioMix(ruleClass, mixClass, mediaRule);

        Class<?> policyClass = Class.forName("android.media.audiopolicy.AudioPolicy");
        Class<?> policyBuilderClass = Class.forName("android.media.audiopolicy.AudioPolicy$Builder");
        Object policyBuilder = policyBuilderClass.getConstructor(Context.class).newInstance(FakeContext.get());
        Method addMix = policyBuilderClass.getMethod("addMix", mixClass);
        addMix.invoke(policyBuilder, navigationMix);
        addMix.invoke(policyBuilder, mediaMix);
        Object policy = policyBuilderClass.getMethod("build").invoke(policyBuilder);
        registerAudioPolicy(policy, policyClass);

        AudioRecord navigationRecorder = null;
        AudioRecord mediaRecorder = null;
        try {
            navigationRecorder = createAudioRecordSink(policyClass, mixClass, policy, navigationMix);
            mediaRecorder = createAudioRecordSink(policyClass, mixClass, policy, mediaMix);
            PolicyRegistration registration = new PolicyRegistration(policy, policyClass, 2);
            Session navigation = new Session(navigationRecorder, registration);
            Session media = new Session(mediaRecorder, registration);
            navigation.start();
            media.start();
            L.i("direct audio capture split ready, navigationUids=" + navigationUids);
            return new SplitSession(media, navigation);
        } catch (Exception e) {
            releaseRecorder(navigationRecorder);
            releaseRecorder(mediaRecorder);
            unregisterAudioPolicy(policy, policyClass);
            throw e;
        }
    }

    private static Object createAudioMix(Class<?> ruleClass, Class<?> mixClass, Object rule) throws Exception {
        Class<?> mixBuilderClass = Class.forName("android.media.audiopolicy.AudioMix$Builder");
        Object mixBuilder = mixBuilderClass.getConstructor(ruleClass).newInstance(rule);
        mixBuilderClass.getMethod("setFormat", AudioFormat.class).invoke(mixBuilder, createAudioFormat());
        int routeFlags = mixClass.getField("ROUTE_FLAG_LOOP_BACK").getInt(null);
        mixBuilderClass.getMethod("setRouteFlags", int.class).invoke(mixBuilder, routeFlags);
        return mixBuilderClass.getMethod("build").invoke(mixBuilder);
    }

    private static void registerAudioPolicy(Object policy, Class<?> policyClass) throws Exception {
        Method register = AudioManager.class.getDeclaredMethod("registerAudioPolicyStatic", policyClass);
        register.setAccessible(true);
        int result = (int) register.invoke(null, policy);
        if (result != 0) throw new RuntimeException("registerAudioPolicy() returned " + result);
    }

    private static AudioRecord createAudioRecordSink(Class<?> policyClass, Class<?> mixClass,
                                                      Object policy, Object mix) throws Exception {
        AudioRecord recorder = (AudioRecord) policyClass.getMethod("createAudioRecordSink", mixClass).invoke(policy, mix);
        if (recorder == null || recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            throw new RuntimeException("AudioRecord sink is not initialized");
        }
        return recorder;
    }

    private static AudioFormat createAudioFormat() {
        return new AudioFormat.Builder()
                .setEncoding(ENCODING)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL_CONFIG)
                .build();
    }

    private static void unregisterAudioPolicy(Object audioPolicy, Class<?> audioPolicyClass) {
        if (audioPolicy == null || audioPolicyClass == null) return;
        try {
            Method unregisterAudioPolicy = AudioManager.class.getDeclaredMethod("unregisterAudioPolicyAsyncStatic", audioPolicyClass);
            unregisterAudioPolicy.setAccessible(true);
            unregisterAudioPolicy.invoke(null, audioPolicy);
        } catch (Exception e) {
            // scrcpy server 进程随后会退出；注销失败不会影响下一次独立进程注册。
            L.w("Cannot unregister UID audio policy", e);
        }
    }

    private static void releaseRecorder(AudioRecord recorder) {
        if (recorder == null) return;
        try {
            recorder.stop();
        } catch (Exception ignored) {
        }
        try {
            recorder.release();
        } catch (Exception ignored) {
        }
    }

    /**
     * 将毫秒换算成 PCM 字节数。
     * 公式就是：采样率 * 通道数 * 每采样字节数 / 1000 * 毫秒数。
     */
    public static int millisToBytes(int millis) {
        return (SAMPLE_RATE * CHANNELS * BYTES_PER_SAMPLE / 1000) * millis;
    }

    /**
     * 使用公开的 AudioRecord.Builder 创建实例。
     * 这条路径适合 Android M 及以上，是最推荐的实现。
     */
    @TargetApi(Build.VERSION_CODES.M)
    @SuppressLint({"WrongConstant", "MissingPermission"})
    private static AudioRecord createAudioRecord() {
        AudioRecord.Builder audioRecordBuilder = new AudioRecord.Builder();
        // Android 12 以后有些构造链需要上下文，这里通过 FakeContext 补齐。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audioRecordBuilder.setContext(FakeContext.get());

        // REMOTE_SUBMIX 表示采系统混音，正是这里要抓的导航播报/媒体声音来源。
        audioRecordBuilder.setAudioSource(MediaRecorder.AudioSource.REMOTE_SUBMIX);
        AudioFormat.Builder audioFormatBuilder = new AudioFormat.Builder();
        // 把编码格式、采样率和声道都统一配置好。
        audioFormatBuilder.setEncoding(ENCODING);
        audioFormatBuilder.setSampleRate(SAMPLE_RATE);
        audioFormatBuilder.setChannelMask(CHANNEL_CONFIG);
        audioRecordBuilder.setAudioFormat(audioFormatBuilder.build());
        // 缓冲区保留一定余量，但不要过大，避免系统混音读取链路积压太多旧音频。
        audioRecordBuilder.setBufferSizeInBytes(BUFFER_SIZE_MULTIPLIER * AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, ENCODING));
        return audioRecordBuilder.build();
    }

    /**
     * 反射兜底方案。
     * 当公开 Builder 走不通时，直接绕到 AudioRecord 内部字段和私有 native_setup。
     */
    @TargetApi(Build.VERSION_CODES.R)
    @SuppressLint("WrongConstant,MissingPermission,BlockedPrivateApi,SoonBlockedPrivateApi,DiscouragedPrivateApi")
    private static AudioRecord createAudioRecordWorkaround() {
        try {
            // 先通过私有构造器创建一个“壳”对象。
            Constructor<AudioRecord> audioRecordConstructor = AudioRecord.class.getDeclaredConstructor(long.class);
            audioRecordConstructor.setAccessible(true);
            AudioRecord audioRecord = audioRecordConstructor.newInstance(0L);

            // 先把录音状态设成停止，避免后续 native 初始化时状态校验失败。
            Field mRecordingStateField = AudioRecord.class.getDeclaredField("mRecordingState");
            mRecordingStateField.setAccessible(true);
            mRecordingStateField.set(audioRecord, AudioRecord.RECORDSTATE_STOPPED);

            // 初始化过程中某些系统实现会用到 Looper，这里优先拿当前线程的，没有就退回主线程。
            Looper looper = Looper.myLooper();
            if (looper == null) {
                looper = Looper.getMainLooper();
            }

            // 写入初始化 Looper，让内部回调链能正常工作。
            Field mInitializationLooperField = AudioRecord.class.getDeclaredField("mInitializationLooper");
            mInitializationLooperField.setAccessible(true);
            mInitializationLooperField.set(audioRecord, looper);

            // 创建 AudioAttributes，并把内部捕获预设设置成 REMOTE_SUBMIX。
            AudioAttributes.Builder audioAttributesBuilder = new AudioAttributes.Builder();
            Method setInternalCapturePresetMethod = AudioAttributes.Builder.class.getMethod("setInternalCapturePreset", int.class);
            setInternalCapturePresetMethod.invoke(audioAttributesBuilder, MediaRecorder.AudioSource.REMOTE_SUBMIX);
            AudioAttributes attributes = audioAttributesBuilder.build();

            // 把构造好的音频属性塞回 AudioRecord 内部字段。
            Field mAudioAttributesField = AudioRecord.class.getDeclaredField("mAudioAttributes");
            mAudioAttributesField.setAccessible(true);
            mAudioAttributesField.set(audioRecord, attributes);

            // 调用私有参数检查，确保采样率、编码格式等组合是合法的。
            Method audioParamCheckMethod = AudioRecord.class.getDeclaredMethod("audioParamCheck", int.class, int.class, int.class);
            audioParamCheckMethod.setAccessible(true);
            audioParamCheckMethod.invoke(audioRecord, MediaRecorder.AudioSource.REMOTE_SUBMIX, SAMPLE_RATE, ENCODING);

            // 写入内部声道数量，保证后面 native 逻辑能正确识别双声道。
            Field mChannelCountField = AudioRecord.class.getDeclaredField("mChannelCount");
            mChannelCountField.setAccessible(true);
            mChannelCountField.set(audioRecord, CHANNELS);

            // 写入内部声道掩码，和上面的通道数量保持一致。
            Field mChannelMaskField = AudioRecord.class.getDeclaredField("mChannelMask");
            mChannelMaskField.setAccessible(true);
            mChannelMaskField.set(audioRecord, CHANNEL_MASK);

            // 先计算系统建议的最小缓冲，再保留适度余量，兼顾稳定和低延迟。
            int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, ENCODING);
            int bufferSizeInBytes = minBufferSize * BUFFER_SIZE_MULTIPLIER;

            // 让内部校验函数确认缓冲区大小没问题。
            Method audioBuffSizeCheckMethod = AudioRecord.class.getDeclaredMethod("audioBuffSizeCheck", int.class);
            audioBuffSizeCheckMethod.setAccessible(true);
            audioBuffSizeCheckMethod.invoke(audioRecord, bufferSizeInBytes);

            // 这里不使用通道索引掩码，默认置 0。
            final int channelIndexMask = 0;

            // native_setup 需要传入数组来回写 sampleRate 和 sessionId。
            int[] sampleRateArray = new int[]{SAMPLE_RATE};
            int[] session = new int[]{AudioManager.AUDIO_SESSION_ID_GENERATE};

            int initResult;
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                // Android 11 及更早版本，native_setup 签名还比较短。
                Method nativeSetupMethod = AudioRecord.class.getDeclaredMethod("native_setup", Object.class, Object.class, int[].class, int.class,
                        int.class, int.class, int.class, int[].class, String.class, long.class);
                nativeSetupMethod.setAccessible(true);
                initResult = (int) nativeSetupMethod.invoke(audioRecord, new WeakReference<AudioRecord>(audioRecord), attributes, sampleRateArray,
                        CHANNEL_MASK, channelIndexMask, audioRecord.getAudioFormat(), bufferSizeInBytes, session, FakeContext.get().getOpPackageName(),
                        0L);
            } else {
                // 新版本需要 AttributionSource，先从 FakeContext 里拿到对应对象。
                AttributionSource attributionSource = FakeContext.get().getAttributionSource();

                // 把 AttributionSource 转成内部可被 native 层读取的 Parcel 状态。
                Method asScopedParcelStateMethod = AttributionSource.class.getDeclaredMethod("asScopedParcelState");
                asScopedParcelStateMethod.setAccessible(true);

                try (AutoCloseable attributionSourceState = (AutoCloseable) asScopedParcelStateMethod.invoke(attributionSource)) {
                    Method getParcelMethod = attributionSourceState.getClass().getDeclaredMethod("getParcel");
                    Parcel attributionSourceParcel = (Parcel) getParcelMethod.invoke(attributionSourceState);

                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        // Android 11/12/13 一类版本，native_setup 的参数还没有进一步扩展。
                        Method nativeSetupMethod = AudioRecord.class.getDeclaredMethod("native_setup", Object.class, Object.class, int[].class,
                                int.class, int.class, int.class, int.class, int[].class, Parcel.class, long.class, int.class);
                        nativeSetupMethod.setAccessible(true);
                        initResult = (int) nativeSetupMethod.invoke(audioRecord, new WeakReference<AudioRecord>(audioRecord), attributes,
                                sampleRateArray, CHANNEL_MASK, channelIndexMask, audioRecord.getAudioFormat(), bufferSizeInBytes, session,
                                attributionSourceParcel, 0L, 0);
                    } else {
                        // Android 14+ 又多了 halInputFlags 参数，所以这里分开处理。
                        Method nativeSetupMethod = AudioRecord.class.getDeclaredMethod("native_setup", Object.class, Object.class, int[].class,
                                int.class, int.class, int.class, int.class, int[].class, Parcel.class, long.class, int.class, int.class);
                        nativeSetupMethod.setAccessible(true);
                        initResult = (int) nativeSetupMethod.invoke(audioRecord, new WeakReference<AudioRecord>(audioRecord), attributes,
                                sampleRateArray, CHANNEL_MASK, channelIndexMask, audioRecord.getAudioFormat(), bufferSizeInBytes, session,
                                attributionSourceParcel, 0L, 0, 0);
                    }
                }
            }

            // 如果 native 初始化没有成功，就直接抛异常，避免返回一个半初始化对象。
            if (initResult != AudioRecord.SUCCESS) {
                L.w("AudioRecord initialization failed with code " + initResult);
                throw new RuntimeException("Cannot create AudioRecord");
            }

            // 把最终结果写回内部字段，补全对象状态。
            Field mSampleRateField = AudioRecord.class.getDeclaredField("mSampleRate");
            mSampleRateField.setAccessible(true);
            mSampleRateField.set(audioRecord, sampleRateArray[0]);

            Field mSessionIdField = AudioRecord.class.getDeclaredField("mSessionId");
            mSessionIdField.setAccessible(true);
            mSessionIdField.set(audioRecord, session[0]);

            Field mStateField = AudioRecord.class.getDeclaredField("mState");
            mStateField.setAccessible(true);
            mStateField.set(audioRecord, AudioRecord.STATE_INITIALIZED);

            return audioRecord;
        } catch (Exception e) {
            // 反射方案一旦失败，直接记录错误并交给上层处理。
            L.e("Cannot create AudioRecord", e);
            throw new RuntimeException("Cannot create AudioRecord");
        }
    }
}
