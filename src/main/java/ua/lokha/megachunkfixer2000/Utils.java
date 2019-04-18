package ua.lokha.megachunkfixer2000;

public class Utils {

    public static String toLogLength(long bytes) {
        if (bytes >= 1_000_000) {
            return (bytes / 1_000_000) + "MB";
        }
        if (bytes >= 1_000) {
            return (bytes / 1_000) + "KB";
        }
        return bytes + "B";
    }

    public static String toLogPercent(long low, long high) {
        return String.format("%.2f", 100 - ((double)low / high) * 100);
    }
}
