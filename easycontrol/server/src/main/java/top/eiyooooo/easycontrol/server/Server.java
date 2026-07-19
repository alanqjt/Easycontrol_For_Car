package top.eiyooooo.easycontrol.server;

import android.hardware.display.VirtualDisplay;
import android.os.Build;
import android.view.Display;
import org.json.JSONArray;
import org.json.JSONObject;
import top.eiyooooo.easycontrol.server.entity.DisplayInfo;
import top.eiyooooo.easycontrol.server.utils.L;
import top.eiyooooo.easycontrol.server.utils.Workarounds;
import top.eiyooooo.easycontrol.server.wrappers.DisplayManager;
import top.eiyooooo.easycontrol.server.wrappers.ServiceManager;
import top.eiyooooo.easycontrol.server.wrappers.UiModeManager;
import top.eiyooooo.easycontrol.server.wrappers.WindowManager;

import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Scanner;
import java.util.Set;
/**
 * 服务端管理入口。
 * 这个类更像是一个本地命令壳，用来接收调试指令、创建虚拟显示器、
 * 打开应用、调整分辨率，以及把结果回写给上层调用者。
 */
public class Server {
    private static final String READY_RESPONSE = "easycontrol-server-ready-v1";
    // 当前 Server 实例持有的通道对象，负责真正和系统服务交互。
    Channel channel;
    // 标准输出流，用于向调用者回写结果。
    public static DataOutputStream outputStream;

    public static void main(String... args) throws Exception {
        // 让日志更偏向服务端场景，方便排查车机问题。
        L.logMode = 2;
        // 把结果直接写到标准输出，供外层进程读取。
        outputStream = new DataOutputStream(System.out);
        // 应用一些系统兼容性修正，尽量让后面的反射调用稳定。
        Workarounds.apply(0);
        // 初始化系统服务代理，后续很多能力都靠它转发。
        ServiceManager.setManagers();
        // 创建 Server，启动命令处理线程。只有系统代理与命令线程都初始化后，
        // 才发送二进制就绪帧，避免客户端把交互式 shell 的命令回显误判为启动成功。
        Server server = new Server();
        server.postResponse(READY_RESPONSE);
        // 主线程保持存活，直到外部结束进程。
        while (true) {
            Thread.sleep(1000);
        }
    }

    public Server() {
        // 初始化系统通道对象。
        this.channel = new Channel();
        // 后台监听输入命令。
        inputHandler();
    }

