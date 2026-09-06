package com.shizuku.rwmiao.config;

public final class SettingsContract {
    public static final String PREFS_NAME = "rwmiao_settings";

    public static final String KEY_NO_FOG = "noFog";
    public static final String KEY_SELECTION_PANEL_COLUMNS = "selectionPanelColumns";
    public static final String KEY_FORMATION_BUTTON_COUNT = "formationButtonCount";
    public static final String KEY_LOBBY_SEARCHER = "lobbySearcher";
    public static final String KEY_LOBBY_AD_KEYWORDS = "lobbyAdKeywords";
    public static final String KEY_LOBBY_AD_KEYWORDS_VERSION = "lobbyAdKeywordsVersion";
    public static final String KEY_LOBBY_PINNED_NAMES = "lobbyPinnedNames";
    public static final String KEY_LOBBY_BLACKLIST = "lobbyBlacklist";
    public static final String KEY_LOBBY_RECENT_ROOMS = "lobbyRecentRooms";
    public static final String DEFAULT_LOBBY_AD_KEYWORDS =
            "广告\n加群\n群号\n群聊\nQQ\nhttp://\nhttps://\n官网\n招人\n群里";
    public static final String LOBBY_AD_KEYWORDS_VERSION = "fix11";
    public static final String KEY_ENEMY_TEAM_CHAT = "enemyTeamChat";
    public static final String KEY_ENEMY_MAP_PINGS = "enemyMapPings";
    public static final String KEY_HOST_MUTE_PANEL = "hostMutePanel";
    public static final String KEY_SHOW_RANGE_ACTION = "showRangeAction";
    public static final String KEY_SHOW_LINE_ACTION = "showLineAction";
    public static final String KEY_SHOW_ATTACK_RANGE = "showAttackRange";
    public static final String KEY_SHOW_TARGET_LINE = "showTargetLine";
    public static final String KEY_SHOW_FACTORY_COUNTDOWN = "showFactoryCountdown";
    public static final String KEY_SHOW_GAME_DURATION = "showGameDuration";
    public static final String KEY_VIEW_ALL = "viewAll";
    public static final String KEY_PLAYER_INFO_PANEL = "playerInfoPanel";
    public static final String KEY_FACTORY_OPT = "factoryOpt";
    public static final String KEY_FACTORY_EXIT_THROUGH = "factoryExitThrough";
    public static final String KEY_MOTHER_RALLY = "motherRally";
    public static final String KEY_REINFORCE_ON = "reinforceOn";
    public static final String KEY_REINFORCE_WEIGHT_MODE = "weightMode";
    public static final String KEY_SHOW_REINFORCE_PANEL = "showReinforcePanel";
    public static final String KEY_FREE_SELECTION = "freeSelection";
    public static final String KEY_FREE_BUILD = "freeBuild";
    public static final String KEY_SELECT_ALL = "selectAll";
    public static final String KEY_COMBAT_VIEW = "combatView";
    public static final String KEY_SEGMENT_COMMAND = "segmentCommand";
    public static final String KEY_SMART_PATHING = "smartPathing";
    public static final String KEY_SHOW_SMART_PATH_ACTION = "showSmartPathAction";
    public static final String KEY_SCRIPTS_MASTER = "scriptsMaster";
    public static final String KEY_SCRIPT_ENABLED_PREFIX = "scriptEnabled.";
    public static final String KEY_SMART_PATHING_THRESHOLD = "smartPathingThreshold";
    public static final int DEFAULT_SMART_PATHING_THRESHOLD = 10;
    public static final String KEY_RANGE_PLAYER_FILTER = "playerFilter";
    public static final String KEY_RANGE_UNIT_TYPES = "unitTypeFilters";
    public static final String KEY_LINE_PLAYER_FILTER = "linePlayerFilter";
    public static final String KEY_LINE_UNIT_TYPES = "lineUnitTypeFilters";
    public static final String KEY_RANGE_COLOR_SELF = "drawColorSelf";
    public static final String KEY_RANGE_COLOR_ENEMY = "drawColorEnemy";
    public static final String KEY_RANGE_COLOR_ALLY = "drawColorAlly";
    public static final String KEY_LINE_COLOR_SELF = "lineColorSelf";
    public static final String KEY_LINE_COLOR_ENEMY = "lineColorEnemy";
    public static final String KEY_LINE_COLOR_ALLY = "lineColorAlly";
    public static final String KEY_FACTORY_PLAYER_FILTER = "factoryPlayerFilter";
    public static final String KEY_FACTORY_COLOR_SELF = "factoryColorSelf";
    public static final String KEY_FACTORY_COLOR_ENEMY = "factoryColorEnemy";
    public static final String KEY_FACTORY_COLOR_ALLY = "factoryColorAlly";
    public static final String KEY_UI_THEME_MODE = "uiThemeMode";
    public static final String KEY_UI_DYNAMIC_COLOR = "uiDynamicColor";
    public static final String KEY_UI_COLOR_MODE = "uiColorMode";
    public static final String KEY_EXTENDED_GAME_OPTIONS = "extendedGameOptions";
    public static final String KEY_GLOBAL_UNIT_CAP_ENABLED = "globalUnitCapEnabled";
    public static final String KEY_GLOBAL_UNIT_CAP = "globalUnitCap";
    public static final String KEY_GLOBAL_TEAM_LIMIT_ENABLED = "globalTeamLimitEnabled";
    public static final String KEY_BATCH_PLACEMENT_UNLIMITED = "batchPlacementUnlimited";
    public static final String KEY_SMART_BUILD_SERIALIZATION = "smartBuildSerialization";
    public static final String KEY_UI_SELECTED_PAGE = "uiSelectedPage";
    public static final String KEY_UI_AUTO_CHECK_UPDATE = "uiAutoCheckUpdate";
    public static final String KEY_UI_UPDATE_IGNORED_VERSION = "uiUpdateIgnoredVersion";
    public static final String KEY_MODULE_LAST_ACTIVE = "moduleLastActive";
    public static final String KEY_MODULE_LAST_PACKAGE = "moduleLastPackage";
    public static final String ACTION_MODULE_HEARTBEAT =
            "com.shizuku.rwmiao.action.MODULE_HEARTBEAT";
    public static final String ARG_MODULE_PACKAGE = "modulePackage";
    public static final long MODULE_ACTIVE_TIMEOUT_MS = 90_000L;
    public static final String KEY_SELECTION_ACTION_SCALE_MASK = "selectionActionScaleMask";
    public static final String KEY_VOLUME_ACTION = "volumeAction";

