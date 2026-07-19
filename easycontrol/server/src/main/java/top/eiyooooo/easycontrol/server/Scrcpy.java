package top.eiyooooo.easycontrol.server;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Build;
import android.system.ErrnoException;
import android.system.Os;
import android.view.Display;
import top.eiyooooo.easycontrol.server.entity.Device;
import top.eiyooooo.easycontrol.server.entity.Options;
import top.eiyooooo.easycontrol.server.helper.AudioEncode;
import top.eiyooooo.easycontrol.server.helper.ControlPacket;
import top.eiyooooo.easycontrol.server.helper.VideoEncode;
import top.eiyooooo.easycontrol.server.utils.L;
import top.eiyooooo.easycontrol.server.utils.Workarounds;
import top.eiyooooo.easycontrol.server.wrappers.ServiceManager;
import top.eiyooooo.easycontrol.server.wrappers.UiModeManager;

import java.io.DataInputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

public final class Scrcpy {
    // 线程同步对象，用来在连接断开时唤醒主线程退出。
    private static final Object object = new Object();
    // 空闲超时阈值，避免连接断开后进程一直挂着。
    private static final int timeoutDelay = 5 * 1000;
    // 首次建立 socket 可以稍慢，但不能在厂商初始化过程中并发释放资源。
    private static final int startupTimeoutDelay = 15 * 1000;
    private static final AtomicBoolean releaseStarted = new AtomicBoolean(false);

    public static void main(String... args) {
        // 服务端日志模式，便于在 shell 里直接看输出。
        L.logMode = 1;
        L.i("scrcpy stage=entry, sdk=" + Build.VERSION.SDK_INT
                + ", brand=" + Build.BRAND
                + ", model=" + Build.MODEL
                + ", args=" + Arrays.toString(args));
        // 打开日志输出。
        L.postLog();
        Thread timeOutThread = null;
        try {
            // 先解析参数，再尽快绑定 socket；厂商兼容初始化放到连接建立之后。
            Options.parse(args);
            L.i("scrcpy stage=options-parsed, displayId=" + Options.displayId
                    + ", socketName=" + Options.socketName
                    + ", mirrorMode=" + Options.mirrorMode
                    + ", audio=" + Options.isAudio
                    + ", audioProtocol=" + Options.audioProtocol
                    + ", audioSplit=" + Options.audioSplit);

            // watchdog 只保护首次 socket 连接。连接成功后立即停止，不能与初始化并发 release()。
            timeOutThread = new Thread(() -> {
                try {
                    Thread.sleep(startupTimeoutDelay);
                    L.w("scrcpy stage=socket-timeout");
                    release();
                } catch (InterruptedException ignored) {
                }
            }, "easycontrol_startup_watchdog");
            timeOutThread.start();

            L.i("scrcpy stage=socket-waiting");
            connectClient();
            L.i("scrcpy stage=socket-connected");
            timeOutThread.interrupt();
            timeOutThread = null;

            // 初始化兼容性修正和系统服务。
            L.i("scrcpy stage=workarounds-start");
            Workarounds.apply(1);
            L.i("scrcpy stage=workarounds-ready");
            ServiceManager.setManagers();
            L.i("scrcpy stage=services-ready");
            Device.init();
            L.i("scrcpy stage=device-ready, displayId=" + Device.displayId
                    + ", video=" + Device.videoSize.first + "x" + Device.videoSize.second);
            // 初始化音频和视频编码子服务。
            AudioEncode.init();
            L.i("scrcpy stage=audio-ready");
            VideoEncode.init();
            L.i("scrcpy stage=video-ready");
            // 启动数据流线程。
            ArrayList<Thread> threads = new ArrayList<>();
            threads.add(new Thread(Scrcpy::executeVideoOut));
            threads.add(new Thread(Scrcpy::executeControlIn));
            for (Thread thread : threads) thread.setPriority(Thread.MAX_PRIORITY);
            for (Thread thread : threads) thread.start();
            L.i("scrcpy stage=streaming");
            if (Options.TurnOnScreenIfStart) {
                // 启动时可选地点亮屏幕。
                Device.keyEvent(224, 0, 0);
                if (Options.TurnOffScreenIfStart)
                    // 点亮后再延迟熄屏，方便某些车机兼容性处理。
                    postDelayed(() -> Device.changeScreenPowerMode(Display.STATE_UNKNOWN), 2000);
            }
            synchronized (object) {
                // 阻塞等待退出信号。
                object.wait();
            }
            // 收到退出信号后，中断所有工作线程。
            for (Thread thread : threads) thread.interrupt();
        } catch (Throwable e) {
            L.e("startScrcpy error", e);
        } finally {
            if (timeOutThread != null) timeOutThread.interrupt();
            // 不管是否异常，都尽量收尾释放。
            release();
        }
    }

