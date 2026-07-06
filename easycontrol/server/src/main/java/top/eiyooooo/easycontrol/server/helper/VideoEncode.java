package top.eiyooooo.easycontrol.server.helper;

import android.graphics.Rect;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.os.IBinder;
import android.system.ErrnoException;
import android.view.Surface;
import top.eiyooooo.easycontrol.server.Scrcpy;
import top.eiyooooo.easycontrol.server.entity.Device;
import top.eiyooooo.easycontrol.server.entity.Options;
import top.eiyooooo.easycontrol.server.utils.L;
import top.eiyooooo.easycontrol.server.wrappers.DisplayManager;
import top.eiyooooo.easycontrol.server.wrappers.SurfaceControl;
import top.eiyooooo.easycontrol.server.wrappers.WindowManager;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.util.HashMap;

public final class VideoEncode {
    // 视频编码器实例。
    private static MediaCodec encoder;
    // 视频编码参数缓存。
    private static MediaFormat encoderFormat;
    // 记录编码配置是否发生变化，发生变化时需要重建编码器。
    public static boolean isHasChangeConfig = false;
    // 是否启用 H.265。
    private static boolean useH265;

    // 主显示器绑定对象。
    private static IBinder display;
    // 记录各个虚拟显示器，方便释放和复用。
    private static final HashMap<Integer, VirtualDisplay> virtualDisplays = new HashMap<>();

    public static void init() throws Exception {
        // 依据设备能力决定是否启用 H.265。
        useH265 = Options.useH265 && Device.isEncoderSupport("hevc");
        // 首次把编码模式和视频尺寸发给客户端。
        ByteBuffer byteBuffer = ByteBuffer.allocate(9);
        byteBuffer.put((byte) (useH265 ? 1 : 0));
        byteBuffer.putInt(Device.videoSize.first);
        byteBuffer.putInt(Device.videoSize.second);
        byteBuffer.flip();
        Scrcpy.writeVideo(byteBuffer);
        // 创建显示器绑定点。
        try {
            display = SurfaceControl.createDisplay("easycontrol_for_car", Build.VERSION.SDK_INT < Build.VERSION_CODES.R || (Build.VERSION.SDK_INT == Build.VERSION_CODES.R && !"S".equals(Build.VERSION.CODENAME)));
        } catch (Exception e) {
            L.w("createDisplay by SurfaceControl error", e);
            Options.mirrorMode = 1;
        }
        // 创建编码器参数并启动。
        createEncoderFormat();
        startEncode();
    }

    private static void createEncoderFormat() throws IOException {
        // 选择 H.264 或 H.265。
        String codecMime = useH265 ? MediaFormat.MIMETYPE_VIDEO_HEVC : MediaFormat.MIMETYPE_VIDEO_AVC;
        encoder = MediaCodec.createEncoderByType(codecMime);
        boolean hardwareAccelerated = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && encoder.getCodecInfo().isHardwareAccelerated();
        boolean softwareOnly = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && encoder.getCodecInfo().isSoftwareOnly();
        L.i("video encoder=" + encoder.getName() + ", mime=" + codecMime + ", hw=" + hardwareAccelerated + ", sw=" + softwareOnly);
        encoderFormat = new MediaFormat();

        // 基础编码属性。
        encoderFormat.setString(MediaFormat.KEY_MIME, codecMime);

        encoderFormat.setInteger(MediaFormat.KEY_BIT_RATE, Options.maxVideoBit);
        encoderFormat.setInteger(MediaFormat.KEY_FRAME_RATE, Options.maxFps);
        // 缩短关键帧间隔，客户端必要时丢旧帧后能更快恢复清晰画面。
        encoderFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
        // 尽量使用稳定码率，避免码率大幅波动导致网络/USB 写入抖动。
        if (encoder.getCodecInfo().getCapabilitiesForType(codecMime).getEncoderCapabilities()
                .isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)) {
            encoderFormat.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
        }
        // 请求编码器按实时场景工作，降低内部排队延迟；不支持的编码器会忽略这些 hint。
        encoderFormat.setInteger(MediaFormat.KEY_PRIORITY, 0);
        encoderFormat.setInteger(MediaFormat.KEY_OPERATING_RATE, Options.maxFps);
        encoderFormat.setInteger(MediaFormat.KEY_LATENCY, 0);
        encoderFormat.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            encoderFormat.setInteger(MediaFormat.KEY_INTRA_REFRESH_PERIOD, Options.maxFps * 3);
        encoderFormat.setFloat("max-fps-to-encoder", Options.maxFps);

