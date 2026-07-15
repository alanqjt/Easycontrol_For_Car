package top.eiyooooo.easycontrol.app.client;

import static android.content.ClipDescription.MIMETYPE_TEXT_PLAIN;

import android.content.ClipData;
import android.content.pm.ApplicationInfo;
import android.hardware.usb.UsbDevice;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
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
  // 状态，0 表示初始化中，1 表示已连接，-1 表示已关闭。
  private volatile int status = 0;
  // 当前进程里所有 Client 实例的集合，用于管理多连接场景。
  public static final CopyOnWriteArrayList<Client> allClient = new CopyOnWriteArrayList<>();
  // 导航和媒体各保留一个 owner：同类不重复播放，两类可以同时输出到不同车机音频通道。
  private static final Client[] audioOwners = new Client[2];
  private static final Object audioOwnerLock = new Object();
  private static final Object multiLinkLock = new Object();
  private static final int AUDIO_OWNER_PRIORITY_NONE = Integer.MIN_VALUE;
  private static final int AUDIO_OWNER_PRIORITY_PRIMARY = 100;
  private static final int AUDIO_OWNER_PRIORITY_DIRECT_MIRROR = 200;
  private static final int AUDIO_OWNER_PRIORITY_UID_FLOW = 300;

  // 连接相关对象。
  public Adb adb;
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
  private boolean startupConnected = false;
  private final Object mediaLifecycleLock = new Object();
  private VideoDecode videoDecode;
  // 同一个直接投屏 Client 可以同时承载媒体、导航两个独立解码器。
  private final AudioDecode[] audioDecodes = new AudioDecode[2];
  // 控制包封装器，最终会把二进制协议写入底层通道。
  public final ControlPacket controlPacket = new ControlPacket(this::write);
  public final ClientView clientView;
  public final String uuid;
  public final String sessionId = UUID.randomUUID().toString();
  // 0 为屏幕镜像模式，1 为应用流转模式。
  public int mode = 0;
  public int displayId = 0;
  // 本次应用流转对应的远端任务信息，也是 UID 定向音频采集的依据。
  private String flowPackageName = "";
  private int flowUid = -1;
  private int flowCategory = ApplicationInfo.CATEGORY_UNDEFINED;
  private int audioRole = AudioDecode.ROLE_MEDIA;
  private boolean uidFilteredAudio;
  private Thread startThread;
  private final Thread loadingTimeOutThread;
  private final Thread keepAliveThread;
  private static final int startupTimeoutDelay = 30 * 1000;
  private static final int keepAliveTimeoutDelay = 5 * 1000;
  private long lastKeepAliveTime;
  // 0 为单连接，1 为多连接主，2 为多连接从。
  public int multiLink = 0;

  private static final String serverName = "/data/local/tmp/easycontrol_for_car_server_" + BuildConfig.VERSION_CODE + ".jar";
  private static final boolean supportH265 = PublicTools.isDecoderSupport("hevc");
  private static final boolean supportOpus = PublicTools.isDecoderSupport("opus");

  public Client(Device device, UsbDevice usbDevice, int mode) {
    // 初始化设备标识，后续多连接判断要优先使用它。
    uuid = device.uuid;
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
    }, () -> release(null));
    // 显示加载中弹窗，提示用户正在连接。
    Pair<View, WindowManager.LayoutParams> loading = PublicTools.createLoading(AppData.main);
    // 连接超时线程，防止连接卡死。
    loadingTimeOutThread = new Thread(() -> {
      try {
        Thread.sleep(startupTimeoutDelay);
        if (!beginRelease(true)) return;
        if (startThread != null) startThread.interrupt();
        AppData.uiHandler.post(() -> {
          if (loading.first.getParent() != null) AppData.windowManager.removeView(loading.first);
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
          if (AppData.setting.getAlwaysFullMode() || device.defaultFull) clientView.changeToFull();
          else clientView.changeToSmall();
        });
      } catch (Exception e) {
        L.log(device.uuid, e);
        release(AppData.main.getString(R.string.log_notify));
      } finally {
        if (!AppData.setting.getAlwaysFullMode()) AppData.uiHandler.post(() -> {
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
    if (AppData.setting.getAlwaysFullMode()) PublicTools.logToast(AppData.main.getString(R.string.loading_text));
    else AppData.windowManager.addView(loading.first, loading.second);
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
    cmd.append("app_process -Djava.class.path=").append(serverName).append(" / top.eiyooooo.easycontrol.server.Scrcpy");
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
      String output = Adb.getStringResponseFromServer(device, "createVirtualDisplay");
      if (output.contains("success")) {
        displayId = Integer.parseInt(output.substring(output.lastIndexOf(" -> ") + 4));
        clientView.displayId = displayId;
        changeMode(1);
        PublicTools.logToast(AppData.main.getString(R.string.tip_application_transfer));
      } else throw new Exception("");
    } catch (Exception ignored) {
      changeMode(0);
      PublicTools.logToast(AppData.main.getString(R.string.error_create_display));
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
            // 已经运行的应用则直接移动任务栈。
            String output = adb.runAdbCmd("am display move-stack " + appTaskId + " " + displayId);
            if (output.contains("Exception")) throw new Exception("");
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
          String output = adb.runAdbCmd("am display move-stack " + targetTaskId + " " + displayId);
          if (output.contains("Exception")) throw new Exception("");
        } else throw new Exception("");
      }
    } catch (Exception ignored) {
      specifiedTransferred = true;
      changeMode(0);
      PublicTools.logToast(AppData.main.getString(R.string.error_transfer_app_failed));
    }
  }

  // 连接Server
  private void connectServer() throws Exception {
    Thread.sleep(50);
    for (int i = 0; i < 60; i++) {
      BufferStream controlStream = null;
      BufferStream pendingVideoStream = null;
      try {
        controlStream = adb.localSocketForward("easycontrol_for_car_scrcpy");
        pendingVideoStream = adb.localSocketForward("easycontrol_for_car_scrcpy");
        synchronized (lifecycleLock) {
          if (releaseStarted.get()) throw new InterruptedException("client released while connecting");
          bufferStream = controlStream;
          videoStream = pendingVideoStream;
          startupConnected = true;
        }
        return;
      } catch (Exception ignored) {
        if (controlStream != null) controlStream.close();
        if (pendingVideoStream != null) pendingVideoStream.close();
        if (releaseStarted.get() || Thread.currentThread().isInterrupted()) throw new InterruptedException("client connection cancelled");
        Thread.sleep(50);
      }
    }
    throw new Exception(AppData.main.getString(R.string.error_connect_server));
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
      while (!Thread.interrupted()) {
        videoDecode.decodeIn(controlPacket.readFrame(videoStream), videoStream.readLong());
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
                  audioDecodes[frameRole] = new AudioDecode(useOpus, audioFrame, audioHandlers[frameRole], frameRole);
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
      if (AppData.setting.getShowReconnect())
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
    clientView.changeSizeLock.set(false);
    if (mode == 0) {
      try {
        Adb.getStringResponseFromServer(clientView.device, "releaseVirtualDisplay", "id=" + displayId);
      } catch (Exception ignored) {
      }
      displayId = 0;
      clientView.displayId = 0;
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
        synchronized (clientView.changeSizeLock) {
          clientView.changeSizeLock.set(true);
          clientView.changeSizeLock.notifyAll();
        }
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
    if (play) requestAudioOwnerLocked(role);
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

  private void requestAudioOwnerLocked(int role) {
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
    if (oldOwner != null
            && oldOwner.audioDecodes[role] != null
            && oldPriority > requestedPriority) {
      decoder.playAudio(false);
      Log.i(AUDIO_LOG_TAG, "audio owner retained role=" + audioRoleName(role)
              + ", owner={" + oldOwner.audioClientDescription() + "}"
              + ", ownerPriority=" + oldPriority
              + ", standby={" + audioClientDescription() + "}"
              + ", standbyPriority=" + requestedPriority);
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
            + ", priority=" + requestedPriority);
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
        if (replacement != null) replacement.requestAudioOwnerLocked(role);
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
