package top.eiyooooo.easycontrol.app;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;

import java.util.UUID;

import android.view.animation.LinearInterpolator;

import top.eiyooooo.easycontrol.app.client.Client;
import top.eiyooooo.easycontrol.app.databinding.ActivityMainBinding;
import top.eiyooooo.easycontrol.app.databinding.DialogSettingSectionBinding;
import top.eiyooooo.easycontrol.app.databinding.ItemPrivacyPolicyBinding;
import top.eiyooooo.easycontrol.app.databinding.ItemRequestPermissionBinding;
import top.eiyooooo.easycontrol.app.entity.AppData;
import top.eiyooooo.easycontrol.app.entity.Device;
import top.eiyooooo.easycontrol.app.helper.DeviceListAdapter;
import top.eiyooooo.easycontrol.app.helper.PublicTools;
import top.eiyooooo.easycontrol.app.helper.ConnectHelper;
import top.eiyooooo.easycontrol.app.helper.SettingsPanelHelper;

public class MainActivity extends Activity {
  // 设备列表
  private DeviceListAdapter deviceListAdapter;
  private ConnectHelper connectHelper;

  // 创建界面
  private ActivityMainBinding mainActivity;

  @SuppressLint("SourceLockedOrientationActivity")
  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    AppData.init(this);
    PublicTools.setStatusAndNavBar(this);
    PublicTools.setLocale(this);
    mainActivity = ActivityMainBinding.inflate(this.getLayoutInflater());
    setContentView(mainActivity.getRoot());
    if (!AppData.setting.getPrivacyPolicyAccepted()) showPrivacyPolicyDialog();
    else continueAfterPrivacyAccepted();
  }

  private void continueAfterPrivacyAccepted() {
    if (AppData.setting.getAlwaysFullMode() || haveOverlayPermission()) startApp();
    else createAlert();
  }

  private void showPrivacyPolicyDialog() {
    ItemPrivacyPolicyBinding privacyPolicyView = ItemPrivacyPolicyBinding.inflate(LayoutInflater.from(this));
    privacyPolicyView.buttonAgree.setOnClickListener(v -> {
      AppData.setting.setPrivacyPolicyAccepted(true);
      Dialog dialog = (Dialog) privacyPolicyView.getRoot().getTag();
      if (dialog != null) dialog.cancel();
      continueAfterPrivacyAccepted();
    });
    privacyPolicyView.buttonCancel.setOnClickListener(v -> finishAffinity());
    Dialog dialog = PublicTools.createDialog(this, false, privacyPolicyView.getRoot());
    privacyPolicyView.getRoot().setTag(dialog);
    dialog.show();
  }

  private void startApp() {
    // 设置设备列表适配器、广播接收器
    deviceListAdapter = new DeviceListAdapter(this, mainActivity.devicesGrid);
    deviceListAdapter.setRenderListener(this::updateDeviceSummary);
    updateDeviceSummary();
    AppData.myBroadcastReceiver.setDeviceListAdapter(deviceListAdapter);
    connectHelper = new ConnectHelper(this);
    AppData.myBroadcastReceiver.setConnectHelper(connectHelper);
    if (AppData.setting.getEnableUSB()) AppData.myBroadcastReceiver.checkConnectedUsb(this);
    ConnectHelper.status = true;
    // 设置按钮监听
    setButtonListener();
    // 首次使用显示使用说明
    if (!AppData.setting.getShowUsage()) {
      AppData.setting.setShowUsage(true);
      AppData.uiHandler.postDelayed(() -> PublicTools.openWebViewActivity(this, "file:///android_asset/usage.html"), 1500);
    }
    if (!Client.allClient.isEmpty()) {
      for (Client client : Client.allClient) {
        if (client.clientView.viewMode == 3) {
          client.clientView.changeToFull();
          break;
        }
      }
    }
  }

  @Override
  protected void onDestroy() {
    if (connectHelper != null) AppData.uiHandler.removeCallbacks(connectHelper.showStartDefaultUSB);
    AppData.myBroadcastReceiver.setDeviceListAdapter(null);
    AppData.myBroadcastReceiver.setConnectHelper(null);
    ConnectHelper.status = false;
    super.onDestroy();
  }

  @Override
  protected void onPause() {
    if (connectHelper != null) AppData.uiHandler.removeCallbacks(connectHelper.showStartDefaultUSB);
    ConnectHelper.status = false;
    super.onPause();
  }

  @Override
  protected void onResume() {
    if (!AppData.setting.getPrivacyPolicyAccepted()) {
      super.onResume();
      return;
    }
    ConnectHelper.status = true;
    if (!AppData.setting.getAlwaysFullMode() && !haveOverlayPermission()) createAlert();
    else {
      if (!Client.allClient.isEmpty()) {
        for (Client client : Client.allClient) {
          if (client.clientView.viewMode == 3) {
            client.clientView.changeToFull();
            break;
          }
        }
      }
    }
    super.onResume();
  }

  @Override
  public void onConfigurationChanged(Configuration newConfig) {
    super.onConfigurationChanged(newConfig);
    refreshDeviceListForOrientation();
  }

  private void refreshDeviceListForOrientation() {
    if (deviceListAdapter != null) deviceListAdapter.render();
  }

  private void updateDeviceSummary() {
    if (mainActivity == null) return;
    int total = DeviceListAdapter.devicesList.size();
    if (total == 0) {
      mainActivity.mainDeviceStatus.setText(R.string.main_device_summary_empty);
      return;
    }
    int ready = 0;
    int checking = 0;
    for (Device device : DeviceListAdapter.devicesList) {
      if (device.connection == 1) ready++;
      else if (device.connection == 0 || device.connection == -1) checking++;
    }
    mainActivity.mainDeviceStatus.setText(getString(R.string.main_device_summary, total, ready, checking));
  }

  // 检查权限
  private boolean haveOverlayPermission() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) return Settings.canDrawOverlays(this);
    else return PublicTools.checkOpNoThrow(this, "OP_SYSTEM_ALERT_WINDOW", 24);
  }

  // 创建无权限提示
  private void createAlert() {
    ItemRequestPermissionBinding requestPermissionView = ItemRequestPermissionBinding.inflate(LayoutInflater.from(this));
    requestPermissionView.buttonGoToSet.setOnClickListener(v -> startActivity(PublicTools.getOverlayPermissionIntent(this)));
    requestPermissionView.buttonAlwaysFullMode.setOnClickListener(v -> AppData.setting.setAlwaysFullMode(true));
    Dialog dialog = PublicTools.createDialog(this, false, requestPermissionView.getRoot());
    dialog.setOnCancelListener(dialog1 -> {
      if (!AppData.setting.getAlwaysFullMode() && !haveOverlayPermission()) dialog.show();
    });
    dialog.show();
    checkPermissionDelay(dialog);
  }

  // 定时检查
  private void checkPermissionDelay(Dialog dialog) {
    // 因为某些设备可能会无法进入设置或其他问题，导致不会有返回结果，为了减少不确定性，使用定时检测的方法
    AppData.uiHandler.postDelayed(() -> {
      if (AppData.setting.getAlwaysFullMode() || haveOverlayPermission()) {
        dialog.cancel();
        startApp();
      } else checkPermissionDelay(dialog);
    }, 1000);
  }

  // 设置按钮监听
  private void setButtonListener() {
    mainActivity.buttonRefresh.setOnClickListener(v -> {
      mainActivity.buttonRefresh.setClickable(false);
      deviceListAdapter.update();

      ObjectAnimator rotation = ObjectAnimator.ofFloat(mainActivity.buttonRefresh, "rotation", 0f, 360f);
      rotation.setDuration(800);
      rotation.setInterpolator(new LinearInterpolator());
      rotation.addListener(new AnimatorListenerAdapter() {
        @Override
        public void onAnimationEnd(Animator animation) {
          super.onAnimationEnd(animation);
          if (deviceListAdapter.checkConnectionExecutor != null) rotation.start();
          else mainActivity.buttonRefresh.setClickable(true);
        }
      });
      rotation.start();
    });
    mainActivity.buttonPair.setOnClickListener(v -> startActivity(new Intent(this, PairActivity.class)));
    mainActivity.buttonAdd.setOnClickListener(v -> PublicTools.createAddDeviceView(this, Device.getDefaultDevice(UUID.randomUUID().toString(), Device.TYPE_NORMAL), deviceListAdapter).show());
    mainActivity.buttonSetDefault.setOnClickListener(v -> showSettingSection(SettingsPanelHelper.SECTION_DEFAULT));
    mainActivity.buttonSetDisplay.setOnClickListener(v -> showSettingSection(SettingsPanelHelper.SECTION_DISPLAY));
    mainActivity.buttonSetOther.setOnClickListener(v -> showSettingSection(SettingsPanelHelper.SECTION_OTHER));
    mainActivity.buttonSetAbout.setOnClickListener(v -> showSettingSection(SettingsPanelHelper.SECTION_ABOUT));
  }

  private void showSettingSection(int section) {
    DialogSettingSectionBinding binding = DialogSettingSectionBinding.inflate(LayoutInflater.from(this));
    binding.settingTitle.setText(SettingsPanelHelper.getSectionTitle(this, section));
    binding.settingDetail.setText(SettingsPanelHelper.getSectionDetail(this, section));
    binding.settingIcon.setImageResource(SettingsPanelHelper.getSectionIcon(section));
    SettingsPanelHelper.populateSection(this, section, binding.settingContent);
    Dialog dialog = PublicTools.createDialog(this, true, binding.getRoot(), SettingsPanelHelper.getSectionDialogWidth(section));
    binding.settingClose.setOnClickListener(v -> dialog.cancel());
    dialog.show();
  }
}
