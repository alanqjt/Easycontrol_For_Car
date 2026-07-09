package top.eiyooooo.easycontrol.app.helper;

import android.content.Context;
import android.content.Intent;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Toast;

import top.eiyooooo.easycontrol.app.AdbKeyActivity;
import top.eiyooooo.easycontrol.app.BuildConfig;
import top.eiyooooo.easycontrol.app.IpActivity;
import top.eiyooooo.easycontrol.app.LogActivity;
import top.eiyooooo.easycontrol.app.MonitorActivity;
import top.eiyooooo.easycontrol.app.R;
import top.eiyooooo.easycontrol.app.StartDeviceActivity;
import top.eiyooooo.easycontrol.app.entity.AppData;

public class SettingsPanelHelper {
  public static final int SECTION_DEFAULT = 0;
  public static final int SECTION_DISPLAY = 1;
  public static final int SECTION_OTHER = 2;
  public static final int SECTION_ABOUT = 3;

  public static String getSectionTitle(Context context, int section) {
    switch (section) {
      case SECTION_DEFAULT:
        return context.getString(R.string.set_default);
      case SECTION_DISPLAY:
        return context.getString(R.string.set_display);
      case SECTION_OTHER:
        return context.getString(R.string.set_other);
      case SECTION_ABOUT:
        return context.getString(R.string.set_about);
      default:
        return context.getString(R.string.set_title);
    }
  }

  public static String getSectionDetail(Context context, int section) {
    switch (section) {
      case SECTION_DEFAULT:
        return context.getString(R.string.main_setting_default_detail);
      case SECTION_DISPLAY:
        return context.getString(R.string.main_setting_display_detail);
      case SECTION_OTHER:
        return context.getString(R.string.main_setting_other_detail);
      case SECTION_ABOUT:
        return context.getString(R.string.main_setting_about_detail);
      default:
        return context.getString(R.string.set_title_detail);
    }
  }

  public static int getSectionIcon(int section) {
    switch (section) {
      case SECTION_DEFAULT:
        return R.drawable.ic_tune;
      case SECTION_DISPLAY:
        return R.drawable.ic_display;
      case SECTION_OTHER:
        return R.drawable.ic_more_settings;
      case SECTION_ABOUT:
        return R.drawable.ic_info;
      default:
        return R.drawable.ic_tune;
    }
  }

  public static int getSectionDialogWidth(int section) {
    if (section == SECTION_ABOUT) return 700;
    return 820;
  }

  public static void populateAll(Context context, ViewGroup setDefault, ViewGroup setDisplay, ViewGroup setOther, ViewGroup setAbout) {
    populateSection(context, SECTION_DEFAULT, setDefault);
    populateSection(context, SECTION_DISPLAY, setDisplay);
    populateSection(context, SECTION_OTHER, setOther);
    populateSection(context, SECTION_ABOUT, setAbout);
  }

  public static void populateSection(Context context, int section, ViewGroup target) {
    target.removeAllViews();
    switch (section) {
      case SECTION_DEFAULT:
        populateDefault(context, target);
        break;
      case SECTION_DISPLAY:
        populateDisplay(context, target);
        break;
      case SECTION_OTHER:
        populateOther(context, target);
        break;
      case SECTION_ABOUT:
        populateAbout(context, target);
        break;
      default:
        break;
    }
  }

  private static void populateDefault(Context context, ViewGroup target) {
    PublicTools.createDeviceOptionSet(context, target, null);
  }

