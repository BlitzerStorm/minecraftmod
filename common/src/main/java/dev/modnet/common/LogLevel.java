package dev.modnet.common;

public enum LogLevel {
    ERROR(0),
    WARN(1),
    INFO(2),
    DEBUG(3);

    private final int level;

    LogLevel(int level) {
        this.level = level;
    }

    public boolean allows(LogLevel other) {
        return other.level <= this.level;
    }

    public static LogLevel fromString(String value) {
        if (value == null) {
            return INFO;
        }
        return switch (value.trim().toLowerCase()) {
            case "error" -> ERROR;
            case "warn", "warning" -> WARN;
            case "debug" -> DEBUG;
            default -> INFO;
        };
    }
}
