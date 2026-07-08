package top.eiyooooo.easycontrol.app;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
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
import top.eiyooooo.easycontrol.app.helper.BydPanoramaMonitor;
import top.eiyooooo.easycontrol.app.helper.DeviceListAdapter;
import top.eiyooooo.easycontrol.app.helper.PublicTools;
import top.eiyooooo.easycontrol.app.helper.ConnectHelper;
import top.eiyooooo.easycontrol.app.helper.SettingsPanelHelper;

public class MainActivity extends Activity {
  private static final String TAG = "MainActivity";
  private static final String BYD_PANORAMA_CLASS = "android.hardware.bydauto.panorama.BYDAutoPanoramaDevice";
  private static final String BYD_PANORAMA_COMMON_PERMISSION = "android.permission.BYDAUTO_PANORAMA_COMMON";
  private static final String BYD_PANORAMA_GET_PERMISSION = "android.permission.BYDAUTO_PANORAMA_GET";
  private static final String[] BYD_PANORAMA_PERMISSIONS = new String[]{
      BYD_PANORAMA_COMMON_PERMISSION,
      BYD_PANORAMA_GET_PERMISSION
  };
  private static final int REQUEST_BYD_PANORAMA_PERMISSION = 13901;

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
    startBydPanoramaMonitorWithPermission();
    // 设置设备列表适配器、广播接收器
    deviceListAdapter = new DeviceListAdapter(this, mainActivity.devicesGrid);
    AppData.myBroadcastReceiver.setDeviceListAdapter(deviceListAdapter);
    connectHelper = new ConnectHelper(this);
    AppData.myBroadcastReceiver.setConnectHelper(connectHelper);
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
    SettingsPanelHelper.populateSection(this, section, binding.settingContent);
    PublicTools.createDialog(this, true, binding.getRoot()).show();
  }

  // BYD 全景影像类在非比亚迪系统上不存在，先探测再申请权限，避免普通设备调试时反复弹无效权限。
  private void startBydPanoramaMonitorWithPermission() {
    if (!isBydPanoramaApiAvailable()) return;
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || haveBydPanoramaPermissions()) {
      startBydPanoramaMonitorSafely();
      return;
    }
    requestPermissions(BYD_PANORAMA_PERMISSIONS, REQUEST_BYD_PANORAMA_PERMISSION);
  }

  private boolean isBydPanoramaApiAvailable() {
    try {
      Class.forName(BYD_PANORAMA_CLASS);
      return true;
    } catch (Throwable ignored) {
      return false;
    }
  }

  private boolean haveBydPanoramaPermissions() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
    for (String permission : BYD_PANORAMA_PERMISSIONS) {
      if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) return false;
    }
    return true;
  }

  private void startBydPanoramaMonitorSafely() {
    try {
      BydPanoramaMonitor.start(this);
    } catch (Throwable throwable) {
      Log.w(TAG, "BYD panorama monitor start failed", throwable);
    }
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    if (requestCode != REQUEST_BYD_PANORAMA_PERMISSION) return;
    if (haveBydPanoramaPermissions()) startBydPanoramaMonitorSafely();
    else Log.w(TAG, "BYD panorama permission denied");
  }
}
