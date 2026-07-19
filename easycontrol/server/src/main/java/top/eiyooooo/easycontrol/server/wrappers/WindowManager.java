package top.eiyooooo.easycontrol.server.wrappers;

import android.annotation.TargetApi;
import android.os.IInterface;
import android.view.IDisplayFoldListener;
import android.view.IRotationWatcher;
import top.eiyooooo.easycontrol.server.utils.L;

import java.lang.reflect.Method;
import java.util.Objects;
/**
 * 类 WindowManager
 * 说明：该类负责 WindowManager 相关功能。
 */

public final class WindowManager {
    private static IInterface manager;
    private static Class<?> CLASS;
    private static Method freezeDisplayRotationMethod = null;
    private static Method isDisplayRotationFrozenMethod = null;
    private static Method thawDisplayRotationMethod = null;
    private static Method setFixedToUserRotationMethod = null;
    private static Method setIgnoreOrientationRequestMethod = null;
    private static Method setShouldShowSystemDecorsMethod = null;
    private static Method shouldShowSystemDecorsMethod = null;
    private static boolean shouldShowSystemDecorsMethodUnavailable;
    private static Method hideTransientBarsMethod = null;
    private static boolean hideTransientBarsMethodUnavailable;
    private static Method hasNavigationBarMethod = null;
    private static boolean hasNavigationBarMethodUnavailable;
    private static int freezeDisplayRotationMethodVersion;
    private static int isDisplayRotationFrozenMethodVersion;
    private static int thawDisplayRotationMethodVersion;
    private static Method getRotationMethod = null;
    private static Method watchRotationExMethod = null;
    private static Method watchRotationMethod = null;
    private static Method removeRotationWatcherMethod = null;
    private static Method registerDisplayFoldListenerMethod = null;
    private static IRotationWatcher rotationWatcher_saved;

    public static void init(IInterface m) {
        manager = m;
        if (manager == null) {
            L.e("Error in WindowManager.init: manager is null");
            return;
        }
        CLASS = manager.getClass();
    }

    private static Method getFreezeDisplayRotationMethod() throws ReflectiveOperationException {
        if (freezeDisplayRotationMethod == null) {
            try {
                freezeDisplayRotationMethod = manager.getClass().getMethod("freezeDisplayRotation", int.class, int.class, String.class);
                freezeDisplayRotationMethodVersion = 0;
            } catch (ReflectiveOperationException e) {
                try {
                    freezeDisplayRotationMethod = manager.getClass().getMethod("freezeDisplayRotation", int.class, int.class);
                    freezeDisplayRotationMethodVersion = 1;
                } catch (ReflectiveOperationException e1) {
                    freezeDisplayRotationMethod = manager.getClass().getMethod("freezeRotation", int.class);
                    freezeDisplayRotationMethodVersion = 2;
                }
            }
        }
        return freezeDisplayRotationMethod;
    }

    private static Method getIsDisplayRotationFrozenMethod() throws ReflectiveOperationException {
        if (isDisplayRotationFrozenMethod == null) {
            try {
                isDisplayRotationFrozenMethod = manager.getClass().getMethod("isDisplayRotationFrozen", int.class);
                isDisplayRotationFrozenMethodVersion = 0;
            } catch (ReflectiveOperationException e) {
                isDisplayRotationFrozenMethod = manager.getClass().getMethod("isRotationFrozen");
                isDisplayRotationFrozenMethodVersion = 1;
            }
        }
        return isDisplayRotationFrozenMethod;
    }

    private static Method getThawDisplayRotationMethod() throws ReflectiveOperationException {
        if (thawDisplayRotationMethod == null) {
            try {
                thawDisplayRotationMethod = manager.getClass().getMethod("thawDisplayRotation", int.class, String.class);
                thawDisplayRotationMethodVersion = 0;
            } catch (ReflectiveOperationException e) {
                try {
                    thawDisplayRotationMethod = manager.getClass().getMethod("thawDisplayRotation", int.class);
                    thawDisplayRotationMethodVersion = 1;
                } catch (ReflectiveOperationException e1) {
                    thawDisplayRotationMethod = manager.getClass().getMethod("thawRotation");
                    thawDisplayRotationMethodVersion = 2;
                }
            }
        }
        return thawDisplayRotationMethod;
    }

    private static Method getRotationMethod() throws ReflectiveOperationException {
        if (getRotationMethod == null) {
            if (CLASS == null) {
                L.e("Error in getRotationMethod: CLASS is null");
                return null;
            }
            try {
                getRotationMethod = CLASS.getMethod("getDefaultDisplayRotation");
            } catch (Exception ignored) {
                getRotationMethod = CLASS.getMethod("getRotation");
            }
        }
        return getRotationMethod;
    }

    private static Method getWatchRotationExMethod() throws ReflectiveOperationException {
        if (watchRotationExMethod == null) {
            if (CLASS == null) {
                L.e("Error in getWatchRotationExMethod: CLASS is null");
                return null;
            }
            watchRotationExMethod = CLASS.getMethod("watchRotation", IRotationWatcher.class, int.class);
        }
        return watchRotationExMethod;
    }

