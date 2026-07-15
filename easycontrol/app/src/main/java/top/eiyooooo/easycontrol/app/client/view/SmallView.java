package top.eiyooooo.easycontrol.app.client.view;

import android.annotation.SuppressLint;
import android.content.res.Configuration;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Pair;
import android.view.*;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import top.eiyooooo.easycontrol.app.MainActivity;
import top.eiyooooo.easycontrol.app.client.Client;
import top.eiyooooo.easycontrol.app.client.ControlPacket;
import top.eiyooooo.easycontrol.app.entity.AppData;
import top.eiyooooo.easycontrol.app.helper.PublicTools;
import top.eiyooooo.easycontrol.app.R;
import top.eiyooooo.easycontrol.app.databinding.ModuleSmallViewBinding;

public class SmallView extends ViewOutlineProvider {
  private static final String LOG_TAG = "EasycontrolSmallView";
  private final ClientView clientView;
  private static int statusBarHeight = 0;
  private boolean LocalIsPortrait() {
    return AppData.main.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
  }
  private boolean LastLocalIsPortrait;
  private boolean RemoteIsPortrait = true;
  private int InitSize = 0;
  private boolean InitPos = false;
  int longEdge;
  int shortEdge;
  private int availableLeft;
  private int availableTop;
  private int availableWidth;
  private int availableHeight;
  private boolean restrictToMainWindow;

  // 悬浮窗
  private final ModuleSmallViewBinding smallView = ModuleSmallViewBinding.inflate(LayoutInflater.from(AppData.main));
  private final WindowManager.LayoutParams smallViewParams =
    new WindowManager.LayoutParams(
      WindowManager.LayoutParams.WRAP_CONTENT,
      WindowManager.LayoutParams.WRAP_CONTENT,
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
      LayoutParamsFlagFocus,
      PixelFormat.TRANSLUCENT
    );

  private static final int LayoutParamsFlagFocus = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
  private static final int LayoutParamsFlagNoFocus = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;

