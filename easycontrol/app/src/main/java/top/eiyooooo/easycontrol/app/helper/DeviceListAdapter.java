package top.eiyooooo.easycontrol.app.helper;

import static android.content.ClipDescription.MIMETYPE_TEXT_PLAIN;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.hardware.usb.UsbDevice;
import android.os.Build;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import top.eiyooooo.easycontrol.app.R;
import top.eiyooooo.easycontrol.app.StartDeviceActivity;
import top.eiyooooo.easycontrol.app.adb.Adb;
import top.eiyooooo.easycontrol.app.client.Client;
import top.eiyooooo.easycontrol.app.databinding.ItemDeviceCardBinding;
import top.eiyooooo.easycontrol.app.databinding.ItemSetDeviceBinding;
import top.eiyooooo.easycontrol.app.entity.AppData;
import top.eiyooooo.easycontrol.app.entity.Device;

/**
 * 设备卡片网格渲染器：竖屏两列，横屏四列。
 */
public class DeviceListAdapter {

  public static final ArrayList<Device> devicesList = new ArrayList<>();
  public static final ConcurrentHashMap<String, UsbDevice> linkDevices = new ConcurrentHashMap<>();
  public static boolean startedDefault = false;

  private final Context context;
  private final GridLayout devicesGrid;
  private Runnable renderListener;

  public ExecutorService checkConnectionExecutor;
  private final Object checkingConnection = new Object();
  private int pendingConnectionChecks;

  public DeviceListAdapter(Context c, GridLayout devicesGrid) {
    context = c;
    this.devicesGrid = devicesGrid;
    queryDevices();
    render();
  }

  public int getDeviceCount() {
    return devicesList.size();
  }

  public void render() {
    if (devicesGrid == null) return;
    int columnCount = getColumnCount();
    devicesGrid.setColumnCount(columnCount);
    devicesGrid.removeAllViews();
    int deviceIndex = 0;
    for (Device device : devicesList) {
      if (device.connection == -1) checkConnection(device);
      ItemDeviceCardBinding cardBinding = ItemDeviceCardBinding.inflate(LayoutInflater.from(context), devicesGrid, false);
      bindCard(cardBinding, device);
      devicesGrid.addView(cardBinding.getRoot(), createCardLayoutParams(columnCount, deviceIndex));
      deviceIndex++;
    }
    if (renderListener != null) renderListener.run();
  }

  public void setRenderListener(Runnable renderListener) {
    this.renderListener = renderListener;
  }

  private int getColumnCount() {
    int widthPx = devicesGrid.getWidth() > 0 ? devicesGrid.getWidth() : context.getResources().getDisplayMetrics().widthPixels;
    int heightPx = devicesGrid.getHeight() > 0 ? devicesGrid.getHeight() : context.getResources().getDisplayMetrics().heightPixels;
    int orientation = context.getResources().getConfiguration().orientation;
    if (orientation == Configuration.ORIENTATION_PORTRAIT) return 2;
    if (orientation == Configuration.ORIENTATION_LANDSCAPE) return 4;
    return widthPx > heightPx ? 4 : 2;
  }

  private GridLayout.LayoutParams createCardLayoutParams(int columnCount, int deviceIndex) {
    GridLayout.LayoutParams params = new GridLayout.LayoutParams();
    int margin = dp(columnCount == 4 ? 7 : 8);
    int gridWidth = getGridContentWidth();
    int cardWidth = (gridWidth - margin * 2 * columnCount) / columnCount;
    params.width = Math.max(1, cardWidth);
    params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
    params.columnSpec = GridLayout.spec(deviceIndex % columnCount, 1);
    params.rowSpec = GridLayout.spec(deviceIndex / columnCount, 1);
    params.setMargins(margin, margin, margin, margin);
    return params;
  }

  private int getGridContentWidth() {
    int width = devicesGrid.getWidth();
    if (width <= 0) {
      width = context.getResources().getDisplayMetrics().widthPixels
              - context.getResources().getDimensionPixelSize(R.dimen.pagePadding) * 2;
    }
    return Math.max(0, width - devicesGrid.getPaddingLeft() - devicesGrid.getPaddingRight());
  }

  private int dp(int value) {
    return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
  }

  private void bindCard(ItemDeviceCardBinding binding, Device device) {
    binding.deviceExpand.setVisibility(View.GONE);
    binding.deviceActions.setVisibility(View.VISIBLE);
    setDeviceIcon(binding, device);
    setDeviceStatus(binding, device);
    binding.deviceName.setText(device.name);

    binding.getRoot().setOnLongClickListener(v -> {
      onLongClickCard(device);
      return true;
    });
    bindActions(binding, device);
  }

  private void setDeviceIcon(ItemDeviceCardBinding binding, Device device) {
    if (device.isLinkDevice()) {
      if (device.connection == 1) binding.deviceIcon.setImageResource(R.drawable.link_can_connect);
      else if (device.connection == 0) binding.deviceIcon.setImageResource(R.drawable.link_checking_connection);
      else binding.deviceIcon.setImageResource(R.drawable.link_can_not_connect);
    } else if (device.connection == 0) {
      binding.deviceIcon.setImageResource(R.drawable.wifi_checking_connection);
    } else if (device.connection == 1) {
      binding.deviceIcon.setImageResource(R.drawable.wifi_can_connect);
    } else {
      binding.deviceIcon.setImageResource(R.drawable.wifi_can_not_connect);
    }
  }

