package com.jiangdg.utils;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageManager;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;

import com.elvishew.xlog.LogConfiguration;
import com.elvishew.xlog.LogLevel;
import com.elvishew.xlog.XLog;
import com.elvishew.xlog.flattener.PatternFlattener;
import com.elvishew.xlog.printer.AndroidPrinter;
import com.elvishew.xlog.printer.file.FilePrinter;
import com.elvishew.xlog.printer.file.naming.FileNameGenerator;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;

/**
 * X Log wrapper
 *
 * @author Created by jiangdg on 2022/7/19
 */
public class XLogWrapper {
    private static final String TAG = "AUSBC";
    private static final String FLATTERER = "{d yyyy-MM-dd HH:mm:ss.SSS} {l}/{t}: {m}";
    private static boolean mHasInit;
    private static String mLogFolderPath;
    private static final InMemoryLogPrinter MEMORY_PRINTER = new InMemoryLogPrinter();

    public static void init(Application application, String folderPath) {
        AndroidPrinter androidPrinter = new AndroidPrinter(true);
        LogConfiguration config = new LogConfiguration.Builder().logLevel(LogLevel.ALL)
                .tag(TAG)
                .disableStackTrace().build();
        String path = TextUtils.isEmpty(folderPath) ? application.getExternalFilesDir(null).getPath() : folderPath;
        path = TextUtils.isEmpty(path) ? application.getFilesDir().getPath(): path;
        mLogFolderPath = path;
        FilePrinter filePrinter = new FilePrinter.Builder(path)
                .fileNameGenerator(new MyFileNameGenerator(application))
                .flattener(new MyFlatterer(FLATTERER))
                .build();
        XLog.init(config, androidPrinter, filePrinter, MEMORY_PRINTER);
        mHasInit = true;
    }

    public static String getAllLogs() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Session logs (memory) ===\n");
        sb.append(InMemoryLogPrinter.getLogs());
        sb.append("\n\n=== Log files ===\n");
        sb.append(readLogFiles());
        return sb.toString();
    }

    public static void clearLogs() {
        InMemoryLogPrinter.clear();
    }

    private static String readLogFiles() {
        if (TextUtils.isEmpty(mLogFolderPath)) {
            return "(log folder not initialized)";
        }
        File folder = new File(mLogFolderPath);
        File[] files = folder.listFiles((dir, name) -> name.endsWith(".log"));
        if (files == null || files.length == 0) {
            return "(no log files in " + mLogFolderPath + ")";
        }
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        StringBuilder sb = new StringBuilder();
        for (File file : files) {
            sb.append("\n--- ").append(file.getName()).append(" ---\n");
            sb.append(readFileTail(file, 256 * 1024));
        }
        return sb.toString();
    }

    private static String readFileTail(File file, int maxBytes) {
        StringBuilder sb = new StringBuilder();
        try (FileInputStream fis = new FileInputStream(file)) {
            long skip = Math.max(0, file.length() - maxBytes);
            if (skip > 0) {
                fis.skip(skip);
                sb.append("...(truncated, showing last ").append(maxBytes / 1024).append("KB)...\n");
            }
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(fis, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (Exception e) {
            sb.append("(failed to read file: ").append(e.getMessage()).append(")\n");
        }
        return sb.toString();
    }

    private static void logToMemory(int logLevel, String tag, String msg) {
        InMemoryLogPrinter.appendDirect(InMemoryLogPrinter.formatLine(logLevel, tag, msg));
    }

    private static void logToMemory(int logLevel, String tag, String msg, Throwable throwable) {
        logToMemory(logLevel, tag, msg);
        if (throwable != null) {
            logToMemory(logLevel, tag, android.util.Log.getStackTraceString(throwable));
        }
    }

    public static void v(String tag, String msg) {
        if (mHasInit) {
            XLog.v( "[" + tag + "]  " +msg);
            return;
        }
        logToMemory(Log.VERBOSE, tag, msg);
        Log.v(tag,  "" +msg);
    }

    public static void i(String tag, String msg) {
        if (mHasInit) {
            XLog.i( "[" + tag + "]  " +msg);
            return;
        }
        logToMemory(Log.INFO, tag, msg);
        Log.i(tag,  "" +msg);
    }

    public static void d(String tag, String msg) {
        if (mHasInit) {
            XLog.d( "[" + tag + "]  " +msg);
            return;
        }
        logToMemory(Log.DEBUG, tag, msg);
        Log.d(tag,  "" +msg);
    }

    public static void w(String tag, String msg) {
        if (mHasInit) {
            XLog.w( "[" + tag + "]  " +msg);
            return;
        }
        logToMemory(Log.WARN, tag, msg);
        Log.w(tag,  "" +msg);
    }

    public static void w(String tag, String msg, Throwable throwable) {
        if (mHasInit) {
            XLog.w( "[" + tag + "]  " +msg, throwable);
            return;
        }
        logToMemory(Log.WARN, tag, msg, throwable);
        Log.w(tag,  msg, throwable);
    }

    public static void w(String tag, Throwable throwable) {
        if (mHasInit) {
            XLog.w("[" + tag, throwable);
            return;
        }
        logToMemory(Log.WARN, tag, throwable != null ? throwable.getMessage() : "", throwable);
        Log.w(tag,"" , throwable);
    }

    public static void e(String tag, String msg) {
        if (mHasInit) {
            XLog.e("[" + tag + "]  " +msg);
            return;
        }
        logToMemory(Log.ERROR, tag, msg);
        Log.e(tag,  "" +msg);
    }

    public static void e(String tag, String msg, Throwable throwable) {
        if (mHasInit) {
            XLog.e("[" + tag + "]  " +msg, throwable);
            return;
        }
        logToMemory(Log.ERROR, tag, msg, throwable);
        Log.e(tag,  "" +msg, throwable);
    }

    static class MyFileNameGenerator implements FileNameGenerator {
        private final Context mCtx;

        public MyFileNameGenerator(Context context) {
            this.mCtx = context;
        }

        private final ThreadLocal<SimpleDateFormat> mLocalDateFormat = new ThreadLocal<SimpleDateFormat>() {
            @NonNull
            @Override
            protected SimpleDateFormat initialValue() {
                return new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            }
        };

        @Override
        public boolean isFileNameChangeable() {
            return true;
        }

        @Override
        public String generateFileName(int logLevel, long timestamp) {
            SimpleDateFormat sdf = mLocalDateFormat.get();
            sdf.setTimeZone(TimeZone.getDefault());
            String dateStr = sdf.format(new Date(timestamp));
            return "AUSBC_v" + getVerName() + "_" + dateStr + ".log";
        }

        private String getVerName() {
            String verName = "";
            try {
                verName = mCtx.getPackageManager().
                        getPackageInfo(mCtx.getPackageName(), 0).versionName;
            } catch (PackageManager.NameNotFoundException e) {
                e.printStackTrace();
            }
            return verName;
        }
    }

    static class MyFlatterer extends PatternFlattener {
        public MyFlatterer(String pattern) {
            super(pattern);
        }
    }
}
