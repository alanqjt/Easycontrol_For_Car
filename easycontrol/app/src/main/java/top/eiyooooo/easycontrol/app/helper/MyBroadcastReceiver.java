package top.eiyooooo.easycontrol.app.helper;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;

import top.eiyooooo.easycontrol.app.BuildConfig;
import top.eiyooooo.easycontrol.app.adb.Adb;
import top.eiyooooo.easycontrol.app.entity.AppData;
import top.eiyooooo.easycontrol.app.entity.Device;
import top.eiyooooo.easycontrol.app.R;
import top.eiyooooo.easycontrol.app.client.Client;

import java.util.Map;
import java.util.Objects;

public class MyBroadcastReceiver extends BroadcastReceiver {

  private static final String ACTION_USB_PERMISSION = BuildConfig.APPLICATION_ID + ".USB_PERMISSION";
  private static final String ACTION_CONTROL = "top.eiyooooo.easycontrol.app.CONTROL";
  private static final String ACTION_SCREEN_OFF = "android.intent.action.SCREEN_OFF";
  private static final int HONOR_USB_VENDOR_ID = 0x339b;
  private static final int USB_IDENTITY_RETRY_COUNT = 3;
  private static final long USB_IDENTITY_RETRY_DELAY_MS = 250L;
  public static final String ACTION_CONFIGURATION_CHANGED = "android.intent.action.CONFIGURATION_CHANGED";

  private DeviceListAdapter deviceListAdapter;
  private ConnectHelper connectHelper;

