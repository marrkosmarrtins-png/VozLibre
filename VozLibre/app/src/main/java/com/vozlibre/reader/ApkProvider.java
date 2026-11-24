package com.vozlibre.reader;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Entrega al instalador del sistema el APK de la actualización ya descargado
 * (el papel del FileProvider de AndroidX, sin librerías externas).
 *
 * El instalador de paquetes de Android SOLO acepta APKs via contenido://,
 * nunca via rutas de archivo directo (prohibido desde Android 7). Sin este
 * proveedor la descarga de la actualización no se podría instalar.
 *
 * URI: content://com.vozlibre.reader.apk/<nombre-del-archivo>
 * Sirve SOLO archivos de la carpeta compartida de la app (cache/updates):
 * cualquier otra ruta se rechaza (nadie puede usar el proveedor para leer
 * datos privados de VozLibre).
 */
public final class ApkProvider extends ContentProvider {

    public static final String AUTHORITY = "com.vozlibre.reader.apk";

    /** Carpeta donde UpdateManager deja el APK descargado. */
    public static File sharedDir(android.content.Context ctx) {
        File dir = new File(ctx.getCacheDir(), "updates");
        if (!dir.exists()) {
            try {
                dir.mkdirs();
            } catch (Exception ignored) { }
        }
        return dir;
    }

    /** URI content:// para entregar un archivo de la carpeta compartida. */
    public static Uri uriFor(File file) {
        return Uri.parse("content://" + AUTHORITY + "/"
                + Uri.encode(file.getName()));
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode)
            throws FileNotFoundException {
        String name = uri != null ? uri.getLastPathSegment() : null;
        if (name == null || name.contains("/") || name.contains("\\")
                || name.contains("..")) {
            throw new FileNotFoundException("Nombre inválido");
        }
        android.content.Context ctx = getContext();
        if (ctx == null) throw new FileNotFoundException("Sin contexto");
        File dir = sharedDir(ctx);
        File target = new File(dir, name);
        try {
            // Restricción de seguridad: el archivo DEBE quedar dentro de la
            // carpeta compartida tras resolver rutas canónicas.
            if (!target.getCanonicalPath().startsWith(dir.getCanonicalPath()
                    + File.separator)) {
                throw new FileNotFoundException("Fuera de la carpeta");
            }
        } catch (IOException e) {
            throw new FileNotFoundException("Ruta no resoluble");
        }
        if (!target.exists()) throw new FileNotFoundException("No existe");
        int imode = android.os.ParcelFileDescriptor.MODE_READ_ONLY;
        return ParcelFileDescriptor.open(target, imode);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        // Información mínima que algunos instaladores piden (nombre y tamaño).
        String name = uri != null ? uri.getLastPathSegment() : "";
        long size = -1;
        try {
            android.content.Context ctx = getContext();
            if (ctx != null && name != null) {
                File f = new File(sharedDir(ctx), name);
                if (f.exists()) size = f.length();
            }
        } catch (Exception ignored) { }
        MatrixCursor c = new MatrixCursor(new String[]{
                OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        c.addRow(new Object[]{String.valueOf(name), size});
        return c;
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
                      String[] selectionArgs) {
        return 0;
    }
}
