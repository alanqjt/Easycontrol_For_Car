package top.eiyooooo.easycontrol.app.client;

import static android.content.ClipDescription.MIMETYPE_TEXT_PLAIN;

import android.content.ClipData;
import android.content.pm.ApplicationInfo;
import android.hardware.usb.UsbDevice;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.util.Pair;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import top.eiyooooo.easycontrol.app.MainActivity;
import top.eiyooooo.easycontrol.app.entity.AppData;
import top.eiyooooo.easycontrol.app.entity.Device;
import top.eiyooooo.easycontrol.app.helper.EventMonitor;
import top.eiyooooo.easycontrol.app.helper.L;
import top.eiyooooo.easycontrol.app.helper.PublicTools;
import top.eiyooooo.easycontrol.app.BuildConfig;
import top.eiyooooo.easycontrol.app.R;
import top.eiyooooo.easycontrol.app.adb.Adb;
import top.eiyooooo.easycontrol.app.buffer.BufferStream;
import top.eiyooooo.easycontrol.app.client.view.ClientView;

public class Client {
  private static final String AUDIO_LOG_TAG = "EasycontrolAudio";
  private static final String DISPLAY_LOG_TAG = "EasycontrolDisplay";
  private static final String TRANSPORT_LOG_TAG = "EasycontrolTransport";
  private static final String VIDEO_LOG_TAG = "EasycontrolVideo";
  private static final long VIDEO_STATS_INTERVAL_MS = 2_000L;
  private static final String DISPLAY_PROFILE_STANDARD = "standard-phone";
  private static final String DISPLAY_PROFILE_EMBEDDED = "embedded-wide";
  private static final float EMBEDDED_FLOW_MIN_ASPECT_RATIO = 16f / 9f;
  private static final int EMBEDDED_FLOW_MIN_SMALLEST_WIDTH_DP = 600;
  private static final long EMBEDDED_DISPLAY_RESIZE_DELAY_MS = 650;
  private static final long EMBEDDED_DISPLAY_SETTLE_DELAY_MS = 300;
  // 状态，0 表示初始化中，1 表示已连接，-1 表示已关闭。
  private volatile int status = 0;
  // 当前进程里所有 Client 实例的集合，用于管理多连接场景。
  public static final CopyOnWriteArrayList<Client> allClient = new CopyOnWriteArrayList<>();
  // 导航和媒体各保留一个 owner：同类不重复播放，两类可以同时输出到不同车机音频通道。
  private static final Client[] audioOwners = new Client[2];
  private static final Object audioOwnerLock = new Object();
  private static final Object multiLinkLock = new Object();
  private static final Object embeddedStartLock = new Object();
  private static boolean embeddedStartReserved;
  private static final int AUDIO_OWNER_PRIORITY_NONE = Integer.MIN_VALUE;
  private static final int AUDIO_OWNER_PRIORITY_PRIMARY = 100;
  private static final int AUDIO_OWNER_PRIORITY_DIRECT_MIRROR = 200;
  private static final int AUDIO_OWNER_PRIORITY_UID_FLOW = 300;
  private static final long AUDIO_OWNER_AUDIBLE_GRACE_MS = 1_500L;
  private static final long AUDIO_OWNER_REEVALUATE_INTERVAL_MS = 250L;

  // 连接相关对象。
  public Adb adb;
  private Adb videoAdb;
  private BufferStream bufferStream;
  private BufferStream videoStream;
  private BufferStream shell;

  // 子服务线程：分别处理控制输入和视频输入。
  private final Thread executeStreamInThread = new Thread(this::executeStreamIn);
  private final Thread executeStreamVideoThread = new Thread(this::executeStreamVideo);
  private HandlerThread handlerThread;
  private Handler handler;
  private final HandlerThread[] audioHandlerThreads = new HandlerThread[2];
  private final Handler[] audioHandlers = new Handler[2];
  private final Object lifecycleLock = new Object();
  private final AtomicBoolean releaseStarted = new AtomicBoolean(false);
  private final AtomicBoolean embeddedDisplayResizeRunning = new AtomicBoolean(false);
  private final Object embeddedHostSizeLock = new Object();
  private int embeddedHostSizeGeneration;
  private int pendingEmbeddedHostWidth;
  private int pendingEmbeddedHostHeight;
  private VirtualDisplaySpec activeVirtualDisplaySpec;
  private boolean startupConnected = false;
  private final Object mediaLifecycleLock = new Object();
  private VideoDecode videoDecode;
  // 同一个直接投屏 Client 可以同时承载媒体、导航两个独立解码器。
  private final AudioDecode[] audioDecodes = new AudioDecode[2];
  private final long[] lastAudibleAudioMs = new long[2];
  private final long[] lastAudioOwnerReevaluateMs = new long[2];
  // 控制包封装器，最终会把二进制协议写入底层通道。
  public final ControlPacket controlPacket = new ControlPacket(this::write);
  public final ClientView clientView;
  public final String uuid;
  public final String sessionId = UUID.randomUUID().toString();
  private final String serverSocketName = "easycontrol_for_car_scrcpy_"
          + sessionId.substring(0, 8);
  public final boolean embeddedMode;
  private final boolean usbTransport;
  // 0 为屏幕镜像模式，1 为应用流转模式。
  public volatile int mode = 0;
  public volatile int displayId = 0;
  // 本次应用流转对应的远端任务信息，也是 UID 定向音频采集的依据。
  private String flowPackageName = "";
  private String embeddedOrientationPackageName = "";
  private int flowUid = -1;
  private int flowCategory = ApplicationInfo.CATEGORY_UNDEFINED;
  private int audioRole = AudioDecode.ROLE_MEDIA;
  private boolean uidFilteredAudio;
  private Thread startThread;
  private final Thread loadingTimeOutThread;
  private final Thread keepAliveThread;
  private static final int startupTimeoutDelay = 30 * 1000;
  private static final int keepAliveTimeoutDelay = 5 * 1000;
  private static final int serverSocketConnectAttempts = 60;
  private static final int serverSocketRetryDelay = 250;
  private long lastKeepAliveTime;
  // 0 为单连接，1 为多连接主，2 为多连接从。
  public int multiLink = 0;

  private static final String serverName = "/data/local/tmp/easycontrol_for_car_server_" + BuildConfig.VERSION_CODE + ".jar";
  private static final boolean supportH265 = PublicTools.isDecoderSupport("hevc");
  private static final boolean supportOpus = PublicTools.isDecoderSupport("opus");

  public static boolean start(Device device, UsbDevice usbDevice, int mode) {
    boolean useEmbeddedMode = AppData.setting.getEmbeddedProjectionMode();
    if (useEmbeddedMode) {
      synchronized (embeddedStartLock) {
        if (embeddedStartReserved || hasActiveClients()) {
          Log.w("EasycontrolEmbedded", "embedded start rejected: active session exists"
                  + ", uuid=" + device.uuid + ", mode=" + mode);
          PublicTools.logToast(AppData.main.getString(R.string.error_embedded_single_session));
          return false;
        }
        if (!MainActivity.isEmbeddedProjectionHostReady()) {
          Log.w("EasycontrolEmbedded", "embedded start rejected: main host unavailable"
                  + ", uuid=" + device.uuid + ", mode=" + mode);
          PublicTools.logToast(AppData.main.getString(R.string.error_embedded_host_unavailable));
          return false;
        }
        embeddedStartReserved = true;
      }
    }

    try {
      new Client(device, usbDevice, mode, useEmbeddedMode);
      return true;
    } finally {
      if (useEmbeddedMode) {
        synchronized (embeddedStartLock) {
          embeddedStartReserved = false;
        }
      }
    }
  }

  public static boolean startEmbeddedPair(
          Device navigationDevice, Device musicDevice, UsbDevice usbDevice) {
    if (!AppData.setting.getEmbeddedProjectionMode()) {
      PublicTools.logToast(AppData.main.getString(R.string.music_navigation_requires_embedded));
      return false;
    }
    synchronized (embeddedStartLock) {
      if (embeddedStartReserved || hasActiveClients()) {
        PublicTools.logToast(AppData.main.getString(R.string.error_embedded_single_session));
        return false;
      }
      if (!MainActivity.isEmbeddedProjectionHostReady()) {
        PublicTools.logToast(AppData.main.getString(R.string.error_embedded_host_unavailable));
        return false;
      }
      embeddedStartReserved = true;
    }

    Client navigationClient = null;
    try {
      navigationDevice.embeddedSlot = Device.EMBEDDED_SLOT_LEFT;
      musicDevice.embeddedSlot = Device.EMBEDDED_SLOT_RIGHT;
      navigationDevice.temporarySession = true;
      musicDevice.temporarySession = true;
      navigationClient = new Client(navigationDevice, usbDevice, 1, true);
      new Client(musicDevice, usbDevice, 1, true);
      Log.i("EasycontrolEmbedded", "embedded music-navigation pair accepted"
              + ", uuid=" + navigationDevice.uuid
              + ", navigation=" + navigationDevice.specified_app
              + ", music=" + musicDevice.specified_app);
      return true;
    } catch (RuntimeException e) {
      Log.e("EasycontrolEmbedded", "embedded music-navigation pair start failed", e);
      if (navigationClient != null) navigationClient.release(null);
      PublicTools.logToast(AppData.main.getString(R.string.log_notify));
      return false;
    } finally {
      synchronized (embeddedStartLock) {
        embeddedStartReserved = false;
      }
    }
  }

