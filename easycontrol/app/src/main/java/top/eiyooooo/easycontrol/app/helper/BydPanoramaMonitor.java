package top.eiyooooo.easycontrol.app.helper;

import android.content.Context;
import android.util.Log;

import java.util.ArrayList;

import android.hardware.bydauto.panorama.AbsBYDAutoPanoramaListener;
import android.hardware.bydauto.panorama.BYDAutoPanoramaDevice;
import top.eiyooooo.easycontrol.app.client.Client;
import top.eiyooooo.easycontrol.app.entity.AppData;

/**
 * 监听比亚迪倒车影像/360 全景状态，并在车辆影像占屏时临时隐藏投屏。
 */
public class BydPanoramaMonitor {
  private static final String TAG = "BydPanoramaMonitor";
  private static BYDAutoPanoramaDevice panoramaDevice;
  private static AbsBYDAutoPanoramaListener panoramaListener;
  private static boolean started = false;
  private static volatile boolean vehicleViewActive = false;

  public static synchronized void start(Context context) {
    if (started) return;
    try {
      panoramaDevice = BYDAutoPanoramaDevice.getInstance(context);
      panoramaListener = new AbsBYDAutoPanoramaListener() {
        @Override
        public void onPanoWorkStateChanged(int state) {
          refreshVehicleViewState("work=" + state);
        }

        @Override
        public void onPanOutputStateChanged(int state) {
          refreshVehicleViewState("output=" + state);
        }

        @Override
        public void onDisplayModeChanged(int mode) {
          refreshVehicleViewState("displayMode=" + mode);
        }
      };
      panoramaDevice.registerListener(panoramaListener);
      started = true;
      refreshVehicleViewState("init");
      Log.i(TAG, "BYD panorama monitor started");
    } catch (Throwable throwable) {
      Log.w(TAG, "BYD panorama API unavailable", throwable);
      stop();
    }
  }

  public static synchronized void stop() {
    if (panoramaDevice != null && panoramaListener != null) {
      try {
        panoramaDevice.unregisterListener(panoramaListener);
      } catch (Throwable ignored) {
      }
    }
    panoramaListener = null;
    panoramaDevice = null;
    started = false;
    vehicleViewActive = false;
  }

  public static boolean isVehicleViewActive() {
    return vehicleViewActive;
  }

  private static void refreshVehicleViewState(String reason) {
    int workState = safeGetPanoWorkState();
    int outputState = safeGetPanoOutputState();
    int displayMode = safeGetDisplayMode();
    boolean active = isVehicleViewActive(workState, outputState, displayMode);
    Log.i(TAG, "state changed by " + reason + ", active=" + active + ", work=" + workState + ", output=" + outputState + ", displayMode=" + displayMode);
    setVehicleViewActive(active);
  }

  private static int safeGetPanoWorkState() {
    try {
      return panoramaDevice == null ? BYDAutoPanoramaDevice.PANORAMA_WORK_OFF : panoramaDevice.getPanoWorkState();
    } catch (Throwable throwable) {
      return BYDAutoPanoramaDevice.PANORAMA_WORK_OFF;
    }
  }

  private static int safeGetPanoOutputState() {
    try {
      return panoramaDevice == null ? BYDAutoPanoramaDevice.PANORAMA_OUTPUT_OFF : panoramaDevice.getPanoOutputState();
    } catch (Throwable throwable) {
      return BYDAutoPanoramaDevice.PANORAMA_OUTPUT_OFF;
    }
  }

  private static int safeGetDisplayMode() {
    try {
      return panoramaDevice == null ? BYDAutoPanoramaDevice.DISPLAY_MODE_WIDGET : panoramaDevice.getDisplayMode();
    } catch (Throwable throwable) {
      return BYDAutoPanoramaDevice.DISPLAY_MODE_WIDGET;
    }
  }

  private static boolean isVehicleViewActive(int workState, int outputState, int displayMode) {
    if (workState == BYDAutoPanoramaDevice.PANORAMA_WORK_ON) return true;
    if (displayMode == BYDAutoPanoramaDevice.DISPLAY_MODE_REVERSE || displayMode == BYDAutoPanoramaDevice.DISPLAY_MODE_RF_REVERSE) return true;
    if (outputState != BYDAutoPanoramaDevice.PANORAMA_OUTPUT_OFF && outputState != BYDAutoPanoramaDevice.PANORAMA_OUTPUT_INVALID) return true;
    return displayMode == BYDAutoPanoramaDevice.DISPLAY_MODE_PANORAMA && workState != BYDAutoPanoramaDevice.PANORAMA_WORK_OFF;
  }

  private static synchronized void setVehicleViewActive(boolean active) {
    if (vehicleViewActive == active) return;
    vehicleViewActive = active;
    AppData.uiHandler.post(() -> {
      ArrayList<Client> clients = new ArrayList<>(Client.allClient);
      for (Client client : clients) {
        if (client == null || client.clientView == null) continue;
        if (active) client.clientView.hideForVehicleView();
        else client.clientView.restoreAfterVehicleView();
      }
    });
  }
}