    public static final String KEY_NETWORK_INFO_SPOOF_ENABLED = "networkInfoSpoofEnabled";
    public static final String KEY_NETWORK_INFO_FAKE_DEVICE_ID = "networkInfoFakeDeviceId";
    public static final String KEY_NETWORK_INFO_FAKE_PLAYER_NAME = "networkInfoFakePlayerName";
    public static final String KEY_NETWORK_INFO_FAKE_PACKAGE_NAME = "networkInfoFakePackageName";
    public static final String KEY_NETWORK_INFO_FAKE_CLIENT_ID_HASH = "networkInfoFakeClientIdHash";
    public static final String KEY_NETWORK_INFO_FAKE_UNIT_CHECKSUM = "networkInfoFakeUnitChecksum";
    public static final String KEY_NETWORK_INFO_FAKE_INTEGRITY = "networkInfoFakeIntegrity";
    public static final String KEY_NETWORK_INFO_FAKE_EXTRA_CHECKSUM = "networkInfoFakeExtraChecksum";

    public static final String KEY_PROXY_SERVICE_ENABLED = "proxyServiceEnabled";
    public static final String KEY_PROXY_AUTO_FIND = "proxyAutoFind";
    public static final String KEY_PROXY_COUNTRY = "proxyCountry";
    public static final String KEY_PROXY_MAX_LATENCY_MS = "proxyMaxLatencyMs";
    public static final String KEY_PROXY_ANONYMITY = "proxyAnonymity";
    public static final String KEY_PROXY_SELECTED_LIST = "proxySelectedList";
    public static final String KEY_PROXY_ACTIVE_ENDPOINT = "proxyActiveEndpoint";
    public static final String KEY_PROXY_LIST_CACHE = "proxyListCache";

