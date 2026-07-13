package top.eiyooooo.easycontrol.app.adb;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.usb.UsbDevice;
import android.os.SystemClock;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import android.util.Pair;
import top.eiyooooo.easycontrol.app.BuildConfig;
import top.eiyooooo.easycontrol.app.R;
import top.eiyooooo.easycontrol.app.buffer.Buffer;
import top.eiyooooo.easycontrol.app.buffer.BufferStream;
import top.eiyooooo.easycontrol.app.entity.AppData;
import top.eiyooooo.easycontrol.app.entity.Device;
import top.eiyooooo.easycontrol.app.helper.L;
import top.eiyooooo.easycontrol.app.helper.PublicTools;
/**
 * 类 Adb
 * 说明：该类负责 Adb 相关功能。
 */

public class Adb {
  public static final ConcurrentHashMap<String, Adb> adbMap = new ConcurrentHashMap<>();
  private static final ConcurrentHashMap<String, Object> adbCreationLocks = new ConcurrentHashMap<>();
  private static final long RESPONSE_TIMEOUT_MS = 15_000;
  private static final long STREAM_OPEN_TIMEOUT_MS = 15_000;
  private static final long STREAM_CLOSE_TIMEOUT_MS = 60_000;
  private static final int MAX_SERVER_RESPONSE_SIZE = 16 * 1024 * 1024;

  private final AdbChannel channel;
  private final AtomicInteger localIdPool = new AtomicInteger(1);
  private int MAX_DATA = AdbProtocol.CONNECT_MAXDATA;
  private final ConcurrentHashMap<Integer, BufferStream> connectionStreams = new ConcurrentHashMap<>(10);
  private final ConcurrentHashMap<Integer, BufferStream> openStreams = new ConcurrentHashMap<>(5);
  private final Buffer sendBuffer = new Buffer();
  private final Object serverRequestLock = new Object();
  private final AtomicBoolean closeStarted = new AtomicBoolean(false);

  private final Thread handleInThread = new Thread(this::handleIn);
  private final Thread handleOutThread = new Thread(this::handleOut);

  private final String uuid;
  private static final String serverName = "/data/local/tmp/easycontrol_for_car_server_" + BuildConfig.VERSION_CODE + ".jar";
  public volatile Thread startServerThread = new Thread(this::startServer);
  public volatile BufferStream serverShell;

  public Adb(String uuid, String address, AdbKeyPair keyPair) throws Exception {
    this.uuid = uuid;
    Pair<String, Integer> addressPair = PublicTools.getIpAndPort(address);
    channel = new TcpChannel(addressPair.first, addressPair.second, false);
    connect(keyPair);
    startServerThread.start();
  }

  public Adb(String address, AdbKeyPair keyPair) throws Exception {
    this.uuid = null;
    Pair<String, Integer> addressPair = PublicTools.getIpAndPort(address);
    channel = new TcpChannel(addressPair.first, addressPair.second, true);
    connect(keyPair);
  }

  public Adb(String uuid, UsbDevice usbDevice, AdbKeyPair keyPair) throws Exception {
    this.uuid = uuid;
    channel = new UsbChannel(usbDevice);
    connect(keyPair);
    startServerThread.start();
  }

  private void connect(AdbKeyPair keyPair) throws Exception {
    // 连接ADB并认证
    channel.write(AdbProtocol.generateConnect());
    AdbProtocol.AdbMessage message = AdbProtocol.AdbMessage.parseAdbMessage(channel);
    if (message.command == AdbProtocol.CMD_AUTH) {
      channel.write(AdbProtocol.generateAuth(AdbProtocol.AUTH_TYPE_SIGNATURE, keyPair.signPayload(message.payload)));
      message = AdbProtocol.AdbMessage.parseAdbMessage(channel);
      if (message.command == AdbProtocol.CMD_AUTH) {
        channel.write(AdbProtocol.generateAuth(AdbProtocol.AUTH_TYPE_RSA_PUBLIC, keyPair.publicKeyBytes));
        message = AdbProtocol.AdbMessage.parseAdbMessage(channel);
      }
    }
    if (message.command != AdbProtocol.CMD_CNXN) {
      channel.close();
      throw new Exception("ADB connect error");
    }
    if (message.arg1 <= 128 || message.arg1 > AdbProtocol.MAX_ADB_PAYLOAD_LENGTH) {
      channel.close();
      throw new IOException("invalid ADB max payload: " + message.arg1);
    }
    MAX_DATA = message.arg1;
    if (uuid == null) {
      channel.close();
      return;
    }
    // 启动后台进程
    handleInThread.setPriority(Thread.MAX_PRIORITY);
    handleInThread.start();
    handleOutThread.start();
  }

