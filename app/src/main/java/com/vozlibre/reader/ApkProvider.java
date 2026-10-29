package com.vozlibre.reader;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Proveedor mínimo (sin librerías externas) para compartir con el
 * instalador del sistema el APK de la actualización ya descargado.
 * Solo entrega archivos de la carpeta de actualizaciones de VozLibre.
 */
public class ApkProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode)
            throws FileNotFoundException {
        if (mode != null && mode.contains("w")) {
            throw new SecurityException("VozLibre solo permite lectura.");
        }
        String name = uri.getLastPathSegment();
        if (name == null || name.length() == 0
                || name.contains("..") || name.contains("/") || name.contains("\\")) {
            throw new FileNotFoundException("Nombre no válido.");
        }
        File dir = UpdateManager.updatesDir(getContext());
        File apk = new File(dir, name);
        if (!apk.isFile()) throw new FileNotFoundException("No descargado todavía.");
        return ParcelFileDescriptor.open(apk, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
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
