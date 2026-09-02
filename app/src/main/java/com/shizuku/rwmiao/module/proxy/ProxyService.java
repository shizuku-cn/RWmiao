package com.shizuku.rwmiao.module.proxy;

import com.shizuku.rwmiao.module.RWmiaoModule;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedInterface;

import static com.shizuku.rwmiao.config.SettingsContract.KEY_PROXY_AUTO_FIND;
import static com.shizuku.rwmiao.config.SettingsContract.KEY_PROXY_LIST_CACHE;
import static com.shizuku.rwmiao.config.SettingsContract.KEY_PROXY_SELECTED_LIST;
import static com.shizuku.rwmiao.config.SettingsContract.KEY_PROXY_SERVICE_ENABLED;

public final class ProxyService {
    private static final String TAG = "RWmiao-Proxy";
    private static final int SOCKS_VERSION = 5;
    private static final int METHOD_NO_AUTH = 0;
    private static final int COMMAND_CONNECT = 1;
    private static final int ADDRESS_IPV4 = 1;
    private static final int ADDRESS_DOMAIN = 3;
    private static final int ADDRESS_IPV6 = 4;
    private static final int MAX_PROBE_CANDIDATES = 8;
    private static final int MAX_IP_QUERY_CANDIDATES = 3;
    private static final long HEALTHY_CACHE_MS = 60_000L;
    private static final long FAILED_CACHE_MS = 30_000L;
    private static final String[] PUBLIC_IP_PROVIDERS = new String[]{
            "https://api.ipify.org",
            "https://api64.ipify.org",
            "https://ifconfig.me/ip"
    };
    private static final ThreadLocal<Boolean> BYPASS =
            new ThreadLocal<Boolean>() {
                @Override protected Boolean initialValue() {
                    return Boolean.FALSE;
                }
            };

    private final RWmiaoModule host;
    private volatile boolean requested;
    private volatile boolean enabled;
    private volatile boolean autoFind;
    private volatile List<ProxyPool.Endpoint> selected = Collections.emptyList();
    private volatile XposedInterface.HookHandle connectHook;
    private final Map<String, Long> healthyUntil = new ConcurrentHashMap<>();
    private final Map<String, Long> failedUntil = new ConcurrentHashMap<>();
    private java.lang.reflect.Method connectMethod;

    public ProxyService(RWmiaoModule host) {
        this.host = host;
    }

    public void install() throws Throwable {
        connectMethod = host.findCompatibleMethod(
                Socket.class, "connect", SocketAddress.class, int.class);
        if (connectMethod == null) {
            throw new NoSuchMethodException("java.net.Socket.connect(SocketAddress,int)");
        }
        refreshSettings();
    }