        encoderFormat.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 50_000);
        encoderFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
    }

    // 初始化编码器。
    private static Surface surface;

    public static void startEncode() throws Exception {
        // 把当前视频尺寸塞进编码器格式。
        encoderFormat.setInteger(MediaFormat.KEY_WIDTH, Device.videoSize.first);
        encoderFormat.setInteger(MediaFormat.KEY_HEIGHT, Device.videoSize.second);
        encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        // 创建输入 Surface，显示器画面会被编码器从这里接走。
        surface = encoder.createInputSurface();
        if (Device.displayId != 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            Options.mirrorMode = 1;
        if (Options.mirrorMode == 1) {
            try {
                // 镜像模式下为目标 display 创建一个独立的虚拟显示器。
                VirtualDisplay virtualDisplay = virtualDisplays.get(Device.displayId);
                if (virtualDisplay != null) virtualDisplay.release();
                virtualDisplay = DisplayManager.createVirtualDisplay("easycontrol_for_car",
                        Device.videoSize.first, Device.videoSize.second, Device.displayId, surface);
                virtualDisplays.put(Device.displayId, virtualDisplay);
                int displayId = virtualDisplay.getDisplay().getDisplayId();
                WindowManager.freezeRotation(displayId, 0);
                Device.display2virtualDisplay.put(Device.displayId, displayId);
                L.d("mirroring display " + Device.displayId + " to " + displayId + " with size " + Device.videoSize.first + "x" + Device.videoSize.second);
            } catch (Exception e) {
                L.e("createVirtualDisplay by DisplayManager error", e);
                throw e;
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+ 可能需要重新创建 display 才能挂接 surface。
                SurfaceControl.destroyDisplay(display);
                display = SurfaceControl.createDisplay("easycontrol_for_car", false);
            }
            setDisplaySurface(display, surface);
        }
        // 编码器真正启动后，后续才会有输出帧。
        encoder.start();
        ControlPacket.sendVideoSizeEvent();
    }

    public static void stopEncode() {
        // 停止并重置编码器，为重新创建做准备。
        encoder.stop();
        encoder.reset();
        surface.release();
    }

    private static void setDisplaySurface(IBinder display, Surface surface) throws InvocationTargetException, NoSuchMethodException, IllegalAccessException {
        // 对系统 display 的 surface / projection / layer stack 做一次原子配置。
        SurfaceControl.openTransaction();
        try {
            SurfaceControl.setDisplaySurface(display, surface);
            SurfaceControl.setDisplayProjection(display, 0, new Rect(0, 0, Device.deviceSize.first, Device.deviceSize.second), new Rect(0, 0, Device.videoSize.first, Device.videoSize.second));
            SurfaceControl.setDisplayLayerStack(display, Device.layerStack);
        } finally {
            SurfaceControl.closeTransaction();
        }
    }

    private static final MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

    public static void encodeOut() throws IOException, ErrnoException {
        try {
            // 阻塞等待一个已经编码完成的输出缓冲区。
            int outIndex;
            do outIndex = encoder.dequeueOutputBuffer(bufferInfo, -1); while (outIndex < 0);
            ByteBuffer buffer = encoder.getOutputBuffer(outIndex);
            if (buffer == null) return;
            // 把编码后的帧连同时间戳一起发给客户端。
            ControlPacket.sendVideoEvent(bufferInfo.presentationTimeUs, buffer);
            encoder.releaseOutputBuffer(outIndex, false);
        } catch (IllegalStateException e) {
            L.e("encodeOut error", e);
        }
    }

    public static void release() {
        try {
            // 先停编码，再释放 display 和虚拟显示器。
            stopEncode();
            encoder.release();
            SurfaceControl.destroyDisplay(display);
            for (VirtualDisplay virtualDisplay : virtualDisplays.values()) {
                virtualDisplay.release();
            }
        } catch (Exception e) {
            L.e("release error", e);
        }
    }

}