  private void setDeviceStatus(ItemDeviceCardBinding binding, Device device) {
    binding.deviceTransport.setText(device.isLinkDevice() ? R.string.device_type_usb : R.string.device_type_wifi);
    binding.deviceIdentity.setText(getDeviceIdentity(device));
    if (device.connection == 1) {
      binding.deviceConnectionStatus.setText(R.string.device_status_ready);
      binding.deviceConnectionStatus.setTextColor(getColor(R.color.statusOnline));
    } else if (device.connection == 0) {
      binding.deviceConnectionStatus.setText(R.string.device_status_checking);
      binding.deviceConnectionStatus.setTextColor(getColor(R.color.statusChecking));
    } else if (device.connection == 2) {
      binding.deviceConnectionStatus.setText(R.string.device_status_offline);
      binding.deviceConnectionStatus.setTextColor(getColor(R.color.statusOffline));
    } else {
      binding.deviceConnectionStatus.setText(R.string.device_status_pending);
      binding.deviceConnectionStatus.setTextColor(getColor(R.color.onCardBackgroundSecond));
    }
  }

  private String getDeviceIdentity(Device device) {
    if (device.isNormalDevice() && device.address != null && !device.address.isEmpty()) {
      return context.getString(R.string.device_identity_address, device.address);
    }
    int start = Math.max(0, device.uuid.length() - 5);
    return context.getString(R.string.device_identity_id, device.uuid.substring(start));
  }

