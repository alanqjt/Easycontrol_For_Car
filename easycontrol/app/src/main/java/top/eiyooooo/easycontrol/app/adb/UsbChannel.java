/*
 * 本页大量借鉴学习了开源ADB库：https://github.com/wuxudong/flashbot/blob/master/adblib/src/main/java/com/cgutman/adblib/UsbChannel.java，在此对该项目表示感谢
 */
package top.eiyooooo.easycontrol.app.adb;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbRequest;
import android.os.Build;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import top.eiyooooo.easycontrol.app.entity.AppData;
import top.eiyooooo.easycontrol.app.buffer.Buffer;
import top.eiyooooo.easycontrol.app.helper.L;

public class UsbChannel implements AdbChannel {

  private static final String TAG = "EasycontrolUsb";
  private static final int HONOR_USB_VENDOR_ID = 0x339b;
  private static final int MAX_ZERO_LENGTH_BACKOFF_MS = 50;
  private static final int MAX_USB_QUEUE_RETRIES = 20;
  private static final int BULK_WRITE_TIMEOUT_MS = 1000;
  private static final int MAX_BULK_WRITE_RETRIES = 5;
  private static final int STALE_INPUT_DRAIN_TIMEOUT_MS = 20;
  private static final int MAX_STALE_INPUT_DRAIN_READS = 32;
  private static final int MAX_STALE_ZERO_LENGTH_READS = 4;
  // 低于车机实测的 1024 字节单次可靠接收上限，同时避开 512 字节端点的 ZLP 边界。
  private static final int HONOR_USB_RECEIVE_MAX_DATA = 1000;

  private final String logId;
  private final String deviceDescription;
  private final boolean honorUsbDevice;
  private final UsbDeviceConnection usbConnection;
  private UsbInterface usbInterface = null;
  private UsbEndpoint endpointIn = null;
  private UsbEndpoint endpointOut = null;
  private final Buffer sourceBuffer = new Buffer();
  private final Thread readBackgroundThread;
  private final Thread honorCompletionThread;
  private final LinkedList<UsbRequest> mInRequestPool = new LinkedList<>();
  private final LinkedList<UsbRequest> mRetiredInRequests = new LinkedList<>();
  private UsbRequest honorOutRequest;
  private final AtomicBoolean closeStarted = new AtomicBoolean(false);
  private final AtomicBoolean readStarted = new AtomicBoolean(false);
  private final AtomicBoolean zeroLengthPacketLogged = new AtomicBoolean(false);
  private final AtomicBoolean outboundZeroLengthPacketLogged = new AtomicBoolean(false);
  private final AtomicBoolean queueRetryLogged = new AtomicBoolean(false);
  private final AtomicBoolean bulkWriteRetryLogged = new AtomicBoolean(false);
  private final AtomicBoolean shortReadLogged = new AtomicBoolean(false);
  private volatile boolean interfaceClaimed;

  public UsbChannel(UsbDevice usbDevice) throws IOException {
    this("USB", usbDevice);
  }

