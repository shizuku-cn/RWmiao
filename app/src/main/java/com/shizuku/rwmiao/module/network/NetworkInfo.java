package com.shizuku.rwmiao.module.network;

import com.shizuku.rwmiao.module.RWmiaoModule;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import io.github.libxposed.api.XposedInterface;

import static com.shizuku.rwmiao.config.SettingsContract.KEY_NETWORK_INFO_FAKE_CLIENT_ID_HASH;
import static com.shizuku.rwmiao.config.SettingsContract.KEY_NETWORK_INFO_FAKE_DEVICE_ID;
import static com.shizuku.rwmiao.config.SettingsContract.KEY_NETWORK_INFO_FAKE_EXTRA_CHECKSUM;
import static com.shizuku.rwmiao.config.SettingsContract.KEY_NETWORK_INFO_FAKE_INTEGRITY;
import static com.shizuku.rwmiao.config.SettingsContract.KEY_NETWORK_INFO_FAKE_PACKAGE_NAME;
import static com.shizuku.rwmiao.config.SettingsContract.KEY_NETWORK_INFO_FAKE_PLAYER_NAME;
import static com.shizuku.rwmiao.config.SettingsContract.KEY_NETWORK_INFO_FAKE_UNIT_CHECKSUM;
import static com.shizuku.rwmiao.config.SettingsContract.KEY_NETWORK_INFO_SPOOF_ENABLED;

public final class NetworkInfo {
    private static final String TAG = "RWmiao";
    private static final int REGISTER_PACKET = 110;
    private static final int MAX_CAPABILITY = 5;

    private final RWmiaoModule host;
    private final ClassLoader loader;
    private volatile Method packetBuilder;
    private volatile XposedInterface.HookHandle packetHook;
    private volatile boolean hookEnabled;
    private volatile SpoofConfig spoofConfig = SpoofConfig.empty();
    private volatile Snapshot lastActual;

    public NetworkInfo(RWmiaoModule host, ClassLoader loader) {
        this.host = host;
        this.loader = loader;
    }

    public void install() throws Throwable {
        Class<?> packetClass = loader.loadClass(host.target("gameFramework.j.bg"));
        packetBuilder = host.findCompatibleMethod(packetClass, "a", int.class);
        if (packetBuilder == null) {
            throw new NoSuchMethodException("gameFramework.j.bg.a(int)");
        }
        refreshSettings();
    }

    public synchronized void refreshSettings() throws Throwable {
        boolean enabled = host.selectionActionEnabled(KEY_NETWORK_INFO_SPOOF_ENABLED);
        spoofConfig = enabled ? SpoofConfig.from(host) : SpoofConfig.empty();
        hookEnabled = enabled;
        if (enabled) {
            if (packetHook == null) {
                packetHook = host.hookExecutable(packetBuilder, chain -> {
                    Object type = chain.getArg(0);
                    if (!(type instanceof Integer)
                            || ((Integer) type).intValue() != REGISTER_PACKET
                            || !hookEnabled) {
                        return chain.proceed();
                    }
                    Object packet = chain.proceed();
                    try {
                        rewriteRegistrationPacket(packet);
                    } catch (Throwable t) {
                        host.log(5, TAG, "Unable to rewrite native registration packet", t);
                    }
                    return packet;
                });
            }
        } else {
            hookEnabled = false;
            if (packetHook != null) {
                try {
                    packetHook.unhook();
                } catch (Throwable ignored) {
                }
                packetHook = null;
            }
        }
    }

    public Snapshot snapshot() {
        Snapshot captured = lastActual;
        Snapshot live = readNativeSnapshot();
        if (captured == null) return live;
        captured.copyRuntimeFieldsTo(live);
        return captured;
    }