    private static Method getWatchRotationMethod() throws ReflectiveOperationException {
        if (watchRotationMethod == null) {
            if (CLASS == null) {
                L.e("Error in getWatchRotationMethod: CLASS is null");
                return null;
            }
            watchRotationMethod = CLASS.getMethod("watchRotation", IRotationWatcher.class);
        }
        return watchRotationMethod;
    }

    private static Method getRemoveRotationWatcherMethod() throws ReflectiveOperationException {
        if (removeRotationWatcherMethod == null) {
            if (CLASS == null) {
                L.e("Error in getRemoveRotationWatcherMethod: CLASS is null");
                return null;
            }
            removeRotationWatcherMethod = CLASS.getMethod("removeRotationWatcher", IRotationWatcher.class);
            removeRotationWatcherMethod.setAccessible(true);
        }
        return removeRotationWatcherMethod;
    }

    private static Method getRegisterDisplayFoldListenerMethod() throws ReflectiveOperationException {
        if (registerDisplayFoldListenerMethod == null) {
            if (CLASS == null) {
                L.e("Error in getRegisterDisplayFoldListenerMethod: CLASS is null");
                return null;
            }
            registerDisplayFoldListenerMethod = CLASS.getMethod("registerDisplayFoldListener", IDisplayFoldListener.class);
        }
        return registerDisplayFoldListenerMethod;
    }

    public static void freezeRotation(int displayId, int rotation) {
        try {
            Method method = getFreezeDisplayRotationMethod();
            switch (freezeDisplayRotationMethodVersion) {
                case 0:
                    method.invoke(manager, displayId, rotation, "scrcpy#freezeRotation");
                    break;
                case 1:
                    method.invoke(manager, displayId, rotation);
                    break;
                default:
                    if (displayId != 0) {
                        L.e("Secondary display rotation not supported on this device");
                        return;
                    }
                    method.invoke(manager, rotation);
                    break;
            }
        } catch (Exception e) {
            L.e("Could not invoke method", e);
        }
    }

    public static boolean isRotationFrozen(int displayId) {
        try {
            Method method = getIsDisplayRotationFrozenMethod();
            switch (isDisplayRotationFrozenMethodVersion) {
                case 0:
                    return (boolean) method.invoke(manager, displayId);
                default:
                    if (displayId != 0) {
                        L.e("Secondary display rotation not supported on this device");
                        return false;
                    }
                    return (boolean) method.invoke(manager);
            }
        } catch (Exception e) {
            L.e("Could not invoke method", e);
            return false;
        }
    }

    public static void thawRotation(int displayId) {
        try {
            Method method = getThawDisplayRotationMethod();
            switch (thawDisplayRotationMethodVersion) {
                case 0:
                    method.invoke(manager, displayId, "scrcpy#thawRotation");
                    break;
                case 1:
                    method.invoke(manager, displayId);
                    break;
                default:
                    if (displayId != 0) {
                        L.e("Secondary display rotation not supported on this device");
                        return;
                    }
                    method.invoke(manager);
                    break;
            }
        } catch (Exception e) {
            L.e("Could not invoke method", e);
        }
    }

    /**
     * 固定副屏使用用户指定的旋转角度，但不会阻止应用继续请求横竖屏方向。
     */
    public static boolean setFixedToUserRotation(int displayId, boolean fixed) {
        try {
            if (setFixedToUserRotationMethod == null) {
                setFixedToUserRotationMethod = manager.getClass().getMethod(
                        "setFixedToUserRotation", int.class, int.class);
            }
            // IWindowManager: DEFAULT=0, DISABLED=1, ENABLED=2。
            setFixedToUserRotationMethod.invoke(manager, displayId, fixed ? 2 : 0);
            return true;
        } catch (Exception e) {
            L.e("Could not set fixed-to-user rotation for display " + displayId, e);
            return false;
        }
    }

    /**
     * 忽略应用对指定显示器发出的方向请求，避免竖屏应用在横屏副屏中进入兼容信箱模式。
     */
    public static boolean setIgnoreOrientationRequest(int displayId, boolean ignore) {
        try {
            if (setIgnoreOrientationRequestMethod == null) {
                setIgnoreOrientationRequestMethod = manager.getClass().getMethod(
                        "setIgnoreOrientationRequest", int.class, boolean.class);
            }
            setIgnoreOrientationRequestMethod.invoke(manager, displayId, ignore);
            L.i("ignore orientation request changed, displayId=" + displayId
                    + ", ignore=" + ignore);
            return true;
        } catch (Exception e) {
            L.e("Could not set ignore-orientation-request for display " + displayId, e);
            return false;
        }
    }

