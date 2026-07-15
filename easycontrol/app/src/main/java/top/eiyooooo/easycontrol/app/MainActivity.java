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
import android.util.Log;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Toast;

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
  private static final String EMBEDDED_LOG_TAG = "EasycontrolEmbedded";
  public static final String EXTRA_EMBEDDED_START_UUID = "embeddedStartUuid";
  public static final String EXTRA_EMBEDDED_START_MODE = "embeddedStartMode";
  public static final String EXTRA_EMBEDDED_START_DEFAULT = "embeddedStartDefault";
  @SuppressLint("StaticFieldLeak")
  private static MainActivity activeInstance;

  // 设备列表
  private DeviceListAdapter deviceListAdapter;
  private ConnectHelper connectHelper;

  // 创建界面
  private ActivityMainBinding mainActivity;
  private boolean appStarted;
  private Boolean compactHeader;
  private View embeddedProjectionRoot;
  private boolean syncingEmbeddedProjectionModeSwitch;
  private int rootPaddingStart;
  private int rootPaddingTop;
  private int rootPaddingEnd;
  private int rootPaddingBottom;
  private int embeddedHostPaddingLeft;
  private int embeddedHostPaddingTop;
  private int embeddedHostPaddingRight;
  private int embeddedHostPaddingBottom;

  @SuppressLint("SourceLockedOrientationActivity")
  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    AppData.init(this);
    PublicTools.setStatusAndNavBar(this);
    PublicTools.setLocale(this);
    mainActivity = ActivityMainBinding.inflate(this.getLayoutInflater());
    setContentView(mainActivity.getRoot());
    rememberMainLayoutPadding();
    activeInstance = this;
    mainActivity.getRoot().post(this::updateResponsiveLayout);
    if (!AppData.setting.getPrivacyPolicyAccepted()) showPrivacyPolicyDialog();
    else continueAfterPrivacyAccepted();
  }

  private void continueAfterPrivacyAccepted() {
    if (AppData.setting.getEmbeddedProjectionMode()
            || AppData.setting.getAlwaysFullMode()
            || haveOverlayPermission()) startApp();
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
    if (appStarted) {
      syncEmbeddedProjectionModeSwitch();
      restoreEmbeddedProjection();
      handleEmbeddedStartRequest();
      return;
    }
    appStarted = true;
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
    restoreEmbeddedProjection();
    handleEmbeddedStartRequest();
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    AppData.init(this);
    setIntent(intent);
    if (appStarted) handleEmbeddedStartRequest();
  }

  @Override
  protected void onDestroy() {
    if (connectHelper != null) AppData.uiHandler.removeCallbacks(connectHelper.showStartDefaultUSB);
    AppData.myBroadcastReceiver.setDeviceListAdapter(null);
    AppData.myBroadcastReceiver.setConnectHelper(null);
    ConnectHelper.status = false;
    detachEmbeddedProjectionInternal(embeddedProjectionRoot);
    if (activeInstance == this) activeInstance = null;
    if (!isChangingConfigurations()) Client.releaseEmbeddedSessions();
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
    AppData.init(this);
    if (!AppData.setting.getPrivacyPolicyAccepted()) {
      super.onResume();
      return;
    }
    syncEmbeddedProjectionModeSwitch();
    ConnectHelper.status = true;
    if (!AppData.setting.getEmbeddedProjectionMode()
            && !AppData.setting.getAlwaysFullMode()
            && !haveOverlayPermission()) createAlert();
    else {
      restoreEmbeddedProjection();
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
    if (mainActivity != null) mainActivity.getRoot().post(this::updateResponsiveLayout);
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
    requestPermissionView.buttonAlwaysFullMode.setOnClickListener(v -> {
      AppData.setting.setEmbeddedProjectionMode(false);
      AppData.setting.setAlwaysFullMode(true);
      syncEmbeddedProjectionModeSwitch();
    });
    requestPermissionView.buttonEmbeddedMode.setOnClickListener(v -> {
      AppData.setting.setAlwaysFullMode(false);
      AppData.setting.setEmbeddedProjectionMode(true);
      syncEmbeddedProjectionModeSwitch();
    });
    Dialog dialog = PublicTools.createDialog(this, false, requestPermissionView.getRoot());
    dialog.setOnCancelListener(dialog1 -> {
      if (!AppData.setting.getEmbeddedProjectionMode()
              && !AppData.setting.getAlwaysFullMode()
              && !haveOverlayPermission()) dialog.show();
    });
    dialog.show();
    checkPermissionDelay(dialog);
  }

  // 定时检查
  private void checkPermissionDelay(Dialog dialog) {
    // 因为某些设备可能会无法进入设置或其他问题，导致不会有返回结果，为了减少不确定性，使用定时检测的方法
    AppData.uiHandler.postDelayed(() -> {
      if (AppData.setting.getEmbeddedProjectionMode()
              || AppData.setting.getAlwaysFullMode()
              || haveOverlayPermission()) {
        dialog.cancel();
        startApp();
      } else checkPermissionDelay(dialog);
    }, 1000);
  }

  // 设置按钮监听
  private void setButtonListener() {
    syncEmbeddedProjectionModeSwitch();
    mainActivity.buttonEmbeddedProjectionMode.setOnClickListener(v -> toggleEmbeddedProjectionMode());
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
    mainActivity.buttonCloseAllProjections.setOnClickListener(v -> closeAllProjections());
  }

  private void closeAllProjections() {
    if (!Client.hasActiveClients()) {
      Toast.makeText(this, R.string.main_no_active_projection, Toast.LENGTH_SHORT).show();
      return;
    }

    mainActivity.buttonCloseAllProjections.setEnabled(false);
    new Thread(() -> {
      int releasedCount = Client.releaseAll();
      AppData.uiHandler.post(() -> {
        if (isFinishing() || isDestroyed()) return;
        mainActivity.buttonCloseAllProjections.setEnabled(true);
        int message = releasedCount == 0
                ? R.string.main_no_active_projection
                : R.string.main_close_all_complete;
        Toast.makeText(this,
                releasedCount == 0 ? getString(message) : getString(message, releasedCount),
                Toast.LENGTH_SHORT).show();
      });
    }, "easycontrol_release_all").start();
  }

  private void toggleEmbeddedProjectionMode() {
    if (syncingEmbeddedProjectionModeSwitch) return;
    if (Client.hasActiveClients()) {
      syncEmbeddedProjectionModeSwitch();
      Toast.makeText(this, R.string.error_projection_mode_change_active, Toast.LENGTH_SHORT).show();
      return;
    }

    boolean enabled = !AppData.setting.getEmbeddedProjectionMode();
    AppData.setting.setEmbeddedProjectionMode(enabled);
    if (enabled) AppData.setting.setAlwaysFullMode(false);
    syncEmbeddedProjectionModeSwitch();

    if (!enabled && !AppData.setting.getAlwaysFullMode() && !haveOverlayPermission()) {
      createAlert();
    }
  }

  private void syncEmbeddedProjectionModeSwitch() {
    if (mainActivity == null) return;
    syncingEmbeddedProjectionModeSwitch = true;
    mainActivity.switchEmbeddedProjectionMode.setChecked(AppData.setting.getEmbeddedProjectionMode());
    syncingEmbeddedProjectionModeSwitch = false;
  }

  private void showSettingSection(int section) {
    DialogSettingSectionBinding binding = DialogSettingSectionBinding.inflate(LayoutInflater.from(this));
    binding.settingTitle.setText(SettingsPanelHelper.getSectionTitle(this, section));
    binding.settingDetail.setText(SettingsPanelHelper.getSectionDetail(this, section));
    binding.settingIcon.setImageResource(SettingsPanelHelper.getSectionIcon(section));
    SettingsPanelHelper.populateSection(this, section, binding.settingContent);
    Dialog dialog = PublicTools.createDialog(this, true, binding.getRoot(), SettingsPanelHelper.getSectionDialogWidth(section));
    binding.settingClose.setOnClickListener(v -> dialog.cancel());
    dialog.setOnDismissListener(v -> syncEmbeddedProjectionModeSwitch());
    dialog.show();
  }

  public static boolean isEmbeddedProjectionHostReady() {
    MainActivity activity = activeInstance;
    return activity != null
            && activity.appStarted
            && activity.mainActivity != null
            && !activity.isFinishing()
            && !activity.isDestroyed();
  }

  public static Pair<Integer, Integer> getEmbeddedProjectionTargetSize() {
    MainActivity activity = activeInstance;
    if (!isEmbeddedProjectionHostReady()) return null;
    int width = activity.mainActivity.getRoot().getWidth();
    int height = activity.mainActivity.getRoot().getHeight();
    if (AppData.setting.getDefaultShowNavBar()) {
      height -= PublicTools.dp2px(42f);
    }
    if (width <= 0 || height <= 0) return null;
    return new Pair<>(width, height);
  }

  public static int getEmbeddedProjectionTargetDensityDpi() {
    MainActivity activity = activeInstance;
    if (!isEmbeddedProjectionHostReady()) return 0;
    return activity.getResources().getDisplayMetrics().densityDpi;
  }

  public static boolean attachEmbeddedProjection(View root) {
    MainActivity activity = activeInstance;
    if (!isEmbeddedProjectionHostReady() || root == null) {
      Log.w(EMBEDDED_LOG_TAG, "embedded host attach skipped: host unavailable");
      return false;
    }
    activity.runOnUiThread(() -> activity.attachEmbeddedProjectionInternal(root));
    return true;
  }

  public static void detachEmbeddedProjection(View root) {
    MainActivity activity = activeInstance;
    if (activity == null || root == null) return;
    activity.runOnUiThread(() -> activity.detachEmbeddedProjectionInternal(root));
  }

  private void attachEmbeddedProjectionInternal(View root) {
    if (mainActivity == null || isFinishing()) return;
    if (embeddedProjectionRoot == root && root.getParent() == mainActivity.embeddedProjectionHost) {
      setEmbeddedProjectionLayout(true);
      mainActivity.devicesList.setVisibility(View.GONE);
      mainActivity.embeddedProjectionHost.setVisibility(View.VISIBLE);
      return;
    }
    if (root.getParent() instanceof ViewGroup) {
      ((ViewGroup) root.getParent()).removeView(root);
    }
    if (embeddedProjectionRoot != null && embeddedProjectionRoot != root
            && embeddedProjectionRoot.getParent() instanceof ViewGroup) {
      ((ViewGroup) embeddedProjectionRoot.getParent()).removeView(embeddedProjectionRoot);
    }
    mainActivity.embeddedProjectionHost.removeAllViews();
    mainActivity.embeddedProjectionHost.addView(root, new android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
    ));
    embeddedProjectionRoot = root;
    setEmbeddedProjectionLayout(true);
    mainActivity.devicesList.setVisibility(View.GONE);
    mainActivity.embeddedProjectionHost.setVisibility(View.VISIBLE);
    Log.i(EMBEDDED_LOG_TAG, "embedded root attached fullscreen to main activity");
  }

  private void detachEmbeddedProjectionInternal(View root) {
    if (root == null) return;
    if (root.getParent() instanceof ViewGroup) {
      ((ViewGroup) root.getParent()).removeView(root);
    }
    if (embeddedProjectionRoot == root) embeddedProjectionRoot = null;
    if (mainActivity != null && embeddedProjectionRoot == null) {
      mainActivity.embeddedProjectionHost.removeAllViews();
      mainActivity.embeddedProjectionHost.setVisibility(View.GONE);
      mainActivity.devicesList.setVisibility(View.VISIBLE);
      setEmbeddedProjectionLayout(false);
    }
    Log.i(EMBEDDED_LOG_TAG, "embedded root detached from main activity");
  }

  private void rememberMainLayoutPadding() {
    rootPaddingStart = mainActivity.getRoot().getPaddingStart();
    rootPaddingTop = mainActivity.getRoot().getPaddingTop();
    rootPaddingEnd = mainActivity.getRoot().getPaddingEnd();
    rootPaddingBottom = mainActivity.getRoot().getPaddingBottom();
    embeddedHostPaddingLeft = mainActivity.embeddedProjectionHost.getPaddingLeft();
    embeddedHostPaddingTop = mainActivity.embeddedProjectionHost.getPaddingTop();
    embeddedHostPaddingRight = mainActivity.embeddedProjectionHost.getPaddingRight();
    embeddedHostPaddingBottom = mainActivity.embeddedProjectionHost.getPaddingBottom();
  }

  private void setEmbeddedProjectionLayout(boolean projectionVisible) {
    if (mainActivity == null) return;
    mainActivity.mainHeader.setVisibility(projectionVisible ? View.GONE : View.VISIBLE);
    if (projectionVisible) {
      mainActivity.getRoot().setPaddingRelative(0, 0, 0, 0);
      mainActivity.embeddedProjectionHost.setPadding(0, 0, 0, 0);
    } else {
      mainActivity.getRoot().setPaddingRelative(
              rootPaddingStart, rootPaddingTop, rootPaddingEnd, rootPaddingBottom);
      mainActivity.embeddedProjectionHost.setPadding(
              embeddedHostPaddingLeft,
              embeddedHostPaddingTop,
              embeddedHostPaddingRight,
              embeddedHostPaddingBottom);
    }
  }

  private void restoreEmbeddedProjection() {
    if (!appStarted) return;
    for (Client client : Client.allClient) {
      if (!client.isClosed() && client.clientView.isEmbeddedMode()) {
        client.clientView.restoreEmbedded(client.isStarted());
        return;
      }
    }
  }

  private void handleEmbeddedStartRequest() {
    if (!appStarted) return;
    Intent intent = getIntent();
    if (intent == null) return;
    boolean startDefault = intent.getBooleanExtra(EXTRA_EMBEDDED_START_DEFAULT, false);
    String uuid = intent.getStringExtra(EXTRA_EMBEDDED_START_UUID);
    if (!startDefault && uuid == null) return;
    int mode = intent.getIntExtra(EXTRA_EMBEDDED_START_MODE, 0);
    intent.removeExtra(EXTRA_EMBEDDED_START_DEFAULT);
    intent.removeExtra(EXTRA_EMBEDDED_START_UUID);
    intent.removeExtra(EXTRA_EMBEDDED_START_MODE);
    AppData.uiHandler.post(() -> {
      if (startDefault) DeviceListAdapter.startDefault(mode);
      else DeviceListAdapter.startByUUID(uuid, mode);
    });
  }

  private void updateResponsiveLayout() {
    if (mainActivity == null) return;
    Configuration configuration = getResources().getConfiguration();
    int widthDp = configuration.screenWidthDp;
    int heightDp = configuration.screenHeightDp;
    if (widthDp <= 0) {
      widthDp = (int) (mainActivity.getRoot().getWidth() / getResources().getDisplayMetrics().density);
    }
    if (heightDp <= 0) {
      heightDp = (int) (mainActivity.getRoot().getHeight() / getResources().getDisplayMetrics().density);
    }
    boolean portraitWindow = configuration.orientation == Configuration.ORIENTATION_PORTRAIT
            || (widthDp > 0 && heightDp > 0 && widthDp < heightDp);
    boolean compact = widthDp > 0 && (portraitWindow || widthDp < 1200);
    if (compactHeader != null && compactHeader == compact) return;
    compactHeader = compact;

    mainActivity.mainHeader.setOrientation(compact ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
    LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
            compact ? ViewGroup.LayoutParams.MATCH_PARENT : 0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            compact ? 0 : 1
    );
    mainActivity.mainHeaderTitle.setLayoutParams(titleParams);

    LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(
            compact ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
    );
    actionsParams.topMargin = compact ? PublicTools.dp2px(10f) : 0;
    mainActivity.mainHeaderActions.setLayoutParams(actionsParams);
    Log.i(EMBEDDED_LOG_TAG, "main window layout compact=" + compact
            + ", portraitWindow=" + portraitWindow
            + ", widthDp=" + widthDp
            + ", heightDp=" + heightDp);
  }
}