    public static void postDelayed(Runnable runnable, long delayMillis) {
        // 简单的延迟执行工具，避免引入额外调度器。
        new Thread(() -> {
            try {
                Thread.sleep(delayMillis);
                runnable.run();
            } catch (InterruptedException e) {
                L.e("postDelayed error", e);
            }
        }).start();
    }

    private static LocalSocket mainSocket;
    private static FileDescriptor mainFD;
    private static LocalSocket videoSocket;
    private static FileDescriptor videoFD;
    public static DataInputStream inputStream;
    // main 和 video 是两条独立 socket，不能共用 class 锁，否则视频写阻塞会拖住音频/控制通道。
    private static final Object mainWriteLock = new Object();
    private static final Object videoWriteLock = new Object();

    private static void connectClient() throws IOException {
        // 使用本地 socket 连接 app 端发起的客户端。
        try (LocalServerSocket serverSocket = new LocalServerSocket(Options.socketName)) {
            L.i("scrcpy stage=main-socket-accept, socketName=" + Options.socketName);
            mainSocket = serverSocket.accept();
            L.i("scrcpy stage=video-socket-accept, socketName=" + Options.socketName);
            videoSocket = serverSocket.accept();
            mainFD = mainSocket.getFileDescriptor();
            videoFD = videoSocket.getFileDescriptor();
            inputStream = new DataInputStream(mainSocket.getInputStream());
        }
    }

    private static void executeVideoOut() {
        try {
            int frame = 0;
            while (!Thread.interrupted()) {
                // 如果视频参数变了，先停再重建编码器。
                if (VideoEncode.isHasChangeConfig) {
                    VideoEncode.isHasChangeConfig = false;
                    VideoEncode.stopEncode();
                    VideoEncode.startEncode();
                }
                VideoEncode.encodeOut();
                frame++;
                if (frame > 120) {
                    // 定期检查 keep-alive，防止死连接。
                    if (System.currentTimeMillis() - lastKeepAliveTime > timeoutDelay) {
                        timeoutClose = true;
                        throw new IOException("Connection disconnected");
                    }
                    frame = 0;
                }
            }
        } catch (Exception e) {
            errorClose(e);
        }
    }

    private static long lastKeepAliveTime = System.currentTimeMillis();

    private static void executeControlIn() {
        try {
            while (!Thread.interrupted()) {
                // 控制通道按单字节命令码分发。
                switch (inputStream.readByte()) {
                    case 1:
                        ControlPacket.handleTouchEvent();
                        break;
                    case 2:
                        ControlPacket.handleKeyEvent();
                        break;
                    case 3:
                        ControlPacket.handleClipboardEvent();
                        break;
                    case 4:
                        // 心跳包刷新活跃时间戳。
                        ControlPacket.sendKeepAlive();
                        lastKeepAliveTime = System.currentTimeMillis();
                        break;
                    case 5:
                        Device.handleConfigChanged(inputStream.readInt());
                        break;
                    case 6:
                        Device.rotateDevice(inputStream.readInt());
                        break;
                    case 7:
                        Device.changeScreenPowerMode(inputStream.readByte());
                        break;
                    case 8:
                        Device.changePower();
                        break;
                    case 9:
                        if (Device.oldNightMode == -1) Device.oldNightMode = UiModeManager.getNightMode();
                        UiModeManager.setNightMode(inputStream.readByte());
                        break;
                }
            }
        } catch (Exception e) {
            errorClose(e);
        }
    }

    public static void writeMain(ByteBuffer byteBuffer) throws IOException, ErrnoException {
        synchronized (mainWriteLock) {
            // 把数据写到 main socket，直到全部写完；同一通道内仍需串行，避免音频/控制包交叉。
            while (byteBuffer.remaining() > 0) Os.write(mainFD, byteBuffer);
        }
    }

    public static void writeVideo(ByteBuffer byteBuffer) throws IOException, ErrnoException {
        synchronized (videoWriteLock) {
            // 视频数据走单独的 socket；独立锁避免网络/解码背压时拖慢音频。
            while (byteBuffer.remaining() > 0) Os.write(videoFD, byteBuffer);
        }
    }

