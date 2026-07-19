package top.eiyooooo.easycontrol.app.client.view;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.SurfaceTexture;
import android.os.Looper;
import android.util.Log;
import android.util.Pair;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewPropertyAnimator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

import androidx.annotation.NonNull;

import top.eiyooooo.easycontrol.app.client.Client;
import top.eiyooooo.easycontrol.app.client.ControlPacket;
import top.eiyooooo.easycontrol.app.entity.AppData;
import top.eiyooooo.easycontrol.app.entity.Device;
import top.eiyooooo.easycontrol.app.helper.PublicTools;

/**
 * 类 ClientView
 * 说明：该类负责 ClientView 相关功能。
 */

public class ClientView implements TextureView.SurfaceTextureListener {
  public interface EmbeddedHostSizeListener {
    void onHostSizeChanged(int width, int height);
  }

  public final Device device;
  public final Device deviceOriginal;
  public int mode = 0;
  public int displayId = 0;
  public final ControlPacket controlPacket;
  final PublicTools.MyFunctionInt changeMode;
  private final PublicTools.MyFunction onReady;
  public final PublicTools.MyFunction onClose;
  public final TextureView textureView;
  private SurfaceTexture surfaceTexture;

  private final SmallView smallView;
  private final MiniView miniView;
  private final EmbeddedView embeddedView;
  private FullActivity fullView;
  private final boolean embeddedMode;
  private final EmbeddedHostSizeListener embeddedHostSizeListener;

  private Pair<Integer, Integer> videoSize;
  private volatile Pair<Integer, Integer> maxSize;
  private Pair<Integer, Integer> surfaceSize;
  public boolean lastTouchIsInside = true;
  boolean lightState;
  public int multiLink = 0;

  public ClientView(Device device, ControlPacket controlPacket, PublicTools.MyFunctionInt changeMode,
                    PublicTools.MyFunction onReady, PublicTools.MyFunction onClose,
                    boolean embeddedMode, EmbeddedHostSizeListener embeddedHostSizeListener) {
    lightState = !AppData.setting.getTurnOffScreenIfStart();
    this.embeddedMode = embeddedMode;
    this.embeddedHostSizeListener = embeddedHostSizeListener;
    this.deviceOriginal = device;
    this.device = new Device(device.uuid, device.type);
    Device.copyDevice(device, this.device);
    textureView = new TextureView(AppData.main);
    if (embeddedMode) {
      smallView = null;
      miniView = null;
      embeddedView = new EmbeddedView(this);
    } else if (!AppData.setting.getAlwaysFullMode()) {
      smallView = new SmallView(this);
      miniView = new MiniView(this);
      embeddedView = null;
    } else {
      smallView = null;
      miniView = null;
      embeddedView = null;
    }
    this.controlPacket = controlPacket;
    this.changeMode = changeMode;
    this.onReady = onReady;
    this.onClose = onClose;
    setTouchListener();
    textureView.setSurfaceTextureListener(this);
    if (smallView != null) smallView.changeMode(mode);
    if (embeddedView != null) embeddedView.changeMode(mode);
  }

  public void updateDevice() {
    if (multiLink != 0 || device.temporarySession || deviceOriginal.temporarySession) return;
    Device.copyDevice(device, deviceOriginal);
    AppData.dbHelper.update(device);
  }

  public void changeMode(int mode) {
    this.mode = mode;
    Runnable updateView = () -> {
      if (smallView != null) smallView.changeMode(mode);
      if (fullView != null) fullView.changeMode(mode);
      if (embeddedView != null) embeddedView.changeMode(mode);
    };
    if (Looper.myLooper() == Looper.getMainLooper()) updateView.run();
    else AppData.uiHandler.post(updateView);
  }

  /**
   * 1:Mini
   * <p>
   * 2:Small
   * <p>
   * 3:Full
   * <p>
   * 4:Embedded
   */
  public int viewMode;
  public boolean needResumeToSmall = false;
  public synchronized void changeToFull() {
    if (embeddedView != null) {
      changeToEmbedded();
      return;
    }
    Client target = null;
    for (Client client : Client.allClient) {
      if (client.clientView == this && !client.isClosed()) {
        target = client;
        break;
      }
    }
    if (target == null) return;
    hide(false);
    Intent intent = new Intent(AppData.main, FullActivity.class);
    intent.putExtra("sessionId", target.sessionId);
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    AppData.main.startActivity(intent);
    viewMode = 3;
  }

  public synchronized void changeToSmall() {
    if (embeddedView != null) {
      changeToEmbedded();
      return;
    }
    needResumeToSmall = false;
    if (smallView == null) return;
    hide(false);
    smallView.show();
    viewMode = 2;
  }

  public synchronized void changeToMini(int mode) {
    if (embeddedView != null) {
      changeToEmbedded();
      return;
    }
    if (miniView == null) return;
    hide(false);
    miniView.show(mode);
    viewMode = 1;
  }