    public static final int SELECTION_ACTION_SCALE_GUARD = 1 << 0;
    public static final int SELECTION_ACTION_SCALE_PATROL = 1 << 1;
    public static final int SELECTION_ACTION_SCALE_SEGMENT = 1 << 2;
    public static final int SELECTION_ACTION_SCALE_SMART_PATH = 1 << 3;
    public static final int SELECTION_ACTION_SCALE_FREE_BUILD = 1 << 4;
    public static final int SELECTION_ACTION_SCALE_MOTHER_RALLY = 1 << 5;
    public static final int SELECTION_ACTION_SCALE_RANGE = 1 << 6;
    public static final int SELECTION_ACTION_SCALE_LINE = 1 << 7;
    public static final int SELECTION_ACTION_SCALE_SCRIPTS = 1 << 8;
    public static final int SELECTION_ACTION_SCALE_DESELECT = 1 << 9;
    public static final int SELECTION_ACTION_SCALE_ALL =
            SELECTION_ACTION_SCALE_GUARD
                    | SELECTION_ACTION_SCALE_PATROL
                    | SELECTION_ACTION_SCALE_SEGMENT
                    | SELECTION_ACTION_SCALE_SMART_PATH
                    | SELECTION_ACTION_SCALE_FREE_BUILD
                    | SELECTION_ACTION_SCALE_MOTHER_RALLY
                    | SELECTION_ACTION_SCALE_RANGE
                    | SELECTION_ACTION_SCALE_LINE
                    | SELECTION_ACTION_SCALE_SCRIPTS
                    | SELECTION_ACTION_SCALE_DESELECT;

    public static final int VOLUME_ACTION_NONE = 0;
    public static final int VOLUME_ACTION_SEGMENT = 1;
    public static final int VOLUME_ACTION_SMART_PATH = 2;
    public static final int VOLUME_ACTION_FREE_BUILD = 3;
    public static final int VOLUME_ACTION_RANGE = 4;
    public static final int VOLUME_ACTION_LINE = 5;

    public static final int DEFAULT_GLOBAL_UNIT_CAP = 1000;
    public static final int MAX_GLOBAL_UNIT_CAP = 10000;
    public static final int GLOBAL_MAX_TEAM_ID = 99;

    public static final int MIN_FORMATION_BUTTON_COUNT = 3;
    public static final int MAX_FORMATION_BUTTON_COUNT = 10;
    public static final int DEFAULT_SELECTION_PANEL_COLUMNS = 2;
    public static final int MIN_SELECTION_PANEL_COLUMNS = 1;
    public static final int MAX_SELECTION_PANEL_COLUMNS = 6;

    public static final int UI_THEME_SYSTEM = 0;
    public static final int UI_THEME_LIGHT = 1;
    public static final int UI_THEME_DARK = 2;
    public static final int UI_COLOR_DEFAULT = 0;
    public static final int UI_COLOR_DYNAMIC = 1;
    public static final int UI_COLOR_GREEN = 2;
    public static final int UI_COLOR_BLUE = 3;
    public static final int UI_COLOR_PINK = 4;
    public static final int UI_COLOR_YELLOW = 5;
    public static final int UI_COLOR_ORANGE = 6;
    public static final int UI_COLOR_RED = 7;
    public static final int UI_COLOR_CYAN = 8;

    public static final int DEFAULT_RANGE_COLOR_SELF = -6250336;
    public static final int DEFAULT_RANGE_COLOR_ENEMY = -65536;
    public static final int DEFAULT_RANGE_COLOR_ALLY = -128;
    public static final int DEFAULT_LINE_COLOR_SELF = -1;
    public static final int DEFAULT_LINE_COLOR_ENEMY = -65536;
    public static final int DEFAULT_LINE_COLOR_ALLY = -16711681;
    public static final int DEFAULT_FACTORY_COLOR_SELF = DEFAULT_RANGE_COLOR_SELF;
    public static final int DEFAULT_FACTORY_COLOR_ENEMY = DEFAULT_RANGE_COLOR_ENEMY;
    public static final int DEFAULT_FACTORY_COLOR_ALLY = DEFAULT_RANGE_COLOR_ALLY;
    public static final int DEFAULT_FACTORY_PLAYER_FILTER = 3;

    public static final int ALL_PLAYERS = 2;
    public static final int ALL_UNIT_TYPES = 31;

    private SettingsContract() {
    }
}
