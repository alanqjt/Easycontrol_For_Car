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
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedList;
import java.util.concurrent.atomic.AtomicBoolean;

import top.eiyooooo.easycontrol.app.entity.AppData;
import top.eiyooooo.easycontrol.app.buffer.Buffer;
import top.eiyooooo.easycontrol.app.helper.L;

public class UsbChannel implements AdbChannel {

  private static final String TAG = "EasycontrolUsb";
  private static final int MAX_CONSECUTIVE_ZERO_LENGTH_PACKETS = 4;
  private static final int STALE_INPUT_DRAIN_TIMEOUT_MS = 20;
  private static final int MAX_STALE_INPUT_DRAIN_READS = 32;

  private final String logId;
  private final String deviceDescription;
  private final UsbDeviceConnection usbConnection;
  private UsbInterface usbInterface = null;
  private UsbEndpoint endpointIn = null;
  private UsbEndpoint endpointOut = null;
  private final Buffer sourceBuffer = new Buffer();
  private final Thread readBackgroundThread;
  private final LinkedList<UsbRequest> mInRequestPool = new LinkedList<>();
  private final AtomicBoolean closeStarted = new AtomicBoolean(false);
  private final AtomicBoolean readStarted = new AtomicBoolean(false);
  private final AtomicBoolean zeroLengthPacketLogged = new AtomicBoolean(false);
  private volatile boolean interfaceClaimed;

  public UsbChannel(UsbDevice usbDevice) throws IOException {
    this("USB", usbDevice);
  }

  public UsbChannel(String logId, UsbDevice usbDevice) throws IOException {
    this.logId = logId == null || logId.isEmpty() ? "USB" : logId;
    deviceDescription = describeDevice(usbDevice);
    readBackgroundThread = new Thread(this::readBackground, "easycontrol_usb_read_" + usbDevice.getDeviceId());
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
    String openLog = "USB ADB opened " + deviceDescription
            + ", interface=" + usbInterface.getId()
            + ", in=0x" + Integer.toHexString(endpointIn.getAddress())
            + ", out=0x" + Integer.toHexString(endpointOut.getAddress())
            + ", maxPacket=" + endpointOut.getMaxPacketSize();
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
      writeFully(header);
      // 读取载荷
      int payloadLength = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt(12);
      if (payloadLength < 0 || payloadLength > AdbProtocol.MAX_ADB_PAYLOAD_LENGTH || payloadLength > data.remaining())
        throw new IOException("invalid ADB USB payload length: " + payloadLength);
      if (payloadLength > 0) {
        byte[] payload = new byte[payloadLength];
        data.get(payload);
        writeFully(payload);
      }
    }
  }

  private void startReadLoopIfNeeded() throws IOException {
    if (!readStarted.compareAndSet(false, true)) return;
    try {
      drainStaleInput();
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
      int read = usbConnection.bulkTransfer(endpointIn, staleData, staleData.length,
              STALE_INPUT_DRAIN_TIMEOUT_MS);
      if (read < 0) break;
      if (read == 0) {
        if (++zeroLengthReads > MAX_CONSECUTIVE_ZERO_LENGTH_PACKETS) break;
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
    while (offset < data.length) {
      if (closeStarted.get()) throw new IOException("USB channel closed during write");
      int transferred = usbConnection.bulkTransfer(endpointOut, data, offset, data.length - offset, 1000);
      if (transferred <= 0) throw new IOException("USB bulk write failed at " + offset + " of " + data.length + " bytes");
      offset += transferred;
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
    int zeroLengthPackets = 0;
    while (true) {
      if (closeStarted.get()) throw new IOException("USB channel is closed");
      UsbRequest request = obtainReadRequest();
      ByteBuffer data = ByteBuffer.allocate(len);
      boolean requestRecycled = false;
      try {
        request.setClientData(data);
        // 车机 ROM 对新版 queue(ByteBuffer) 的实现不稳定；旧接口是本项目原有且已验证的路径。
        if (!request.queue(data, len)) throw new IOException("Unable to queue USB read request for " + len + " bytes");

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

        data.flip();
        int actualLength = data.remaining();

        if (actualLength == 0) {
          // 部分手机会插入 USB 零长度包。该请求不再复用，下一轮创建新请求继续等数据。
          zeroLengthPackets++;
          if (zeroLengthPacketLogged.compareAndSet(false, true)) {
            L.log(logId, "USB zero-length packet skipped while waiting for " + len + " bytes");
          }
          if (zeroLengthPackets > MAX_CONSECUTIVE_ZERO_LENGTH_PACKETS) {
            throw new IOException("Too many consecutive USB zero-length packets");
          }
          continue;
        }
        if (actualLength != len) {
          throw new IOException("Short USB read: expected " + len + " bytes, received " + actualLength);
        }
        recycleReadRequest(request);
        requestRecycled = true;
        return data;
      } catch (IOException e) {
        throw e;
      } catch (Exception e) {
        throw new IOException("USB read request failed for " + len + " bytes", e);
      } finally {
        if (!requestRecycled) closeRequest(request);
      }
    }
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

  private void recycleReadRequest(UsbRequest request) {
    request.setClientData(null);
    synchronized (mInRequestPool) {
      if (closeStarted.get()) closeRequest(request);
      else mInRequestPool.addLast(request);
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
    synchronized (mInRequestPool) {
      while (!mInRequestPool.isEmpty()) closeRequest(mInRequestPool.removeFirst());
    }

    // 旧版厂商 adbd 可能在 USB 重连后继续发送上一会话的数据，主动发送非法包让其丢弃旧会话。
    try {
      if (interfaceClaimed && endpointOut != null) {
        usbConnection.bulkTransfer(endpointOut, new byte[40], 40, 100);
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
