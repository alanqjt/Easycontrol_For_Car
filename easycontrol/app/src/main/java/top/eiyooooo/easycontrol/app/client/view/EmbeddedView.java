package top.eiyooooo.easycontrol.app.client.view;

import android.text.InputType;
import android.util.Log;
import android.util.Pair;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import top.eiyooooo.easycontrol.app.MainActivity;
import top.eiyooooo.easycontrol.app.R;
import top.eiyooooo.easycontrol.app.databinding.ViewEmbeddedProjectionBinding;
import top.eiyooooo.easycontrol.app.entity.AppData;

/**
 * 把远端画面直接挂到主界面，不创建系统悬浮窗。
 */
public final class EmbeddedView {
  private static final String TAG = "EasycontrolEmbedded";

  private final ClientView clientView;
  private final ViewEmbeddedProjectionBinding binding;
  private boolean ready;
  private boolean toolbarVisible = false;
  private int lastHostWidth;
  private int lastHostHeight;
  private int lastHostMode = Integer.MIN_VALUE;

  public EmbeddedView(ClientView clientView) {
    this.clientView = clientView;
    binding = ViewEmbeddedProjectionBinding.inflate(LayoutInflater.from(AppData.main));
    setButtonListeners();
    setKeyListener();
    setNavBarVisible(AppData.setting.getDefaultShowNavBar());
    binding.textureViewLayout.addOnLayoutChangeListener((view, left, top, right, bottom,
                                                          oldLeft, oldTop, oldRight, oldBottom) -> {
      if (right - left == oldRight - oldLeft && bottom - top == oldBottom - oldTop) return;
      updateMaxSize();
    });
  }

  public void showLoading() {
    ready = false;
    binding.loadingGroup.setVisibility(View.VISIBLE);
    binding.buttonToolbarToggle.setVisibility(View.GONE);
    binding.toolbarContainer.setVisibility(View.GONE);
    binding.navBar.setVisibility(View.GONE);
    MainActivity.attachEmbeddedProjection(binding.getRoot(), clientView.device.embeddedSlot);
    // 连接线程创建虚拟屏前先记录真实宿主尺寸，避免分屏启动时误用整窗回退值。
    binding.getRoot().post(this::updateMaxSize);
    binding.getRoot().postDelayed(this::updateMaxSize, 250);
    Log.i(TAG, "embedded loading attached uuid=" + clientView.device.uuid
            + ", slot=" + clientView.device.embeddedSlot);
  }

  public void show() {
    ready = true;
    MainActivity.attachEmbeddedProjection(binding.getRoot(), clientView.device.embeddedSlot);
    binding.loadingGroup.setVisibility(View.GONE);
    binding.buttonToolbarToggle.setVisibility(View.VISIBLE);
    updateToolbarVisibility();
    setNavBarVisible(AppData.setting.getDefaultShowNavBar());
    attachTextureView();
    binding.editText.requestFocus();
    binding.getRoot().post(this::updateMaxSize);
    Log.i(TAG, "embedded projection shown uuid=" + clientView.device.uuid
            + ", mode=" + clientView.mode
            + ", slot=" + clientView.device.embeddedSlot);
  }

  public void restore(boolean streamReady) {
    if (streamReady) show();
    else showLoading();
  }

  public void hide() {
    ready = false;
    try {
      ViewParentTools.removeFromParent(clientView.textureView);
      MainActivity.detachEmbeddedProjection(binding.getRoot());
      clientView.updateDevice();
    } catch (Exception e) {
      Log.w(TAG, "embedded projection detach failed", e);
    }
  }

  public void changeMode(int mode) {
    binding.buttonSwitch.setVisibility(mode == 0 ? View.VISIBLE : View.INVISIBLE);
    binding.buttonHome.setVisibility(mode == 0 ? View.VISIBLE : View.INVISIBLE);
    binding.buttonTransfer.setImageResource(mode == 0 ? R.drawable.share_out : R.drawable.share_in);
    if (ready) binding.getRoot().post(this::updateMaxSize);
  }