  public UsbChannel(String logId, UsbDevice usbDevice) throws IOException {
    this.logId = logId == null || logId.isEmpty() ? "USB" : logId;
    deviceDescription = describeDevice(usbDevice);
    honorUsbDevice = usbDevice.getVendorId() == HONOR_USB_VENDOR_ID;
    readBackgroundThread = new Thread(this::readBackground, "easycontrol_usb_read_" + usbDevice.getDeviceId());
    honorCompletionThread = new Thread(this::dispatchHonorUsbCompletions,
            "easycontrol_usb_completion_" + usbDevice.getDeviceId());
    // 连接USB设备
    if (AppData.usbManager == null) throw new IOException("not have usbManager");
    boolean hasPermission;
    try {
      hasPermission = AppData.usbManager.hasPermission(usbDevice);
    } catch (Exception e) {
      throw new IOException("Unable to check USB permission for " + deviceDescription, e);
    }
    if (!hasPermission) throw new IOException("USB permission missing for " + deviceDescription);
    usbConnection = AppData.usbManager.openDevice(usbDevice);
    if (usbConnection == null) throw new IOException("Unable to open USB device (permission=true): " + deviceDescription);
    // 查找ADB的接口
    for (int i = 0; i < usbDevice.getInterfaceCount(); i++) {
      UsbInterface tmpUsbInterface = usbDevice.getInterface(i);
      if ((tmpUsbInterface.getInterfaceClass() == UsbConstants.USB_CLASS_VENDOR_SPEC) && (tmpUsbInterface.getInterfaceSubclass() == 66) && (tmpUsbInterface.getInterfaceProtocol() == 1)) {
        usbInterface = tmpUsbInterface;
        break;
      }
    }
    if (usbInterface == null) {
      IOException error = new IOException("ADB USB interface not found: " + deviceDescription);
      closeInternal(error);
      throw error;
    }
    // 宣告独占接口
    if (!usbConnection.claimInterface(usbInterface, true)) {
      IOException error = new IOException("Unable to claim ADB USB interface: " + deviceDescription);
      closeInternal(error);
      throw error;
    }
    interfaceClaimed = true;
    // 查找输入输出端点
    for (int i = 0; i < usbInterface.getEndpointCount(); i++) {
      UsbEndpoint endpoint = usbInterface.getEndpoint(i);
      if (endpoint.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
        if (endpoint.getDirection() == UsbConstants.USB_DIR_OUT) endpointOut = endpoint;
        else if (endpoint.getDirection() == UsbConstants.USB_DIR_IN) endpointIn = endpoint;
      }
    }
    if (endpointIn == null || endpointOut == null) {
      IOException error = new IOException("ADB USB bulk endpoints not found: " + deviceDescription);
      closeInternal(error);
      throw error;
    }
    if (honorUsbDevice) {
      honorOutRequest = new UsbRequest();
      if (!honorOutRequest.initialize(usbConnection, endpointOut)) {
        IOException error = new IOException("Unable to initialize Honor USB write request");
        closeInternal(error);
        throw error;
      }
    }
    String openLog = "USB ADB opened " + deviceDescription
            + ", interface=" + usbInterface.getId()
            + ", in=0x" + Integer.toHexString(endpointIn.getAddress())
            + ", out=0x" + Integer.toHexString(endpointOut.getAddress())
            + ", maxPacket=" + endpointOut.getMaxPacketSize()
            + ", readMode=" + (honorUsbDevice ? "async-legacy-direct-split" : "async");
    Log.i(TAG, openLog);
    L.log(this.logId, openLog);
  }

  @Override
  public synchronized void write(ByteBuffer data) throws IOException {
    if (closeStarted.get()) throw new IOException("USB channel is closed");
    startReadLoopIfNeeded();
    // 此处感谢群友：○_○ 的帮助，ADB通过USB连接时必须头部和载荷分开发送，否则会导致ADB连接重置（官方的实现真差劲，明明可以顺序读取的）
    while (data.remaining() > 0) {
      if (data.remaining() < AdbProtocol.ADB_HEADER_LENGTH) throw new IOException("incomplete ADB USB header");
      // 读取头部
      byte[] header = new byte[AdbProtocol.ADB_HEADER_LENGTH];
      data.get(header);
      writeTransfer(header);
      // 读取载荷
      int payloadLength = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt(12);
      if (payloadLength < 0 || payloadLength > AdbProtocol.MAX_ADB_PAYLOAD_LENGTH || payloadLength > data.remaining())
        throw new IOException("invalid ADB USB payload length: " + payloadLength);
      if (payloadLength > 0) {
        byte[] payload = new byte[payloadLength];
        data.get(payload);
        writeTransfer(payload);
      }
    }
  }

  /**
   * One ADB USB header and one ADB payload are separate USB transfers. A transfer whose length is
   * an exact multiple of wMaxPacketSize must be terminated by a zero-length packet.
   */
  private void writeTransfer(byte[] data) throws IOException {
    writeFully(data);
    int maxPacketSize = endpointOut.getMaxPacketSize();
    if (data.length == 0 || maxPacketSize <= 0 || data.length % maxPacketSize != 0) return;

    int transferred = honorUsbDevice
            ? asyncHonorWrite(new byte[0], 0, 0)
            : bulkTransfer(endpointOut, new byte[0], 0, 0, BULK_WRITE_TIMEOUT_MS);
    if (transferred < 0) {
      throw new IOException("USB zero-length packet failed after " + data.length + " bytes");
    }
    if (outboundZeroLengthPacketLogged.compareAndSet(false, true)) {
      L.log(logId, "USB outbound zero-length packet enabled, maxPacket=" + maxPacketSize);
    }
  }

  private void startReadLoopIfNeeded() throws IOException {
    if (!readStarted.compareAndSet(false, true)) return;
    try {
      drainStaleInput();
      if (honorUsbDevice) honorCompletionThread.start();
      readBackgroundThread.start();
    } catch (Exception e) {
      readStarted.set(false);
      throw e instanceof IOException
              ? (IOException) e
              : new IOException("Unable to start USB receive loop", e);
    }
  }