    /**
     * 控制指定副屏是否显示系统状态栏和导航栏。
     */
    public static boolean setShouldShowSystemDecors(int displayId, boolean shouldShow) {
        try {
            if (setShouldShowSystemDecorsMethod == null) {
                setShouldShowSystemDecorsMethod = manager.getClass().getMethod(
                        "setShouldShowSystemDecors", int.class, boolean.class);
            }
            setShouldShowSystemDecorsMethod.invoke(manager, displayId, shouldShow);
            L.i("system decorations changed, displayId=" + displayId
                    + ", shouldShow=" + shouldShow);
            return true;
        } catch (Exception e) {
            L.e("Could not change system decorations for display " + displayId, e);
            return false;
        }
    }

    /**
     * 读取 WindowManager 最终采用的系统装饰状态。null 表示当前系统没有暴露查询接口。
     */
    public static Boolean shouldShowSystemDecors(int displayId) {
        if (shouldShowSystemDecorsMethodUnavailable) return null;
        try {
            if (shouldShowSystemDecorsMethod == null) {
                shouldShowSystemDecorsMethod = manager.getClass().getMethod(
                        "shouldShowSystemDecors", int.class);
            }
            Object result = shouldShowSystemDecorsMethod.invoke(manager, displayId);
            return result instanceof Boolean ? (Boolean) result : null;
        } catch (NoSuchMethodException e) {
            shouldShowSystemDecorsMethodUnavailable = true;
            L.w("System decorations state query is unavailable");
            return null;
        } catch (Exception e) {
            L.e("Could not query system decorations for display " + displayId, e);
            return null;
        }
    }

    /**
     * 请求 SystemUI 立即收起指定显示器上已经创建的瞬态系统栏。
     */
    public static boolean hideTransientBars(int displayId) {
        if (hideTransientBarsMethodUnavailable) return false;
        try {
            if (hideTransientBarsMethod == null) {
                hideTransientBarsMethod = manager.getClass().getMethod(
                        "hideTransientBars", int.class);
            }
            hideTransientBarsMethod.invoke(manager, displayId);
            L.i("transient system bars hide requested, displayId=" + displayId);
            return true;
        } catch (NoSuchMethodException e) {
            hideTransientBarsMethodUnavailable = true;
            L.w("Transient system bars API is unavailable");
            return false;
        } catch (Exception e) {
            Throwable cause = e.getCause();
            if (cause instanceof SecurityException) {
                hideTransientBarsMethodUnavailable = true;
                L.w("Transient system bars API is not permitted for the shell process");
            } else {
                L.e("Could not hide transient system bars for display " + displayId, e);
            }
            return false;
        }
    }

    /**
     * 查询 WindowManager 是否仍为指定显示器维护导航栏窗口。
     */
    public static Boolean hasNavigationBar(int displayId) {
        if (hasNavigationBarMethodUnavailable) return null;
        try {
            if (hasNavigationBarMethod == null) {
                hasNavigationBarMethod = manager.getClass().getMethod(
                        "hasNavigationBar", int.class);
            }
            Object result = hasNavigationBarMethod.invoke(manager, displayId);
            return result instanceof Boolean ? (Boolean) result : null;
        } catch (NoSuchMethodException e) {
            hasNavigationBarMethodUnavailable = true;
            L.w("Navigation bar state query is unavailable");
            return null;
        } catch (Exception e) {
            L.e("Could not query navigation bar for display " + displayId, e);
            return null;
        }
    }

    public static int getRotation() {
        try {
            return (int) Objects.requireNonNull(getRotationMethod()).invoke(manager);
        } catch (Exception e) {
            L.e("getRotation error", e);
            return -1;
        }
    }

    public static void registerRotationWatcher(IRotationWatcher rotationWatcher, int displayId) {
        try {
            try {
                Objects.requireNonNull(getWatchRotationExMethod()).invoke(manager, rotationWatcher, displayId);
            } catch (Exception e) {
                if (displayId != 0) throw e;
                Objects.requireNonNull(getWatchRotationMethod()).invoke(manager, rotationWatcher);
            }
            rotationWatcher_saved = rotationWatcher;
        } catch (Exception e) {
            L.e("registerRotationWatcher error, retrying", e);
            new Thread(() -> {
                try {
                    Thread.sleep(500);
                    registerRotationWatcher(rotationWatcher, displayId);
                } catch (InterruptedException ignored) {
                }
            }).start();
        }
    }

    public static void removeRotationWatcher() {
        if (rotationWatcher_saved == null) return;
        try {
            Objects.requireNonNull(getRemoveRotationWatcherMethod()).invoke(manager, rotationWatcher_saved);
        } catch (Exception e) {
            L.e("removeRotationWatcher error", e);
        }
    }

    @TargetApi(29)
    public static void registerDisplayFoldListener(IDisplayFoldListener displayFoldListener) {
        try {
            Objects.requireNonNull(getRegisterDisplayFoldListenerMethod()).invoke(manager, displayFoldListener);
        } catch (Exception e) {
            L.e("Could not register display fold listener", e);
        }
    }
}
