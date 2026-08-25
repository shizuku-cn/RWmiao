package com.shizuku.rwmiao.module.lobby;

/** Immutable UI snapshot of one native multiplayer lobby record. */
public final class LobbyRoom {
    final Object nativeRecord;
    final String roomId;
    final String addressDisplay;
    final String addressKey;
    final String status;
    final String hostName;
    final String serverNameKey;
    final String mapName;
    final String modName;
    final String versionName;
    final String visibility;
    final String playersLabel;
    final String externalUrl;
    final String joinAddress;
    final String details;
    final String searchText;
    final int players;
    final int maxPlayers;
    final boolean password;
    final boolean compatible;
    final boolean hasSlots;
    final boolean joinable;
    final boolean adLike;
    final boolean modded;
    final boolean official;
    final boolean officialLinkAd;

    LobbyRoom(
            Object nativeRecord,
            String roomId,
            String addressDisplay,
            String addressKey,
            String status,
            String hostName,
            String serverNameKey,
            String mapName,
            String modName,
            String versionName,
            String visibility,
            String playersLabel,
            String externalUrl,
            String joinAddress,
            String details,
            String searchText,
            int players,
            int maxPlayers,
            boolean password,
            boolean compatible,
            boolean hasSlots,
            boolean joinable,
            boolean adLike,
            boolean modded,
            boolean official,
            boolean officialLinkAd
    ) {
        this.nativeRecord = nativeRecord;
        this.roomId = roomId;
        this.addressDisplay = addressDisplay;
        this.addressKey = addressKey;
        this.status = status;
        this.hostName = hostName;
        this.serverNameKey = serverNameKey;
        this.mapName = mapName;
        this.modName = modName;
        this.versionName = versionName;
        this.visibility = visibility;
        this.playersLabel = playersLabel;
        this.externalUrl = externalUrl;
        this.joinAddress = joinAddress;
        this.details = details;
        this.searchText = searchText;
        this.players = players;
        this.maxPlayers = maxPlayers;
        this.password = password;
        this.compatible = compatible;
        this.hasSlots = hasSlots;
        this.joinable = joinable;
        this.adLike = adLike;
        this.modded = modded;
        this.official = official;
        this.officialLinkAd = officialLinkAd;
    }

    public Object getNativeRecord() { return nativeRecord; }
    public String getRoomId() { return roomId; }
    public String getAddressDisplay() { return addressDisplay; }
    public String getAddressKey() { return addressKey; }
    public String getStatus() { return status; }
    public String getHostName() { return hostName; }
    public String getServerNameKey() { return serverNameKey; }
    public String getMapName() { return mapName; }
    public String getModName() { return modName; }
    public String getVersionName() { return versionName; }
    public String getVisibility() { return visibility; }
    public String getPlayersLabel() { return playersLabel; }
    public String getExternalUrl() { return externalUrl; }
    public String getJoinAddress() { return joinAddress; }
    public String getDetails() { return details; }
    public String getSearchText() { return searchText; }
    public int getPlayers() { return players; }
    public int getMaxPlayers() { return maxPlayers; }
    public boolean isPassword() { return password; }
    public boolean isCompatible() { return compatible; }
    public boolean isHasSlots() { return hasSlots; }
    public boolean isJoinable() { return joinable; }
    public boolean isAdLike() { return adLike; }
    public boolean isModded() { return modded; }
    public boolean isOfficial() { return official; }
    public boolean isOfficialLinkAd() { return officialLinkAd; }

    public String getHistoryKey() {
        return roomId + "|" + addressKey + "|" + serverNameKey;
    }

    public String getStableKey() {
        return roomId + "|" + joinAddress + "|" + hostName + "|"
                + Integer.toHexString(System.identityHashCode(nativeRecord));
    }
}