    public String queryPublicIp() {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL("https://api.ipify.org")
                    .openConnection(java.net.Proxy.NO_PROXY);
            connection.setConnectTimeout(2500);
            connection.setReadTimeout(2500);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "text/plain");
            if (connection.getResponseCode() < 200 || connection.getResponseCode() >= 300) {
                return "（查询失败）";
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), StandardCharsets.UTF_8))) {
                String value = reader.readLine();
                if (value == null || value.trim().isEmpty()) return "（查询失败）";
                return value.trim();
            }
        } catch (Throwable ignored) {
            return "（查询失败）";
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    public String describeCurrent() {
        return snapshot().toDisplayText("（未查询）");
    }

    private void rewriteRegistrationPacket(Object packet) throws Throwable {
        if (packet == null) return;
        Field payloadField = host.findField(packet.getClass(), "c");
        Object rawPayload = payloadField.get(packet);
        if (!(rawPayload instanceof byte[])) return;

        WireData actual = WireData.read((byte[]) rawPayload);
        lastActual = actual.toSnapshot();
        SpoofConfig config = spoofConfig;
        String fakeClientHash = deriveClientHash(config.deviceId);
        if (fakeClientHash != null) config = config.withClientIdHash(fakeClientHash);
        byte[] rewritten = actual.rewrite(config);
        if (rewritten != null) payloadField.set(packet, rewritten);
    }

    private String deriveClientHash(String fakeDeviceId) {
        if (fakeDeviceId == null || fakeDeviceId.isEmpty()) return null;
        try {
            Object engine = host.findEngine(loader);
            Object network = host.findField(engine.getClass(), "bU").get(engine);
            String serverUuid = stringField(network, "U");
            if (serverUuid == null || serverUuid.isEmpty()) return null;
            Class<?> checksumClass = loader.loadClass(host.target("gameFramework.f"));
            Method hash = host.findCompatibleMethod(checksumClass, "f", String.class);
            if (hash == null) return null;
            Object value = hash.invoke(null, fakeDeviceId + serverUuid);
            return value == null ? null : String.valueOf(value);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Snapshot readNativeSnapshot() {
        Snapshot snapshot = Snapshot.empty();
        try {
            Object engine = host.findEngine(loader);
            snapshot.packageName = invokeStringOrUnknown(engine, "h");
            snapshot.unitChecksum = invokeStringOrUnknown(engine, "r");

            try {
                Object settings = host.findField(engine.getClass(), "bN").get(engine);
                snapshot.deviceId = stringField(settings, "networkClientId");
                snapshot.deviceMachineKey = stringField(settings, "networkClientIdMachineKey");
            } catch (Throwable ignored) {
                snapshot.deviceId = "（尚未生成或不可用）";
                snapshot.deviceMachineKey = "（不可用）";
            }
            Object network = host.findField(engine.getClass(), "bU").get(engine);
            if (network == null) return snapshot;
            snapshot.playerName = stringField(network, "y");

            snapshot.clientIdHash = "待生成";

            int challenge = intField(network, "V", -1);
            try {
                Method integrity = host.findCompatibleMethod(network.getClass(), "e", int.class);
                if (integrity != null && challenge != -1) {
                    snapshot.integrity = String.valueOf(integrity.invoke(network, challenge));
                }
            } catch (Throwable ignored) {
                snapshot.integrity = "待生成";
            }

            int extra = intField(network, "W", -1);
            try {
                Class<?> checksumClass = loader.loadClass(host.target("gameFramework.f"));
                Method checksum = host.findCompatibleMethod(checksumClass, "e", int.class);
                if (checksum != null && extra != -1) {
                    snapshot.extraChecksum = String.valueOf(checksum.invoke(null, extra));
                }
            } catch (Throwable ignored) {
                snapshot.extraChecksum = "待生成";
            }
        } catch (Throwable t) {
            host.log(4, TAG, "Unable to read native registration information", t);
        }
        return snapshot;
    }

    private String invokeStringOrUnknown(Object target, String name, Object... args) {
        try {
            Class<?>[] types = new Class<?>[args.length];
            for (int i = 0; i < args.length; i++) {
                types[i] = args[i] instanceof Boolean ? boolean.class : args[i].getClass();
            }
            Method method = host.findCompatibleMethod(target.getClass(), name, types);
            if (method == null) return "（未知）";
            Object value = method.invoke(target, args);
            return value == null ? "（空）" : String.valueOf(value);
        } catch (Throwable ignored) {
            return "（未知）";
        }
    }

    private String stringField(Object target, String name) {
        try {
            Object value = host.findField(target.getClass(), name).get(target);
            return value == null ? null : String.valueOf(value);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private int intField(Object target, String name, int fallback) {
        try {
            return host.findField(target.getClass(), name).getInt(target);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    public static final class Snapshot {
        public String playerName;
        public String packageName;
        public String clientIdHash;
        public String unitChecksum;
        public String integrity;
        public String extraChecksum;
        public String deviceId;
        public String deviceMachineKey;

        static Snapshot empty() {
            Snapshot snapshot = new Snapshot();
            snapshot.playerName = "（未知）";
            snapshot.packageName = "（未知）";
            snapshot.clientIdHash = "（未知）";
            snapshot.unitChecksum = "（未知）";
            snapshot.integrity = "（未知）";
            snapshot.extraChecksum = "（未知）";
            snapshot.deviceId = "（未知）";
            snapshot.deviceMachineKey = "（未知）";
            return snapshot;
        }

        public static Snapshot emptyForUi() {
            return empty();
        }

        void copyRuntimeFieldsTo(Snapshot live) {
            if (live == null) return;
            deviceId = live.deviceId;
            deviceMachineKey = live.deviceMachineKey;
        }

        String toDisplayText(String publicIp) {
            return "玩家名称: " + display(playerName) + "\n"
                    + "客户端包名: " + display(packageName) + "\n"
                    + "服务器可见设备 ID: " + display(clientIdHash) + "\n"
                    + "核心单位校验值: " + display(unitChecksum) + "\n"
                    + "完整性挑战响应: " + display(integrity) + "\n"
                    + "Mod/额外内容校验值: " + display(extraChecksum) + "\n"
                    + "原生设备 ID: " + display(deviceId) + "\n"
                    + "设备绑定键: " + display(deviceMachineKey) + "\n"
                    + "公网IP: " + display(publicIp);
        }

        public String toDisplayTextForUi(String publicIp) {
            return toDisplayText(publicIp);
        }

        public String toNonSpoofedTextForUi(String publicIp) {
            return "设备绑定键: " + display(deviceMachineKey) + "\n"
                    + "公网IP: " + display(publicIp);
        }

        private static String display(String value) {
            return value == null || value.isEmpty() ? "（空）" : value;
        }
    }

    private static final class SpoofConfig {
        final String deviceId;
        final String playerName;
        final String packageName;
        final String clientIdHash;
        final String unitChecksum;
        final String integrity;
        final String extraChecksum;

        SpoofConfig(String deviceId, String playerName, String packageName,
                String clientIdHash, String unitChecksum, String integrity,
                String extraChecksum) {
            this.deviceId = deviceId;
            this.playerName = playerName;
            this.packageName = packageName;
            this.clientIdHash = clientIdHash;
            this.unitChecksum = unitChecksum;
            this.integrity = integrity;
            this.extraChecksum = extraChecksum;
        }

        static SpoofConfig empty() {
            return new SpoofConfig("", "", "", "", "", "", "");
        }

        static SpoofConfig from(RWmiaoModule host) {
            return new SpoofConfig(
                    host.preferenceString(KEY_NETWORK_INFO_FAKE_DEVICE_ID, ""),
                    host.preferenceString(KEY_NETWORK_INFO_FAKE_PLAYER_NAME, ""),
                    host.preferenceString(KEY_NETWORK_INFO_FAKE_PACKAGE_NAME, ""),
                    host.preferenceString(KEY_NETWORK_INFO_FAKE_CLIENT_ID_HASH, ""),
                    host.preferenceString(KEY_NETWORK_INFO_FAKE_UNIT_CHECKSUM, ""),
                    host.preferenceString(KEY_NETWORK_INFO_FAKE_INTEGRITY, ""),
                    host.preferenceString(KEY_NETWORK_INFO_FAKE_EXTRA_CHECKSUM, ""));
        }

        SpoofConfig withClientIdHash(String value) {
            return new SpoofConfig(deviceId, playerName, packageName, value,
                    unitChecksum, integrity, extraChecksum);
        }
    }

    private static final class WireData {
        final String gameMarker;
        final int capability;
        final int networkVersion;
        final int appVersion;
        final String playerName;
        final String passwordHash;
        final String packageName;
        final String clientIdHash;
        final int unitChecksum;
        final String integrity;
        final String extraChecksum;

        WireData(String gameMarker, int capability, int networkVersion, int appVersion,
                String playerName, String passwordHash, String packageName,
                String clientIdHash, int unitChecksum, String integrity,
                String extraChecksum) {
            this.gameMarker = gameMarker;
            this.capability = capability;
            this.networkVersion = networkVersion;
            this.appVersion = appVersion;
            this.playerName = playerName;
            this.passwordHash = passwordHash;
            this.packageName = packageName;
            this.clientIdHash = clientIdHash;
            this.unitChecksum = unitChecksum;
            this.integrity = integrity;
            this.extraChecksum = extraChecksum;
        }

        static WireData read(byte[] bytes) throws IOException {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes));
            String gameMarker = input.readUTF();
            int capability = input.readInt();
            if (capability < 0 || capability > MAX_CAPABILITY) {
                throw new IOException("unsupported registration capability: " + capability);
            }
            int networkVersion = input.readInt();
            int appVersion = input.readInt();
            String playerName = input.readUTF();
            String passwordHash = input.readBoolean() ? input.readUTF() : null;
            String packageName = capability > 0 ? input.readUTF() : null;
            String clientIdHash = capability >= 2 ? input.readUTF() : null;
            int unitChecksum = capability >= 3 ? input.readInt() : -1;
            String integrity = capability >= 4 ? input.readUTF() : null;
            String extraChecksum = capability >= 5 ? input.readUTF() : null;
            return new WireData(gameMarker, capability, networkVersion, appVersion,
                    playerName, passwordHash, packageName, clientIdHash, unitChecksum,
                    integrity, extraChecksum);
        }

        Snapshot toSnapshot() {
            Snapshot snapshot = Snapshot.empty();
            snapshot.playerName = playerName;
            snapshot.packageName = packageName;
            snapshot.clientIdHash = clientIdHash;
            snapshot.unitChecksum = unitChecksum == -1 ? "（未发送）" : String.valueOf(unitChecksum);
            snapshot.integrity = integrity;
            snapshot.extraChecksum = extraChecksum;
            return snapshot;
        }

        byte[] rewrite(SpoofConfig config) throws IOException {
            int capability = this.capability;
            int networkVersion = this.networkVersion;
            int appVersion = this.appVersion;
            int unitChecksum = parseInt(config.unitChecksum, this.unitChecksum);

            ByteArrayOutputStream bytes = new ByteArrayOutputStream(192);
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeUTF(safe(gameMarker));
            output.writeInt(capability);
            output.writeInt(networkVersion);
            output.writeInt(appVersion);
            output.writeUTF(select(config.playerName, playerName));
            output.writeBoolean(passwordHash != null);
            if (passwordHash != null) output.writeUTF(passwordHash);
            if (capability > 0) output.writeUTF(select(config.packageName, packageName));
            if (capability >= 2) output.writeUTF(select(config.clientIdHash, clientIdHash));
            if (capability >= 3) output.writeInt(unitChecksum);
            if (capability >= 4) output.writeUTF(select(config.integrity, integrity));
            if (capability >= 5) output.writeUTF(select(config.extraChecksum, extraChecksum));
            output.flush();
            return bytes.toByteArray();
        }

        private static String select(String override, String actual) {
            return override == null || override.isEmpty() ? safe(actual) : fitUtf(override);
        }

        private static String safe(String value) {
            return value == null ? "" : fitUtf(value);
        }

        private static String fitUtf(String value) {
            return value.length() <= 60000 ? value : value.substring(0, 60000);
        }

        private static int parseInt(String value, int fallback) {
            if (value == null || value.trim().isEmpty()) return fallback;
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
    }
}