  public synchronized void hide(boolean isRelease) {
    if (fullView != null) fullView.hide();
    if (smallView != null) smallView.hide();
    if (miniView != null) miniView.hide();
    if (embeddedView != null) embeddedView.hide();
    if (isRelease && surfaceTexture != null) {
      surfaceTexture.release();
      surfaceTexture = null;
    }
  }

  public void setFullView(FullActivity fullView) {
    this.fullView = fullView;
  }

  public synchronized void changeToEmbedded() {
    if (embeddedView == null) return;
    embeddedView.show();
    viewMode = 4;
  }

  public void showEmbeddedLoading() {
    if (embeddedView == null) return;
    if (Looper.myLooper() == Looper.getMainLooper()) embeddedView.showLoading();
    else AppData.uiHandler.post(embeddedView::showLoading);
  }

  public void restoreEmbedded(boolean streamReady) {
    if (embeddedView != null) embeddedView.restore(streamReady);
  }

  public boolean isEmbeddedMode() {
    return embeddedMode;
  }

  public void notifyEmbeddedHostSizeChanged(int width, int height) {
    if (!embeddedMode || embeddedHostSizeListener == null || width <= 0 || height <= 0) return;
    embeddedHostSizeListener.onHostSizeChanged(width, height);
  }

  private static final float aspectRatioThreshold = 0.15f;
  private static final float embeddedFlowMinAspectRatio = 16f / 9f;

  public void updateMaxSize(Pair<Integer, Integer> maxSize) {
    if (maxSize == null || maxSize.first == 0 || maxSize.second == 0) return;
    this.maxSize = maxSize;
    if (embeddedMode) {
      reCalculateEmbeddedTextureViewSize();
      return;
    }
    if (fullView != null && fullView.fullMaxSize != null && AppData.setting.getFillFull()) {
      if (videoSize != null) {
        float fullMaxAspectRatio = (float) fullView.fullMaxSize.first / fullView.fullMaxSize.second;
        float videoAspectRatio = (float) videoSize.first / videoSize.second;
        if (Math.abs(fullMaxAspectRatio - videoAspectRatio) < aspectRatioThreshold) {
          reCalculateTextureViewSize(fullView.fullMaxSize.first, fullView.fullMaxSize.second);
          return;
        }
      }
    }
    reCalculateTextureViewSize();
  }

  public void updateVideoSize(Pair<Integer, Integer> videoSize) {
    if (videoSize == null || videoSize.first == 0 || videoSize.second == 0) return;
    this.videoSize = videoSize;
    if (embeddedMode && maxSize != null) {
      reCalculateEmbeddedTextureViewSize();
      return;
    }
    if (fullView != null && fullView.fullMaxSize != null && AppData.setting.getFillFull()) {
      float fullMaxAspectRatio = (float) fullView.fullMaxSize.first / fullView.fullMaxSize.second;
      float videoAspectRatio = (float) videoSize.first / videoSize.second;
      if (Math.abs(fullMaxAspectRatio - videoAspectRatio) < aspectRatioThreshold) {
        reCalculateTextureViewSize(fullView.fullMaxSize.first, fullView.fullMaxSize.second);
        return;
      }
    }
    reCalculateTextureViewSize();
  }

  public Pair<Integer, Integer> getVideoSize() {
    return videoSize;
  }

  public Pair<Integer, Integer> getMaxSize() {
    return maxSize;
  }

  private void reCalculateEmbeddedTextureViewSize() {
    if (maxSize == null || videoSize == null) return;
    reCalculateTextureViewSize();
    float videoRatio = (float) videoSize.first / videoSize.second;
    float hostRatio = (float) maxSize.first / maxSize.second;
    float expectedVideoRatio = mode == 1
            ? Math.max(hostRatio, embeddedFlowMinAspectRatio) : hostRatio;
    boolean flowRatioMismatch = mode == 1
            && Math.abs(videoRatio - expectedVideoRatio) >= aspectRatioThreshold;
    String message = "embedded stream geometry"
            + ", uuid=" + device.uuid
            + ", mode=" + mode
            + ", video=" + videoSize.first + "x" + videoSize.second
            + ", host=" + maxSize.first + "x" + maxSize.second
            + ", surface=" + surfaceSize.first + "x" + surfaceSize.second
            + ", videoRatio=" + videoRatio
            + ", hostRatio=" + hostRatio
            + ", expectedVideoRatio=" + expectedVideoRatio
            + ", scale=fit-center"
            + ", flowRatioMismatch=" + flowRatioMismatch;
    if (flowRatioMismatch) {
      Log.w("EasycontrolEmbedded", message
              + ", reason=virtual-display-or-capture-did-not-switch-to-embedded-wide");
    } else {
      Log.i("EasycontrolEmbedded", message);
    }
  }

  public Surface getSurface() {
    return new Surface(surfaceTexture);
  }

