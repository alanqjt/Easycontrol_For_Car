package top.eiyooooo.easycontrol.app.adb;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;

import androidx.annotation.Nullable;

import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Resolves the random Android 11+ wireless-debugging TLS port for one phone. */
final class AdbTlsEndpointResolver {
    private static final String SERVICE_TYPE = "_adb-tls-connect._tcp";
    private static final ConcurrentHashMap<String, Endpoint> CACHE = new ConcurrentHashMap<>();

    private AdbTlsEndpointResolver() {
    }

    @Nullable
    static Endpoint getCached(String host) {
        return CACHE.get(normalizeHost(host));
    }

    static void invalidate(String host, Endpoint endpoint) {
        CACHE.remove(normalizeHost(host), endpoint);
    }

    @Nullable
    static synchronized Endpoint resolve(Context context, String expectedHost, long timeoutMs)
            throws InterruptedException {
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN) return null;

        final InetAddress[] expectedAddresses;
        try {
            expectedAddresses = InetAddress.getAllByName(normalizeHost(expectedHost));
        } catch (Exception ignored) {
            return null;
        }

        NsdManager nsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        if (nsdManager == null) return null;

        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Endpoint> result = new AtomicReference<>();
        DiscoverySession session = new DiscoverySession(
                nsdManager, expectedAddresses, completed, result);

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, session);
            completed.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (RuntimeException ignored) {
            return null;
        } finally {
            session.stop();
        }

        Endpoint endpoint = result.get();
        if (endpoint != null) CACHE.put(normalizeHost(expectedHost), endpoint);
        return endpoint;
    }

    /** Old Android NSD permits only one resolve operation at a time. */
    private static final class DiscoverySession implements NsdManager.DiscoveryListener {
        private final NsdManager nsdManager;
        private final InetAddress[] expectedAddresses;
        private final CountDownLatch completed;
        private final AtomicReference<Endpoint> result;
        private final ArrayDeque<NsdServiceInfo> pendingServices = new ArrayDeque<>();
        private boolean resolving;
        private volatile boolean discoveryStarted;
        private volatile boolean stopRequested;

        DiscoverySession(NsdManager nsdManager, InetAddress[] expectedAddresses,
                         CountDownLatch completed, AtomicReference<Endpoint> result) {
            this.nsdManager = nsdManager;
            this.expectedAddresses = expectedAddresses;
            this.completed = completed;
            this.result = result;
        }

        @Override
        public void onDiscoveryStarted(String serviceType) {
            discoveryStarted = true;
            if (stopRequested) stop();
        }

        @Override
        public void onStartDiscoveryFailed(String serviceType, int errorCode) {
            completed.countDown();
        }

        @Override
        public void onDiscoveryStopped(String serviceType) {
            discoveryStarted = false;
        }

        @Override
        public void onStopDiscoveryFailed(String serviceType, int errorCode) {
            discoveryStarted = false;
        }

        @Override
        public synchronized void onServiceFound(NsdServiceInfo serviceInfo) {
            if (!matchesServiceType(serviceInfo.getServiceType()) || result.get() != null) return;
            pendingServices.addLast(serviceInfo);
            resolveNext();
        }

        @Override
        public void onServiceLost(NsdServiceInfo serviceInfo) {
        }

        private synchronized void resolveNext() {
            if (resolving || result.get() != null) return;
            while (!pendingServices.isEmpty()) {
                NsdServiceInfo serviceInfo = pendingServices.removeFirst();
                resolving = true;
                try {
                    nsdManager.resolveService(serviceInfo, new NsdManager.ResolveListener() {
                        @Override
                        public void onResolveFailed(NsdServiceInfo info, int errorCode) {
                            finishResolve(null);
                        }

                        @Override
                        public void onServiceResolved(NsdServiceInfo info) {
                            finishResolve(info);
                        }
                    });
                    return;
                } catch (RuntimeException ignored) {
                    resolving = false;
                }
            }
        }

        private void finishResolve(@Nullable NsdServiceInfo serviceInfo) {
            if (serviceInfo != null) {
                InetAddress host = serviceInfo.getHost();
                if (host != null && matchesAny(host, expectedAddresses)) {
                    result.compareAndSet(null, new Endpoint(host.getHostAddress(), serviceInfo.getPort()));
                    completed.countDown();
                }
            }
            synchronized (this) {
                resolving = false;
                resolveNext();
            }
        }

        void stop() {
            stopRequested = true;
            if (!discoveryStarted) return;
            try {
                nsdManager.stopServiceDiscovery(this);
            } catch (RuntimeException ignored) {
                discoveryStarted = false;
            }
        }
    }

    private static boolean matchesServiceType(String serviceType) {
        return serviceType != null
                && serviceType.replace(".", "").equals(SERVICE_TYPE.replace(".", ""));
    }

    private static boolean matchesAny(InetAddress actual, InetAddress[] expected) {
        for (InetAddress address : expected) {
            if (Arrays.equals(actual.getAddress(), address.getAddress())) return true;
        }
        return false;
    }

    private static String normalizeHost(String host) {
        if (host != null && host.length() > 1 && host.charAt(0) == '['
                && host.charAt(host.length() - 1) == ']') {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    static final class Endpoint {
        final String host;
        final int port;

        Endpoint(String host, int port) {
            this.host = host;
            this.port = port;
        }

        boolean isSame(String otherHost, int otherPort) {
            if (port != otherPort) return false;
            try {
                return Arrays.equals(InetAddress.getByName(host).getAddress(),
                        InetAddress.getByName(normalizeHost(otherHost)).getAddress());
            } catch (Exception ignored) {
                return host.equals(otherHost);
            }
        }
    }
}
