package com.cosmo.assistant;

/** A parsed local command. DeviceActions enforces permissions and confirmation at execution. */
public final class Command {
    public enum Type {
        TIME, DATE, BATTERY,
        FLASHLIGHT_ON, FLASHLIGHT_OFF,
        VOLUME_UP, VOLUME_DOWN, VOLUME_MUTE,
        BRIGHTNESS_UP, BRIGHTNESS_DOWN, BRIGHTNESS_SET, ROTATION_AUTO, ROTATION_PORTRAIT,
        WIFI_SETTINGS, BLUETOOTH_SETTINGS, INTERNET_SETTINGS, LOCATION_SETTINGS,
        APP_SETTINGS, DISPLAY_SETTINGS, SOUND_SETTINGS, ACCESSIBILITY_SETTINGS,
        BATTERY_SETTINGS, AIRPLANE_SETTINGS, HOTSPOT_SETTINGS, NFC_SETTINGS,
        PRIVACY_SETTINGS, STORAGE_SETTINGS,
        OPEN_APP, DIAL, CALL, DIAL_CONTACT, CONTACT_SHOW, COMPOSE_SMS, COMPOSE_SMS_CONTACT,
        MAP_SEARCH, WEB_SEARCH, SET_ALARM, SET_TIMER, CALENDAR_INSERT,
        CAMERA_PHOTO, CAMERA_VIDEO, SCREENSHOT, SCREEN_LOCK,
        NOTE_SAVE, NOTE_LIST, MEDIA_PLAY, MEDIA_PAUSE, MEDIA_PLAY_PAUSE, MEDIA_NEXT, MEDIA_PREVIOUS,
        ACCESSIBILITY_BACK, ACCESSIBILITY_HOME, ACCESSIBILITY_RECENTS,
        ACCESSIBILITY_NOTIFICATIONS, ACCESSIBILITY_QUICK_SETTINGS,
        ACCESSIBILITY_SCROLL_UP, ACCESSIBILITY_SCROLL_DOWN,
        SCREEN_CLICK, SCREEN_TYPE, SCREEN_READ,
        SELF_STATUS, GREETING, POWER_OFF, REBOOT,
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