  public SmallView(ClientView clientView) {
    this.clientView = clientView;
    smallViewParams.gravity = Gravity.START | Gravity.TOP;
    // 设置默认导航栏状态
    setNavBarHide(AppData.setting.getDefaultShowNavBar());
    // 悬浮窗属于系统窗口，多窗口模式下必须使用当前 APP 的实际区域，而不是整块屏幕。
    refreshAvailableWindowBounds();
    // 设置默认大小
    if (clientView.device.small_p_p_width == 0 || clientView.device.small_p_p_height == 0
            || clientView.device.small_p_l_width == 0 || clientView.device.small_p_l_height == 0
            || clientView.device.small_l_p_width == 0 || clientView.device.small_l_p_height == 0
            || clientView.device.small_l_l_width == 0 || clientView.device.small_l_l_height == 0
            || clientView.device.small_free_width == 0 || clientView.device.small_free_height == 0) {
      clientView.device.small_p_p_width = shortEdge * 4 / 5;
      clientView.device.small_p_p_height = longEdge * 4 / 5;
      clientView.device.small_p_l_width = shortEdge * 4 / 5;
      clientView.device.small_p_l_height = longEdge * 4 / 5;
      clientView.device.small_l_p_width = longEdge * 4 / 5;
      clientView.device.small_l_p_height = shortEdge * 4 / 5;
      clientView.device.small_l_l_width = longEdge * 4 / 5;
      clientView.device.small_l_l_height = shortEdge * 4 / 5;
      clientView.device.small_free_width = shortEdge * 2 / 5;
      clientView.device.small_free_height = shortEdge * 3 / 5;
    }
    // 设置监听控制
    setFloatVideoListener();
    setReSizeListener();
    setBarListener();
    // 设置窗口监听
    smallView.textureViewLayout.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
      int contentWidth = right - left;
      int contentHeight = bottom - top;
      if (contentWidth <= 0 || contentHeight <= 0) return;
      InitSize++;
      boolean remoteIsPortrait = contentWidth < contentHeight;
      if (InitSize < 2) {
        RemoteIsPortrait = remoteIsPortrait;
        return;
      }

      boolean localIsPortrait = LocalIsPortrait();
      if (!InitPos || remoteIsPortrait != RemoteIsPortrait || localIsPortrait != LastLocalIsPortrait) {
        refreshAvailableWindowBounds();
        InitPos = true;
        LastLocalIsPortrait = localIsPortrait;
        Pair<Integer, Integer> savedPosition = getSavedPosition(localIsPortrait, remoteIsPortrait);
        Pair<Integer, Integer> savedMaxSize = getSavedMaxSize(localIsPortrait, remoteIsPortrait);
        updateConstrainedMaxSize(savedMaxSize);

        ViewGroup.LayoutParams layoutParams = clientView.textureView.getLayoutParams();
        int windowWidth = Math.max(1, layoutParams.width);
        int windowHeight = Math.max(1, layoutParams.height);
        boolean useDefaultPosition = savedPosition.first == 0 && savedPosition.second == 0;
        if (useDefaultPosition) {
          smallViewParams.x = availableLeft + (availableWidth - windowWidth) / 2;
          smallViewParams.y = availableTop + (availableHeight - windowHeight) / 2;
        } else {
          smallViewParams.x = savedPosition.first;
          smallViewParams.y = savedPosition.second;
        }
        constrainWindowPosition(windowWidth, windowHeight);
        if (useDefaultPosition) saveCurrentPosition(localIsPortrait, remoteIsPortrait);
        AppData.windowManager.updateViewLayout(smallView.getRoot(), smallViewParams);
      }
      RemoteIsPortrait = remoteIsPortrait;
    });
    // 设置窗口大小
    clientView.updateMaxSize(new Pair<>(availableWidth * 4 / 5, availableHeight * 4 / 5));
    // 设置圆角
    smallView.getRoot().setOutlineProvider(this);
    smallView.getRoot().setClipToOutline(true);
  }

  public void show() {
    // 初始化
    InitPos = false;
    refreshAvailableWindowBounds();
    updateConstrainedMaxSize(getSavedMaxSize(LocalIsPortrait(), RemoteIsPortrait));
    smallView.barView.setVisibility(View.GONE);
    smallView.bar.setVisibility(View.VISIBLE);
    barTimer();
    // 设置监听
    setButtonListener(clientView.controlPacket);
    setKeyEvent(clientView.controlPacket);
    // 显示
    AppData.windowManager.addView(smallView.getRoot(), smallViewParams);
    smallView.textureViewLayout.addView(clientView.textureView, 0);
    clientView.viewAnim(smallView.getRoot(), true, 0, PublicTools.dp2px(40f), null);
  }

  public void hide() {
    try {
      if (barTimerThread != null) barTimerThread.interrupt();
      if (barViewTimerThread != null) barViewTimerThread.interrupt();
      smallView.textureViewLayout.removeView(clientView.textureView);
      AppData.windowManager.removeView(smallView.getRoot());
      clientView.updateDevice();
    } catch (Exception ignored) {
    }
  }

  public void changeMode(int mode) {
    smallView.buttonSwitch.setVisibility(mode == 0 ? View.VISIBLE : View.INVISIBLE);
    smallView.buttonHome.setVisibility(mode == 0 ? View.VISIBLE : View.INVISIBLE);
    if (mode == 0) smallView.buttonTransfer.setImageResource(R.drawable.share_out);
    else smallView.buttonTransfer.setImageResource(R.drawable.share_in);
    InitPos = false;
  }

  // 设置焦点监听
  @SuppressLint("ClickableViewAccessibility")
  private void setFloatVideoListener() {
    smallView.getRoot().setOnTouchHandle(event -> {
      if (event.getAction() == MotionEvent.ACTION_OUTSIDE) {
        clientView.lastTouchIsInside = false;
        if (AppData.setting.getDefaultMiniOnOutside()) {
          if (Client.allClient.size() > 1) {
            new Thread(() -> {
              try {
                Thread.sleep(100);
                for (Client client : Client.allClient)
                  if (client.clientView.lastTouchIsInside) return;
                AppData.uiHandler.post(() -> clientView.changeToMini(1));
              } catch (InterruptedException ignored) {}
            }).start();
          }
          else clientView.changeToMini(1);
        }
        else if (smallViewParams.flags != LayoutParamsFlagNoFocus) {
          smallView.editText.clearFocus();
          smallViewParams.flags = LayoutParamsFlagNoFocus;
          AppData.windowManager.updateViewLayout(smallView.getRoot(), smallViewParams);
        }
        if (smallView.barView.getVisibility() == View.VISIBLE) {
          clientView.viewAnim(smallView.barView, false, 0, PublicTools.dp2px(-40f), (isStart -> {
            if (!isStart) smallView.barView.setVisibility(View.GONE);
          }));
        }
      } else {
        clientView.lastTouchIsInside = true;
        changeBar(1);
        barTimer();
        if (smallViewParams.flags != LayoutParamsFlagFocus) {
          smallView.editText.requestFocus();
          smallViewParams.flags = LayoutParamsFlagFocus;
          AppData.windowManager.updateViewLayout(smallView.getRoot(), smallViewParams);
        }
      }
    });
  }

  // 设置上横条监听控制
  @SuppressLint("ClickableViewAccessibility")
  private void setBarListener() {
    AtomicBoolean isFilp = new AtomicBoolean(false);
    AtomicInteger xx = new AtomicInteger();
    AtomicInteger yy = new AtomicInteger();
    AtomicInteger paramsX = new AtomicInteger();
    AtomicInteger paramsY = new AtomicInteger();
    smallView.bar.setOnTouchListener((v, event) -> {
      switch (event.getActionMasked()) {
        case MotionEvent.ACTION_DOWN: {
          xx.set((int) event.getRawX());
          yy.set((int) event.getRawY());
          paramsX.set(smallViewParams.x);
          paramsY.set(smallViewParams.y);
          isFilp.set(false);
          break;
        }
        case MotionEvent.ACTION_MOVE: {
          int x = (int) event.getRawX();
          int y = (int) event.getRawY();
          int flipX = x - xx.get();
          int flipY = y - yy.get();
          // 适配一些机器将点击视作小范围移动(小于50的圆内不做处理)
          if (!isFilp.get()) {
            if (flipX * flipX + flipY * flipY < 2500) return true;
            isFilp.set(true);
          }
          // 全屏模式保留原限制；分屏模式按当前 APP 区域限制。
          if (!restrictToMainWindow && y < statusBarHeight + 10) return true;
          // 更新
          smallViewParams.x = paramsX.get() + flipX;
          smallViewParams.y = paramsY.get() + flipY;
          constrainWindowPosition(smallView.getRoot().getWidth(), smallView.getRoot().getHeight());
          saveCurrentPosition(LocalIsPortrait(), RemoteIsPortrait);
          AppData.windowManager.updateViewLayout(smallView.getRoot(), smallViewParams);
          break;
        }
        case MotionEvent.ACTION_UP:
          if (!isFilp.get()) {
            changeBarView();
            barViewTimer();
          }
          break;
      }
      return true;
    });
  }

  // 设置按钮监听
  private void setButtonListener(ControlPacket controlPacket) {
    smallView.buttonRotate.setOnClickListener(v -> {
      controlPacket.sendRotateEvent();
      changeBarView();
    });
    smallView.buttonBack.setOnClickListener(v -> controlPacket.sendKeyEvent(4, 0, -1));
    smallView.buttonHome.setOnClickListener(v -> controlPacket.sendKeyEvent(3, 0, -1));
    smallView.buttonSwitch.setOnClickListener(v -> controlPacket.sendKeyEvent(187, 0, -1));
    smallView.buttonNavBar.setOnClickListener(v -> {
      setNavBarHide(smallView.navBar.getVisibility() == View.GONE);
      barViewTimer();
    });
    smallView.buttonMini.setOnClickListener(v -> {
      clientView.changeToMini(0);
      barViewTimer();
    });
    smallView.buttonFull.setOnClickListener(v -> {
      clientView.changeToFull();
      barViewTimer();
    });
    smallView.buttonClose.setOnClickListener(v -> {
      clientView.updateDevice();
      clientView.onClose.run();
    });
    if (clientView.mode == 1) smallView.buttonTransfer.setImageResource(R.drawable.share_in);
    smallView.buttonTransfer.setOnClickListener(v -> {
      clientView.changeMode.run(clientView.mode == 0 ? 1 : 0);
      barViewTimer();
    });
    smallView.buttonLight.setOnClickListener(v -> {
      controlPacket.sendLightEvent(Display.STATE_ON);
      clientView.lightState = true;
      barViewTimer();
    });
    smallView.buttonLightOff.setOnClickListener(v -> {
      controlPacket.sendLightEvent(Display.STATE_UNKNOWN);
      clientView.lightState = false;
      barViewTimer();
    });
    smallView.resetLocation.setOnClickListener(v -> {
      clientView.device.small_p_p_x = 0;
      clientView.device.small_p_p_y = 0;
      clientView.device.small_p_l_x = 0;
      clientView.device.small_p_l_y = 0;
      clientView.device.small_l_p_x = 0;
      clientView.device.small_l_p_y = 0;
      clientView.device.small_l_l_x = 0;
      clientView.device.small_l_l_y = 0;
      clientView.device.small_free_x = 0;
      clientView.device.small_free_y = 0;
      clientView.device.small_p_p_width = shortEdge * 4 / 5;
      clientView.device.small_p_p_height = longEdge * 4 / 5;
      clientView.device.small_p_l_width = shortEdge * 4 / 5;
      clientView.device.small_p_l_height = longEdge * 4 / 5;
      clientView.device.small_l_p_width = longEdge * 4 / 5;
      clientView.device.small_l_p_height = shortEdge * 4 / 5;
      clientView.device.small_l_l_width = longEdge * 4 / 5;
      clientView.device.small_l_l_height = shortEdge * 4 / 5;
      clientView.device.small_free_width = shortEdge * 2 / 5;
      clientView.device.small_free_height = shortEdge * 3 / 5;
      InitPos = false;
      updateConstrainedMaxSize(getSavedMaxSize(LocalIsPortrait(), RemoteIsPortrait));
      smallView.getRoot().requestLayout();
      barViewTimer();
    });
    smallView.buttonPower.setOnClickListener(v -> {
      controlPacket.sendPowerEvent();
      barViewTimer();
    });
  }

  // 导航栏隐藏
  private void setNavBarHide(boolean isShow) {
    smallView.navBar.setVisibility(isShow ? View.VISIBLE : View.GONE);
    smallView.buttonNavBar.setImageResource(isShow ? R.drawable.not_equal : R.drawable.equals);
  }

  private void changeBarView() {
    if (clientView == null) return;
    boolean toShowView = smallView.barView.getVisibility() == View.GONE;
    clientView.viewAnim(smallView.barView, toShowView, 0, PublicTools.dp2px(-40f), (isStart -> {
      if (isStart && toShowView) smallView.barView.setVisibility(View.VISIBLE);
      else if (!isStart && !toShowView) smallView.barView.setVisibility(View.GONE);
    }));
  }

  private void changeBar(int Show) {
    if (clientView == null) return;
    if (Show == 1 & smallView.bar.getVisibility() == View.VISIBLE) return;
    if (Show == -1 & smallView.bar.getVisibility() == View.GONE) return;
    boolean toShow = smallView.bar.getVisibility() == View.GONE;
    clientView.viewAnim(smallView.bar, toShow, 0, PublicTools.dp2px(-20f), (isStart -> {
      if (isStart && toShow) smallView.bar.setVisibility(View.VISIBLE);
      else if (!isStart && !toShow) smallView.bar.setVisibility(View.GONE);
    }));
  }

  private Thread barTimerThread = null;
  private void barTimer() {
    if (barTimerThread != null) barTimerThread.interrupt();
    barTimerThread = new Thread(() -> {
      try {
        Thread.sleep(10000);
        if (smallView.bar.getVisibility() == View.VISIBLE)
          AppData.uiHandler.post(() -> changeBar(0));
      } catch (InterruptedException ignored) {
      }
    });
    barTimerThread.start();
  }

  private Thread barViewTimerThread = null;
  private void barViewTimer() {
    if (barViewTimerThread != null) barViewTimerThread.interrupt();
    barViewTimerThread = new Thread(() -> {
      try {
        Thread.sleep(2000);
        if (smallView.barView.getVisibility() == View.VISIBLE)
          AppData.uiHandler.post(this::changeBarView);
      } catch (InterruptedException ignored) {
      }
    });
    barViewTimerThread.start();
  }

  // 设置悬浮窗大小拖动按钮监听控制
  @SuppressLint("ClickableViewAccessibility")
  private void setReSizeListener() {
    int minSize = PublicTools.dp2px(150f);
    smallView.reSize.setOnTouchListener((v, event) -> {
      if (!InitPos) return true;
      if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
        int sizeX = (int) (event.getRawX() - smallViewParams.x);
        int sizeY = (int) (event.getRawY() - smallViewParams.y);
        if (restrictToMainWindow) {
          sizeX = Math.min(sizeX, availableLeft + availableWidth - smallViewParams.x);
          sizeY = Math.min(sizeY, availableTop + availableHeight - smallViewParams.y);
        }
        if (sizeX < minSize || sizeY < minSize) return true;

        clientView.updateMaxSize(new Pair<>(sizeX, sizeY));

        if (sizeX < sizeY) {
          if (LocalIsPortrait()) {
            clientView.device.small_p_p_width = sizeX;
            clientView.device.small_p_p_height = sizeY;
          } else {
            clientView.device.small_l_p_width = sizeX;
            clientView.device.small_l_p_height = sizeY;
          }
        } else {
          if (LocalIsPortrait()) {
            clientView.device.small_p_l_width = sizeX;
            clientView.device.small_p_l_height = sizeY;
          } else {
            clientView.device.small_l_l_width = sizeX;
            clientView.device.small_l_l_height = sizeY;
          }
        }
      }
      return true;
    });
  }

  private void refreshAvailableWindowBounds() {
    int oldLeft = availableLeft;
    int oldTop = availableTop;
    int oldWidth = availableWidth;
    int oldHeight = availableHeight;
    boolean oldRestriction = restrictToMainWindow;

    Rect mainWindowBounds = MainActivity.getMainWindowBoundsOnScreen();
    if (mainWindowBounds != null && mainWindowBounds.width() > 0 && mainWindowBounds.height() > 0) {
      availableLeft = mainWindowBounds.left;
      availableTop = mainWindowBounds.top;
      availableWidth = mainWindowBounds.width();
      availableHeight = mainWindowBounds.height();
      restrictToMainWindow = MainActivity.shouldRestrictFloatingWindowsToMainWindow();
    } else {
      DisplayMetrics displayMetrics = AppData.main.getResources().getDisplayMetrics();
      availableLeft = 0;
      availableTop = 0;
      availableWidth = displayMetrics.widthPixels;
      availableHeight = displayMetrics.heightPixels + statusBarHeight;
      restrictToMainWindow = false;
    }
    longEdge = Math.max(availableWidth, availableHeight);
    shortEdge = Math.min(availableWidth, availableHeight);

    if (oldLeft != availableLeft
            || oldTop != availableTop
            || oldWidth != availableWidth
            || oldHeight != availableHeight
            || oldRestriction != restrictToMainWindow) {
      Log.i(LOG_TAG, "floating bounds"
              + ", left=" + availableLeft
              + ", top=" + availableTop
              + ", size=" + availableWidth + "x" + availableHeight
              + ", multiWindow=" + restrictToMainWindow);
    }
  }

  private Pair<Integer, Integer> getSavedPosition(boolean localIsPortrait, boolean remoteIsPortrait) {
    if (localIsPortrait) {
      if (remoteIsPortrait) {
        return new Pair<>(clientView.device.small_p_p_x, clientView.device.small_p_p_y);
      }
      return new Pair<>(clientView.device.small_p_l_x, clientView.device.small_p_l_y);
    }
    if (remoteIsPortrait) {
      return new Pair<>(clientView.device.small_l_p_x, clientView.device.small_l_p_y);
    }
    return new Pair<>(clientView.device.small_l_l_x, clientView.device.small_l_l_y);
  }

  private Pair<Integer, Integer> getSavedMaxSize(boolean localIsPortrait, boolean remoteIsPortrait) {
    if (localIsPortrait) {
      if (remoteIsPortrait) {
        return new Pair<>(clientView.device.small_p_p_width, clientView.device.small_p_p_height);
      }
      return new Pair<>(clientView.device.small_p_l_width, clientView.device.small_p_l_height);
    }
    if (remoteIsPortrait) {
      return new Pair<>(clientView.device.small_l_p_width, clientView.device.small_l_p_height);
    }
    return new Pair<>(clientView.device.small_l_l_width, clientView.device.small_l_l_height);
  }

  private void saveCurrentPosition(boolean localIsPortrait, boolean remoteIsPortrait) {
    if (localIsPortrait) {
      if (remoteIsPortrait) {
        clientView.device.small_p_p_x = smallViewParams.x;
        clientView.device.small_p_p_y = smallViewParams.y;
      } else {
        clientView.device.small_p_l_x = smallViewParams.x;
        clientView.device.small_p_l_y = smallViewParams.y;
      }
    } else if (remoteIsPortrait) {
      clientView.device.small_l_p_x = smallViewParams.x;
      clientView.device.small_l_p_y = smallViewParams.y;
    } else {
      clientView.device.small_l_l_x = smallViewParams.x;
      clientView.device.small_l_l_y = smallViewParams.y;
    }
  }

  private void updateConstrainedMaxSize(Pair<Integer, Integer> savedMaxSize) {
    if (savedMaxSize == null) return;
    int width = savedMaxSize.first;
    int height = savedMaxSize.second;
    if (width <= 0 || height <= 0) return;
    if (restrictToMainWindow) {
      int maxWidth = Math.max(1, availableWidth * 4 / 5);
      int maxHeight = Math.max(1, availableHeight * 4 / 5);
      int constrainedWidth = Math.min(width, maxWidth);
      int constrainedHeight = Math.min(height, maxHeight);
      if (constrainedWidth != width || constrainedHeight != height) {
        Log.i(LOG_TAG, "floating size constrained"
                + ", saved=" + width + "x" + height
                + ", applied=" + constrainedWidth + "x" + constrainedHeight
                + ", window=" + availableWidth + "x" + availableHeight);
      }
      width = constrainedWidth;
      height = constrainedHeight;
    }
    clientView.updateMaxSize(new Pair<>(width, height));
  }

  private void constrainWindowPosition(int windowWidth, int windowHeight) {
    if (!restrictToMainWindow) return;
    int safeWidth = Math.max(1, windowWidth);
    int safeHeight = Math.max(1, windowHeight);
    int minX = availableLeft;
    int minY = Math.max(availableTop, statusBarHeight + 10);
    int maxX = Math.max(minX, availableLeft + availableWidth - safeWidth);
    int maxY = Math.max(minY, availableTop + availableHeight - safeHeight);
    smallViewParams.x = Math.max(minX, Math.min(smallViewParams.x, maxX));
    smallViewParams.y = Math.max(minY, Math.min(smallViewParams.y, maxY));
  }

  // 设置键盘监听
  private void setKeyEvent(ControlPacket controlPacket) {
    smallView.editText.setInputType(InputType.TYPE_NULL);
    smallView.editText.setOnKeyListener((v, keyCode, event) -> {
      if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
        controlPacket.sendKeyEvent(event.getKeyCode(), event.getMetaState(), 0);
        return true;
      }
      return false;
    });
  }

  @Override
  public void getOutline(View view, Outline outline) {
    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), AppData.main.getResources().getDimension(R.dimen.round));
  }

  static {
    @SuppressLint("InternalInsetResource") int resourceId = AppData.main.getResources().getIdentifier("status_bar_height", "dimen", "android");
    if (resourceId > 0) {
      statusBarHeight = AppData.main.getResources().getDimensionPixelSize(resourceId);
    }
  }

}