    public synchronized void refreshSettings() throws Throwable {
        boolean shouldEnable = host.selectionActionEnabled(KEY_PROXY_SERVICE_ENABLED);
        requested = shouldEnable;
        autoFind = host.preferenceBoolean(KEY_PROXY_AUTO_FIND, true);
        selected = ProxyPool.deserialize(host.preferenceString(KEY_PROXY_SELECTED_LIST, ""));
        healthyUntil.clear();
        failedUntil.clear();
        boolean hasCachedFallback = !ProxyPool.deserialize(
                host.preferenceString(KEY_PROXY_LIST_CACHE, "")).isEmpty();
        enabled = shouldEnable && (!selected.isEmpty() || (autoFind && hasCachedFallback));
        if (enabled) {
            if (connectHook == null) {
                connectHook = host.hookExecutable(connectMethod, chain -> {
                    if (!enabled || Boolean.TRUE.equals(BYPASS.get())) {
                        return chain.proceed();
                    }
                    Object argument = chain.getArg(0);
                    if (!(chain.getThisObject() instanceof Socket)
                            || !(argument instanceof InetSocketAddress)) {
                        return chain.proceed();
                    }
                    Socket socket = (Socket) chain.getThisObject();
                    InetSocketAddress target = (InetSocketAddress) argument;
                    if (!isEligible(socket, target)) return chain.proceed();

                    ProxyPool.Endpoint endpoint = chooseEndpoint(target);
                    if (endpoint == null) return chain.proceed();
                    try {
                        connectThroughProxy(socket, target, endpoint);
                        return null;
                    } catch (Throwable failure) {
                        healthyUntil.remove(endpoint.key());
                        failedUntil.put(endpoint.key(),
                                System.currentTimeMillis() + FAILED_CACHE_MS);
                        closeQuietly(socket);
                        host.log(5, TAG, "SOCKS5 connection failed: " + endpoint.display(), failure);
                        throw failure;
                    }
                });
            }
        } else {
            enabled = false;
            if (connectHook != null) {
                try {
                    connectHook.unhook();
                } catch (Throwable ignored) {
                }
                connectHook = null;
            }
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isRequested() {
        return requested;
    }

    public String queryPublicIp() {
        if (!enabled) return null;
        List<ProxyPool.Endpoint> candidates = collectQueryCandidates();
        for (ProxyPool.Endpoint endpoint : candidates) {
            for (String provider : PUBLIC_IP_PROVIDERS) {
                String observedIp = queryPublicIp(endpoint, provider);
                if (observedIp != null) {
                    healthyUntil.put(endpoint.key(),
                            System.currentTimeMillis() + HEALTHY_CACHE_MS);
                    return observedIp;
                }
            }
            healthyUntil.remove(endpoint.key());
            failedUntil.put(endpoint.key(),
                    System.currentTimeMillis() + FAILED_CACHE_MS);
        }
        return null;
    }

    private List<ProxyPool.Endpoint> collectQueryCandidates() {
        Map<String, ProxyPool.Endpoint> candidates = new LinkedHashMap<>();
        for (ProxyPool.Endpoint endpoint : selected) {
            candidates.put(endpoint.key(), endpoint);
        }
        if (autoFind) {
            for (ProxyPool.Endpoint endpoint : ProxyPool.deserialize(
                    host.preferenceString(KEY_PROXY_LIST_CACHE, ""))) {
                candidates.put(endpoint.key(), endpoint);
            }
        }

        List<ProxyPool.Endpoint> result = new ArrayList<>(MAX_IP_QUERY_CANDIDATES);
        long now = System.currentTimeMillis();
        int probed = 0;
        for (ProxyPool.Endpoint endpoint : candidates.values()) {
            Long failed = failedUntil.get(endpoint.key());
            if (failed != null && failed > now) continue;
            Long healthy = healthyUntil.get(endpoint.key());
            if ((healthy != null && healthy > now)
                    || (probed++ < MAX_PROBE_CANDIDATES && probeEndpoint(
                    endpoint, 2_500))) {
                result.add(endpoint);
                if (result.size() >= MAX_IP_QUERY_CANDIDATES) break;
                if (healthy == null) {
                    healthyUntil.put(endpoint.key(), now + HEALTHY_CACHE_MS);
                }
            }
        }
        return result;
    }

    private String queryPublicIp(ProxyPool.Endpoint endpoint, String provider) {
        HttpURLConnection connection = null;
        boolean previousBypass = Boolean.TRUE.equals(BYPASS.get());
        try {
            BYPASS.set(Boolean.TRUE);
            connection = (HttpURLConnection) new URL(provider).openConnection(
                    new java.net.Proxy(
                            java.net.Proxy.Type.SOCKS,
                            new InetSocketAddress(endpoint.host, endpoint.port)));
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(5_000);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "text/plain");
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) return null;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), StandardCharsets.UTF_8))) {
                String value = reader.readLine();
                return normalizePublicIp(value);
            }
        } catch (Throwable ignored) {
            return null;
        } finally {
            BYPASS.set(previousBypass);
            if (connection != null) connection.disconnect();
        }
    }

    private static String normalizePublicIp(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.isEmpty() || value.length() > 64) return null;
        try {
            InetAddress parsed = InetAddress.getByName(value);
            if (!(parsed instanceof Inet4Address) && !(parsed instanceof Inet6Address)) {
                return null;
            }
            if (parsed.isAnyLocalAddress()
                    || parsed.isLoopbackAddress()
                    || parsed.isLinkLocalAddress()
                    || parsed.isSiteLocalAddress()
                    || parsed.isMulticastAddress()) {
                return null;
            }
            return value;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean isEligible(Socket socket, InetSocketAddress target) {
        if (socket.isConnected() || socket.isClosed()) return false;
        InetAddress address = target.getAddress();
        if (address != null && (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress())) {
            return false;
        }
        String hostName = target.getHostString();
        if (hostName == null || hostName.isEmpty()) return false;
        if ("localhost".equalsIgnoreCase(hostName)
                || "127.0.0.1".equals(hostName)
                || "::1".equals(hostName)) return false;
        if (address != null && address.isSiteLocalAddress()) return false;
        for (ProxyPool.Endpoint endpoint : selected) {
            if (endpoint.host.equalsIgnoreCase(hostName) && endpoint.port == target.getPort()) {
                return false;
            }
        }
        return target.getPort() > 0;
    }

    private ProxyPool.Endpoint chooseEndpoint(InetSocketAddress target) {
        Map<String, ProxyPool.Endpoint> candidates = new LinkedHashMap<>();
        for (ProxyPool.Endpoint endpoint : selected) {
            candidates.put(endpoint.key(), endpoint);
        }
        if (autoFind) {
            for (ProxyPool.Endpoint endpoint : ProxyPool.deserialize(
                    host.preferenceString(KEY_PROXY_LIST_CACHE, ""))) {
                candidates.put(endpoint.key(), endpoint);
            }
        }
        long now = System.currentTimeMillis();
        int timeout = 2_500;
        int probed = 0;
        for (ProxyPool.Endpoint endpoint : candidates.values()) {
            Long healthy = healthyUntil.get(endpoint.key());
            if (healthy != null && healthy > now) return endpoint;
            Long failed = failedUntil.get(endpoint.key());
            if (failed != null && failed > now) continue;
            if (probed++ >= MAX_PROBE_CANDIDATES) break;
            if (probeEndpoint(endpoint, timeout)) {
                healthyUntil.put(endpoint.key(), now + HEALTHY_CACHE_MS);
                return endpoint;
            }
            failedUntil.put(endpoint.key(), now + FAILED_CACHE_MS);
        }
        return null;
    }

    private boolean probeEndpoint(ProxyPool.Endpoint endpoint, int timeoutMs) {
        Socket probe = new Socket();
        try {
            connectWithoutProxy(probe,
                    new InetSocketAddress(endpoint.host, endpoint.port), timeoutMs);
            probe.setSoTimeout(timeoutMs);
            OutputStream output = probe.getOutputStream();
            InputStream input = probe.getInputStream();
            output.write(new byte[]{SOCKS_VERSION, 1, METHOD_NO_AUTH});
            output.flush();
            return (readByte(input) & 0xff) == SOCKS_VERSION
                    && (readByte(input) & 0xff) == METHOD_NO_AUTH;
        } catch (Throwable ignored) {
            return false;
        } finally {
            closeQuietly(probe);
        }
    }

    private void connectThroughProxy(
            Socket socket,
            InetSocketAddress target,
            ProxyPool.Endpoint endpoint
    ) throws IOException {
        int timeout = 8_000;
        connectWithoutProxy(socket,
                new InetSocketAddress(endpoint.host, endpoint.port), timeout);
        int previousTimeout = 0;
        boolean restored = false;
        try {
            previousTimeout = socket.getSoTimeout();
            socket.setSoTimeout(timeout);
            performSocks5Connect(socket, target);
            socket.setSoTimeout(previousTimeout);
            restored = true;
        } finally {
            if (!restored) {
                try {
                    socket.setSoTimeout(previousTimeout);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void performSocks5Connect(Socket socket, InetSocketAddress target)
            throws IOException {
        InputStream input = socket.getInputStream();
        OutputStream output = socket.getOutputStream();
        output.write(new byte[]{SOCKS_VERSION, 1, METHOD_NO_AUTH});
        output.flush();
        if ((readByte(input) & 0xff) != SOCKS_VERSION || (readByte(input) & 0xff) != METHOD_NO_AUTH) {
            throw new IOException("SOCKS5 authentication method rejected");
        }

        byte[] address = targetAddress(target);
        output.write(SOCKS_VERSION);
        output.write(COMMAND_CONNECT);
        output.write(0);
        output.write(address);
        writeUnsignedShort(output, target.getPort());
        output.flush();

        if ((readByte(input) & 0xff) != SOCKS_VERSION) {
            throw new IOException("Invalid SOCKS5 response");
        }
        int result = readByte(input) & 0xff;
        readByte(input);
        int addressType = readByte(input) & 0xff;
        if (addressType == ADDRESS_IPV4) {
            readFully(input, 4);
        } else if (addressType == ADDRESS_IPV6) {
            readFully(input, 16);
        } else if (addressType == ADDRESS_DOMAIN) {
            int length = readByte(input) & 0xff;
            readFully(input, length);
        } else {
            throw new IOException("Unknown SOCKS5 address type: " + addressType);
        }
        readFully(input, 2);
        if (result != 0) throw new IOException("SOCKS5 CONNECT rejected: " + result);
    }

    private byte[] targetAddress(InetSocketAddress target) throws IOException {
        InetAddress address = target.getAddress();
        if (address instanceof Inet4Address) {
            return concat(new byte[]{ADDRESS_IPV4}, address.getAddress());
        }
        if (address instanceof Inet6Address) {
            return concat(new byte[]{ADDRESS_IPV6}, address.getAddress());
        }
        String hostName = target.getHostString();
        byte[] host = hostName.getBytes(StandardCharsets.UTF_8);
        if (host.length == 0 || host.length > 255) {
            throw new IOException("Invalid SOCKS5 target host");
        }
        byte[] result = new byte[2 + host.length];
        result[0] = ADDRESS_DOMAIN;
        result[1] = (byte) host.length;
        System.arraycopy(host, 0, result, 2, host.length);
        return result;
    }

    private void connectWithoutProxy(Socket socket, SocketAddress address, int timeout)
            throws IOException {
        boolean previous = Boolean.TRUE.equals(BYPASS.get());
        BYPASS.set(Boolean.TRUE);
        try {
            socket.connect(address, timeout);
        } finally {
            BYPASS.set(previous);
        }
    }

    private static int readByte(InputStream input) throws IOException {
        int value = input.read();
        if (value < 0) throw new IOException("Unexpected end of SOCKS5 response");
        return value;
    }

    private static byte[] readFully(InputStream input, int length) throws IOException {
        byte[] result = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = input.read(result, offset, length - offset);
            if (count < 0) throw new IOException("Unexpected end of SOCKS5 response");
            offset += count;
        }
        return result;
    }

    private static void writeUnsignedShort(OutputStream output, int value) throws IOException {
        output.write((value >>> 8) & 0xff);
        output.write(value & 0xff);
    }

    private static byte[] concat(byte[] prefix, byte[] value) {
        byte[] result = new byte[prefix.length + value.length];
        System.arraycopy(prefix, 0, result, 0, prefix.length);
        System.arraycopy(value, 0, result, prefix.length, value.length);
        return result;
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (Throwable ignored) {
        }
    }
}