  // 注册广播
  @SuppressLint("UnspecifiedRegisterReceiverFlag")
  public void register(Context context) {
    IntentFilter filter = new IntentFilter();
    filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
    filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
    filter.addAction(ACTION_USB_PERMISSION);
    filter.addAction(ACTION_CONTROL);
    filter.addAction(ACTION_SCREEN_OFF);
    filter.addAction(ACTION_CONFIGURATION_CHANGED);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) context.registerReceiver(this, filter, Context.RECEIVER_EXPORTED);
    else context.registerReceiver(this, filter);
  }

  public void unRegister(Context context) {
    context.unregisterReceiver(this);
  }

  @Override
  public void onReceive(Context context, Intent intent) {
    String action = intent.getAction();
    if (ACTION_SCREEN_OFF.equals(action)) handleScreenOff();
    else if (ACTION_CONTROL.equals(action)) handleControl(intent);
    else if (ACTION_CONFIGURATION_CHANGED.equals(action)) handleConfigurationChanged();
    else handleUSB(context, intent);
  }

  public void handleConfigurationChanged() {
    int nightMode = AppData.uiModeManager.getNightMode();
    if (nightMode == AppData.nightMode) return;
    for (Client client : Client.allClient) {
      if (client.clientView.device.nightModeSync) {
        client.controlPacket.sendNightModeEvent(nightMode);
      }
    }
    AppData.nightMode = nightMode;
  }

  public void setDeviceListAdapter(DeviceListAdapter deviceListAdapter) {
    this.deviceListAdapter = deviceListAdapter;
  }

  public void setConnectHelper(ConnectHelper connectHelper) {
    this.connectHelper = connectHelper;
  }

  private void handleScreenOff() {
    for (Client client : Client.allClient) client.release(null);
  }

  private void handleControl(Intent intent) {
    String action = intent.getStringExtra("action");
    if (action == null) return;
    if (action.equals("startDefault")) {
      DeviceListAdapter.startDefault(intent.getIntExtra("mode", 0));
      return;
    }
    String uuid = intent.getStringExtra("uuid");
    if (uuid == null) return;
    if (action.equals("start")) DeviceListAdapter.startByUUID(uuid, intent.getIntExtra("mode", 0));
    else {
      for (Client client : Client.allClient) {
        if (Objects.equals(client.uuid, uuid)) {
            switch (action) {
                case "changeToSmall":
                    client.clientView.changeToSmall();
                    break;
                case "changeToFull":
                    client.clientView.changeToFull();
                    break;
                case "changeToMini":
                    client.clientView.changeToMini(0);
                    break;
                case "buttonPower":
                    client.controlPacket.sendPowerEvent();
                    break;
                case "buttonWake":
                    client.controlPacket.sendKeyEvent(224, 0, 0);
                    break;
                case "buttonLock":
                    client.controlPacket.sendKeyEvent(223, 0, 0);
                    break;
                case "buttonLight":
                    client.controlPacket.sendLightEvent(1);
                    break;
                case "buttonLightOff":
                    client.controlPacket.sendLightEvent(0);
                    break;
                case "buttonBack":
                    client.controlPacket.sendKeyEvent(4, 0, -1);
                    break;
                case "buttonHome":
                    client.controlPacket.sendKeyEvent(3, 0, -1);
                    break;
                case "buttonSwitch":
                    client.controlPacket.sendKeyEvent(187, 0, -1);
                    break;
                case "buttonRotate":
                    client.controlPacket.sendRotateEvent();
                    break;
                case "close":
                    client.release(null);
                    break;
                case "runShell":
                    String cmd = intent.getStringExtra("cmd");
                    if (cmd == null) return;
                    try {
                        client.adb.runAdbCmd(cmd);
                    } catch (Exception ignored) {
                    }
                    break;
            }
          return;
        }
      }
    }
  }

  public void handleReconnect(Device device, int mode) {
    if (device.isLinkDevice() && !DeviceListAdapter.devicesList.contains(device)) return;
    ConnectHelper.show(connectHelper, device.uuid, mode);
  }

  private void handleUSB(Context context, Intent intent) {
    UsbDevice usbDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
    String action = intent.getAction();
    if (usbDevice == null && action != null) return;
    if (Objects.equals(action, UsbManager.ACTION_USB_DEVICE_DETACHED)) onCutUsb(usbDevice);
    if (AppData.setting.getEnableUSB()) {
        if (Objects.equals(action, UsbManager.ACTION_USB_DEVICE_ATTACHED)) onConnectUsb(context, usbDevice);
        else if (Objects.equals(action, ACTION_USB_PERMISSION)) {
          if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) onGetUsbPer(usbDevice);
          else L.log("USB", "USB permission denied: " + describeUsbDevice(usbDevice));
        }
    }
  }

  // 检查已连接设备
  public void checkConnectedUsb(Context context) {
    if (AppData.usbManager==null)return;
    for (Map.Entry<String, UsbDevice> entry : AppData.usbManager.getDeviceList().entrySet()) onConnectUsb(context, entry.getValue());
  }

  // 请求USB设备权限
  private void onConnectUsb(Context context, UsbDevice usbDevice) {
    if (AppData.usbManager==null)return;
    if (hasUsbPermission(usbDevice)) {
      L.log("USB", "USB permission already granted: " + describeUsbDevice(usbDevice));
      onGetUsbPer(usbDevice);
      return;
    }
    Intent usbPermissionIntent = new Intent(ACTION_USB_PERMISSION);
    usbPermissionIntent.setPackage(AppData.main.getPackageName());
    int flags = PendingIntent.FLAG_UPDATE_CURRENT;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_MUTABLE;
    PendingIntent permissionIntent = PendingIntent.getBroadcast(context, usbDevice.getDeviceId(), usbPermissionIntent, flags);
    try {
      AppData.usbManager.requestPermission(usbDevice, permissionIntent);
    } catch (Exception e) {
      L.log("USB", "USB permission request failed: " + describeUsbDevice(usbDevice));
      L.log("USB", e);
    }
  }

  // 当断开设备
  private void onCutUsb(UsbDevice usbDevice) {
    for (Map.Entry<String, UsbDevice> entry : DeviceListAdapter.linkDevices.entrySet()) {
      UsbDevice tmp = entry.getValue();
      if (tmp.getVendorId() == usbDevice.getVendorId() && tmp.getProductId() == usbDevice.getProductId()) {
        boolean preserveAdb = false;
        for (Client client : Client.allClient) {
          if (!client.uuid.equals(entry.getKey())) continue;
          if (client.shouldIgnoreUsbDetach()) preserveAdb = true;
          else client.release(AppData.main.getString(R.string.error_stream_closed));
        }
        DeviceListAdapter.linkDevices.remove(entry.getKey());
        if (!preserveAdb) {
          Adb disconnectedAdb = Adb.adbMap.remove(entry.getKey());
          if (disconnectedAdb != null) disconnectedAdb.close();
        }
        ConnectHelper.needStartDefaultUSB.remove(entry.getKey());
        break;
      }
    }
    if (deviceListAdapter != null) deviceListAdapter.update();
  }

  // 处理USB授权结果
  private void onGetUsbPer(UsbDevice usbDevice) {
    onGetUsbPer(usbDevice, USB_IDENTITY_RETRY_COUNT);
  }

  private void onGetUsbPer(UsbDevice usbDevice, int retriesRemaining) {
    // 有线设备使用序列号作为唯一标识符
    if (!hasUsbPermission(usbDevice)) {
      L.log("USB", "USB permission missing after callback: " + describeUsbDevice(usbDevice));
      return;
    }
    String uuid = getUsbUuid(usbDevice);
    if (uuid == null) {
      if (retriesRemaining > 0) {
        AppData.uiHandler.postDelayed(
                () -> retryUsbIdentity(usbDevice, retriesRemaining - 1),
                USB_IDENTITY_RETRY_DELAY_MS);
      } else {
        L.log("USB", "USB device ignored because no stable serial is available: "
                + describeUsbDevice(usbDevice));
      }
      return;
    }
    // 查找ADB的接口
    for (int i = 0; i < usbDevice.getInterfaceCount(); i++) {
      UsbInterface tmpUsbInterface = usbDevice.getInterface(i);
      if ((tmpUsbInterface.getInterfaceClass() == UsbConstants.USB_CLASS_VENDOR_SPEC) && (tmpUsbInterface.getInterfaceSubclass() == 66) && (tmpUsbInterface.getInterfaceProtocol() == 1)) {
        // 若没有该设备，则新建设备
        Device device = AppData.dbHelper.getByUUID(uuid);
        if (device == null) {
          device = Device.getDefaultDevice(uuid, Device.TYPE_LINK);
          AppData.dbHelper.insert(device);
        }
        DeviceListAdapter.linkDevices.put(uuid, usbDevice);
        if (device.connectOnStart && DeviceListAdapter.startedDefault) {
          ConnectHelper.needStartDefaultUSB.put(device.uuid, device);
          if (connectHelper != null) {
            AppData.uiHandler.removeCallbacks(connectHelper.showStartDefaultUSB);
            AppData.uiHandler.postDelayed(connectHelper.showStartDefaultUSB, 1000);
          }
        }
        if (deviceListAdapter != null) deviceListAdapter.update();
        break;
      }
    }
  }

  private void retryUsbIdentity(UsbDevice previousDevice, int retriesRemaining) {
    if (AppData.usbManager == null) return;
    UsbDevice currentDevice = AppData.usbManager.getDeviceList().get(previousDevice.getDeviceName());
    if (currentDevice == null || currentDevice.getDeviceId() != previousDevice.getDeviceId()) return;
    onGetUsbPer(currentDevice, retriesRemaining);
  }

  private boolean hasUsbPermission(UsbDevice usbDevice) {
    try {
      return AppData.usbManager != null && AppData.usbManager.hasPermission(usbDevice);
    } catch (Exception ignored) {
      return false;
    }
  }

  private String getUsbUuid(UsbDevice usbDevice) {
    try {
      String serialNumber = usbDevice.getSerialNumber();
      if (serialNumber != null && !serialNumber.trim().isEmpty()) return serialNumber.trim();
    } catch (SecurityException e) {
      L.log("USB", e);
    } catch (Exception ignored) {
    }
    // /dev/bus/usb/... 是每次重新枚举都会变化的内核路径，不能作为持久 UUID。
    // 荣耀 ADB 接口会短暂拿不到序列号；等待下一次重试/重枚举，避免制造幽灵设备。
    if (usbDevice.getVendorId() == HONOR_USB_VENDOR_ID) return null;
    // 兼容确实不提供序列号的旧设备，同时避免使用会变化的 deviceName。
    return "usb-" + Integer.toHexString(usbDevice.getVendorId())
            + "-" + Integer.toHexString(usbDevice.getProductId());
  }

  private static String describeUsbDevice(UsbDevice usbDevice) {
    if (usbDevice == null) return "device=null";
    return "name=" + usbDevice.getDeviceName()
            + ", deviceId=" + usbDevice.getDeviceId()
            + ", vendor=0x" + Integer.toHexString(usbDevice.getVendorId())
            + ", product=0x" + Integer.toHexString(usbDevice.getProductId());
  }
}
