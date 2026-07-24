package top.eiyooooo.easycontrol.server.entity;

import android.os.Build;

public final class Options {
    public static String socketName = "easycontrol_for_car_scrcpy";
    public static boolean isAudio = true;
    public static boolean isVideo = true;
    public static int maxSize = 1600;
    public static int maxVideoBit = 4000000;
    public static int maxFps = 60;
    public static int displayId = 0;
    public static boolean keepAwake = true;
    public static boolean TurnOnScreenIfStart = true;
    public static boolean TurnOffScreenIfStart = false;
    public static boolean TurnOffScreenIfStop = false;
    public static boolean TurnOnScreenIfStop = true;
    public static boolean useH265 = true;
    public static boolean useOpus = true;
    public static int mirrorMode = 0;
    // 大于 0 时只采集该 UID 的播放音频；-1 保持原来的整机混音采集。
    public static int audioUid = -1;
    // UID 定向采集不可用时，是否允许退回整机混音。
    public static boolean audioFallback = true;
    // 1 为旧版单路音频帧，2 为每帧携带 navigation/media 角色的新协议。
    public static int audioProtocol = 1;
    // 单路应用流转的音频角色：0 为媒体，1 为导航。
    public static int audioRole = 0;
    // 直接投屏时按导航 UID 拆成导航、媒体两路音频。
    public static boolean audioSplit = false;

    public static void parse(String... args) {
        for (String arg : args) {
            int equalIndex = arg.indexOf('=');
            if (equalIndex == -1) throw new IllegalArgumentException("参数格式错误");
            String key = arg.substring(0, equalIndex);
            String value = arg.substring(equalIndex + 1);
            switch (key) {
                case "socketName":
                    if (!value.matches("[A-Za-z0-9_.-]{1,80}")) {
                        throw new IllegalArgumentException("socketName 参数非法");
                    }
                    socketName = value;
                    break;
                case "isAudio":
                    isAudio = Integer.parseInt(value) == 1;
                    break;
                case "isVideo":
                    isVideo = Integer.parseInt(value) == 1;
                    break;
                case "maxSize":
                    maxSize = Integer.parseInt(value);
                    break;
                case "maxFps":
                    maxFps = Integer.parseInt(value);
                    break;
                case "maxVideoBit":
                    maxVideoBit = Integer.parseInt(value) * 1000000;
                    break;
                case "displayId":
                    displayId = Integer.parseInt(value);
                    break;
                case "keepAwake":
                    keepAwake = Integer.parseInt(value) == 1;
                    break;
                case "ScreenMode":
                    int ScreenMode = Integer.parseInt(value);
                    TurnOnScreenIfStart = (ScreenMode / 1000) % 10 == 1;
                    TurnOffScreenIfStart = (ScreenMode / 100) % 10 == 1;
                    TurnOffScreenIfStop = (ScreenMode / 10) % 10 == 1;
                    TurnOnScreenIfStop = ScreenMode % 10 == 1;
                    break;
                case "useH265":
                    useH265 = Integer.parseInt(value) == 1;
                    break;
                case "useOpus":
                    useOpus = Integer.parseInt(value) == 1;
                    break;
                case "mirrorMode":
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        mirrorMode = 0;
                        break;
                    }
                    mirrorMode = Integer.parseInt(value);
                    break;
                case "audioUid":
                    audioUid = Integer.parseInt(value);
                    break;
                case "audioFallback":
                    audioFallback = Integer.parseInt(value) == 1;
                    break;
                case "audioProtocol":
                    audioProtocol = Integer.parseInt(value);
                    break;
                case "audioRole":
                    audioRole = Integer.parseInt(value) == 1 ? 1 : 0;
                    break;
                case "audioSplit":
                    audioSplit = Integer.parseInt(value) == 1;
                    break;
            }
        }
    }
}