  private int getColor(int colorRes) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) return context.getColor(colorRes);
    return context.getResources().getColor(colorRes);
  }

  private void bindActions(ItemDeviceCardBinding binding, Device device) {
    binding.isAudio.setChecked(device.isAudio);
    binding.defaultFull.setChecked(device.defaultFull);
    binding.isAudio.setOnCheckedChangeListener((buttonView, isChecked) -> {
      device.isAudio = isChecked;
      AppData.dbHelper.update(device);
    });
    View isAudioParent = (View) binding.isAudio.getParent();
    isAudioParent.setOnClickListener(v -> binding.isAudio.toggle());

    binding.defaultFull.setOnCheckedChangeListener((buttonView, isChecked) -> {
      device.defaultFull = isChecked;
      AppData.dbHelper.update(device);
    });
    View defaultFullParent = (View) binding.defaultFull.getParent();
    defaultFullParent.setOnClickListener(v -> binding.defaultFull.toggle());

    binding.displayMirroring.setOnClickListener(v -> startDevice(device, 0));
    binding.createDisplay.setOnClickListener(v -> startDevice(device, 1));
  }

  private void checkConnection(Device device) {
    if (device.isLinkDevice() && !linkDevices.containsKey(device.uuid)) {
      device.connection = 2;
      return;
    }
    device.connection = 0;

    synchronized (checkingConnection) {
      if (checkConnectionExecutor == null || checkConnectionExecutor.isShutdown()) {
        checkConnectionExecutor = Executors.newFixedThreadPool(Math.max(1, devicesList.size()));
      }
      pendingConnectionChecks++;
    }

    checkConnectionExecutor.execute(() -> {
      try {
        Adb.getOrCreate(device.uuid, () -> {
          if (device.isLinkDevice()) {
            UsbDevice usbDevice = linkDevices.get(device.uuid);
            if (usbDevice == null) throw new Exception("USB device not ready");
            return new Adb(device.uuid, usbDevice, AppData.keyPair);
          }
          new Adb(device.address, AppData.keyPair);
          return new Adb(device.uuid, device.address, AppData.keyPair);
        });
        if (device.connection == 0) device.connection = 1;
      } catch (Exception e) {
        device.connection = 2;
        L.log(device.uuid, e);
      } finally {
        AppData.uiHandler.post(this::render);
        finishConnectionCheck();
      }
    });
  }

  private void finishConnectionCheck() {
    ExecutorService executorToShutdown = null;
    boolean shouldStartDefault = false;
    synchronized (checkingConnection) {
      if (pendingConnectionChecks > 0) pendingConnectionChecks--;
      if (pendingConnectionChecks == 0) {
        executorToShutdown = checkConnectionExecutor;
        checkConnectionExecutor = null;
        if (!startedDefault) {
          startedDefault = true;
          shouldStartDefault = true;
        }
      }
    }
    if (executorToShutdown != null) executorToShutdown.shutdown();
    if (shouldStartDefault) {
      AppData.uiHandler.post(() -> startDefault(AppData.setting.getTryStartDefaultInAppTransfer() ? 1 : 0));
    }
  }

  private void onLongClickCard(Device device) {
    ItemSetDeviceBinding itemSetDeviceBinding = ItemSetDeviceBinding.inflate(LayoutInflater.from(context));
    Dialog dialog = PublicTools.createDialog(context, true, itemSetDeviceBinding.getRoot(), 640);
    itemSetDeviceBinding.deviceDetail.setText(device.name + " · " + (device.isNormalDevice() ? device.address : device.uuid));
    if (device.isLinkDevice()) {
      itemSetDeviceBinding.buttonStartWireless.setVisibility(View.VISIBLE);
      itemSetDeviceBinding.buttonStartWireless.setOnClickListener(v -> {
        dialog.cancel();
        UsbDevice usbDevice = linkDevices.get(device.uuid);
        if (usbDevice == null) return;
        Client.restartOnTcpip(device, usbDevice, result -> AppData.uiHandler.post(() -> Toast.makeText(AppData.main, AppData.main.getString(result ? R.string.set_device_button_start_wireless_success : R.string.set_device_button_recover_error), Toast.LENGTH_SHORT).show()));
      });
    } else {
      itemSetDeviceBinding.buttonStartWireless.setVisibility(View.GONE);
    }
    itemSetDeviceBinding.buttonNightMode.setOnClickListener(v -> {
      dialog.cancel();
      PublicTools.showNightModeChanger(context, device);
    });
    itemSetDeviceBinding.buttonGetUuid.setOnClickListener(v -> {
      dialog.cancel();
      AppData.clipBoard.setPrimaryClip(ClipData.newPlainText(MIMETYPE_TEXT_PLAIN, device.uuid));
      Toast.makeText(AppData.main, AppData.main.getString(R.string.set_device_button_get_uuid_success), Toast.LENGTH_SHORT).show();
    });
    itemSetDeviceBinding.buttonCreateShortcut.setOnClickListener(v -> {
      try {
        if (device.specified_app == null || device.specified_app.isEmpty()) throw new Exception();
        ShortcutHelper.addShortcut(AppData.main, StartDeviceActivity.class, device.name, Adb.getRemoteIconByDevice(device, device.specified_app), device.uuid);
      } catch (Exception e) {
        L.log(device.uuid, e);
        ShortcutHelper.addShortcut(AppData.main, StartDeviceActivity.class, device.name, R.drawable.phone, device.uuid);
      }
    });
    itemSetDeviceBinding.buttonChange.setOnClickListener(v -> {
      dialog.cancel();
      PublicTools.createAddDeviceView(context, device, this).show();
    });
    itemSetDeviceBinding.buttonDelete.setOnClickListener(v -> {
      new AlertDialog.Builder(context)
              .setTitle(R.string.set_device_delete_confirm_title)
              .setMessage(R.string.set_device_delete_confirm_message)
              .setNegativeButton(R.string.cancel, null)
              .setPositiveButton(R.string.set_device_delete_confirm_action, (confirmDialog, which) -> {
                AppData.dbHelper.delete(device);
                Adb existingAdb = Adb.adbMap.get(device.uuid);
                if (existingAdb != null) existingAdb.close();
                update();
                dialog.cancel();
              })
              .show();
    });
    dialog.show();
  }

  private void queryDevices() {
    ArrayList<Device> rawDevices = AppData.dbHelper.getAll();
    ArrayList<Device> connectedLinkDevices = new ArrayList<>();
    ArrayList<Device> normalDevices = new ArrayList<>();
    ArrayList<Device> offlineLinkDevices = new ArrayList<>();
    for (Device device : rawDevices) {
      if (device.isNormalDevice()) {
        normalDevices.add(device);
      } else if (device.isLinkDevice() && linkDevices.containsKey(device.uuid)) {
        connectedLinkDevices.add(device);
      } else if (device.isLinkDevice()) {
        offlineLinkDevices.add(device);
      }
    }
    devicesList.clear();
    devicesList.addAll(connectedLinkDevices);
    devicesList.addAll(normalDevices);
    devicesList.addAll(offlineLinkDevices);
    if (!startedDefault && devicesList.isEmpty()) startedDefault = true;
  }

  public static void startByUUID(String uuid, int mode) {
    for (Device device : devicesList) {
      if (Objects.equals(device.uuid, uuid)) startDevice(device, mode);
    }
  }

  public static void startDevice(Device device, int mode) {
    if (device.isLinkDevice()) {
      UsbDevice usbDevice = linkDevices.get(device.uuid);
      if (usbDevice == null) return;
      new Client(device, usbDevice, mode);
    } else new Client(device, null, mode);
  }

  public static void startDefault(int mode) {
    boolean started = false;
    for (Device device : devicesList) {
      if (device.connectOnStart) {
        startDevice(device, mode);
        started = true;
        if (AppData.setting.getAlwaysFullMode()) break;
      }
    }
    if (started && !AppData.setting.getAlwaysFullMode() && AppData.setting.getAutoBackOnStartDefault()) {
      Intent home = new Intent(Intent.ACTION_MAIN);
      home.addCategory(Intent.CATEGORY_HOME);
      AppData.activity.startActivity(home);
    }
  }

  public final void update() {
    queryDevices();
    render();
  }
}
