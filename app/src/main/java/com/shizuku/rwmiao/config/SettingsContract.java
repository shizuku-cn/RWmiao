package com.shizuku.rwmiao.config;

/** Shared preference contract used by target-process hooks and settings UI. */
public final class SettingsContract {
    public static final String PREFS_NAME = "rwmiao_settings";

    public static final String KEY_NO_FOG = "noFog";
    /** Selected-unit panel column count; absent means the native two-column layout. */
    public static final String KEY_SELECTION_PANEL_COLUMNS = "selectionPanelColumns";
    /** Formation button count; absent means use the target build's native default. */
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
    public static final String KEY_SHOW_RANGE_ACTION = "showRangeAction";
    public static final String KEY_SHOW_LINE_ACTION = "showLineAction";
    public static final String KEY_SHOW_ATTACK_RANGE = "showAttackRange";
    public static final String KEY_SHOW_TARGET_LINE = "showTargetLine";
    public static final String KEY_SHOW_AMMO_COUNT = "showAmmoCount";
    public static final String KEY_SHOW_FACTORY_COUNTDOWN = "showFactoryCountdown";
    public static final String KEY_VIEW_ALL = "viewAll";
    public static final String KEY_ECONOMIC_PANEL = "economicPanel";
    public static final String KEY_FACTORY_OPT = "factoryOpt";
    public static final String KEY_FACTORY_EXIT_THROUGH = "factoryExitThrough";
    public static final String KEY_MOTHER_RALLY = "motherRally";
    public static final String KEY_REINFORCE_ON = "reinforceOn";
    public static final String KEY_REINFORCE_WEIGHT_MODE = "weightMode";
    public static final String KEY_SHOW_REINFORCE_PANEL = "showReinforcePanel";
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
    public static final String KEY_AMMO_COLOR_SELF = "ammoColorSelf";
    public static final String KEY_AMMO_COLOR_ENEMY = "ammoColorEnemy";
    public static final String KEY_AMMO_COLOR_ALLY = "ammoColorAlly";
    public static final String KEY_FACTORY_COLOR_SELF = "factoryColorSelf";
    public static final String KEY_FACTORY_COLOR_ENEMY = "factoryColorEnemy";
    public static final String KEY_FACTORY_COLOR_ALLY = "factoryColorAlly";
    public static final String KEY_UI_THEME_MODE = "uiThemeMode";
    public static final String KEY_UI_DYNAMIC_COLOR = "uiDynamicColor";
    public static final String KEY_EXTENDED_GAME_OPTIONS = "extendedGameOptions";
    public static final String KEY_GLOBAL_UNIT_CAP_ENABLED = "globalUnitCapEnabled";
    public static final String KEY_GLOBAL_UNIT_CAP = "globalUnitCap";
    public static final String KEY_GLOBAL_TEAM_LIMIT_ENABLED = "globalTeamLimitEnabled";
    /** Continue native line-placement batches after the game's 29-point return. */
    public static final String KEY_BATCH_PLACEMENT_UNLIMITED = "batchPlacementUnlimited";
    public static final String KEY_UI_SELECTED_PAGE = "uiSelectedPage";
    public static final String KEY_UI_AUTO_CHECK_UPDATE = "uiAutoCheckUpdate";

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

    public static final int DEFAULT_RANGE_COLOR_SELF = -6250336;
    public static final int DEFAULT_RANGE_COLOR_ENEMY = -65536;
    public static final int DEFAULT_RANGE_COLOR_ALLY = -128;
    public static final int DEFAULT_LINE_COLOR_SELF = -1;
    public static final int DEFAULT_LINE_COLOR_ENEMY = -65536;
    public static final int DEFAULT_LINE_COLOR_ALLY = -16711681;
    /** Text overlays use the same relation colors as the attack-range drawing. */
    public static final int DEFAULT_AMMO_COLOR_SELF = DEFAULT_RANGE_COLOR_SELF;
    public static final int DEFAULT_AMMO_COLOR_ENEMY = DEFAULT_RANGE_COLOR_ENEMY;
    public static final int DEFAULT_AMMO_COLOR_ALLY = DEFAULT_RANGE_COLOR_ALLY;
    public static final int DEFAULT_FACTORY_COLOR_SELF = DEFAULT_RANGE_COLOR_SELF;
    public static final int DEFAULT_FACTORY_COLOR_ENEMY = DEFAULT_RANGE_COLOR_ENEMY;
    public static final int DEFAULT_FACTORY_COLOR_ALLY = DEFAULT_RANGE_COLOR_ALLY;
    public static final int DEFAULT_FACTORY_PLAYER_FILTER = 3;

    public static final int ALL_PLAYERS = 2;
    public static final int ALL_UNIT_TYPES = 31;

    private SettingsContract() {
    }
}
