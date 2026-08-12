package com.jiangdg.utils;

import com.elvishew.xlog.printer.Printer;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Keeps recent log lines in memory so they can be shown inside the app.
 */
public class InMemoryLogPrinter implements Printer {
    private static final int MAX_CHARS = 512 * 1024;
    private static final StringBuilder BUFFER = new StringBuilder();
    private static final Object LOCK = new Object();
    private static final ThreadLocal<SimpleDateFormat> DATE_FORMAT = new ThreadLocal<SimpleDateFormat>() {
        @Override
        protected SimpleDateFormat initialValue() {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
            sdf.setTimeZone(TimeZone.getDefault());
            return sdf;
        }
    };

    @Override
    public void println(int logLevel, String tag, String msg) {
        append(formatLine(logLevel, tag, msg));
    }

    public static void appendDirect(String line) {
        append(line);
    }

    public static String getLogs() {
        synchronized (LOCK) {
            return BUFFER.toString();
        }
    }

    public static void clear() {
        synchronized (LOCK) {
            BUFFER.setLength(0);
        }
    }

    private static void append(String line) {
        synchronized (LOCK) {
            BUFFER.append(line).append('\n');
            trimIfNeeded();
        }
    }

    private static void trimIfNeeded() {
        if (BUFFER.length() <= MAX_CHARS) {
            return;
        }
        int cut = BUFFER.length() - MAX_CHARS;
        int newline = BUFFER.indexOf("\n", cut);
        if (newline >= 0) {
            BUFFER.delete(0, newline + 1);
        } else {
            BUFFER.delete(0, cut);
        }
    }

    static String formatLine(int logLevel, String tag, String msg) {
        return DATE_FORMAT.get().format(new Date())
                + " " + levelName(logLevel) + "/" + tag + ": " + msg;
    }

    private static String levelName(int logLevel) {
        switch (logLevel) {
            case 2: return "V";
            case 3: return "D";
            case 4: return "I";
            case 5: return "W";
            case 6: return "E";
            default: return String.valueOf(logLevel);
        }
    }
}
