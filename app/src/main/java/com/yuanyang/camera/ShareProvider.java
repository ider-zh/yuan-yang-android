package com.yuanyang.camera;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;

/** Only the explicitly granted media cache exports can be opened by receiving apps. */
public final class ShareProvider extends ContentProvider {
    static final String DIRECTORY = "shared-reports";
    private static final Pattern SAFE_NAME = Pattern.compile("yuanyang-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|gif|mp4)");
    private static final String[] DEFAULT_COLUMNS =
            { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE };

    static boolean isExportName(String name) {
        return name != null && SAFE_NAME.matcher(name).matches();
    }

    static String mimeType(String name) {
        if (!isExportName(name)) return null;
        if (name.endsWith(".gif")) return "image/gif";
        if (name.endsWith(".mp4")) return "video/mp4";
        return "image/jpeg";
    }

    @Override public boolean onCreate() { return true; }

    @Override public String getType(Uri uri) {
        return uri.getPathSegments().size() == 1 ? mimeType(uri.getLastPathSegment()) : null;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("只支持读取导出文件");
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        String[] columns = projection != null ? projection : DEFAULT_COLUMNS;
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        try {
            File file = resolve(uri);
            Object[] row = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = file.getName();
                else if (OpenableColumns.SIZE.equals(columns[i])) row[i] = file.length();
            }
            cursor.addRow(row);
        } catch (FileNotFoundException ignored) {
            // A previously granted cache item may have been cleared by the system.
        }
        return cursor;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw readOnly(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw readOnly(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw readOnly();
    }

    private UnsupportedOperationException readOnly() {
        return new UnsupportedOperationException("只支持读取导出文件");
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        Context ctx = getContext();
        List<String> parts = uri.getPathSegments();
        if (ctx == null || !("content".equals(uri.getScheme()))
                || !(ctx.getPackageName() + ".share").equals(uri.getAuthority())
                || parts.size() != 1 || !isExportName(parts.get(0)))
            throw new FileNotFoundException("无效的导出地址");
        try {
            File root = new File(ctx.getCacheDir(), DIRECTORY).getCanonicalFile();
            File target = new File(root, parts.get(0)).getCanonicalFile();
            if (!root.equals(target.getParentFile()) || !target.isFile())
                throw new FileNotFoundException("导出文件不存在");
            return target;
        } catch (IOException error) {
            FileNotFoundException missing = new FileNotFoundException("找不到导出文件");
            missing.initCause(error);
            throw missing;
        }
    }
}