  public final void startServer() {
    synchronized (serverRequestLock) {
      try {
        if (BuildConfig.ENABLE_DEBUG_FEATURE || !runAdbCmd("ls /data/local/tmp/easycontrol_*").contains(serverName)) {
          runAdbCmd("rm /data/local/tmp/easycontrol_* ");
          pushFile(AppData.main.getResources().openRawResource(R.raw.easycontrol_server), serverName);
        }
        if (serverShell != null) serverShell.close();
        String cmd = "CLASSPATH=" + serverName + " app_process / top.eiyooooo.easycontrol.server.Server\n";
        serverShell = getShell();
        serverShell.write(ByteBuffer.wrap(cmd.getBytes(StandardCharsets.UTF_8)));
        waitingData(0);
      } catch (Exception e) {
        if (serverShell != null) serverShell.close();
        L.log(uuid, e);
        PublicTools.logToast(AppData.main.getString(R.string.log_notify));
      }
    }
  }

  public void ensureServerStarted() throws InterruptedException {
    Thread serverThread;
    synchronized (this) {
      if (closeStarted.get()) throw new InterruptedException("ADB connection is closed");
      if (!startServerThread.isAlive() && (serverShell == null || serverShell.isClosed())) {
        startServerThread = new Thread(this::startServer);
        startServerThread.start();
      }
      serverThread = startServerThread;
    }
    if (serverThread.isAlive()) serverThread.join();
    if (serverShell == null || serverShell.isClosed()) throw new InterruptedException("ADB helper server failed to start");
  }

  public static String getStringResponseFromServer(Device device, String request, String... args) throws Exception {
    Adb adb = getAdb(device);
    return adb.getStringResponse(request, args);
  }

  private static Adb getAdb(Device device) throws Exception {
    String uuid = device.uuid;
    Adb adb = adbMap.get(uuid);
    if (adb == null) throw new Exception("adb not start");
    adb.ensureServerStarted();
    return adb;
  }

  public static Adb getOrCreate(String uuid, AdbFactory factory) throws Exception {
    Object creationLock = adbCreationLocks.get(uuid);
    if (creationLock == null) {
      Object newLock = new Object();
      Object existingLock = adbCreationLocks.putIfAbsent(uuid, newLock);
      creationLock = existingLock == null ? newLock : existingLock;
    }
    synchronized (creationLock) {
      Adb existing = adbMap.get(uuid);
      if (existing != null && !existing.closeStarted.get()) return existing;
      if (existing != null) adbMap.remove(uuid, existing);
      Adb created = factory.create();
      adbMap.put(uuid, created);
      return created;
    }
  }

  public interface AdbFactory {
    Adb create() throws Exception;
  }

  private String getStringResponse(String request, String... args) throws Exception {
    return new String(getResponse(request, args), StandardCharsets.UTF_8);
  }

  private byte[] getResponse(String request, String[] args) throws Exception {
    synchronized (serverRequestLock) {
      StringBuilder sb = new StringBuilder();
      sb.append("/").append(request).append("?");
      for (String arg : args) {
        sb.append(arg).append("&");
      }
      sb.deleteCharAt(sb.length() - 1).append("\n");
      byte[] requestBytes = sb.toString().getBytes(StandardCharsets.UTF_8);

      if (serverShell == null || serverShell.isClosed()) throw new IOException("server shell is closed");
      serverShell.readAllBytes();
      serverShell.write(ByteBuffer.wrap(requestBytes));
      waitingData(requestBytes.length + 1);
      serverShell.readByteArray(requestBytes.length + 1);
      waitingData(8);
      int len1 = serverShell.readInt();
      int len2 = serverShell.readInt();
      if (len1 != len2 || len1 < 0 || len1 > MAX_SERVER_RESPONSE_SIZE) throw new IOException("bad server response length");
      if (len1 == 0) return new byte[0];
      waitingData(len1);
      return serverShell.readByteArray(len1).array();
    }
  }

  private void waitingData(int byteNum) throws InterruptedException, IOException {
    long deadline = SystemClock.elapsedRealtime() + RESPONSE_TIMEOUT_MS;
    int previousSize = -1;
    int stableChecks = 0;
    while (SystemClock.elapsedRealtime() < deadline) {
      BufferStream currentShell = serverShell;
      if (currentShell == null || currentShell.isClosed()) throw new IOException("server shell is closed");
      int newSize = currentShell.getSize();
      if (byteNum > 0 && newSize >= byteNum) return;
      if (byteNum == 0 && newSize > 0) {
        if (newSize == previousSize) {
          if (++stableChecks >= 2) return;
        } else {
          previousSize = newSize;
          stableChecks = 0;
        }
      }
      Thread.sleep(50);
    }
    throw new IOException("timed out waiting for server data");
  }