  private void drainStaleInput() throws IOException {
    byte[] staleData = new byte[Math.max(16 * 1024, endpointIn.getMaxPacketSize())];
    int drainedBytes = 0;
    int drainedReads = 0;
    int zeroLengthReads = 0;
    while (drainedReads < MAX_STALE_INPUT_DRAIN_READS) {
      int read = bulkTransfer(endpointIn, staleData, 0, staleData.length,
              STALE_INPUT_DRAIN_TIMEOUT_MS);
      if (read < 0) break;
      if (read == 0) {
        if (++zeroLengthReads > MAX_STALE_ZERO_LENGTH_READS) break;
        continue;
      }
      drainedBytes += read;
      drainedReads++;
      zeroLengthReads = 0;
    }
    if (drainedBytes > 0) {
      L.log(logId, "USB stale input drained before ADB handshake, bytes=" + drainedBytes
              + ", reads=" + drainedReads);
    }
  }

  private void writeFully(byte[] data) throws IOException {
    int offset = 0;
    int failures = 0;
    while (offset < data.length) {
      if (closeStarted.get()) throw new IOException("USB channel closed during write");
      int transferred = honorUsbDevice
              ? asyncHonorWrite(data, offset, data.length - offset)
              : bulkTransfer(endpointOut, data, offset, data.length - offset,
              BULK_WRITE_TIMEOUT_MS);
      if (transferred <= 0) {
        failures++;
        if (bulkWriteRetryLogged.compareAndSet(false, true)) {
          String message = "USB bulk write retry enabled for " + deviceDescription
                  + ", offset=" + offset + ", bytes=" + data.length;
          Log.w(TAG, message);
          L.log(logId, message);
        }
        if (failures > MAX_BULK_WRITE_RETRIES) {
          throw new IOException("USB bulk write failed at " + offset + " of " + data.length
                  + " bytes after " + failures + " attempts");
        }
        try {
          Thread.sleep(failures * 5L);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IOException("USB write interrupted while retrying", e);
        }
        continue;
      }
      failures = 0;
      offset += transferred;
    }
  }

  private int bulkTransfer(UsbEndpoint endpoint, byte[] data, int offset, int length, int timeout) {
    return usbConnection.bulkTransfer(endpoint, data, offset, length, timeout);
  }

  private int asyncHonorWrite(byte[] data, int offset, int length) throws IOException {
    if (honorOutRequest == null) throw new IOException("Honor USB write request is unavailable");
    ByteBuffer buffer = ByteBuffer.allocateDirect(length);
    if (length > 0) buffer.put(data, offset, length);
    buffer.flip();
    RequestCompletion completion = new RequestCompletion(buffer);
    honorOutRequest.setClientData(completion);
    boolean queued;
    try {
      queued = honorOutRequest.queue(buffer, length);
    } catch (Exception e) {
      throw new IOException("Unable to queue Honor USB write", e);
    }
    if (!queued) throw new IOException("Unable to queue Honor USB write");
    return awaitHonorCompletion(completion, BULK_WRITE_TIMEOUT_MS * 5L, "write");
  }

  private int awaitHonorCompletion(RequestCompletion completion, long timeoutMs, String operation)
          throws IOException {
    long deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs;
    try {
      while (!closeStarted.get()) {
        long remaining = deadline - android.os.SystemClock.elapsedRealtime();
        if (remaining <= 0) throw new IOException("Honor USB " + operation + " timed out");
        if (completion.done.await(Math.min(remaining, 250L), TimeUnit.MILLISECONDS)) {
          return completion.actualLength;
        }
      }
      throw new IOException("USB channel closed during Honor " + operation);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Honor USB " + operation + " interrupted", e);
    }
  }

  private void dispatchHonorUsbCompletions() {
    try {
      while (!closeStarted.get() && !Thread.currentThread().isInterrupted()) {
        UsbRequest request = usbConnection.requestWait();
        if (request == null) throw new IOException("USB requestWait returned null");
        Object clientData = request.getClientData();
        request.setClientData(null);
        if (!(clientData instanceof RequestCompletion)) {
          throw new IOException("Unexpected Honor USB completion data");
        }
        RequestCompletion completion = (RequestCompletion) clientData;
        completion.actualLength = completion.buffer.position();
        completion.done.countDown();
      }
    } catch (Exception e) {
      if (!closeStarted.get()) {
        IOException failure = e instanceof IOException
                ? (IOException) e
                : new IOException("Honor USB completion loop failed", e);
        Log.e(TAG, "Honor USB completion dispatcher failed for " + deviceDescription, failure);
        closeInternal(failure);
      }
    }
  }

  private static final class RequestCompletion {
    final ByteBuffer buffer;
    final CountDownLatch done = new CountDownLatch(1);
    volatile int actualLength;