  private static void populateDisplay(Context context, ViewGroup target) {
    LinearLayout wakeGroup = PublicTools.createSettingGroup(context, context.getString(R.string.setting_group_wake_backlight));
    LinearLayout windowGroup = PublicTools.createSettingGroup(context, context.getString(R.string.setting_group_window));

    PublicTools.addSettingCard(wakeGroup, PublicTools.createSwitchCard(context, context.getString(R.string.set_wake_up_screen_on_connect), context.getString(R.string.set_wake_up_screen_on_connect_detail), AppData.setting.getTurnOnScreenIfStart(),
            isChecked -> {
              if (!isChecked) {
                AppData.setting.setTurnOffScreenIfStart(false);
                replaceDisplayLightOffCard(context, wakeGroup);
              }
              AppData.setting.setTurnOnScreenIfStart(isChecked);
            }).getRoot());

    PublicTools.addSettingCard(wakeGroup, createLightOffCard(context, wakeGroup));

    PublicTools.addSettingCard(wakeGroup, PublicTools.createSwitchCard(context, context.getString(R.string.set_lock_screen_on_close), context.getString(R.string.set_lock_screen_on_close_detail), AppData.setting.getTurnOffScreenIfStop(),
            isChecked -> {
              if (isChecked) {
                AppData.setting.setTurnOnScreenIfStop(false);
                replaceDisplayLightOnCard(context, wakeGroup);
              }
              AppData.setting.setTurnOffScreenIfStop(isChecked);
            }).getRoot());

    PublicTools.addSettingCard(wakeGroup, createLightOnCard(context));
    PublicTools.addSettingCard(wakeGroup, PublicTools.createSwitchCard(context, context.getString(R.string.set_display_keep_screen_awake), context.getString(R.string.set_display_keep_screen_awake_detail), AppData.setting.getKeepAwake(), isChecked -> AppData.setting.setKeepAwake(isChecked)).getRoot());

    PublicTools.addSettingCard(windowGroup, PublicTools.createSwitchCard(context, context.getString(R.string.set_display_auto_back_on_start_default), context.getString(R.string.set_display_auto_back_on_start_default_detail), AppData.setting.getAutoBackOnStartDefault(), isChecked -> AppData.setting.setAutoBackOnStartDefault(isChecked)).getRoot());
    PublicTools.addSettingCard(windowGroup, PublicTools.createSwitchCard(context, context.getString(R.string.set_display_default_mini_on_outside), context.getString(R.string.set_display_default_mini_on_outside_detail), AppData.setting.getDefaultMiniOnOutside(), isChecked -> AppData.setting.setDefaultMiniOnOutside(isChecked)).getRoot());
    PublicTools.addSettingCard(windowGroup, PublicTools.createSwitchCard(context, context.getString(R.string.set_display_mini_recover_on_timeout), context.getString(R.string.set_display_mini_recover_on_timeout_detail), AppData.setting.getMiniRecoverOnTimeout(), isChecked -> AppData.setting.setMiniRecoverOnTimeout(isChecked)).getRoot());
    PublicTools.addSettingCard(windowGroup, PublicTools.createSwitchCard(context, context.getString(R.string.set_display_full_to_mini_on_exit), context.getString(R.string.set_display_full_to_mini_on_exit_detail), AppData.setting.getFullToMiniOnExit(), isChecked -> AppData.setting.setFullToMiniOnExit(isChecked)).getRoot());
    PublicTools.addSettingCard(windowGroup, PublicTools.createSwitchCard(context, context.getString(R.string.set_display_full_fill), context.getString(R.string.set_display_full_fill_detail), AppData.setting.getFillFull(), isChecked -> AppData.setting.setFillFull(isChecked)).getRoot());
    PublicTools.addSettingCard(windowGroup, PublicTools.createSwitchCard(context, context.getString(R.string.set_display_default_show_nav_bar), context.getString(R.string.set_display_default_show_nav_bar_detail), AppData.setting.getDefaultShowNavBar(), isChecked -> AppData.setting.setDefaultShowNavBar(isChecked)).getRoot());

    target.addView(wakeGroup);
    target.addView(windowGroup);
  }

  private static android.view.View createLightOffCard(Context context, ViewGroup target) {
    return PublicTools.createSwitchCardEx(context, context.getString(R.string.set_light_off_on_connect), context.getString(R.string.set_light_off_on_connect_detail), AppData.setting.getTurnOffScreenIfStart(),
            (buttonView, isChecked) -> {
              if (!AppData.setting.getTurnOnScreenIfStart()) {
                buttonView.setChecked(false);
                Toast.makeText(context, context.getString(R.string.set_light_off_on_connect_error), Toast.LENGTH_SHORT).show();
              } else AppData.setting.setTurnOffScreenIfStart(isChecked);
            }).getRoot();
  }

  private static android.view.View createLightOnCard(Context context) {
    return PublicTools.createSwitchCardEx(context, context.getString(R.string.set_light_on_on_close), context.getString(R.string.set_light_on_on_close_detail), AppData.setting.getTurnOnScreenIfStop(),
            (buttonView, isChecked) -> {
              if (AppData.setting.getTurnOffScreenIfStop()) {
                buttonView.setChecked(false);
                Toast.makeText(context, context.getString(R.string.set_light_on_on_close_error), Toast.LENGTH_SHORT).show();
              } else AppData.setting.setTurnOnScreenIfStop(isChecked);
            }).getRoot();
  }

  private static void replaceDisplayLightOffCard(Context context, ViewGroup target) {
    replaceSettingCardAt(target, 2, createLightOffCard(context, target));
  }

  private static void replaceDisplayLightOnCard(Context context, ViewGroup target) {
    replaceSettingCardAt(target, 4, createLightOnCard(context));
  }