  // 重新计算TextureView大小
  private void reCalculateTextureViewSize() {
    if (maxSize == null || videoSize == null) return;
    // 根据原画面大小videoSize计算在maxSize空间内的最大缩放大小
    int tmp1 = videoSize.second * maxSize.first / videoSize.first;
    // 横向最大不会超出
    if (maxSize.second > tmp1) surfaceSize = new Pair<>(maxSize.first, tmp1);
      // 竖向最大不会超出
    else surfaceSize = new Pair<>(videoSize.first * maxSize.second / videoSize.second, maxSize.second);
    // 更新大小
    ViewGroup.LayoutParams layoutParams = textureView.getLayoutParams();
    layoutParams.width = surfaceSize.first;
    layoutParams.height = surfaceSize.second;
    textureView.setLayoutParams(layoutParams);
  }
  public void reCalculateTextureViewSize(int width, int height) {
    surfaceSize = new Pair<>(width, height);
    ViewGroup.LayoutParams layoutParams = textureView.getLayoutParams();
    layoutParams.width = width;
    layoutParams.height = height;
    textureView.setLayoutParams(layoutParams);
  }

  // 设置视频区域触摸监听
  @SuppressLint("ClickableViewAccessibility")
  private void setTouchListener() {
    textureView.setOnTouchListener((view, event) -> {
      if (surfaceSize == null) return true;
      int action = event.getActionMasked();
      if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
        int i = event.getActionIndex();
        int pointerId = event.getPointerId(i);
        if (pointerId < 0 || pointerId >= MAX_TRACKED_POINTERS) return true;
        pointerDownTime[pointerId] = event.getEventTime();
        createTouchPacket(event, MotionEvent.ACTION_DOWN, i);
      } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) createTouchPacket(event, MotionEvent.ACTION_UP, event.getActionIndex());
      else for (int i = 0; i < event.getPointerCount(); i++) createTouchPacket(event, MotionEvent.ACTION_MOVE, i);
      return true;
    });
  }

  private static final int MAX_TRACKED_POINTERS = 32;
  private final int[] pointerX = new int[MAX_TRACKED_POINTERS];
  private final int[] pointerY = new int[MAX_TRACKED_POINTERS];
  private final long[] pointerDownTime = new long[MAX_TRACKED_POINTERS];

  private void createTouchPacket(MotionEvent event, int action, int i) {
    if (i < 0 || i >= event.getPointerCount()) return;
    int p = event.getPointerId(i);
    if (p < 0 || p >= MAX_TRACKED_POINTERS) return;
    long downTime = pointerDownTime[p] == 0 ? event.getDownTime() : pointerDownTime[p];
    long elapsed = Math.max(0, event.getEventTime() - downTime);
    int offsetTime = (int) Math.min(Integer.MAX_VALUE, elapsed);
    int x = (int) event.getX(i);
    int y = (int) event.getY(i);
    if (action == MotionEvent.ACTION_MOVE) {
      // 减少发送小范围移动(小于4的圆内不做处理)
      int flipX = pointerX[p] - x;
      if (flipX > -4 && flipX < 4) {
        int flipY = pointerY[p] - y;
        if (flipY > -4 && flipY < 4) return;
      }
    }
    pointerX[p] = x;
    pointerY[p] = y;
    controlPacket.sendTouchEvent(action, p, (float) x / surfaceSize.first, (float) y / surfaceSize.second, offsetTime);
    if (action == MotionEvent.ACTION_UP) pointerDownTime[p] = 0;
  }

  // 更改View的形态
  public void viewAnim(View view, boolean toShowView, int translationX, int translationY, PublicTools.MyFunctionBoolean action) {
    // 创建平移动画
    view.setTranslationX(toShowView ? translationX : 0);
    float endX = toShowView ? 0 : translationX;
    view.setTranslationY(toShowView ? translationY : 0);
    float endY = toShowView ? 0 : translationY;
    // 创建透明度动画
    view.setAlpha(toShowView ? 0f : 1f);
    float endAlpha = toShowView ? 1f : 0f;

    // 设置动画时长和插值器
    ViewPropertyAnimator animator = view.animate()
      .translationX(endX)
      .translationY(endY)
      .alpha(endAlpha)
      .setDuration(toShowView ? 300 : 200)
      .setInterpolator(toShowView ? new OvershootInterpolator() : new DecelerateInterpolator());
    animator.withStartAction(() -> {
      if (action != null) action.run(true);
    });
    animator.withEndAction(() -> {
      if (action != null) action.run(false);
    });

    // 启动动画
    animator.start();
  }

  @Override
  public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surfaceTexture, int i, int i1) {
    // 初始化
    if (this.surfaceTexture == null) {
      this.surfaceTexture = surfaceTexture;
      onReady.run();
    } else textureView.setSurfaceTexture(this.surfaceTexture);
  }

  @Override
  public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surfaceTexture, int i, int i1) {
  }

  @Override
  public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surfaceTexture) {
    return false;
  }

  @Override
  public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surfaceTexture) {
  }

}