  private BufferStream open(String destination, boolean canMultipleSend) throws InterruptedException {
    if (closeStarted.get()) throw new InterruptedException("ADB connection is closed");
    int localId = localIdPool.getAndIncrement() * (canMultipleSend ? 1 : -1);
    sendBuffer.write(AdbProtocol.generateOpen(localId, destination));
    long deadline = SystemClock.elapsedRealtime() + STREAM_OPEN_TIMEOUT_MS;
    synchronized (this) {
      while (true) {
        BufferStream bufferStream = openStreams.remove(localId);
        if (bufferStream != null) return bufferStream;
        if (closeStarted.get()) throw new InterruptedException("ADB connection is closed");
        long remaining = deadline - SystemClock.elapsedRealtime();
        if (remaining <= 0) {
          close();
          throw new InterruptedException("timed out opening ADB stream: " + destination);
        }
        wait(remaining);
      }
    }
  }

  private void waitForStreamClose(BufferStream bufferStream) throws InterruptedException {
    long deadline = SystemClock.elapsedRealtime() + STREAM_CLOSE_TIMEOUT_MS;
    synchronized (this) {
      while (!bufferStream.isClosed()) {
        if (closeStarted.get()) throw new InterruptedException("ADB connection is closed");
        long remaining = deadline - SystemClock.elapsedRealtime();
        if (remaining <= 0) {
          bufferStream.close();
          throw new InterruptedException("timed out waiting for ADB stream to close");
        }
        wait(remaining);
      }
    }
  }

  public final String restartOnTcpip(int port) throws InterruptedException {
    closing = true;
    BufferStream bufferStream = open("tcpip:" + port, false);
    waitForStreamClose(bufferStream);
    return new String(bufferStream.readByteArrayBeforeClose().array(), StandardCharsets.UTF_8);
  }

  public final void pushFile(InputStream file, String remotePath) throws Exception {
    // 打开链接
    BufferStream bufferStream = open("sync:", false);
    // 发送信令，建立push通道
    String sendString = remotePath + ",33206";
    byte[] bytes = sendString.getBytes();
    bufferStream.write(AdbProtocol.generateSyncHeader("SEND", sendString.length()));
    bufferStream.write(ByteBuffer.wrap(bytes));
    // 发送文件
    byte[] byteArray = new byte[10240 - 8];
    int len;
    while ((len = file.read(byteArray, 0, byteArray.length)) > 0) {
      bufferStream.write(AdbProtocol.generateSyncHeader("DATA", len));
      bufferStream.write(ByteBuffer.wrap(byteArray, 0, len));
    }
    file.close();
    // 传输完成，为了方便，文件日期定为2024.1.1 0:0
    bufferStream.write(AdbProtocol.generateSyncHeader("DONE", 1704038400));
    bufferStream.write(AdbProtocol.generateSyncHeader("QUIT", 0));
    waitForStreamClose(bufferStream);
  }

  public static Bitmap getRemoteIconByDevice(Device device, String packageName) throws Exception {
    Adb adb = getAdb(device);
    return adb.getRemoteIcon(packageName);
  }

  public final Bitmap getRemoteIcon(String packageName) throws Exception {
    String path = getStringResponse("getIcon", "package=" + packageName);
    if (!path.endsWith(".png")) throw new Exception("get icon fail");
    BufferStream bufferStream = open("sync:", false);
    byte[] bytes = path.getBytes();
    bufferStream.write(AdbProtocol.generateSyncHeader("RECV", bytes.length));
    bufferStream.write(ByteBuffer.wrap(bytes));
    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
    String check1 = new String(bufferStream.readByteArray(4).array());
    if ("FAIL".equals(check1)) throw new Exception("get icon fail");
    int len1 = bufferStream.readByteArray(4).getInt() * 256;
    int size = bufferStream.getSize();
    if (size > len1) size = len1;
    int position = 0;
    do {
      byteArrayOutputStream.write(bufferStream.readByteArray(size).array(), 0, size);
      position += size;
      if (position == len1) {
        String check2 = new String(bufferStream.readByteArray(4).array());
        if (!"FAIL".equals(check2)) {
          len1 = bufferStream.readByteArray(4).getInt() * 256;
          if (len1 != 65536) {
            int len2 = bufferStream.getSize() - 8;
            byteArrayOutputStream.write(bufferStream.readByteArray(len2).array(), 0, len2);
            bufferStream.readByteArray(4);
          }
          position = 0;
        }
      } else {
        if (bufferStream.getSize() == 0) Thread.sleep(120);
        int len3 = bufferStream.getSize();
        if (position + len3 > len1) len3 = len1 - position;
        size = len3;
      }
    } while (bufferStream.getSize() > 0);
    bufferStream.write(AdbProtocol.generateSyncHeader("QUIT", 0));
    byteArrayOutputStream.flush();
    waitForStreamClose(bufferStream);
    runAdbCmd("rm " + path);
    return BitmapFactory.decodeByteArray(byteArrayOutputStream.toByteArray(), 0, byteArrayOutputStream.size());
  }