  private void attachTextureView() {
    if (clientView.textureView.getParent() == binding.textureViewLayout) return;
    ViewParentTools.removeFromParent(clientView.textureView);
    FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.CENTER
    );
    binding.textureViewLayout.addView(clientView.textureView, 0, params);
  }

  private void updateMaxSize() {
    int width = binding.textureViewLayout.getMeasuredWidth();
    int height = binding.textureViewLayout.getMeasuredHeight();
    int mode = clientView.mode;
    if (width <= 0 || height <= 0
            || (width == lastHostWidth && height == lastHostHeight && mode == lastHostMode)) return;
    lastHostWidth = width;
    lastHostHeight = height;
    lastHostMode = mode;
    clientView.updateMaxSize(new Pair<>(width, height));
    clientView.notifyEmbeddedHostSizeChanged(width, height);
    Log.i(TAG, "embedded host resized uuid=" + clientView.device.uuid
            + ", width=" + width + ", height=" + height
            + ", mode=" + mode + ", ready=" + ready
            + ", slot=" + clientView.device.embeddedSlot
            + ", scale=fit-center, runtimeDisplayResize=true");
  }

  private void setButtonListeners() {
    binding.buttonLoadingClose.setOnClickListener(v -> clientView.onClose.run());
    binding.buttonToolbarToggle.setOnClickListener(v -> {
      toolbarVisible = !toolbarVisible;
      updateToolbarVisibility();
      Log.i(TAG, "embedded toolbar visible=" + toolbarVisible
              + ", uuid=" + clientView.device.uuid);
    });
    binding.buttonRotate.setOnClickListener(v -> clientView.controlPacket.sendRotateEvent());
    binding.buttonBack.setOnClickListener(v -> clientView.controlPacket.sendKeyEvent(4, 0, -1));
    binding.buttonHome.setOnClickListener(v -> clientView.controlPacket.sendKeyEvent(3, 0, -1));
    binding.buttonSwitch.setOnClickListener(v -> clientView.controlPacket.sendKeyEvent(187, 0, -1));
    binding.buttonNavBar.setOnClickListener(v -> setNavBarVisible(binding.navBar.getVisibility() == View.GONE));
    binding.buttonClose.setOnClickListener(v -> clientView.onClose.run());
    binding.buttonTransfer.setOnClickListener(v -> clientView.changeMode.run(clientView.mode == 0 ? 1 : 0));
    if (!clientView.lightState) binding.buttonLightOff.setImageResource(R.drawable.lightbulb);
    binding.buttonLightOff.setOnClickListener(v -> {
      if (clientView.lightState) {
        clientView.controlPacket.sendLightEvent(Display.STATE_UNKNOWN);
        binding.buttonLightOff.setImageResource(R.drawable.lightbulb);
      } else {
        clientView.controlPacket.sendLightEvent(Display.STATE_ON);
        binding.buttonLightOff.setImageResource(R.drawable.lightbulb_off);
      }
      clientView.lightState = !clientView.lightState;
    });
    binding.buttonPower.setOnClickListener(v -> clientView.controlPacket.sendPowerEvent());
  }

  private void updateToolbarVisibility() {
    binding.toolbarContainer.setVisibility(toolbarVisible ? View.VISIBLE : View.GONE);
    binding.buttonToolbarToggle.setContentDescription(AppData.main.getString(
            toolbarVisible ? R.string.embedded_toolbar_hide : R.string.embedded_toolbar_show));
  }

  private void setNavBarVisible(boolean visible) {
    binding.navBar.setVisibility(ready && visible ? View.VISIBLE : View.GONE);
    binding.buttonNavBar.setImageResource(visible ? R.drawable.not_equal : R.drawable.equals);
  }

  private void setKeyListener() {
    binding.editText.setInputType(InputType.TYPE_NULL);
    binding.editText.setOnKeyListener((view, keyCode, event) -> {
      if (event.getAction() == KeyEvent.ACTION_DOWN
              && keyCode != KeyEvent.KEYCODE_VOLUME_UP
              && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
        clientView.controlPacket.sendKeyEvent(event.getKeyCode(), event.getMetaState(), 0);
        return true;
      }
      return false;
    });
  }

  private static final class ViewParentTools {
    private ViewParentTools() {
    }

    static void removeFromParent(View view) {
      if (view.getParent() instanceof ViewGroup) {
        ((ViewGroup) view.getParent()).removeView(view);
      }
    }
  }
}
