package top.eiyooooo.easycontrol.app.client;

import static android.content.ClipDescription.MIMETYPE_TEXT_PLAIN;

import android.content.ClipData;
import android.hardware.usb.UsbDevice;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Pair;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
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
  // 状态，0 表示初始化中，1 表示已连接，-1 表示已关闭。
  private int status = 0;
  // 当前进程里所有 Client 实例的集合，用于管理多连接场景。
  public static final ArrayList<Client> allClient = new ArrayList<>();
  // 车机端只保留一个真正播放的音频 owner，避免多投屏时多路 AudioTrack 混在一起。
  private static Client audioOwner;
  private static final Object audioOwnerLock = new Object();

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
  private VideoDecode videoDecode;
  private AudioDecode audioDecode;
  // 控制包封装器，最终会把二进制协议写入底层通道。
  public final ControlPacket controlPacket = new ControlPacket(this::write);
  public final ClientView clientView;
  public final String uuid;
  // 0 为屏幕镜像模式，1 为应用流转模式。
  public int mode = 0;
  public int displayId = 0;
  private Thread startThread;
  private final Thread loadingTimeOutThread;
  private final Thread keepAliveThread;
  private static final int timeoutDelay = 5 * 1000;
  private long lastKeepAliveTime;
  // 0 为单连接，1 为多连接主，2 为多连接从。
  public int multiLink = 0;

  private static final String serverName = "/data/local/tmp/easycontrol_for_car_server_" + BuildConfig.VERSION_CODE + ".jar";
  private static final boolean supportH265 = PublicTools.isDecoderSupport("hevc");
  private static final boolean supportOpus = PublicTools.isDecoderSupport("opus");

  public Client(Device device, UsbDevice usbDevice, int mode) {
    // 初始化设备标识，后续多连接判断要优先使用它。
    uuid = device.uuid;
    // 如果当前设备已经有一个 Client 在跑，多连接时需要重新标记主从关系。
    for (Client client : allClient) {
      if (client.isSamePhysicalDevice(device)) {
        if (client.multiLink == 0) client.changeMultiLinkMode(1);
        this.multiLink = 2;
        break;
      }
    }
    allClient.add(this);
    if (!EventMonitor.monitorRunning && AppData.setting.getMonitorState()) EventMonitor.startMonitor();
    // 如果指定了转移模式，就先标记为已转移。
    if (mode == 0) specifiedTransferred = true;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      // 音视频解码回调线程。
      handlerThread = new HandlerThread("easycontrol_mediacodec");
      handlerThread.start();
      handler = new Handler(handlerThread.getLooper());
    }
    // 创建客户端界面对象，连接成功后会自动启动后续线程。
    clientView = new ClientView(device, controlPacket, this::changeMode, () -> {
      status = 1;
      executeStreamInThread.start();
      executeStreamVideoThread.start();
      AppData.uiHandler.post(this::executeOtherService);
    }, () -> release(null));
    // 显示加载中弹窗，提示用户正在连接。
    Pair<View, WindowManager.LayoutParams> loading = PublicTools.createLoading(AppData.main);
    // 连接超时线程，防止连接卡死。
    loadingTimeOutThread = new Thread(() -> {
      try {
        Thread.sleep(timeoutDelay);
        if (startThread != null) startThread.interrupt();
        if (loading.first.getParent() != null) AppData.windowManager.removeView(loading.first);
        release(null);
      } catch (InterruptedException ignored) {
      }
    });
    // 保活线程，持续监控与服务端之间的心跳。
    keepAliveThread = new Thread(() -> {
      lastKeepAliveTime = System.currentTimeMillis();
      while (status != -1) {
        if (System.currentTimeMillis() - lastKeepAliveTime > timeoutDelay)
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
        AppData.uiHandler.post(() -> {
          if (device.nightModeSync) controlPacket.sendNightModeEvent(AppData.nightMode);
          if (AppData.setting.getAlwaysFullMode() || device.defaultFull) clientView.changeToFull();
          else clientView.changeToSmall();
        });
      } catch (Exception e) {
        L.log(device.uuid, e);
        release(AppData.main.getString(R.string.log_notify));
      } finally {
        if (!AppData.setting.getAlwaysFullMode() && loading.first.getParent() != null) AppData.windowManager.removeView(loading.first);
        loadingTimeOutThread.interrupt();
        keepAliveThread.start();
      }
    });
    if (AppData.setting.getAlwaysFullMode()) PublicTools.logToast(AppData.main.getString(R.string.loading_text));
    else AppData.windowManager.addView(loading.first, loading.second);
    loadingTimeOutThread.start();
    startThread.start();
  }

  // 连接 ADB，如果同一个设备已经有连接则复用。
  private static Adb connectADB(Device device, UsbDevice usbDevice) throws Exception {
    if (Adb.adbMap.containsKey(device.uuid)) return Adb.adbMap.get(device.uuid);
    Adb adb;
    if (usbDevice == null) adb = new Adb(device.uuid, device.address, AppData.keyPair);
    else adb = new Adb(device.uuid, usbDevice, AppData.keyPair);
    Adb.adbMap.put(device.uuid, adb);
    return adb;
  }

  // 启动服务端 JAR。
  private void startServer(Device device) throws Exception {
    if (adb.serverShell == null || adb.serverShell.isClosed()) adb.startServer();
    shell = adb.getShell();
    int ScreenMode = (AppData.setting.getTurnOnScreenIfStart() ? 1 : 0) * 1000
            + (AppData.setting.getTurnOffScreenIfStart() ? 1 : 0) * 100
            + (AppData.setting.getTurnOffScreenIfStop() ? 1 : 0) * 10
            + (AppData.setting.getTurnOnScreenIfStop() ? 1 : 0);
    StringBuilder cmd = new StringBuilder();
    cmd.append("app_process -Djava.class.path=").append(serverName).append(" / top.eiyooooo.easycontrol.server.Scrcpy");
    if (!shouldStartServerAudio(device)) cmd.append(" isAudio=0");
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

  private Thread loggerThread;
  private void logger() {
    // 持续读取服务端 shell 输出，方便把日志同步到 app 侧。
    loggerThread = new Thread(() -> {
      try {
        while (!Thread.interrupted()) {
          String log = new String(shell.readAllBytes().array(), StandardCharsets.UTF_8);
          if (!log.isEmpty()) L.logWithoutTime(uuid, log);
          Thread.sleep(1000);
        }
      } catch (Exception ignored) {
      }
    });
    loggerThread.start();
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
          String output = adb.runAdbCmd("am display move-stack " + tasksArray.getJSONObject(0).getInt("taskId") + " " + displayId);
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
      try {
        bufferStream = adb.localSocketForward("easycontrol_for_car_scrcpy");
        videoStream = adb.localSocketForward("easycontrol_for_car_scrcpy");
        return;
      } catch (Exception ignored) {
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
      // 视频流参数
      boolean useH265 = videoStream.readByte() == 1;
      Pair<Integer, Integer> videoSize = new Pair<>(videoStream.readInt(), videoStream.readInt());
      Surface surface = clientView.getSurface();
      Pair<byte[], Long> csd0 = new Pair<>(controlPacket.readFrame(videoStream), videoStream.readLong());
      Pair<byte[], Long> csd1 = useH265 ? null : new Pair<>(controlPacket.readFrame(videoStream), videoStream.readLong());
      videoDecode = new VideoDecode(videoSize, surface, csd0, csd1, handler);
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
      if (bufferStream.readByte() == 1) useOpus = bufferStream.readByte() == 1;
      // 循环处理报文
      while (!Thread.interrupted()) {
        switch (bufferStream.readByte()) {
          case AUDIO_EVENT:
            byte[] audioFrame = controlPacket.readFrame(bufferStream);
            if (!canPlayAudio()) break;
            if (audioDecode != null) audioDecode.decodeIn(audioFrame);
            else {
              audioDecode = new AudioDecode(useOpus, audioFrame, handler);
              playAudio(true);
            }
            break;
          case CLIPBOARD_EVENT:
            controlPacket.nowClipboardText = new String(bufferStream.readByteArray(bufferStream.readInt()).array());
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
    if (status == -1) return;
    status = -1;
    releaseAudioOwner();
    allClient.remove(this);
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
              Adb.getStringResponseFromServer(clientView.device, "releaseVirtualDisplay", "id=" + displayId);
            }
            break;
          case 1:
            if (multiLink == 1) {
              Client target = null;
              boolean multi = false;
              for (Client client : allClient) {
                if (client.uuid.equals(uuid) && client.multiLink == 2) {
                  if (target != null) {
                    multi = true;
                    break;
                  }
                  target = client;
                }
              }
              if (target != null) {
                if (multi) target.changeMultiLinkMode(1);
                else target.changeMultiLinkMode(0);
              }
            }
            break;
          case 2:
            if (loggerThread != null) loggerThread.interrupt();
            String log = new String(shell.readAllBytes().array(), StandardCharsets.UTF_8);
            if (!log.isEmpty()) L.logWithoutTime(uuid, log);
            break;
          case 3:
            keepAliveThread.interrupt();
            executeStreamInThread.interrupt();
            executeStreamVideoThread.interrupt();
            if (handlerThread != null) handlerThread.quit();
            break;
          case 4:
            AppData.uiHandler.post(() -> clientView.hide(true));
            break;
          case 5:
            bufferStream.close();
            break;
          case 6:
            videoDecode.release();
            if (audioDecode != null) audioDecode.release();
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
      if (Adb.adbMap.get(device.uuid) == null) {
        if (device.isLinkDevice()) Adb.adbMap.put(device.uuid, new Adb(device.uuid, usbDevice, AppData.keyPair));
        else Adb.adbMap.put(device.uuid, new Adb(device.uuid, device.address, AppData.keyPair));
      }
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

  public void changeMode(int mode) {
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
        while (!isStarted()) {
          Thread.sleep(1000);
        }
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
    playAudio(multiLink == 0 || multiLink == 1);
    if (multiLink == 2) {
      clientView.device.clipboardSync = false;
      clientView.device.nightModeSync = false;
    } else if (multiLink == 0 || multiLink == 1) {
      if (clientView.deviceOriginal.clipboardSync) clientView.device.clipboardSync = true;
      if (clientView.deviceOriginal.nightModeSync) clientView.device.nightModeSync = true;
    }
    this.multiLink = multiLink;
    clientView.multiLink = multiLink;
  }

  public void playAudio(boolean play) {
    if (audioDecode == null) return;
    if (play && !canPlayAudio()) return;
    synchronized (audioOwnerLock) {
      if (play) requestAudioOwnerLocked();
      else {
        if (audioOwner == this) audioOwner = null;
        audioDecode.playAudio(false);
      }
    }
  }

  private boolean canPlayAudio() {
    return multiLink == 0 || multiLink == 1;
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

  private void requestAudioOwnerLocked() {
    if (audioOwner != null && audioOwner != this && audioOwner.audioDecode != null) audioOwner.audioDecode.playAudio(false);
    audioOwner = this;
    audioDecode.playAudio(true);
  }

  private void releaseAudioOwner() {
    synchronized (audioOwnerLock) {
      if (audioOwner != this) return;
      audioOwner = null;
      for (Client client : allClient) {
        if (client != this && !client.isClosed() && client.canPlayAudio() && client.audioDecode != null) {
          client.requestAudioOwnerLocked();
          break;
        }
      }
    }
  }
}