    public static void writeVideoFrame(int size, ByteBuffer data, long pts) throws IOException, ErrnoException {
        ByteBuffer sizeBuffer = ByteBuffer.allocate(4);
        sizeBuffer.putInt(size);
        sizeBuffer.flip();
        ByteBuffer ptsBuffer = ByteBuffer.allocate(8);
        ptsBuffer.putLong(pts);
        ptsBuffer.flip();
        synchronized (videoWriteLock) {
            // 三段在同一把锁内连续写入，协议顺序不变，同时避免复制整帧编码数据。
            while (sizeBuffer.remaining() > 0) Os.write(videoFD, sizeBuffer);
            while (data.remaining() > 0) Os.write(videoFD, data);
            while (ptsBuffer.remaining() > 0) Os.write(videoFD, ptsBuffer);
        }
    }

    public static void errorClose(Exception e) {
        // 出错时统一走这里收尾。
        L.e("errorClose: ", e);
        synchronized (object) {
            object.notify();
        }
    }

    private static boolean timeoutClose = false;

    // 释放资源
    private static void release() {
        if (!releaseStarted.compareAndSet(false, true)) return;
        L.i("scrcpy stage=release-start");
        // 检查是不是最后一个 scrcpy 实例，决定是否恢复一些全局状态。
        boolean lastScrcpy = false;
        try {
            lastScrcpy = Integer.parseInt(Channel.execReadOutput("ps -ef | grep easycontrol.server.Scrcpy | grep -v grep | grep -c 'easycontrol.server.Scrcpy'").replace("<!@n@!>", "")) == 1;
        } catch (Exception e) {
            L.w("get lastScrcpy error", e);
        }

        // 1. 先关闭 socket 与输入流。
        closeQuietly(inputStream, "main input");
        closeQuietly(mainSocket, "main socket");
        closeQuietly(videoSocket, "video socket");

        // 2. 释放音视频编码器。
        try {
            VideoEncode.release();
        } catch (Throwable e) {
            L.e("release video error", e);
        }
        try {
            AudioEncode.release();
        } catch (Throwable e) {
            L.e("release audio error", e);
        }

        // 3. 恢复被修改过的屏幕尺寸、密度和夜间模式。
        if (Device.needReset) {
            try {
                if (Device.realDeviceSize != null)
                    Channel.execReadOutput("wm size " + Device.realDeviceSize.first + "x" + Device.realDeviceSize.second);
                else
                    Channel.execReadOutput("wm size reset");
            } catch (Exception e) {
                L.e("release error", e);
            }

            try {
                if (Device.realDeviceDensity != 0)
                    Channel.execReadOutput("wm density " + Device.realDeviceDensity);
                else
                    Channel.execReadOutput("wm density reset");
            } catch (Exception e) {
                L.e("release error", e);
            }
        }
        if (lastScrcpy && Device.oldNightMode != -1 && UiModeManager.getNightMode() != Device.oldNightMode) {
            UiModeManager.setNightMode(Device.oldNightMode);
        }

        // 4. 如果开启了保持唤醒，就把熄屏超时恢复回去。
        if (Options.keepAwake) {
            try {
                Channel.execReadOutput("settings put system screen_off_timeout " + Device.oldScreenOffTimeout);
            } catch (Exception e) {
                L.e("release error", e);
            }
        }

        // 5. 如有必要，把状态改回关闭并唤醒主线程退出。
        try {
            if (timeoutClose || lastScrcpy) {
                if (Options.TurnOffScreenIfStop) Device.keyEvent(223, 0, 0);
                else if (Options.TurnOnScreenIfStop) Device.changeScreenPowerMode(Display.STATE_ON);
            }
        } catch (Exception e) {
            L.e("release error", e);
        }

        // 6
        if (timeoutClose) {
            try {
                Channel.execReadOutput("ps -ef | grep easycontrol.server.Scrcpy | grep -v grep | grep -E \"^[a-z]+ +[0-9]+\" -o | grep -E \"[0-9]+\" -o | xargs kill -9");
            } catch (Exception e) {
                L.e("release error", e);
            }
        }

        // 7
        L.d("scrcpy release success");
        System.exit(0);
    }

    private static void closeQuietly(AutoCloseable closeable, String name) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Throwable e) {
            L.e("release " + name + " error", e);
        }
    }

}