    RequestCompletion(ByteBuffer buffer) {
      this.buffer = buffer;
    }
  }

  @Override
  public ByteBuffer read(int size) throws InterruptedException, IOException {
    return sourceBuffer.read(size);
  }

  @Override
  public int available() {
    return sourceBuffer.getSize();
  }

  private void readBackground() {
    try {
      while (!closeStarted.get() && !Thread.currentThread().isInterrupted()) {
        // 读取头部
        ByteBuffer header = readRequest(AdbProtocol.ADB_HEADER_LENGTH).order(ByteOrder.LITTLE_ENDIAN);
        if (header.remaining() < AdbProtocol.ADB_HEADER_LENGTH) throw new IOException("Incomplete ADB USB header");
        // 读取载荷
        int payloadLength = header.getInt(12);
        if (payloadLength < 0 || payloadLength > AdbProtocol.MAX_ADB_PAYLOAD_LENGTH)
          throw new IOException("Invalid ADB USB payload length: " + payloadLength);
        sourceBuffer.write(header);
        if (payloadLength > 0) {
          ByteBuffer payload = readRequest(payloadLength);
          sourceBuffer.write(payload);
        }
      }
    } catch (Exception e) {
      if (!closeStarted.get()) {
        IOException failure = e instanceof IOException
                ? (IOException) e
                : new IOException("USB receive loop failed", e);
        Log.e(TAG, "USB ADB receive failed for " + deviceDescription, failure);
        L.log(logId, "USB receive failed: " + failure.getMessage());
        closeInternal(failure);
      }
    }
  }

  private ByteBuffer readRequest(int len) throws IOException {
    if (len < 0) throw new IOException("Invalid USB read length: " + len);
    if (closeStarted.get()) throw new IOException("USB channel is closed");
    ByteBuffer result = ByteBuffer.allocate(len);
    int received = 0;
    int zeroLengthPackets = 0;
    int queueFailures = 0;
    while (received < len) {
      if (closeStarted.get()) throw new IOException("USB channel is closed");
      UsbRequest request = obtainReadRequest();
      int remaining = len - received;
      ByteBuffer data = ByteBuffer.allocateDirect(remaining);
      boolean requestRecycled = false;
      try {
        RequestCompletion honorCompletion = honorUsbDevice ? new RequestCompletion(data) : null;
        request.setClientData(honorCompletion == null ? data : honorCompletion);
        // 荣耀 + 部分 Android 11 车机只有 direct buffer 配合旧队列能稳定收到真实数据；
        // 其他设备继续用 API 26+ 新队列。两条路径都在下方累积厂商拆分的短包。
        boolean queued = honorUsbDevice
                ? request.queue(data, remaining)
                : Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? request.queue(data)
                : request.queue(data, remaining);
        if (!queued) {
          queueFailures++;
          if (queueRetryLogged.compareAndSet(false, true)) {
            String message = "USB read queue retry enabled for " + deviceDescription
                    + ", waitingFor=" + remaining;
            Log.w(TAG, message);
            L.log(logId, message);
          }
          if (!honorUsbDevice && queueFailures > MAX_USB_QUEUE_RETRIES) {
            throw new IOException("Unable to queue USB read request for " + remaining
                    + " bytes after " + queueFailures + " attempts");
          }
          if (honorUsbDevice) {
            retireReadRequest(request);
            requestRecycled = true;
          }
          try {
            Thread.sleep(Math.min(MAX_USB_QUEUE_RETRIES, queueFailures) * 5L);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("USB read interrupted while retrying queue", e);
          }
          continue;
        }
        queueFailures = 0;

        if (honorUsbDevice) {
          awaitHonorCompletion(honorCompletion, 30_000L, "read");
        } else {
          while (true) {
            UsbRequest completedRequest = usbConnection.requestWait();
            if (completedRequest == null) throw new IOException("USB requestWait returned null");
            if (completedRequest == request) break;
            Object clientData = completedRequest.getClientData();
            completedRequest.setClientData(null);
            closeRequest(completedRequest);
            L.log(logId, "Ignored unexpected USB request completion, clientData="
                    + (clientData == null ? "null" : clientData.getClass().getSimpleName()));
          }
        }

        data.flip();
        int actualLength = data.remaining();

        if (actualLength == 0) {
          // AOSP adbd 通常不会在 Device→Host 方向发送 ZLP，但部分荣耀 USB 栈会在等待
          // 用户确认调试授权或切换大流量时连续返回零长度完成事件。新版队列在
          // requestWait() 后已完成 dequeue，可以复用，避免频繁 native close/init 耗尽 URB。
          if (zeroLengthPackets < Integer.MAX_VALUE) zeroLengthPackets++;
          if (zeroLengthPacketLogged.compareAndSet(false, true)) {
            L.log(logId, "USB zero-length packet skipped while waiting for " + len + " bytes");
            Log.i(TAG, "USB zero-length completions tolerated for " + deviceDescription
                    + ", waitingFor=" + len);
          }
          // requestWait() 已完成 dequeue；异步 OUT 也由统一分发线程处理后，可以安全
          // 复用这个 IN request 等待真正数据，且不会因 close() 触发荣耀重枚举。
          if (honorUsbDevice) {
            recycleReadRequest(request);
            requestRecycled = true;
          }
          try {
            Thread.sleep(Math.min(MAX_ZERO_LENGTH_BACKOFF_MS,
                    Math.max(1, zeroLengthPackets * 5L)));
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("USB read interrupted while waiting after zero-length packet", e);
          }
          continue;
        }
        if (actualLength > remaining) {
          throw new IOException("Oversized USB read: remaining " + remaining
                  + " bytes, received " + actualLength);
        }
        if (actualLength < remaining && shortReadLogged.compareAndSet(false, true)) {
          String message = "USB split read enabled for " + deviceDescription
                  + ", expected=" + remaining + ", firstChunk=" + actualLength;
          Log.i(TAG, message);
          L.log(logId, message);
        }
        result.put(data);
        received += actualLength;
        zeroLengthPackets = 0;
        recycleReadRequest(request);
        requestRecycled = true;
      } catch (IOException e) {
        throw e;
      } catch (Exception e) {
        throw new IOException("USB read request failed for " + len + " bytes", e);
      } finally {
        if (!requestRecycled) closeRequest(request);
      }
    }
    result.flip();
    return result;
  }

