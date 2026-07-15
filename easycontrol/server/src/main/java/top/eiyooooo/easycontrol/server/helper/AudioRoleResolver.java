package top.eiyooooo.easycontrol.server.helper;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.os.Build;

import top.eiyooooo.easycontrol.server.utils.L;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 直接投屏音频角色解析器。
 * 导航应用在连接建立前就按包类别和包名预热，避免等播报开始后才创建采集链路而吞掉开头。
 */
public final class AudioRoleResolver {
    public static final int ROLE_MEDIA = 0;
    public static final int ROLE_NAVIGATION = 1;
    private static final String[] KNOWN_NAVIGATION_PACKAGES = {
            "com.autonavi.minimap",
            "com.baidu.BaiduMap",
            "com.tencent.map",
            "com.google.android.apps.maps",
            "com.waze"
    };

    public static final class NavigationTargets {
        public final Set<Integer> uids;
        public final List<String> packages;

        private NavigationTargets(Set<Integer> uids, List<String> packages) {
            this.uids = Collections.unmodifiableSet(uids);
            this.packages = Collections.unmodifiableList(packages);
        }
    }

    private AudioRoleResolver() {
    }

    public static NavigationTargets findInstalledNavigationApps() {
        Set<Integer> uids = new LinkedHashSet<>();
        List<String> packages = new ArrayList<>();
        try {
            PackageManager packageManager = FakeContext.get().getPackageManager();
            List<ApplicationInfo> applications = packageManager.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS);
            for (ApplicationInfo applicationInfo : applications) {
                if (!isNavigationApp(applicationInfo.packageName, applicationInfo.category)) continue;
                addNavigationTarget(applicationInfo, uids, packages);
            }
            // 某些厂商 PackageManager 会过滤 installedApplications，再按常见包名补查一次。
            for (String packageName : KNOWN_NAVIGATION_PACKAGES) {
                try {
                    addNavigationTarget(packageManager.getApplicationInfo(packageName, 0), uids, packages);
                } catch (PackageManager.NameNotFoundException ignored) {
                }
            }
        } catch (Exception e) {
            L.w("Cannot scan installed navigation applications", e);
        }
        L.i("direct audio navigation targets=" + packages);
        return new NavigationTargets(uids, packages);
    }

    private static void addNavigationTarget(ApplicationInfo applicationInfo, Set<Integer> uids, List<String> packages) {
        if (applicationInfo.uid <= 0 || uids.contains(applicationInfo.uid)) return;
        uids.add(applicationInfo.uid);
        packages.add(applicationInfo.packageName + "(uid=" + applicationInfo.uid + ")");
    }

    private static boolean isNavigationApp(String packageName, int category) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && category == ApplicationInfo.CATEGORY_MAPS) return true;
        String value = packageName == null ? "" : packageName.toLowerCase(Locale.ROOT);
        return value.contains("autonavi")
                || value.contains("baidumap")
                || value.contains("tencent.map")
                || value.contains("google.android.apps.maps")
                || value.contains("waze");
    }

    /**
     * 只用于诊断：把正在播放的 UID、包名和最终角色写入服务端日志。
     */
    public static Thread startActivePlaybackMonitor(Set<Integer> navigationUids) {
        Thread thread = new Thread(() -> monitorActivePlayback(navigationUids), "easycontrol_audio_role_monitor");
        thread.start();
        return thread;
    }

    private static void monitorActivePlayback(Set<Integer> navigationUids) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        try {
            AudioManager audioManager = (AudioManager) FakeContext.get().getSystemService(android.content.Context.AUDIO_SERVICE);
            PackageManager packageManager = FakeContext.get().getPackageManager();
            Method getClientUid = AudioPlaybackConfiguration.class.getDeclaredMethod("getClientUid");
            getClientUid.setAccessible(true);
            Set<String> previous = Collections.emptySet();
            while (!Thread.currentThread().isInterrupted()) {
                Set<String> current = new LinkedHashSet<>();
                for (AudioPlaybackConfiguration configuration : audioManager.getActivePlaybackConfigurations()) {
                    int uid = (int) getClientUid.invoke(configuration);
                    AudioAttributes attributes = configuration.getAudioAttributes();
                    int usage = attributes == null ? AudioAttributes.USAGE_UNKNOWN : attributes.getUsage();
                    int role = navigationUids.contains(uid)
                            || usage == AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
                            ? ROLE_NAVIGATION : ROLE_MEDIA;
                    String[] names = packageManager.getPackagesForUid(uid);
                    String name = names == null || names.length == 0 ? "uid:" + uid : names[0];
                    current.add("direct audio active package=" + name
                            + ", uid=" + uid
                            + ", usage=" + usage
                            + ", role=" + (role == ROLE_NAVIGATION ? "navigation" : "media"));
                }
                for (String entry : current) {
                    if (!previous.contains(entry)) L.i(entry);
                }
                previous = current;
                Thread.sleep(500);
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            L.w("Cannot monitor active playback roles", e);
        }
    }
}