    private void inputHandler() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                // 从标准输入读取调试命令。
                Scanner scanner = new Scanner(System.in);
                while (true) {
                    try {
                        if (!scanner.hasNextLine()) {
                            scanner.close();
                            System.exit(0);
                            return;
                        }
                        // 一行一条命令，支持 /path?key=value 这种简易格式。
                        String input = scanner.nextLine();
                        L.d("INPUT: " + input);
                        // /exit 用于快速退出服务端。
                        if (input.startsWith("/exit")) System.exit(0);
                        // 其余以 / 开头的内容都按请求处理。
                        else if (input.startsWith("/")) handleRequest(parseRequest(input));
                        else throw new Exception("Unknown command");
                    } catch (Exception e) {
                        // 输入异常不应该拖垮整个服务端。
                        L.e("consoleInputHandler error", e);
                    }
                }
            }
        }).start();
    }

    private void postResponse(String response) {
        try {
            // 统一空值，避免上层解析时出现歧义。
            if (response == null) response = "null";
            L.d("RESPONSE: " + response);
            // 先写长度，再写内容，方便上层按字节读取。
            byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
            outputStream.writeInt(responseBytes.length);
            outputStream.writeInt(responseBytes.length);
            outputStream.write(responseBytes);
            outputStream.flush();
        } catch (Exception e) {
            // 输出失败时只记录，不直接中断业务流程。
            L.e("postResponse error", e);
        }
    }

    private static HashMap<String, String> parseRequest(String input) {
        // 解析成 key-value 结构，便于后面按参数名读取。
        HashMap<String, String> request = new HashMap<>();

        // 先取路径，再取查询参数。
        String[] parts = input.split("\\?", 2);
        String path = parts[0];
        request.put("request", path);
        if (parts.length == 1) return request;

        // 后半段是查询参数，按照 & 和 = 拆开。
        String params = parts[1];
        String[] keyValuePairs = params.split("&");
        for (String pair : keyValuePairs) {
            String[] keyValue = pair.split("=", 2);
            if (keyValue.length != 2) continue;
            String key = keyValue[0];
            String value = keyValue[1];
            request.put(key, value);
        }
        return request;
    }

    // 保存创建过的虚拟显示器，方便后面 resize / release。
    Map<Integer, VirtualDisplay> cache = new HashMap<>();
    private final Object embeddedDisplayPolicyLock = new Object();
    private final Set<Integer> embeddedDisplayIds = new HashSet<>();

    private void handleRequest(HashMap<String, String> request) {
        try {
            // 根据 request 字段分发到不同功能。
            switch (Objects.requireNonNull(request.get("request"))) {
                case "/getPhoneInfo": {
                    // 返回设备基础信息。
                    postResponse(Channel.getPhoneInfo().toString());
                    break;
                }
                case "/getRecentTasks": {
                    // 获取最近任务列表，参数都允许缺省。
                    String line1 = request.get("maxNum");
                    String line2 = request.get("flags");
                    String line3 = request.get("userId");

                    int maxNum = 25;
                    if (line1 != null) maxNum = Integer.parseInt(line1);
                    int flags = 0;
                    if (line2 != null) flags = Integer.parseInt(line2);
                    int userId = 0;
                    if (line3 != null) userId = Integer.parseInt(line3);

                    postResponse(channel.getRecentTasksJson(maxNum, flags, userId).toString());
                    break;
                }
                case "/getIcon": {
                    String packageName = request.get("package");
                    if (packageName == null) throw new Exception("parameter 'package' not found");
                    postResponse(channel.Bitmap2file(packageName));
                    break;
                }
                case "/getAllAppInfo": {
                    String line = request.get("app_type");
                    if (line == null) throw new Exception("parameter 'app_type' not found");
                    int appType = Integer.parseInt(line);
                    postResponse(channel.getAllAppInfo(appType));
                    break;
                }
                case "/getAppDetail": {
                    String packageName = request.get("package");
                    if (packageName == null) throw new Exception("parameter 'package' not found");
                    postResponse(channel.getAppDetail(packageName));
                    break;
                }
                case "/getAppAudioInfo": {
                    String packageName = request.get("package");
                    if (packageName == null) throw new Exception("parameter 'package' not found");
                    postResponse(channel.getAppAudioInfo(packageName).toString());
                    break;
                }
                case "/getAppMainActivity": {
                    String packageName = request.get("package");
                    if (packageName == null) throw new Exception("parameter 'package' not found");
                    postResponse(channel.getAppMainActivity(packageName));
                    break;
                }
                case "/createVirtualDisplay": {
                    // Android 11 以下不支持这里的虚拟显示创建逻辑。
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R)
                        throw new Exception("Virtual display is not supported before Android 11");
                    // width/height/density 都是可选参数。
                    String line1 = request.get("width");
                    String line2 = request.get("height");
                    String line3 = request.get("density");
                    String profile = request.get("profile");
                    if (profile == null || profile.isEmpty()) profile = "standard-phone";
                    if (line1 != null && line2 == null)
                        throw new Exception("parameter 'width' found, but 'height' not found");
                    if (line1 == null && line2 != null)
                        throw new Exception("parameter 'height' found, but 'width' not found");

                    // 读取默认屏幕参数，作为新虚拟屏幕的参考。
                    DisplayInfo defaultDisplay = DisplayManager.getDisplayInfo(Display.DEFAULT_DISPLAY);
                    if (defaultDisplay == null) throw new Exception("Default display not found");
                    int width, height, density;
                    if (line1 != null) {
                        width = Integer.parseInt(line1);
                        height = Integer.parseInt(line2);
                    } else {
                        // logicalWidth/logicalHeight 已包含当前旋转，不能再次交换。
                        width = defaultDisplay.size.first;
                        height = defaultDisplay.size.second;
                    }
                    if (line3 != null) density = Integer.parseInt(line3);
                    else density = defaultDisplay.density;
                    if (width <= 0 || height <= 0 || density <= 0)
                        throw new Exception("Invalid virtual display configuration");

                    boolean embeddedWide = "embedded-wide".equals(profile);
                    // 真正创建虚拟显示器。
                    VirtualDisplay display = channel.createVirtualDisplay(
                            width, height, density, embeddedWide);
                    if (display == null) throw new Exception("Failed to create virtual display");
                    int createdDisplayId = display.getDisplay().getDisplayId();
                    // 缓存下来，后续可以重设尺寸或释放。
                    cache.put(createdDisplayId, display);
                    boolean fixedToUserRotation;
                    boolean ignoreOrientationRequest;
                    boolean systemDecorationsRequestApplied;
                    Boolean systemDecorationsShown;
                    boolean transientBarsHideRequested;
                    Boolean navigationBarPresent;
                    if (embeddedWide) {
                        synchronized (embeddedDisplayPolicyLock) {
                            embeddedDisplayIds.add(createdDisplayId);
                        }
                        EmbeddedDisplayPolicyResult policy = applyEmbeddedDisplayPolicy(
                                createdDisplayId, "create");
                        fixedToUserRotation = policy.fixedToUserRotation;
                        ignoreOrientationRequest = policy.ignoreOrientationRequest;
                        systemDecorationsRequestApplied = policy.systemDecorationsRequestApplied;
                        systemDecorationsShown = policy.systemDecorationsShown;
                        transientBarsHideRequested = policy.transientBarsHideRequested;
                        navigationBarPresent = policy.navigationBarPresent;
                        scheduleEmbeddedDisplayPolicyRefresh(createdDisplayId, "create");
                    } else {
                        WindowManager.freezeRotation(createdDisplayId, 0);
                        fixedToUserRotation = WindowManager.setFixedToUserRotation(
                                createdDisplayId, true);
                        ignoreOrientationRequest = false;
                        systemDecorationsRequestApplied = false;
                        systemDecorationsShown = null;
                        transientBarsHideRequested = false;
                        navigationBarPresent = null;
                    }
                    DisplayInfo createdDisplay = DisplayManager.getDisplayInfo(createdDisplayId);
                    L.i("virtual display created"
                            + ", profile=" + profile
                            + ", source=" + defaultDisplay.size.first + "x" + defaultDisplay.size.second
                            + "@" + defaultDisplay.density + "dpi"
                            + ", sourceRotation=" + defaultDisplay.rotation
                            + ", requested=" + width + "x" + height + "@" + density + "dpi"
                            + ", actual=" + (createdDisplay == null ? "unknown"
                            : createdDisplay.size.first + "x" + createdDisplay.size.second
                            + "@" + createdDisplay.density + "dpi")
                            + ", actualRotation=" + (createdDisplay == null ? "unknown" : createdDisplay.rotation)
                            + ", fixedToUserRotation=" + fixedToUserRotation
                            + ", ignoreOrientationRequest=" + ignoreOrientationRequest
                            + ", systemDecorationsRequestApplied=" + systemDecorationsRequestApplied
                            + ", systemDecorationsShown=" + formatNullableBoolean(systemDecorationsShown)
                            + ", transientBarsHideRequested=" + transientBarsHideRequested
                            + ", navigationBarPresent=" + formatNullableBoolean(navigationBarPresent));
                    int[] displayIds = DisplayManager.getDisplayIds();
                    for (int displayId : displayIds) {
                        L.d(">>>display -> " + displayId);
                    }
                    postResponse("success create display, fixedToUserRotation="
                            + fixedToUserRotation
                            + ", ignoreOrientationRequest=" + ignoreOrientationRequest
                            + ", systemDecorationsRequestApplied=" + systemDecorationsRequestApplied
                            + ", systemDecorationsShown=" + formatNullableBoolean(systemDecorationsShown)
                            + ", transientBarsHideRequested=" + transientBarsHideRequested
                            + ", navigationBarPresent=" + formatNullableBoolean(navigationBarPresent)
                            + ", id -> " + createdDisplayId);
                    break;
                }
                case "/resizeDisplay": {
                    // 修改系统显示或虚拟显示器尺寸。
                    String line1 = request.get("id");
                    String line2 = request.get("width");
                    String line3 = request.get("height");
                    String line4 = request.get("density");
                    int id = 0;
                    if (line1 != null) id = Integer.parseInt(line1);
                    DisplayInfo display = DisplayManager.getDisplayInfo(id);
                    if (display == null) throw new Exception("specified display not found");

                    if (line2 == null && line3 == null && line4 == null)
                        throw new Exception("please give parameter 'width'&'height' or 'density'");
                    if (line2 != null && line3 == null)
                        throw new Exception("parameter 'width' found, but 'height' not found");
                    if (line2 == null && line3 != null)
                        throw new Exception("parameter 'height' found, but 'width' not found");

                    int width, height, density;
                    if (line2 != null) {
                        width = Integer.parseInt(line2);
                        height = Integer.parseInt(line3);
                    } else {
                        width = display.size.first;
                        height = display.size.second;
                    }
                    if (line4 != null) density = Integer.parseInt(line4);
                    else density = display.density;

                    if (id == 0) {
                        // id 为 0 时表示改的是系统主屏参数。
                        if (line2 != null) Channel.execReadOutput("wm size " + width + "x" + height);
                        if (line4 != null) Channel.execReadOutput("wm density " + density);
                        postResponse("success resize display, id -> " + id);
                    } else {
                        // 非 0 时说明是本类创建的虚拟显示器。
                        VirtualDisplay virtualDisplay = cache.get(id);
                        if (virtualDisplay == null)
                            throw new Exception("specified virtual display not found, it might not be created by this server");
                        virtualDisplay.resize(width, height, density);
                        DisplayInfo resizedDisplay = waitForDisplayConfiguration(
                                id, width, height, density, 1200);
                        int refreshedTasks = channel.relayoutTasksOnDisplay(id);
                        applyEmbeddedDisplayPolicy(id, "resize-and-relayout");
                        scheduleEmbeddedDisplayPolicyRefresh(id, "resize-and-relayout");
                        postResponse("success resize display, id -> " + id
                                + ", actual=" + (resizedDisplay == null ? "unknown"
                                : resizedDisplay.size.first + "x" + resizedDisplay.size.second
                                + "@" + resizedDisplay.density + "dpi")
                                + ", refreshedTasks=" + refreshedTasks);
                    }
                    break;
                }
                case "/releaseVirtualDisplay": {
                    // 释放虚拟显示器之前，先把对应任务移回默认屏幕。
                    String id = request.get("id");
                    if (id == null) throw new Exception("parameter 'id' not found");
                    VirtualDisplay display = cache.get(Integer.parseInt(id));
                    if (display == null)
                        throw new Exception("specified virtual display not found, it might not be created by this server");
                    JSONObject tasks = channel.getRecentTasksJson(25, 0, 0);
                    JSONArray tasks_data = tasks.getJSONArray("data");
                    for (int i = 0; i < tasks_data.length(); i++) {
                        JSONObject task = tasks_data.getJSONObject(i);
                        if (id.equals(String.valueOf(task.getInt("displayId")))) {
                            try {
                                Channel.execReadOutput("am display move-stack " + task.getInt("id") + " 0");
                            } catch (Exception ignored) {
                            }
                        }
                    }
                    int displayId = Integer.parseInt(id);
                    synchronized (embeddedDisplayPolicyLock) {
                        embeddedDisplayIds.remove(displayId);
                    }
                    display.release();
                    cache.remove(displayId);
                    postResponse("success release display, id -> " + id);
                    break;
                }
                case "/openAppByPackage": {
                    // 按包名打开应用，也可以指定 activity 和显示器。
                    String packageName = request.get("package");
                    String activity = request.get("activity");
                    String id = request.get("displayId");
                    boolean forceLandscape = "1".equals(request.get("forceLandscape"));

                    if (packageName == null) throw new Exception("parameter 'package' not found");
                    if (activity == null) activity = channel.getAppMainActivity(packageName);
                    if (id == null) id = "0";

                    int displayId = Integer.parseInt(id);
                    boolean orientationOverrideApplied = false;
                    if (forceLandscape) {
                        // 首次启动前就让 Activity 跟随横向虚拟屏，避免系统套用竖屏兼容信箱。
                        orientationOverrideApplied = Channel.setUserOrientationOverride(packageName, true);
                        L.i("open app orientation policy"
                                + ", package=" + packageName
                                + ", displayId=" + displayId
                                + ", forceLandscape=true"
                                + ", applied=" + orientationOverrideApplied);
                    }
                    String error = channel.openApp(packageName, activity, displayId);
                    if (error != null) throw new Exception(error);
                    applyEmbeddedDisplayPolicy(displayId, "open-app");
                    scheduleEmbeddedDisplayPolicyRefresh(displayId, "open-app");
                    postResponse("success,orientationOverride=" + orientationOverrideApplied);
                    break;
                }
                case "/moveTaskToDisplay": {
                    String taskId = request.get("taskId");
                    String displayId = request.get("displayId");
                    String packageName = request.get("package");
                    boolean recreate = "1".equals(request.get("recreate"));
                    if (taskId == null) throw new Exception("parameter 'taskId' not found");
                    if (displayId == null) throw new Exception("parameter 'displayId' not found");
                    int targetDisplayId = Integer.parseInt(displayId);
                    String strategy = channel.moveTaskToDisplay(
                            Integer.parseInt(taskId), targetDisplayId, packageName,
                            recreate);
                    applyEmbeddedDisplayPolicy(targetDisplayId, "move-task");
                    scheduleEmbeddedDisplayPolicyRefresh(targetDisplayId, "move-task");
                    postResponse("success move task, strategy=" + strategy);
                    break;
                }
                case "/setOrientationOverride": {
                    String packageName = request.get("package");
                    String enabled = request.get("enabled");
                    if (packageName == null) throw new Exception("parameter 'package' not found");
                    if (enabled == null) throw new Exception("parameter 'enabled' not found");
                    boolean applied = Channel.setUserOrientationOverride(
                            packageName, "1".equals(enabled));
                    postResponse("success orientation override, applied=" + applied);
                    break;
                }
                case "/stopAppByPackage": {
                    String packageName = request.get("package");
                    if (packageName == null) throw new Exception("parameter 'package' not found");
                    String cmd = "am force-stop " + packageName;
                    L.d("stopActivity activity cmd: " + cmd);
                    Channel.execReadOutput(cmd);
                    postResponse("success");
                    break;
                }
                case "/getDisplayInfo": {
                    int[] displayIds = DisplayManager.getDisplayIds();
                    JSONArray jsonArray = new JSONArray();
                    for (int displayId : displayIds) {
                        DisplayInfo display = DisplayManager.getDisplayInfo(displayId);
                        if (display == null) continue;
                        JSONObject jsonObject = new JSONObject();
                        jsonObject.put("id", displayId);
                        jsonObject.put("width", display.size.first);
                        jsonObject.put("height", display.size.second);
                        jsonObject.put("density", display.density);
                        jsonObject.put("rotation", display.rotation);
                        jsonArray.put(jsonObject);
                    }
                    postResponse(jsonArray.toString());
                    break;
                }
                case "/runShell": {
                    String cmd = request.get("cmd");
                    if (cmd == null) throw new Exception("parameter 'cmd' not found");
                    L.d("runShell cmd: " + cmd);
                    postResponse(Channel.execReadOutput(cmd));
                    break;
                }
                case "/getNightMode": {
                    int nightMode = UiModeManager.getNightMode();
                    L.d("nightMode: " + nightMode);
                    postResponse(String.valueOf(nightMode));
                    break;
                }
                case "/setNightMode": {
                    String nightMode = request.get("nightMode");
                    if (nightMode == null) throw new Exception("parameter 'nightMode' not found");
                    UiModeManager.setNightMode(Integer.parseInt(nightMode));
                    postResponse("success");
                    break;
                }
                default:
                    postResponse("Unknown request");
            }
        } catch (Exception e) {
            postResponse(e.getMessage());
            L.e("handleRequest error", e);
        }
    }

    private static DisplayInfo waitForDisplayConfiguration(
            int displayId, int width, int height, int density, long timeoutMs)
            throws InterruptedException {
        long deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs;
        DisplayInfo current;
        do {
            current = DisplayManager.getDisplayInfo(displayId);
            if (current != null
                    && current.size.first == width
                    && current.size.second == height
                    && current.density == density) return current;
            Thread.sleep(50);
        } while (android.os.SystemClock.elapsedRealtime() < deadline);
        L.w("virtual display resize did not settle before timeout"
                + ", displayId=" + displayId
                + ", requested=" + width + "x" + height + "@" + density + "dpi"
                + ", actual=" + (current == null ? "unknown"
                : current.size.first + "x" + current.size.second
                + "@" + current.density + "dpi"));
        return current;
    }

    private EmbeddedDisplayPolicyResult applyEmbeddedDisplayPolicy(int displayId, String reason) {
        synchronized (embeddedDisplayPolicyLock) {
            if (!embeddedDisplayIds.contains(displayId)) return null;
            WindowManager.freezeRotation(displayId, 0);
            boolean fixedToUserRotation = WindowManager.setFixedToUserRotation(displayId, true);
            boolean ignoreOrientationRequest = WindowManager.setIgnoreOrientationRequest(
                    displayId, true);
            boolean systemDecorationsRequestApplied = WindowManager.setShouldShowSystemDecors(
                    displayId, false);
            boolean transientBarsHideRequested = WindowManager.hideTransientBars(displayId);
            Boolean systemDecorationsShown = WindowManager.shouldShowSystemDecors(displayId);
            Boolean navigationBarPresent = WindowManager.hasNavigationBar(displayId);
            L.i("embedded display policy applied"
                    + ", displayId=" + displayId
                    + ", reason=" + reason
                    + ", fixedToUserRotation=" + fixedToUserRotation
                    + ", ignoreOrientationRequest=" + ignoreOrientationRequest
                    + ", requestedSystemDecors=false"
                    + ", requestApplied=" + systemDecorationsRequestApplied
                    + ", actualSystemDecors=" + formatNullableBoolean(systemDecorationsShown)
                    + ", transientBarsHideRequested=" + transientBarsHideRequested
                    + ", navigationBarPresent=" + formatNullableBoolean(navigationBarPresent));
            return new EmbeddedDisplayPolicyResult(
                    fixedToUserRotation,
                    ignoreOrientationRequest,
                    systemDecorationsRequestApplied,
                    systemDecorationsShown,
                    transientBarsHideRequested,
                    navigationBarPresent);
        }
    }

    private void scheduleEmbeddedDisplayPolicyRefresh(int displayId, String reason) {
        synchronized (embeddedDisplayPolicyLock) {
            if (!embeddedDisplayIds.contains(displayId)) return;
        }
        Thread thread = new Thread(() -> {
            int[] delaysMs = {250, 750, 1500};
            for (int i = 0; i < delaysMs.length; i++) {
                try {
                    Thread.sleep(delaysMs[i]);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (applyEmbeddedDisplayPolicy(displayId,
                        reason + "+" + (i == 0 ? 250 : i == 1 ? 1000 : 2500) + "ms") == null) {
                    return;
                }
            }
        }, "easycontrol-display-policy-" + displayId);
        thread.setDaemon(true);
        thread.start();
    }

    private static String formatNullableBoolean(Boolean value) {
        return value == null ? "unknown" : String.valueOf(value);
    }

    private static final class EmbeddedDisplayPolicyResult {
        final boolean fixedToUserRotation;
        final boolean ignoreOrientationRequest;
        final boolean systemDecorationsRequestApplied;
        final Boolean systemDecorationsShown;
        final boolean transientBarsHideRequested;
        final Boolean navigationBarPresent;

        EmbeddedDisplayPolicyResult(boolean fixedToUserRotation,
                                    boolean ignoreOrientationRequest,
                                    boolean systemDecorationsRequestApplied,
                                    Boolean systemDecorationsShown,
                                    boolean transientBarsHideRequested,
                                    Boolean navigationBarPresent) {
            this.fixedToUserRotation = fixedToUserRotation;
            this.ignoreOrientationRequest = ignoreOrientationRequest;
            this.systemDecorationsRequestApplied = systemDecorationsRequestApplied;
            this.systemDecorationsShown = systemDecorationsShown;
            this.transientBarsHideRequested = transientBarsHideRequested;
            this.navigationBarPresent = navigationBarPresent;
        }
    }
}
