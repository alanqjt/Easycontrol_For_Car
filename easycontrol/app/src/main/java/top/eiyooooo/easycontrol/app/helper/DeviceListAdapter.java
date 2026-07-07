package top.eiyooooo.easycontrol.app.helper;

import static android.content.ClipDescription.MIMETYPE_TEXT_PLAIN;

import android.app.Dialog;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
  public static final HashMap<String, UsbDevice> linkDevices = new HashMap<>();
  public static boolean startedDefault = false;

  private final Context context;
  private final GridLayout devicesGrid;

  public ExecutorService checkConnectionExecutor;
  private final Object checkingConnection = new Object();
  private Thread checkingConnectionThread;

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
  }

  private int getColumnCount() {
    int widthPx = devicesGrid.getWidth() > 0 ? devicesGrid.getWidth() : context.getResources().getDisplayMetrics().widthPixels;
    int heightPx = context.getResources().getDisplayMetrics().heightPixels;
    int widthDp = (int) (widthPx / context.getResources().getDisplayMetrics().density);
    return widthPx > heightPx || widthDp >= 720 ? 4 : 2;
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
    device.connection = 0;

    if (checkConnectionExecutor != null && checkConnectionExecutor.isShutdown() && checkingConnectionThread != null) {
      try {
        checkingConnectionThread.join();
      } catch (Exception ignored) {
      }
    }

    if (checkConnectionExecutor == null) {
      checkConnectionExecutor = Executors.newFixedThreadPool(devicesList.size() + 1);
    }

    if (checkingConnectionThread != null) checkingConnectionThread.interrupt();
    checkingConnectionThread = new Thread(() -> {
      try {
        Thread.sleep(1500);
        checkConnectionExecutor.shutdown();
        synchronized (checkingConnection) {
          checkingConnection.notifyAll();
        }
        while (!checkConnectionExecutor.awaitTermination(600, TimeUnit.MILLISECONDS)) {
          checkConnectionExecutor.shutdownNow();
        }
        AppData.uiHandler.post(this::render);
        checkConnectionExecutor = null;
        if (!startedDefault) {
          AppData.uiHandler.post(() -> startDefault(AppData.setting.getTryStartDefaultInAppTransfer() ? 1 : 0));
          startedDefault = true;
        }
      } catch (InterruptedException ignored) {
      }
    });
    checkingConnectionThread.start();

    checkConnectionExecutor.execute(() -> {
      try {
        if (!Adb.adbMap.containsKey(device.uuid)) {
          if (device.isLinkDevice()) {
            Adb adb = new Adb(device.uuid, linkDevices.get(device.uuid), AppData.keyPair);
            Adb.adbMap.put(device.uuid, adb);
          } else {
            new Adb(device.address, AppData.keyPair);
            Adb adb = new Adb(device.uuid, device.address, AppData.keyPair);
            Adb.adbMap.put(device.uuid, adb);
          }
        }
        synchronized (checkingConnection) {
          checkingConnection.wait();
        }
        if (device.connection == 0) device.connection = 1;
      } catch (Exception e) {
        device.connection = 2;
        L.log(device.uuid, e);
      }
    });
  }

  private void onLongClickCard(Device device) {
    ItemSetDeviceBinding itemSetDeviceBinding = ItemSetDeviceBinding.inflate(LayoutInflater.from(context));
    Dialog dialog = PublicTools.createDialog(context, true, itemSetDeviceBinding.getRoot());
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
      AppData.dbHelper.delete(device);
      if (Adb.adbMap.containsKey(device.uuid)) Objects.requireNonNull(Adb.adbMap.get(device.uuid)).close();
      update();
      dialog.cancel();
    });
    dialog.show();
  }

  private void queryDevices() {
    ArrayList<Device> rawDevices = AppData.dbHelper.getAll();
    ArrayList<Device> tmp1 = new ArrayList<>();
    ArrayList<Device> tmp2 = new ArrayList<>();
    for (Device device : rawDevices) {
      if (device.isLinkDevice() && linkDevices.containsKey(device.uuid)) tmp1.add(device);
      else if (device.isNormalDevice()) tmp2.add(device);
    }
    devicesList.clear();
    devicesList.addAll(tmp1);
    devicesList.addAll(tmp2);
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