  private static void replaceSettingCardAt(ViewGroup target, int index, android.view.View card) {
    if (target.getChildCount() > index) {
      target.removeViewAt(index);
      LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT,
              ViewGroup.LayoutParams.WRAP_CONTENT
      );
      params.setMargins(0, 0, 0, PublicTools.dp2px(2f));
      target.addView(card, index, params);
    }
  }

  private static void populateOther(Context context, ViewGroup target) {
    ArrayAdapter<String> audioChannelAdapter = new ArrayAdapter<>(context, R.layout.item_spinner_item, new String[]{"0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16", "17", "18", "19", "20"});
    ArrayAdapter<String> reconnectTimeAdapter = new ArrayAdapter<>(context, R.layout.item_spinner_item, new String[]{context.getString(R.string.set_no_auto_countdown), "3", "5", "10"});

    LinearLayout connectionGroup = PublicTools.createSettingGroup(context, context.getString(R.string.setting_group_connection_usb));
    LinearLayout transferGroup = PublicTools.createSettingGroup(context, context.getString(R.string.setting_group_fullscreen_transfer));
    LinearLayout automationGroup = PublicTools.createSettingGroup(context, context.getString(R.string.setting_group_auto_task));
    LinearLayout toolsGroup = PublicTools.createSettingGroup(context, context.getString(R.string.setting_group_tools_security));

    PublicTools.addSettingCard(connectionGroup, PublicTools.createSpinnerCard(context, context.getString(R.string.set_audio_channel), context.getString(R.string.set_audio_channel_detail), String.valueOf(AppData.setting.getAudioChannel()), audioChannelAdapter, str -> AppData.setting.setAudioChannel(Integer.parseInt(str))).getRoot());
    PublicTools.addSettingCard(connectionGroup, PublicTools.createSwitchCard(context, context.getString(R.string.set_enable_usb), context.getString(R.string.set_enable_usb_detail), AppData.setting.getEnableUSB(), isChecked -> AppData.setting.setEnableUSB(isChecked)).getRoot());
    PublicTools.addSettingCard(transferGroup, PublicTools.createSwitchCard(context, context.getString(R.string.set_set_full_screen), context.getString(R.string.set_set_full_screen_detail), AppData.setting.getSetFullScreen(), isChecked -> AppData.setting.setSetFullScreen(isChecked)).getRoot());
    PublicTools.addSettingCard(transferGroup, PublicTools.createSwitchCard(context, context.getString(R.string.set_always_full_mode), context.getString(R.string.set_always_full_mode_detail), AppData.setting.getAlwaysFullMode(), isChecked -> AppData.setting.setAlwaysFullMode(isChecked)).getRoot());
    PublicTools.addSettingCard(transferGroup, PublicTools.createSwitchCard(context, context.getString(R.string.set_mirror_mode), context.getString(R.string.set_mirror_mode_detail), AppData.setting.getNewMirrorMode(), isChecked -> AppData.setting.setNewMirrorMode(isChecked)).getRoot());
    PublicTools.addSettingCard(transferGroup, PublicTools.createSwitchCard(context, context.getString(R.string.set_force_desktop_mode), context.getString(R.string.set_force_desktop_mode_detail), AppData.setting.getForceDesktopMode(), isChecked -> AppData.setting.setForceDesktopMode(isChecked)).getRoot());
    PublicTools.addSettingCard(transferGroup, PublicTools.createSwitchCard(context, context.getString(R.string.set_try_start_default_in_app_transfer), context.getString(R.string.set_try_start_default_in_app_transfer_detail), AppData.setting.getTryStartDefaultInAppTransfer(), isChecked -> AppData.setting.setTryStartDefaultInAppTransfer(isChecked)).getRoot());
    PublicTools.addSettingCard(automationGroup, PublicTools.createSwitchCard(context, context.getString(R.string.set_reconnect), context.getString(R.string.set_reconnect_detail), AppData.setting.getShowReconnect(), isChecked -> AppData.setting.setShowReconnect(isChecked)).getRoot());
    PublicTools.addSettingCard(automationGroup, PublicTools.createSwitchCard(context, context.getString(R.string.set_connect_usb), context.getString(R.string.set_connect_usb_detail), AppData.setting.getShowConnectUSB(), isChecked -> AppData.setting.setShowConnectUSB(isChecked)).getRoot());
    PublicTools.addSettingCard(automationGroup, PublicTools.createSpinnerCard(context, context.getString(R.string.set_auto_countdown), context.getString(R.string.set_auto_countdown_detail), AppData.setting.getCountdownTime(), reconnectTimeAdapter, str -> AppData.setting.setCountdownTime(str)).getRoot());
    PublicTools.addSettingCard(automationGroup, PublicTools.createTextCardDetail(context, context.getString(R.string.set_app_monitor), context.getString(R.string.set_app_monitor_detail), () -> context.startActivity(new Intent(context, MonitorActivity.class))).getRoot());
    PublicTools.addSettingCard(automationGroup, PublicTools.createTextCard(context, context.getString(R.string.set_create_startup_shortcut), () -> ShortcutHelper.addShortcut(AppData.main, StartDeviceActivity.class, context.getString(R.string.tip_default_device), R.drawable.phones, null)).getRoot());
    PublicTools.addSettingCard(toolsGroup, PublicTools.createTextCard(context, context.getString(R.string.set_other_log), () -> context.startActivity(new Intent(context, LogActivity.class))).getRoot());
    PublicTools.addSettingCard(toolsGroup, PublicTools.createTextCard(context, context.getString(R.string.set_about_ip), () -> context.startActivity(new Intent(context, IpActivity.class))).getRoot());
    PublicTools.addSettingCard(toolsGroup, PublicTools.createTextCard(context, context.getString(R.string.set_other_custom_key), () -> context.startActivity(new Intent(context, AdbKeyActivity.class))).getRoot());
    PublicTools.addSettingCard(toolsGroup, PublicTools.createTextCard(context, context.getString(R.string.set_other_clear_key), () -> {
      AppData.reGenerateAdbKeyPair(context);
      Toast.makeText(context, context.getString(R.string.set_other_clear_key_code), Toast.LENGTH_SHORT).show();
    }).getRoot());
    PublicTools.addSettingCard(toolsGroup, PublicTools.createTextCard(context, context.getString(R.string.set_other_locale), () -> {
      changeLocale(context);
      Toast.makeText(context, context.getString(R.string.set_other_locale_code), Toast.LENGTH_SHORT).show();
    }).getRoot());

    target.addView(connectionGroup);
    target.addView(transferGroup);
    target.addView(automationGroup);
    target.addView(toolsGroup);
  }

  private static void changeLocale(Context context) {
    String locale = AppData.setting.getDefaultLocale();
    if (locale.isEmpty()) {
      if (context.getString(R.string.set_about).contains("关于")) AppData.setting.setDefaultLocale("en");
      else AppData.setting.setDefaultLocale("zh");
    } else if (locale.equals("en")) AppData.setting.setDefaultLocale("zh");
    else if (locale.equals("zh")) AppData.setting.setDefaultLocale("en");
  }

  private static void populateAbout(Context context, ViewGroup target) {
    LinearLayout projectGroup = PublicTools.createSettingGroup(context, context.getString(R.string.setting_group_about_project));
    LinearLayout docsGroup = PublicTools.createSettingGroup(context, context.getString(R.string.setting_group_about_docs));
    LinearLayout versionGroup = PublicTools.createSettingGroup(context, context.getString(R.string.setting_group_about_version));

    PublicTools.addSettingCard(projectGroup, PublicTools.createTextCard(context, context.getString(R.string.set_about_website), () -> PublicTools.startUrl(context, "https://github.com/alanqjt/Easycontrol_For_Car")).getRoot());
    PublicTools.addSettingCard(projectGroup, PublicTools.createTextCard(context, context.getString(R.string.car_version_message), () -> PublicTools.startUrl(context, "https://github.com/alanqjt/Easycontrol_For_Car")).getRoot());
    PublicTools.addSettingCard(docsGroup, PublicTools.createTextCard(context, context.getString(R.string.set_about_how_to_use), () -> PublicTools.openWebViewActivity(context, "file:///android_asset/usage.html")).getRoot());
    PublicTools.addSettingCard(docsGroup, PublicTools.createTextCard(context, context.getString(R.string.set_about_privacy), () -> PublicTools.openWebViewActivity(context, "file:///android_asset/privacy.html")).getRoot());
    PublicTools.addSettingCard(docsGroup, PublicTools.createTextCard(context, context.getString(R.string.set_license), () -> PublicTools.openWebViewActivity(context, "file:///android_asset/license.html")).getRoot());
    PublicTools.addSettingCard(versionGroup, PublicTools.createTextCard(context, context.getString(R.string.set_about_version) + BuildConfig.VERSION_NAME, () -> PublicTools.startUrl(context, "https://github.com/alanqjt/Easycontrol_For_Car/releases")).getRoot());

    target.addView(projectGroup);
    target.addView(docsGroup);
    target.addView(versionGroup);
  }
}