  private Client(Device device, UsbDevice usbDevice, int mode, boolean embeddedMode) {
    // 初始化设备标识，后续多连接判断要优先使用它。
    uuid = device.uuid;
    this.embeddedMode = embeddedMode;
    usbTransport = usbDevice != null;
    // 如果指定了转移模式，就先标记为已转移。
    if (mode == 0) specifiedTransferred = true;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      // 音视频解码回调线程。
      handlerThread = new HandlerThread("easycontrol_mediacodec");
      handlerThread.start();
      handler = new Handler(handlerThread.getLooper());
      // 两种角色各用独立回调线程，导航阻塞完整写入时不会拖住媒体解码。
      for (int role = 0; role < audioHandlerThreads.length; role++) {
        audioHandlerThreads[role] = new HandlerThread(role == AudioDecode.ROLE_NAVIGATION
                ? "easycontrol_audio_navigation" : "easycontrol_audio_media");
        audioHandlerThreads[role].start();
        audioHandlers[role] = new Handler(audioHandlerThreads[role].getLooper());
      }
    }
    // 创建客户端界面对象，连接成功后会自动启动后续线程。
    clientView = new ClientView(device, controlPacket, this::changeMode, () -> {
      synchronized (lifecycleLock) {
        if (releaseStarted.get() || status != 0) return;
        status = 1;
        executeStreamInThread.start();
        executeStreamVideoThread.start();
      }
      AppData.uiHandler.post(this::executeOtherService);
    }, () -> release(null), embeddedMode, this::onEmbeddedHostSizeChanged);
    // 显示加载中弹窗，提示用户正在连接。
    Pair<View, WindowManager.LayoutParams> loading = embeddedMode || AppData.setting.getAlwaysFullMode()
            ? null : PublicTools.createLoading(AppData.main);
    // 连接超时线程，防止连接卡死。
    loadingTimeOutThread = new Thread(() -> {
      try {
        Thread.sleep(startupTimeoutDelay);
        if (!beginRelease(true)) return;
        if (startThread != null) startThread.interrupt();
        AppData.uiHandler.post(() -> {
          if (loading != null && loading.first.getParent() != null) AppData.windowManager.removeView(loading.first);
        });
        releaseResources(null);
      } catch (InterruptedException ignored) {
      }
    });
    // 保活线程，持续监控与服务端之间的心跳。
    keepAliveThread = new Thread(() -> {
      lastKeepAliveTime = System.currentTimeMillis();
      while (status != -1) {
        if (System.currentTimeMillis() - lastKeepAliveTime > keepAliveTimeoutDelay)
          release(AppData.main.getString(R.string.error_stream_closed));
        try {
          Thread.sleep(1500);
        } catch (InterruptedException ignored) {
        }
      }
    });
    // 真正发起连接和初始化服务端的线程。
    startThread = new Thread(() -> {
      try {
        adb = connectADB(device, usbDevice);
        changeMode(mode);
        changeMultiLinkMode(multiLink);
        startServer(device);
        connectServer();
        loadingTimeOutThread.interrupt();
        AppData.uiHandler.post(() -> {
          if (releaseStarted.get()) return;
          if (device.nightModeSync) controlPacket.sendNightModeEvent(AppData.nightMode);
          if (embeddedMode) clientView.changeToEmbedded();
          else if (AppData.setting.getAlwaysFullMode() || device.defaultFull) clientView.changeToFull();
          else clientView.changeToSmall();
        });
      } catch (Exception e) {
        L.log(device.uuid, e);
        release(AppData.main.getString(R.string.log_notify));
      } finally {
        if (loading != null) AppData.uiHandler.post(() -> {
          if (loading.first.getParent() != null) AppData.windowManager.removeView(loading.first);
        });
        loadingTimeOutThread.interrupt();
        if (!releaseStarted.get()) keepAliveThread.start();
      }
    });
    synchronized (multiLinkLock) {
      allClient.add(this);
      rebalanceMultiLinkModesLocked(device);
    }
    if (!EventMonitor.monitorRunning && AppData.setting.getMonitorState()) EventMonitor.startMonitor();
    if (embeddedMode) {
      Log.i("EasycontrolEmbedded", "embedded start accepted uuid=" + uuid + ", mode=" + mode);
      clientView.showEmbeddedLoading();
    } else if (AppData.setting.getAlwaysFullMode()) {
      PublicTools.logToast(AppData.main.getString(R.string.loading_text));
    } else {
      AppData.windowManager.addView(loading.first, loading.second);
    }
    loadingTimeOutThread.start();
    startThread.start();
  }

  // 连接 ADB，如果同一个设备已经有连接则复用。
  private static Adb connectADB(Device device, UsbDevice usbDevice) throws Exception {
    return Adb.getOrCreate(device.uuid, () -> {
      if (usbDevice == null) return new Adb(device.uuid, device.address, AppData.keyPair);
      return new Adb(device.uuid, usbDevice, AppData.keyPair);
    });
  }

  // 启动服务端 JAR。
  private void startServer(Device device) throws Exception {
    adb.ensureServerStarted();
    resolveFlowAudioTarget(device);
    shell = adb.getShell();
    int ScreenMode = (AppData.setting.getTurnOnScreenIfStart() ? 1 : 0) * 1000
            + (AppData.setting.getTurnOffScreenIfStart() ? 1 : 0) * 100
            + (AppData.setting.getTurnOffScreenIfStop() ? 1 : 0) * 10
            + (AppData.setting.getTurnOnScreenIfStop() ? 1 : 0);
    StringBuilder cmd = new StringBuilder();
    // 使用 Android 官方 app_process 常见的 CLASSPATH 形式。部分厂商 ROM 对
    // -Djava.class.path 的处理不稳定，会在类加载阶段直接 SIGABRT。
    cmd.append("CLASSPATH=").append(serverName).append(" app_process / top.eiyooooo.easycontrol.server.Scrcpy");
    cmd.append(" socketName=").append(serverSocketName);
    boolean startAudio = shouldStartServerAudio(device);
    Log.i(AUDIO_LOG_TAG, "audio start decision " + audioClientDescription()
            + ", deviceAudio=" + device.isAudio
            + ", enabled=" + startAudio);
    if (!startAudio) cmd.append(" isAudio=0");
    else {
      // v2 音频帧携带角色；直接投屏可在同一个 socket 内交错传输导航和媒体帧。
      cmd.append(" audioProtocol=2");
      cmd.append(" audioRole=").append(audioRole);
      if (mode == 0) cmd.append(" audioSplit=1");
      if (uidFilteredAudio) {
        cmd.append(" audioUid=").append(flowUid);
        // 只有第一路连接可回退整机混音，防止厂商不支持 UID AudioPolicy 时出现多路重复声音。
        cmd.append(" audioFallback=").append(multiLink == 2 ? 0 : 1);
      }
    }
    if (device.maxSize != 1600) cmd.append(" maxSize=").append(device.maxSize);
    if (device.maxFps != 60) cmd.append(" maxFps=").append(device.maxFps);
    if (device.maxVideoBit != 4) cmd.append(" maxVideoBit=").append(device.maxVideoBit);
    if (displayId != 0) cmd.append(" displayId=").append(displayId);
    if (AppData.setting.getNewMirrorMode()) cmd.append(" mirrorMode=1");
    if (!AppData.setting.getKeepAwake()) cmd.append(" keepAwake=0");
    if (ScreenMode != 1001) cmd.append(" ScreenMode=").append(ScreenMode);
    if (!(device.useH265 && supportH265)) cmd.append(" useH265=0");
    if (!(device.useOpus && supportOpus)) cmd.append(" useOpus=0");
    Log.i(VIDEO_LOG_TAG, "video start request uuid=" + uuid
            + ", transport=" + (usbTransport ? "USB" : "WiFi")
            + ", mode=" + mode
            + ", embedded=" + embeddedMode
            + ", socketName=" + serverSocketName
            + ", maxSize=" + device.maxSize
            + ", targetFps=" + device.maxFps
            + ", targetMbps=" + device.maxVideoBit
            + ", codec=" + (device.useH265 && supportH265 ? "H265" : "H264"));
    cmd.append(" \n");
    shell.write(ByteBuffer.wrap(cmd.toString().getBytes()));
    logger();
  }

  private void resolveFlowAudioTarget(Device device) {
    flowPackageName = "";
    flowUid = -1;
    flowCategory = ApplicationInfo.CATEGORY_UNDEFINED;
    audioRole = AudioDecode.ROLE_MEDIA;
    uidFilteredAudio = false;
    if (!device.isAudio || mode != 1) return;

    try {
      String packageName = device.specified_app == null ? "" : device.specified_app.trim();
      JSONObject appInfo;
      if (!packageName.isEmpty()) {
        appInfo = new JSONObject(Adb.getStringResponseFromServer(device, "getAppAudioInfo", "package=" + packageName));
      } else {
        appInfo = findRecentFlowTask(device);
        packageName = appInfo.optString("packageName", appInfo.optString("topPackage", ""));
      }

      flowPackageName = packageName;
      flowUid = appInfo.optInt("uid", -1);
      flowCategory = appInfo.optInt("category", ApplicationInfo.CATEGORY_UNDEFINED);
      audioRole = isNavigationApp(flowPackageName, flowCategory) ? AudioDecode.ROLE_NAVIGATION : AudioDecode.ROLE_MEDIA;
      uidFilteredAudio = flowUid > 0 && !flowPackageName.isEmpty();
      L.log(uuid, "flow audio target package=" + flowPackageName
              + ", uid=" + flowUid
              + ", displayId=" + displayId
              + ", role=" + (audioRole == AudioDecode.ROLE_NAVIGATION ? "navigation" : "media"));
    } catch (Exception e) {
      L.log(uuid, "flow audio target unavailable: " + e.getMessage());
    }
  }

  private JSONObject findRecentFlowTask(Device device) throws Exception {
    JSONObject tasks = new JSONObject(Adb.getStringResponseFromServer(device, "getRecentTasks"));
    JSONArray data = tasks.getJSONArray("data");
    for (int i = 0; i < data.length(); i++) {
      JSONObject task = data.getJSONObject(i);
      String packageName = task.optString("packageName", task.optString("topPackage", ""));
      if (task.optInt("taskId", task.optInt("id", 0)) > 0 && !packageName.isEmpty()) return task;
    }
    throw new IllegalStateException("no recent application task");
  }

  private boolean isNavigationApp(String packageName, int category) {
    if (category == ApplicationInfo.CATEGORY_MAPS) return true;
    String value = packageName == null ? "" : packageName.toLowerCase(Locale.ROOT);
    return value.contains("autonavi")
            || value.contains("baidumap")
            || value.contains("tencent.map")
            || value.contains("google.android.apps.maps")
            || value.contains("waze")
            || value.contains("navigation");
  }

  private Thread loggerThread;
  private void logger() {
    // 持续读取服务端 shell 输出，方便把日志同步到 app 侧。
    loggerThread = new Thread(() -> {
      try {
        while (!Thread.interrupted()) {
          String log = new String(shell.readAllBytes().array(), StandardCharsets.UTF_8);
          if (!log.isEmpty()) {
            L.logWithoutTime(uuid, log);
            logServerAudioLines(log);
          }
          Thread.sleep(1000);
        }
      } catch (Exception ignored) {
      }
    });
    loggerThread.start();
  }

  private void logServerAudioLines(String serverLog) {
    for (String line : serverLog.split("\\r?\\n")) {
      String normalized = line.toLowerCase(Locale.ROOT);
      if (normalized.contains("audio") || normalized.contains("opus") || normalized.contains("aopushdr")) {
        Log.i(AUDIO_LOG_TAG, "server uuid=" + uuid + " | " + line);
      }
    }
  }

  private void tryCreateDisplay(Device device) {
    try {
      // 根据用户设置决定是否启用强制桌面模式。
      if (AppData.setting.getForceDesktopMode()) adb.runAdbCmd("settings put global force_desktop_mode_on_external_displays 1");
      else adb.runAdbCmd("settings put global force_desktop_mode_on_external_displays 0");

      // 让服务端创建一个可供应用转移使用的虚拟显示器。
      VirtualDisplaySpec displaySpec = calculateVirtualDisplaySpec(device);
      if (embeddedMode && displaySpec == null) {
        Thread.sleep(200);
        displaySpec = calculateVirtualDisplaySpec(device);
        if (displaySpec == null) {
          throw new IllegalStateException("embedded projection host size unavailable");
        }
      }
      String profile = embeddedMode ? DISPLAY_PROFILE_EMBEDDED : DISPLAY_PROFILE_STANDARD;
      int createdDisplayId = createVirtualDisplay(device, displaySpec, profile, false);
      displayId = createdDisplayId;
      clientView.displayId = createdDisplayId;
      activeVirtualDisplaySpec = displaySpec;
      logCreatedDisplayConfiguration(device, displaySpec, createdDisplayId, false);
      changeMode(1);
      PublicTools.logToast(AppData.main.getString(R.string.tip_application_transfer));
    } catch (Exception e) {
      boolean androidVersionUnsupported = isAndroidVersionUnsupported(e);
      Log.e(DISPLAY_LOG_TAG, "virtual display creation failed"
              + ", profile=" + (embeddedMode ? DISPLAY_PROFILE_EMBEDDED : DISPLAY_PROFILE_STANDARD)
              + ", uuid=" + uuid
              + ", failureType=" + (androidVersionUnsupported
              ? "android-version-unsupported" : "transport-or-display"), e);
      changeMode(0);
      PublicTools.logToast(AppData.main.getString(androidVersionUnsupported
              ? R.string.error_create_display_android_version
              : R.string.error_create_display));
    }
  }

  private static boolean isAndroidVersionUnsupported(Throwable error) {
    Throwable current = error;
    while (current != null) {
      String message = current.getMessage();
      if (message != null
              && message.contains("Virtual display is not supported before Android 11")) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  private int createVirtualDisplay(Device device, VirtualDisplaySpec displaySpec,
                                   String profile, boolean runtimeRecreate) throws Exception {
    String output;
    if (displaySpec == null) {
      Log.i(DISPLAY_LOG_TAG, "create virtual display"
              + ", profile=" + profile
              + ", uuid=" + uuid
              + ", requested=phone-default"
              + ", runtimeRecreate=" + runtimeRecreate);
      output = Adb.getStringResponseFromServer(device, "createVirtualDisplay",
              "profile=" + profile);
    } else {
      Log.i(DISPLAY_LOG_TAG, "create virtual display"
              + ", profile=" + profile
              + ", uuid=" + uuid
              + ", requested=" + displaySpec
              + ", runtimeRecreate=" + runtimeRecreate);
      try {
        output = Adb.getStringResponseFromServer(device, "createVirtualDisplay",
                "width=" + displaySpec.width,
                "height=" + displaySpec.height,
                "density=" + displaySpec.densityDpi,
                "profile=" + profile);
      } catch (Exception e) {
        Log.w(DISPLAY_LOG_TAG, "custom virtual display density rejected, retrying target size", e);
        output = "";
      }
      if (!output.contains("success")) {
        Log.w(DISPLAY_LOG_TAG, "custom virtual display density failed, response=" + output);
        output = Adb.getStringResponseFromServer(device, "createVirtualDisplay",
                "width=" + displaySpec.width,
                "height=" + displaySpec.height,
                "profile=" + profile);
      }
    }
    if (!output.contains("success")) throw new IOException("create virtual display failed: " + output);
    int createdDisplayId = Integer.parseInt(output.substring(output.lastIndexOf(" -> ") + 4).trim());
    Log.i(DISPLAY_LOG_TAG, "virtual display policy response"
            + ", profile=" + profile
            + ", uuid=" + uuid
            + ", id=" + createdDisplayId
            + ", runtimeRecreate=" + runtimeRecreate
            + ", response=" + output);
    return createdDisplayId;
  }

  private VirtualDisplaySpec calculateVirtualDisplaySpec(Device device) {
    return calculateVirtualDisplaySpec(device, null);
  }

  private VirtualDisplaySpec calculateVirtualDisplaySpec(
          Device device, Pair<Integer, Integer> hostSizeOverride) {
    try {
      JSONArray displays = new JSONArray(Adb.getStringResponseFromServer(device, "getDisplayInfo"));
      int sourceWidth = 0;
      int sourceHeight = 0;
      int sourceDensityDpi = 0;
      for (int i = 0; i < displays.length(); i++) {
        JSONObject display = displays.getJSONObject(i);
        if (display.getInt("id") == 0) {
          sourceWidth = display.getInt("width");
          sourceHeight = display.getInt("height");
          sourceDensityDpi = display.optInt("density", 0);
          break;
        }
      }
      if (sourceWidth <= 0 || sourceHeight <= 0 || sourceDensityDpi <= 0) return null;

      if (!embeddedMode) {
        Log.i(DISPLAY_LOG_TAG, "standard virtual display copies phone geometry"
                + ", source=" + sourceWidth + "x" + sourceHeight
                + "@" + sourceDensityDpi + "dpi");
        return new VirtualDisplaySpec(sourceWidth, sourceHeight, sourceDensityDpi);
      }

      Pair<Integer, Integer> measuredHostSize = clientView.getMaxSize();
      Pair<Integer, Integer> hostSize;
      String hostSizeSource;
      if (isUsableHostSize(hostSizeOverride)) {
        hostSize = hostSizeOverride;
        hostSizeSource = "runtime-layout";
      } else if (isUsableHostSize(measuredHostSize)) {
        hostSize = measuredHostSize;
        hostSizeSource = "embedded-layout";
      } else {
        hostSize = MainActivity.getEmbeddedProjectionTargetSize(device.embeddedSlot);
        hostSizeSource = "main-window-fallback";
      }
      int hostDensityDpi = MainActivity.getEmbeddedProjectionTargetDensityDpi();
      if (!isUsableHostSize(hostSize)) {
        Log.w("EasycontrolEmbedded", "embedded host size unavailable");
        return null;
      }

      float rawHostRatio = (float) hostSize.first / hostSize.second;
      if (rawHostRatio < 0.25f || rawHostRatio > 4f) return null;
      // 高德等车载布局在接近方形的虚拟屏上会切换到窄屏布局并裁掉右侧区域。
      // 分屏时保留至少 16:9 的逻辑画布，再由 TextureView 等比完整放入宿主。
      boolean portraitMusicSlot = device.embeddedSlot == Device.EMBEDDED_SLOT_RIGHT;
      float targetRatio = portraitMusicSlot
              ? rawHostRatio : Math.max(rawHostRatio, EMBEDDED_FLOW_MIN_ASPECT_RATIO);
      String ratioPolicy = portraitMusicSlot
              ? "follow-music-host"
              : rawHostRatio < EMBEDDED_FLOW_MIN_ASPECT_RATIO
              ? "minimum-16:9-fit-center" : "follow-host";
      int longEdge = Math.max(sourceWidth, sourceHeight);
      int width;
      int height;
      if (targetRatio >= 1f) {
        width = longEdge;
        height = Math.round(longEdge / targetRatio);
      } else {
        width = Math.round(longEdge * targetRatio);
        height = longEdge;
      }
      width = alignDisplaySize(width);
      height = alignDisplaySize(height);
      int densityDpi = calculateEmbeddedDisplayDensity(
              width, height, hostSize.first, hostSize.second, hostDensityDpi);
      int smallestWidthDp = Math.round(Math.min(width, height) * 160f / densityDpi);
      Log.i("EasycontrolEmbedded", "create virtual display with host ratio"
              + ", host=" + hostSize.first + "x" + hostSize.second
              + "@" + hostDensityDpi + "dpi"
              + ", hostSource=" + hostSizeSource
              + ", source=" + sourceWidth + "x" + sourceHeight
              + ", target=" + width + "x" + height + "@" + densityDpi + "dpi"
              + ", rawHostRatio=" + rawHostRatio
              + ", targetRatio=" + targetRatio
              + ", ratioPolicy=" + ratioPolicy
              + ", densityPolicy=fit-center-large-screen"
              + ", targetDp=" + Math.round(width * 160f / densityDpi)
              + "x" + Math.round(height * 160f / densityDpi)
              + ", smallestWidthDp=" + smallestWidthDp);
      return new VirtualDisplaySpec(width, height, densityDpi);
    } catch (Exception e) {
      Log.w(DISPLAY_LOG_TAG, "failed to calculate virtual display size", e);
      return null;
    }
  }

  private static int alignDisplaySize(int value) {
    return Math.max(16, (value + 8) & ~15);
  }

  private static boolean isUsableHostSize(Pair<Integer, Integer> size) {
    return size != null && size.first != null && size.second != null
            && size.first > 0 && size.second > 0;
  }

  private static int calculateEmbeddedDisplayDensity(int targetWidth, int targetHeight,
                                                       int hostWidth, int hostHeight,
                                                       int hostDensityDpi) {
    if (hostDensityDpi <= 0) return 160;
    float widthScale = (float) targetWidth / hostWidth;
    float heightScale = (float) targetHeight / hostHeight;
    // 与 fit-center 的实际缩放轴一致，避免存在黑边时控件物理尺寸忽大忽小。
    int densityDpi = Math.round(hostDensityDpi * Math.max(widthScale, heightScale));
    // 像素已是横屏仍可能因密度过高而只有 300~400dp，高德会继续加载手机竖屏资源。
    // 限制密度使内嵌虚拟屏至少进入 600dp 大屏档，不影响普通镜像和非内嵌流转。
    int largeScreenMaxDensity = Math.max(72,
            (int) Math.floor(Math.min(targetWidth, targetHeight) * 160f
                    / EMBEDDED_FLOW_MIN_SMALLEST_WIDTH_DP));
    densityDpi = Math.min(densityDpi, largeScreenMaxDensity);
    return Math.max(72, Math.min(1000, densityDpi));
  }

  private void logCreatedDisplayConfiguration(Device device, VirtualDisplaySpec requestedSpec,
                                              int createdDisplayId, boolean runtimeResize) {
    try {
      JSONArray displays = new JSONArray(Adb.getStringResponseFromServer(device, "getDisplayInfo"));
      for (int i = 0; i < displays.length(); i++) {
        JSONObject display = displays.getJSONObject(i);
        if (display.optInt("id", -1) != createdDisplayId) continue;
        int actualWidth = display.optInt("width");
        int actualHeight = display.optInt("height");
        int actualRotation = display.optInt("rotation");
        boolean geometryMismatch = requestedSpec != null
                && (actualWidth != requestedSpec.width || actualHeight != requestedSpec.height);
        String message = "virtual display ready"
                + ", profile=" + (embeddedMode ? DISPLAY_PROFILE_EMBEDDED : DISPLAY_PROFILE_STANDARD)
                + ", id=" + createdDisplayId
                + ", requested=" + (requestedSpec == null ? "phone-default" : requestedSpec)
                + ", actual=" + actualWidth + "x" + actualHeight
                + "@" + display.optInt("density") + "dpi"
                + ", rotation=" + actualRotation
                + ", geometryMismatch=" + geometryMismatch
                + ", runtimeResize=" + runtimeResize;
        if (geometryMismatch || actualRotation != 0) Log.w(DISPLAY_LOG_TAG, message);
        else Log.i(DISPLAY_LOG_TAG, message);
        return;
      }
      Log.w(DISPLAY_LOG_TAG, "created virtual display not found in display info, id=" + createdDisplayId);
    } catch (Exception e) {
      Log.w(DISPLAY_LOG_TAG, "failed to read created virtual display configuration", e);
    }
  }

  private void onEmbeddedHostSizeChanged(int width, int height) {
    if (!embeddedMode || mode != 1 || width <= 0 || height <= 0 || releaseStarted.get()) return;
    final int generation;
    synchronized (embeddedHostSizeLock) {
      pendingEmbeddedHostWidth = width;
      pendingEmbeddedHostHeight = height;
      generation = ++embeddedHostSizeGeneration;
    }
    scheduleEmbeddedDisplayResize(width, height, generation, EMBEDDED_DISPLAY_RESIZE_DELAY_MS);
  }

  private void scheduleEmbeddedDisplayResize(
          int width, int height, int generation, long delayMs) {
    AppData.uiHandler.postDelayed(() -> {
      synchronized (embeddedHostSizeLock) {
        if (generation != embeddedHostSizeGeneration
                || width != pendingEmbeddedHostWidth
                || height != pendingEmbeddedHostHeight) return;
      }
      new Thread(() -> resizeEmbeddedVirtualDisplay(width, height, generation),
              "easycontrol_embedded_display_resize").start();
    }, delayMs);
  }

  private void resizeEmbeddedVirtualDisplay(int hostWidth, int hostHeight, int generation) {
    if (releaseStarted.get() || mode != 1) return;
    if (status == 0 || displayId == 0) {
      // TextureView 首次布局可能早于 scrcpy socket 就绪。保留本次尺寸，连接完成后再执行，
      // 否则全屏/分屏启动时会永久沿用创建阶段的旧比例。
      scheduleEmbeddedDisplayResize(
              hostWidth, hostHeight, generation, EMBEDDED_DISPLAY_SETTLE_DELAY_MS);
      return;
    }
    if (status != 1) return;
    if (!embeddedDisplayResizeRunning.compareAndSet(false, true)) {
      Log.i(DISPLAY_LOG_TAG, "embedded display resize deferred"
              + ", uuid=" + uuid
              + ", host=" + hostWidth + "x" + hostHeight);
      scheduleEmbeddedDisplayResize(
              hostWidth, hostHeight, generation, EMBEDDED_DISPLAY_RESIZE_DELAY_MS);
      return;
    }

    try {
      if (releaseStarted.get() || status != 1 || mode != 1 || displayId == 0) return;
      synchronized (embeddedHostSizeLock) {
        if (generation != embeddedHostSizeGeneration) return;
      }

      VirtualDisplaySpec nextSpec = calculateVirtualDisplaySpec(
              clientView.device, new Pair<>(hostWidth, hostHeight));
      if (nextSpec == null) throw new IOException("embedded host display spec unavailable");
      VirtualDisplaySpec currentSpec = activeVirtualDisplaySpec;
      if (!nextSpec.materiallyDiffersFrom(currentSpec)) {
        Log.i(DISPLAY_LOG_TAG, "embedded display resize skipped"
                + ", uuid=" + uuid
                + ", host=" + hostWidth + "x" + hostHeight
                + ", active=" + currentSpec
                + ", requested=" + nextSpec);
        return;
      }

      int resizedDisplayId = displayId;
      Log.i(DISPLAY_LOG_TAG, "embedded display resize requested"
              + ", uuid=" + uuid
              + ", displayId=" + resizedDisplayId
              + ", host=" + hostWidth + "x" + hostHeight
              + ", old=" + currentSpec
              + ", new=" + nextSpec);
      String response = Adb.getStringResponseFromServer(
              clientView.device,
              "resizeDisplay",
              "id=" + resizedDisplayId,
              "width=" + nextSpec.width,
              "height=" + nextSpec.height,
              "density=" + nextSpec.densityDpi);
      if (!response.contains("success")) {
        throw new IOException("resize virtual display failed: " + response);
      }
      if (releaseStarted.get() || status != 1 || mode != 1 || displayId != resizedDisplayId) {
        throw new IOException("projection session changed during display resize");
      }
      activeVirtualDisplaySpec = nextSpec;

      // 先等 Android 把新的显示配置分发给高德，再让采集端重新读取同一个 display。
      Thread.sleep(EMBEDDED_DISPLAY_SETTLE_DELAY_MS);
      controlPacket.sendConfigChangedEvent(2);
      Thread.sleep(EMBEDDED_DISPLAY_SETTLE_DELAY_MS);
      logCreatedDisplayConfiguration(clientView.device, nextSpec, resizedDisplayId, true);
      Log.i(DISPLAY_LOG_TAG, "embedded display resize complete"
              + ", uuid=" + uuid
              + ", host=" + hostWidth + "x" + hostHeight
              + ", displayId=" + resizedDisplayId
              + ", spec=" + nextSpec
              + ", response=" + response
              + ", scale=fit-center");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      Log.w(DISPLAY_LOG_TAG, "embedded display resize interrupted, uuid=" + uuid, e);
    } catch (Exception e) {
      Log.e(DISPLAY_LOG_TAG, "embedded display resize failed"
              + ", uuid=" + uuid
              + ", host=" + hostWidth + "x" + hostHeight
              + ", displayId=" + displayId, e);
    } finally {
      embeddedDisplayResizeRunning.set(false);
    }
  }

  private static final class VirtualDisplaySpec {
    final int width;
    final int height;
    final int densityDpi;

    VirtualDisplaySpec(int width, int height, int densityDpi) {
      this.width = width;
      this.height = height;
      this.densityDpi = densityDpi;
    }

    boolean materiallyDiffersFrom(VirtualDisplaySpec other) {
      return other == null
              || Math.abs(width - other.width) >= 16
              || Math.abs(height - other.height) >= 16
              || Math.abs(densityDpi - other.densityDpi) >= 8;
    }

    @Override
    public String toString() {
      return width + "x" + height + "@" + densityDpi + "dpi";
    }
  }

  boolean specifiedTransferred = false;
  private void appTransfer(Device device) {
    try {
      // 先尽量拿到最近任务列表，用于决定把哪个任务移到新显示器。
      JSONArray tasksArray = null;
      try {
        JSONObject tasks = new JSONObject(Adb.getStringResponseFromServer(device, "getRecentTasks"));
        tasksArray = tasks.getJSONArray("data");
        for (int i = 0; i < tasksArray.length(); i++) {
          int taskId = tasksArray.getJSONObject(i).getInt("taskId");
          String topPackage = tasksArray.getJSONObject(i).getString("topPackage");
          if (taskId <= 0 || topPackage.isEmpty()) {
            tasksArray.remove(i);
            i--;
          }
        }
      } catch (Exception ignored) {
      }
      if (!specifiedTransferred && !device.specified_app.isEmpty()) {
        // 如果用户指定了目标应用，就优先把这个应用转移过去。
        String checkApp = Adb.getStringResponseFromServer(device, "getAppMainActivity", "package=" + device.specified_app);
        if (checkApp.isEmpty()) {
          PublicTools.logToast(AppData.main.getString(R.string.error_app_not_found));
          throw new Exception("");
        } else {
          int appTaskId = 0;
          if (tasksArray != null) {
            for (int i = 0; i < tasksArray.length(); i++) {
              if (tasksArray.getJSONObject(i).getString("topPackage").equals(device.specified_app)) {
                try {
                  appTaskId = tasksArray.getJSONObject(i).getInt("taskId");
                } catch (JSONException ignored) {
                }
                break;
              }
            }
          }
          if (appTaskId == 0) {
            // 应用还没在前台时，直接拉起到目标显示器。
            String output = Adb.getStringResponseFromServer(device, "openAppByPackage", "package=" + device.specified_app, "displayId=" + displayId);
            if (output.contains("failed")) throw new Exception("");
          } else {
            // 从最近任务正式启动到目标显示器，让应用收到目标屏尺寸和 DPI 配置。
            moveFlowTaskToDisplay(device, appTaskId, device.specified_app);
          }
          specifiedTransferred = true;
        }
      } else {
        // 没有指定应用时，默认把最近前台任务移动过去。
        if (tasksArray != null && tasksArray.length() > 0) {
          int targetTaskId = tasksArray.getJSONObject(0).getInt("taskId");
          // 音频 UID 和被移动任务必须来自同一个包，避免任务顺序变化后抓错应用声音。
          if (!flowPackageName.isEmpty()) {
            for (int i = 0; i < tasksArray.length(); i++) {
              JSONObject task = tasksArray.getJSONObject(i);
              if (flowPackageName.equals(task.optString("topPackage", ""))) {
                targetTaskId = task.getInt("taskId");
                break;
              }
            }
          }
          String targetPackage = "";
          for (int i = 0; i < tasksArray.length(); i++) {
            JSONObject task = tasksArray.getJSONObject(i);
            if (task.optInt("taskId", 0) == targetTaskId) {
              targetPackage = task.optString("topPackage", "");
              break;
            }
          }
          moveFlowTaskToDisplay(device, targetTaskId, targetPackage);
        } else throw new Exception("");
      }
    } catch (Exception ignored) {
      specifiedTransferred = true;
      changeMode(0);
      PublicTools.logToast(AppData.main.getString(R.string.error_transfer_app_failed));
    }
  }

  private void moveFlowTaskToDisplay(Device device, int taskId, String packageName) throws Exception {
    ArrayList<String> parameters = new ArrayList<>();
    parameters.add("taskId=" + taskId);
    parameters.add("displayId=" + displayId);
    if (packageName != null && !packageName.isEmpty()) parameters.add("package=" + packageName);
    if (embeddedMode) parameters.add("recreate=1");
    String output = Adb.getStringResponseFromServer(
            device, "moveTaskToDisplay", parameters.toArray(new String[0]));
    Log.i(DISPLAY_LOG_TAG, "flow task moved"
            + ", profile=" + (embeddedMode ? DISPLAY_PROFILE_EMBEDDED : DISPLAY_PROFILE_STANDARD)
            + ", taskId=" + taskId
            + ", package=" + packageName
            + ", displayId=" + displayId
            + ", response=" + output);
    if (!output.contains("success")) throw new IllegalStateException(output);
    if (embeddedMode && packageName != null && !packageName.isEmpty()
            && output.contains("orientationOverride=true")) {
      synchronized (lifecycleLock) {
        embeddedOrientationPackageName = packageName;
      }
    }
  }

  private void restoreEmbeddedOrientationOverride(Device device) {
    String packageName;
    synchronized (lifecycleLock) {
      packageName = embeddedOrientationPackageName;
      embeddedOrientationPackageName = "";
    }
    if (packageName == null || packageName.isEmpty()) return;
    try {
      String response = Adb.getStringResponseFromServer(
              device, "setOrientationOverride", "package=" + packageName, "enabled=0");
      Log.i(DISPLAY_LOG_TAG, "embedded orientation override restored"
              + ", package=" + packageName + ", response=" + response);
    } catch (Exception error) {
      Log.w(DISPLAY_LOG_TAG, "restore embedded orientation override failed"
              + ", package=" + packageName, error);
    }
  }

  // 连接Server
  private void connectServer() throws Exception {
    Thread.sleep(50);
    Adb pendingVideoAdb = null;
    try {
      if (!usbTransport) {
        pendingVideoAdb = Adb.createAuxiliaryTcp(
                uuid + "-video-" + sessionId.substring(0, 8),
                clientView.device.address,
                AppData.keyPair);
        Log.i(TRANSPORT_LOG_TAG, "WiFi split transport ready uuid=" + uuid
                + ", control=primary, video=auxiliary");
      }

      for (int i = 0; i < serverSocketConnectAttempts; i++) {
        BufferStream controlStream = null;
        BufferStream pendingVideoStream = null;
        try {
          controlStream = adb.localSocketForward(serverSocketName);
          pendingVideoStream = (pendingVideoAdb == null ? adb : pendingVideoAdb)
                  .localSocketForward(serverSocketName);
          synchronized (lifecycleLock) {
            if (releaseStarted.get()) {
              throw new InterruptedException("client released while connecting");
            }
            bufferStream = controlStream;
            videoStream = pendingVideoStream;
            videoAdb = pendingVideoAdb;
            startupConnected = true;
          }
          pendingVideoAdb = null;
          L.log(uuid, "scrcpy sockets connected, attempt=" + (i + 1)
                  + ", socketName=" + serverSocketName
                  + ", transport=" + (usbTransport ? "USB shared" : "WiFi split"));
          return;
        } catch (Exception ignored) {
          if (controlStream != null) controlStream.close();
          if (pendingVideoStream != null) pendingVideoStream.close();
          if (releaseStarted.get() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("client connection cancelled");
          }
          Thread.sleep(serverSocketRetryDelay);
        }
      }
      throw new Exception(AppData.main.getString(R.string.error_connect_server));
    } finally {
      if (pendingVideoAdb != null) pendingVideoAdb.close();
    }
  }

  // 服务分发
  private static final int AUDIO_EVENT = 2;
  private static final int CLIPBOARD_EVENT = 3;
  private static final int CHANGE_SIZE_EVENT = 4;
  private static final int KEEP_ALIVE_EVENT = 5;

  private void executeStreamVideo() {
    try {
      if (status != 1 || Thread.currentThread().isInterrupted()) return;
      // 视频流参数
      boolean useH265 = videoStream.readByte() == 1;
      Pair<Integer, Integer> videoSize = new Pair<>(videoStream.readInt(), videoStream.readInt());
      Log.i(VIDEO_LOG_TAG, "video handshake uuid=" + uuid
              + ", transport=" + (usbTransport ? "USB" : "WiFi")
              + ", codec=" + (useH265 ? "H265" : "H264")
              + ", size=" + videoSize.first + "x" + videoSize.second);
      Surface surface = clientView.getSurface();
      Pair<byte[], Long> csd0 = new Pair<>(controlPacket.readFrame(videoStream), videoStream.readLong());
      Pair<byte[], Long> csd1 = useH265 ? null : new Pair<>(controlPacket.readFrame(videoStream), videoStream.readLong());
      if (status != 1 || Thread.currentThread().isInterrupted()) {
        surface.release();
        return;
      }
      synchronized (mediaLifecycleLock) {
        if (status != 1 || Thread.currentThread().isInterrupted()) {
          surface.release();
          return;
        }
        videoDecode = new VideoDecode(videoSize, surface, csd0, csd1, handler);
      }
      surface.release();
      // 循环处理报文
      long statsStartedAtMs = SystemClock.elapsedRealtime();
      long receivedFrames = 0;
      long receivedBytes = 0;
      int largestFrameBytes = 0;
      while (!Thread.interrupted()) {
        byte[] frame = controlPacket.readFrame(videoStream);
        long pts = videoStream.readLong();
        receivedFrames++;
        receivedBytes += frame.length;
        largestFrameBytes = Math.max(largestFrameBytes, frame.length);
        videoDecode.decodeIn(frame, pts);

        long now = SystemClock.elapsedRealtime();
        long elapsedMs = now - statsStartedAtMs;
        if (elapsedMs >= VIDEO_STATS_INTERVAL_MS) {
          float fps = receivedFrames * 1_000f / elapsedMs;
          float mbps = receivedBytes * 8f / elapsedMs / 1_000f;
          Log.i(VIDEO_LOG_TAG, String.format(Locale.US,
                  "video receive stats uuid=%s, transport=%s, in=%.1ffps/%.2fMbps, largest=%dKB, adbBuffered=%dKB, decoderPending=%d",
                  uuid, usbTransport ? "USB" : "WiFi", fps, mbps, largestFrameBytes / 1_024,
                  videoStream.getSize() / 1_024, videoDecode.getPendingFrameCount()));
          statsStartedAtMs = now;
          receivedFrames = 0;
          receivedBytes = 0;
          largestFrameBytes = 0;
        }
      }
    } catch (Exception e) {
      L.log(uuid, e);
      release(AppData.main.getString(R.string.log_notify));
    }
  }

  private void executeStreamIn() {
    try {
      // 音频流参数
      boolean useOpus = true;
      int audioProtocol = bufferStream.readByte() & 0xff;
      boolean taggedAudio = audioProtocol == 2;
      if (audioProtocol != 0) useOpus = bufferStream.readByte() == 1;
      Log.i(AUDIO_LOG_TAG, "handshake uuid=" + uuid
              + ", protocol=" + audioProtocol
              + ", tagged=" + taggedAudio
              + ", codec=" + (useOpus ? "opus" : "aac"));
      // 循环处理报文
      while (!Thread.interrupted()) {
        switch (bufferStream.readByte()) {
          case AUDIO_EVENT:
            int frameRole = taggedAudio ? normalizeAudioRole(bufferStream.readByte()) : normalizeAudioRole(audioRole);
            byte[] audioFrame = controlPacket.readAudioFrame(bufferStream);
            if (audioFrame.length == 0) break;
            if (!canPlayAudio()) break;
            AudioDecode decoder = audioDecodes[frameRole];
            if (decoder != null) decoder.decodeIn(audioFrame);
            else {
              synchronized (mediaLifecycleLock) {
                if (status != 1 || Thread.currentThread().isInterrupted()) return;
                if (audioDecodes[frameRole] == null) {
                  final int decoderRole = frameRole;
                  audioDecodes[frameRole] = new AudioDecode(
                          useOpus, audioFrame, audioHandlers[frameRole], frameRole,
                          () -> onDecodedAudioActivity(decoderRole));
                  L.log(uuid, "audio decoder created role=" + audioRoleName(frameRole)
                          + ", protocol=" + (taggedAudio ? "tagged" : "legacy"));
                }
              }
              playAudio(frameRole, true);
            }
            break;
          case CLIPBOARD_EVENT:
            int clipboardLength = bufferStream.readInt();
            if (clipboardLength < 0 || clipboardLength > 5000) throw new IOException("invalid clipboard length: " + clipboardLength);
            controlPacket.nowClipboardText = new String(bufferStream.readByteArray(clipboardLength).array(), StandardCharsets.UTF_8);
            if (clientView.device.clipboardSync) AppData.clipBoard.setPrimaryClip(ClipData.newPlainText(MIMETYPE_TEXT_PLAIN, controlPacket.nowClipboardText));
            break;
          case CHANGE_SIZE_EVENT:
            Pair<Integer, Integer> newVideoSize = new Pair<>(bufferStream.readInt(), bufferStream.readInt());
            Log.i(DISPLAY_LOG_TAG, "video size event"
                    + ", uuid=" + uuid
                    + ", displayId=" + displayId
                    + ", video=" + newVideoSize.first + "x" + newVideoSize.second
                    + ", host=" + clientView.getMaxSize()
                    + ", virtualDisplay=" + activeVirtualDisplaySpec);
            AppData.uiHandler.post(() -> clientView.updateVideoSize(newVideoSize));
            break;
          case KEEP_ALIVE_EVENT:
            lastKeepAliveTime = System.currentTimeMillis();
            break;
        }
      }
    } catch (Exception e) {
      L.log(uuid, e);
      release(AppData.main.getString(R.string.log_notify));
    }
  }

  private void executeOtherService() {
    if (status == 1) {
      if (clientView.device.clipboardSync) controlPacket.checkClipBoard();
      controlPacket.sendKeepAlive();
      AppData.uiHandler.postDelayed(this::executeOtherService, 1500);
    }
  }

  private void write(ByteBuffer byteBuffer) {
    try {
      bufferStream.write(byteBuffer);
    } catch (Exception e) {
      L.log(uuid, e);
      release(AppData.main.getString(R.string.log_notify));
    }
  }

  public void release(String error) {
    if (!beginRelease(false)) return;
    releaseResources(error);
  }

  private boolean beginRelease(boolean initializationTimeout) {
    synchronized (lifecycleLock) {
      if (initializationTimeout && (startupConnected || status != 0)) return false;
      if (!releaseStarted.compareAndSet(false, true)) return false;
      status = -1;
      return true;
    }
  }

  private void releaseResources(String error) {
    Log.i(AUDIO_LOG_TAG, "client release " + audioClientDescription()
            + ", error=" + (error == null ? "none" : error));
    releaseAudioOwner();
    synchronized (multiLinkLock) {
      allClient.remove(this);
      rebalanceMultiLinkModesLocked(clientView.deviceOriginal);
    }
    if (error != null) {
      PublicTools.logToast(error);
      if (AppData.setting.getShowReconnect() && !clientView.deviceOriginal.temporarySession)
        AppData.uiHandler.postDelayed(() -> AppData.myBroadcastReceiver.handleReconnect(clientView.deviceOriginal, mode), 500);
    }
    for (int i = 0; i < 7; i++) {
      try {
        switch (i) {
          case 0:
            if (displayId != 0) {
              int releasedDisplayId = displayId;
              new Thread(() -> {
                try {
                  restoreEmbeddedOrientationOverride(clientView.device);
                  Adb.getStringResponseFromServer(clientView.device, "releaseVirtualDisplay", "id=" + releasedDisplayId);
                } catch (Exception ignored) {
                }
              }).start();
            }
            break;
          case 1:
            break;
          case 2:
            if (loggerThread != null) loggerThread.interrupt();
            String log = new String(shell.readAllBytes().array(), StandardCharsets.UTF_8);
            if (!log.isEmpty()) {
              L.logWithoutTime(uuid, log);
              logServerAudioLines(log);
            }
            break;
          case 3:
            if (startThread != null) startThread.interrupt();
            loadingTimeOutThread.interrupt();
            keepAliveThread.interrupt();
            executeStreamInThread.interrupt();
            executeStreamVideoThread.interrupt();
            break;
          case 4:
            break;
          case 5:
            try {
              if (bufferStream != null) bufferStream.close();
            } catch (Exception ignored) {
            }
            try {
              if (videoStream != null) videoStream.close();
            } catch (Exception ignored) {
            }
            try {
              if (videoAdb != null) videoAdb.close();
            } catch (Exception ignored) {
            } finally {
              videoAdb = null;
            }
            try {
              if (shell != null) shell.close();
            } catch (Exception ignored) {
            }
            break;
          case 6:
            synchronized (mediaLifecycleLock) {
              if (videoDecode != null) videoDecode.release();
              for (AudioDecode decoder : audioDecodes) {
                if (decoder != null) decoder.release();
              }
              if (handlerThread != null) handlerThread.quitSafely();
              for (HandlerThread audioHandlerThread : audioHandlerThreads) {
                if (audioHandlerThread != null) audioHandlerThread.quitSafely();
              }
            }
            AppData.uiHandler.post(() -> clientView.hide(true));
            break;
        }
      } catch (Exception ignored) {
      }
    }
  }

  public static void runOnceCmd(Device device, UsbDevice usbDevice, String cmd, PublicTools.MyFunctionBoolean handle) {
    new Thread(() -> {
      try {
        Adb adb = connectADB(device, usbDevice);
        adb.runAdbCmd(cmd);
        handle.run(true);
      } catch (Exception ignored) {
        handle.run(false);
      }
    }).start();
  }

  public static ArrayList<String> getAppList(Device device, UsbDevice usbDevice) {
    try {
      Adb.getOrCreate(device.uuid, () -> {
        if (device.isLinkDevice()) return new Adb(device.uuid, usbDevice, AppData.keyPair);
        return new Adb(device.uuid, device.address, AppData.keyPair);
      });
      ArrayList<String> appList = new ArrayList<>();
      String output = Adb.getStringResponseFromServer(device, "getAllAppInfo", "app_type=1");
      String[] allAppInfo = output.split("<!@n@!>");
      for (String info : allAppInfo) {
        String[] appInfo = info.split("<!@r@!>");
        if (appInfo.length > 1) appList.add(appInfo[1] + "@" + appInfo[0]);
      }
      return appList;
    } catch (Exception e) {
      L.log(device.uuid, e);
      return new ArrayList<>();
    }
  }

  public static void restartOnTcpip(Device device, UsbDevice usbDevice, PublicTools.MyFunctionBoolean handle) {
    new Thread(() -> {
      try {
        Adb adb = connectADB(device, usbDevice);
        String output = adb.restartOnTcpip(5555);
        handle.run(output.contains("restarting"));
      } catch (Exception ignored) {
        handle.run(false);
      }
    }).start();
  }

  // 检查是否启动完成
  public boolean isStarted() {
    return status == 1 && clientView != null;
  }

  public boolean isClosed() {
    return status == -1 || clientView == null;
  }

  public static boolean hasActiveClients() {
    for (Client client : allClient) {
      if (!client.isClosed()) return true;
    }
    return false;
  }

  public static int releaseAll() {
    ArrayList<Client> activeClients = new ArrayList<>();
    for (Client client : allClient) {
      if (!client.isClosed()) activeClients.add(client);
    }
    Log.i(DISPLAY_LOG_TAG, "release all projections requested, activeClients=" + activeClients.size());
    for (Client client : activeClients) client.release(null);
    return activeClients.size();
  }

  public static void releaseEmbeddedSessions() {
    for (Client client : allClient) {
      if (!client.isClosed() && client.embeddedMode) client.release(null);
    }
  }

  public static Client findBySessionId(String sessionId) {
    if (sessionId == null) return null;
    for (Client client : allClient) {
      if (sessionId.equals(client.sessionId)) return client;
    }
    return null;
  }

  public void changeMode(int mode) {
    if (isClosed()) return;
    if (this.mode == mode) return;
    this.mode = mode;
    if (mode == 0) {
      synchronized (embeddedHostSizeLock) {
        embeddedHostSizeGeneration++;
      }
      try {
        restoreEmbeddedOrientationOverride(clientView.device);
        Adb.getStringResponseFromServer(clientView.device, "releaseVirtualDisplay", "id=" + displayId);
      } catch (Exception ignored) {
      }
      displayId = 0;
      clientView.displayId = 0;
      activeVirtualDisplaySpec = null;
    } else if (mode == 1) {
      tryCreateDisplay(clientView.device);
      if (displayId == 0) return;
    }
    new Thread(() -> {
      try {
        while (!isStarted() && !isClosed()) {
          Thread.sleep(1000);
        }
        if (isClosed()) return;
        controlPacket.sendConfigChangedEvent(-displayId);
        if (mode != 0) appTransfer(clientView.device);
      } catch (Exception ignored) {
      }
    }).start();
    clientView.changeMode(mode);
  }

  public void changeMultiLinkMode(int multiLink) {
    int oldMultiLink = this.multiLink;
    this.multiLink = multiLink;
    clientView.multiLink = multiLink;
    playAudio(canPlayAudio());
    if (oldMultiLink != multiLink) {
      Log.i(AUDIO_LOG_TAG, "multi-link changed " + audioClientDescription()
              + ", old=" + oldMultiLink
              + ", new=" + multiLink
              + ", audioEligible=" + canPlayAudio());
    }
    if (multiLink == 2) {
      clientView.device.clipboardSync = false;
      clientView.device.nightModeSync = false;
    } else if (multiLink == 0 || multiLink == 1) {
      if (clientView.deviceOriginal.clipboardSync) clientView.device.clipboardSync = true;
      if (clientView.deviceOriginal.nightModeSync) clientView.device.nightModeSync = true;
    }
  }

  public void playAudio(boolean play) {
    synchronized (audioOwnerLock) {
      for (int role = 0; role < audioDecodes.length; role++) {
        playAudioLocked(role, play);
      }
    }
  }

  private void playAudio(int role, boolean play) {
    synchronized (audioOwnerLock) {
      playAudioLocked(normalizeAudioRole(role), play);
    }
  }

  private void playAudioLocked(int role, boolean play) {
    AudioDecode decoder = audioDecodes[role];
    if (decoder == null) return;
    if (play && !canPlayAudio()) return;
    if (play) requestAudioOwnerLocked(role, false);
    else {
      if (audioOwners[role] == this) audioOwners[role] = null;
      decoder.playAudio(false);
    }
  }

  private boolean canPlayAudio() {
    // 直接投屏即使是同设备的从连接，也要预建双角色音频管线，供应用流转关闭后无缝接管。
    return uidFilteredAudio || mode == 0 || multiLink == 0 || multiLink == 1;
  }

  private boolean shouldStartServerAudio(Device device) {
    return device.isAudio && canPlayAudio();
  }

  private boolean isSamePhysicalDevice(Device device) {
    if (device.uuid.equals(uuid)) return true;
    if (clientView == null || clientView.deviceOriginal == null) return false;
    Device oldDevice = clientView.deviceOriginal;
    if (!oldDevice.isNormalDevice() || !device.isNormalDevice()) return false;
    if (oldDevice.address == null || device.address == null) return false;
    return !oldDevice.address.isEmpty() && oldDevice.address.equals(device.address);
  }

  private static void rebalanceMultiLinkModesLocked(Device device) {
    if (device == null) return;
    ArrayList<Client> sameDeviceClients = new ArrayList<>();
    for (Client client : allClient) {
      if (!client.isClosed() && client.isSamePhysicalDevice(device)) sameDeviceClients.add(client);
    }
    for (int i = 0; i < sameDeviceClients.size(); i++) {
      int targetMode = sameDeviceClients.size() == 1 ? 0 : (i == 0 ? 1 : 2);
      sameDeviceClients.get(i).changeMultiLinkMode(targetMode);
    }
    Log.i(AUDIO_LOG_TAG, "multi-link rebalanced uuid=" + device.uuid
            + ", activeClients=" + sameDeviceClients.size());
  }

  private void onDecodedAudioActivity(int role) {
    role = normalizeAudioRole(role);
    synchronized (audioOwnerLock) {
      long now = SystemClock.elapsedRealtime();
      lastAudibleAudioMs[role] = now;
      if (audioOwners[role] == this
              || now - lastAudioOwnerReevaluateMs[role] < AUDIO_OWNER_REEVALUATE_INTERVAL_MS) {
        return;
      }
      lastAudioOwnerReevaluateMs[role] = now;
      requestAudioOwnerLocked(role, true);
    }
  }

  private void requestAudioOwnerLocked(int role, boolean audibleRequest) {
    AudioDecode decoder = audioDecodes[role];
    if (decoder == null) return;
    int requestedPriority = audioOwnerPriority(role);
    if (requestedPriority == AUDIO_OWNER_PRIORITY_NONE) {
      decoder.playAudio(false);
      Log.i(AUDIO_LOG_TAG, "audio owner rejected role=" + audioRoleName(role)
              + ", requester={" + audioClientDescription() + "}");
      return;
    }

    Client oldOwner = audioOwners[role];
    if (oldOwner == this) {
      decoder.playAudio(true);
      return;
    }

    int oldPriority = oldOwner == null ? AUDIO_OWNER_PRIORITY_NONE : oldOwner.audioOwnerPriority(role);
    long now = SystemClock.elapsedRealtime();
    long oldAudibleAgeMs = oldOwner == null || oldOwner.lastAudibleAudioMs[role] == 0
            ? Long.MAX_VALUE : now - oldOwner.lastAudibleAudioMs[role];
    boolean retainEqualPriorityOwner = oldPriority == requestedPriority
            && (!audibleRequest || oldAudibleAgeMs <= AUDIO_OWNER_AUDIBLE_GRACE_MS);
    if (oldOwner != null
            && oldOwner.audioDecodes[role] != null
            && (oldPriority > requestedPriority || retainEqualPriorityOwner)) {
      decoder.playAudio(false);
      Log.i(AUDIO_LOG_TAG, "audio owner retained role=" + audioRoleName(role)
              + ", owner={" + oldOwner.audioClientDescription() + "}"
              + ", ownerPriority=" + oldPriority
              + ", ownerAudibleAgeMs=" + (oldAudibleAgeMs == Long.MAX_VALUE
                      ? "never" : oldAudibleAgeMs)
              + ", standby={" + audioClientDescription() + "}"
              + ", standbyPriority=" + requestedPriority
              + ", request=" + (audibleRequest ? "audible" : "pipeline"));
      return;
    }

    if (oldOwner != null && oldOwner != this && oldOwner.audioDecodes[role] != null) {
      oldOwner.audioDecodes[role].playAudio(false);
    }
    audioOwners[role] = this;
    decoder.playAudio(true);
    Log.i(AUDIO_LOG_TAG, "audio owner changed role=" + audioRoleName(role)
            + ", old={" + (oldOwner == null ? "none" : oldOwner.audioClientDescription()) + "}"
            + ", new={" + audioClientDescription() + "}"
            + ", priority=" + requestedPriority
            + ", request=" + (audibleRequest ? "audible" : "pipeline")
            + ", oldAudibleAgeMs=" + (oldAudibleAgeMs == Long.MAX_VALUE
                    ? "never" : oldAudibleAgeMs));
  }

  private void releaseAudioOwner() {
    synchronized (audioOwnerLock) {
      for (int role = 0; role < audioOwners.length; role++) {
        if (audioOwners[role] != this) continue;
        audioOwners[role] = null;
        Client replacement = null;
        int replacementPriority = AUDIO_OWNER_PRIORITY_NONE;
        for (Client client : allClient) {
          if (client == this || client.audioDecodes[role] == null) continue;
          int candidatePriority = client.audioOwnerPriority(role);
          if (candidatePriority > replacementPriority) {
            replacement = client;
            replacementPriority = candidatePriority;
          }
        }
        Log.i(AUDIO_LOG_TAG, "audio owner released role=" + audioRoleName(role)
                + ", old={" + audioClientDescription() + "}"
                + ", replacement={" + (replacement == null ? "none" : replacement.audioClientDescription()) + "}"
                + ", replacementPriority=" + replacementPriority);
        if (replacement != null) replacement.requestAudioOwnerLocked(role, false);
      }
    }
  }

  private int audioOwnerPriority(int role) {
    role = normalizeAudioRole(role);
    if (isClosed() || audioDecodes[role] == null || !canPlayAudio()) return AUDIO_OWNER_PRIORITY_NONE;
    if (uidFilteredAudio) {
      return normalizeAudioRole(audioRole) == role
              ? AUDIO_OWNER_PRIORITY_UID_FLOW : AUDIO_OWNER_PRIORITY_NONE;
    }
    if (mode == 0) return AUDIO_OWNER_PRIORITY_DIRECT_MIRROR;
    if (multiLink == 0 || multiLink == 1) return AUDIO_OWNER_PRIORITY_PRIMARY;
    return AUDIO_OWNER_PRIORITY_NONE;
  }

  private String audioClientDescription() {
    String shortSessionId = sessionId.length() > 8 ? sessionId.substring(0, 8) : sessionId;
    return "session=" + shortSessionId
            + ", uuid=" + uuid
            + ", mode=" + (mode == 0 ? "direct" : "flow")
            + ", displayId=" + displayId
            + ", package=" + (flowPackageName.isEmpty() ? "none" : flowPackageName)
            + ", uid=" + flowUid
            + ", configuredRole=" + audioRoleName(audioRole)
            + ", uidFiltered=" + uidFilteredAudio
            + ", multiLink=" + multiLink;
  }

  private static int normalizeAudioRole(int role) {
    return role == AudioDecode.ROLE_NAVIGATION ? AudioDecode.ROLE_NAVIGATION : AudioDecode.ROLE_MEDIA;
  }

  private static String audioRoleName(int role) {
    return normalizeAudioRole(role) == AudioDecode.ROLE_NAVIGATION ? "navigation" : "media";
  }
}