  private UsbRequest obtainReadRequest() throws IOException {
    synchronized (mInRequestPool) {
      if (!mInRequestPool.isEmpty()) return mInRequestPool.removeFirst();
    }
    UsbRequest request = new UsbRequest();
    if (!request.initialize(usbConnection, endpointIn)) {
      closeRequest(request);
      throw new IOException("Unable to initialize USB read request");
    }
    return request;
  }

  int getPreferredReceiveMaxData() {
    return honorUsbDevice ? HONOR_USB_RECEIVE_MAX_DATA : AdbProtocol.CONNECT_MAXDATA;
  }

  private void recycleReadRequest(UsbRequest request) {
    request.setClientData(null);
    synchronized (mInRequestPool) {
      if (closeStarted.get()) closeRequest(request);
      else mInRequestPool.addLast(request);
    }
  }

  private void retireReadRequest(UsbRequest request) {
    request.setClientData(null);
    synchronized (mInRequestPool) {
      mRetiredInRequests.addLast(request);
    }
  }

  private void closeRequest(UsbRequest request) {
    if (request == null) return;
    try {
      request.close();
    } catch (Exception ignored) {
    }
  }

  @Override
  public void flush() {
  }

  @Override
  public void close() {
    closeInternal(null);
  }

  private void closeInternal(IOException cause) {
    if (!closeStarted.compareAndSet(false, true)) return;
    sourceBuffer.close(cause);
    readBackgroundThread.interrupt();
    honorCompletionThread.interrupt();
    synchronized (mInRequestPool) {
      while (!mInRequestPool.isEmpty()) closeRequest(mInRequestPool.removeFirst());
      while (!mRetiredInRequests.isEmpty()) closeRequest(mRetiredInRequests.removeFirst());
    }
    closeRequest(honorOutRequest);
    honorOutRequest = null;

    // 旧版厂商 adbd 可能在 USB 重连后继续发送上一会话的数据，主动发送非法包让其丢弃旧会话。
    try {
      if (!honorUsbDevice && interfaceClaimed && endpointOut != null) {
        bulkTransfer(endpointOut, new byte[40], 0, 40, 100);
      }
    } catch (Exception ignored) {
    }

    try {
      if (interfaceClaimed && usbInterface != null) usbConnection.releaseInterface(usbInterface);
    } catch (Exception ignored) {
    } finally {
      interfaceClaimed = false;
    }
    try {
      usbConnection.close();
    } catch (Exception ignored) {
    }
  }

  private static String describeDevice(UsbDevice usbDevice) {
    return "name=" + usbDevice.getDeviceName()
            + ", deviceId=" + usbDevice.getDeviceId()
            + ", vendor=0x" + Integer.toHexString(usbDevice.getVendorId())
            + ", product=0x" + Integer.toHexString(usbDevice.getProductId());
  }

}