  public final String runAdbCmd(String cmd) throws InterruptedException {
    BufferStream bufferStream = open("shell:" + cmd, true);
    waitForStreamClose(bufferStream);
    return new String(bufferStream.readByteArrayBeforeClose().array(), StandardCharsets.UTF_8);
  }

  public BufferStream getShell() throws InterruptedException {
    return open("shell:", true);
  }

  public BufferStream tcpForward(int port) throws IOException, InterruptedException {
    BufferStream bufferStream = open("tcp:" + port, true);
    if (bufferStream.isClosed()) throw new IOException("error forward");
    return bufferStream;
  }

  public BufferStream localSocketForward(String socketName) throws IOException, InterruptedException {
    BufferStream bufferStream = open("localabstract:" + socketName, true);
    if (bufferStream.isClosed()) throw new IOException("error forward");
    return bufferStream;
  }

  private void handleIn() {
    try {
      while (!Thread.interrupted()) {
        AdbProtocol.AdbMessage message = AdbProtocol.AdbMessage.parseAdbMessage(channel);
        BufferStream bufferStream = connectionStreams.get(message.arg1);
        boolean isNeedNotify = bufferStream == null;
        // 新连接
        if (isNeedNotify) bufferStream = createNewStream(message.arg1, message.arg0, message.arg1 > 0);
        switch (message.command) {
          case AdbProtocol.CMD_OKAY:
            bufferStream.setCanWrite(true);
            break;
          case AdbProtocol.CMD_WRTE:
            bufferStream.pushSource(message.payload);
            sendBuffer.write(AdbProtocol.generateOkay(message.arg1, message.arg0));
            break;
          case AdbProtocol.CMD_CLSE:
            bufferStream.close();
            isNeedNotify = true;
            break;
        }
        if (isNeedNotify) {
          synchronized (this) {
            notifyAll();
          }
        }
      }
    } catch (Exception e) {
      if (!closing) {
        L.log(uuid, e);
        PublicTools.logToast(AppData.main.getString(R.string.log_notify));
      }
      close();
    }
  }

  private void handleOut() {
    try {
      while (!Thread.interrupted()) {
        channel.write(sendBuffer.readNext());
        if (!sendBuffer.isEmpty()) channel.write(sendBuffer.read(sendBuffer.getSize()));
        channel.flush();
      }
    } catch (Exception e) {
      if (!closing) {
        L.log(uuid, e);
        PublicTools.logToast(AppData.main.getString(R.string.log_notify));
      }
      close();
    }
  }

  private BufferStream createNewStream(int localId, int remoteId, boolean canMultipleSend) throws Exception {
    return new BufferStream(false, canMultipleSend, new BufferStream.UnderlySocketFunction() {
      @Override
      public void connect(BufferStream bufferStream) {
        connectionStreams.put(localId, bufferStream);
        openStreams.put(localId, bufferStream);
      }

      @Override
      public void write(BufferStream bufferStream, ByteBuffer buffer) {
        while (buffer.hasRemaining()) {
          byte[] byteArray = new byte[Math.min(MAX_DATA - 128, buffer.remaining())];
          buffer.get(byteArray);
          sendBuffer.write(AdbProtocol.generateWrite(localId, remoteId, byteArray));
        }
      }

      @Override
      public void flush(BufferStream bufferStream) {
        sendBuffer.write(AdbProtocol.generateOkay(localId, remoteId));
      }

      @Override
      public void close(BufferStream bufferStream) {
        connectionStreams.remove(localId);
        sendBuffer.write(AdbProtocol.generateClose(localId, remoteId));
      }
    });
  }

  private volatile boolean closing = false;

  public void close() {
    if (!closeStarted.compareAndSet(false, true)) return;
    closing = true;
    if (uuid != null) adbMap.remove(uuid, this);
    synchronized (this) {
      notifyAll();
    }
    for (Object bufferStream : connectionStreams.values().toArray()) ((BufferStream) bufferStream).close();
    handleInThread.interrupt();
    handleOutThread.interrupt();
    channel.close();
  }

}
