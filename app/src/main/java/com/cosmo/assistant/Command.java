package com.cosmo.assistant;

/** A parsed, local command. Phone actions prepare Android UI; they never send or call. */
public final class Command {
    public enum Type {
        TIME, DATE, BATTERY,
        FLASHLIGHT_ON, FLASHLIGHT_OFF,
        VOLUME_UP, VOLUME_DOWN, VOLUME_MUTE,
        WIFI_SETTINGS, BLUETOOTH_SETTINGS, INTERNET_SETTINGS, LOCATION_SETTINGS,
        OPEN_APP, DIAL, COMPOSE_SMS, MAP_SEARCH, WEB_SEARCH, SET_ALARM, SET_TIMER,
        NOTE_SAVE, NOTE_LIST, MEDIA_PLAY, MEDIA_PAUSE, MEDIA_PLAY_PAUSE, MEDIA_NEXT,
        ACCESSIBILITY_BACK, ACCESSIBILITY_HOME, ACCESSIBILITY_RECENTS,
        ACCESSIBILITY_NOTIFICATIONS, ACCESSIBILITY_QUICK_SETTINGS,
        ACCESSIBILITY_SCROLL_UP, ACCESSIBILITY_SCROLL_DOWN,
        STOP_LISTENING, HELP, CANCEL, CONFIRM, UNKNOWN
    }

    public final Type type;
    public final String argument;
    public final String secondary;

    public Command(Type type) {
        this(type, "", "");
    }

    public Command(Type type, String argument) {
        this(type, argument, "");
    }

    public Command(Type type, String argument, String secondary) {
        if (type == null) throw new IllegalArgumentException("Command type is required");
        this.type = type;
        this.argument = argument == null ? "" : argument;
        this.secondary = secondary == null ? "" : secondary;
    }
}
