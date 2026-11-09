package jp.mapsintentbridge;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.Xml;

import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class GpxProvider extends ContentProvider {
    static final String MIME = "application/gpx+xml";
    private static final String GPX = "http://www.topografix.com/GPX/1/1";

    static Uri write(Context context, String name, List<MapsRoutes.Point> points) throws IOException {
        File directory = new File(context.getCacheDir(), "gpx");
        if (!directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("GPX の保存先を作成できませんでした。");
        }
        File[] expired = directory.listFiles(
                file -> System.currentTimeMillis() - file.lastModified() > TimeUnit.DAYS.toMillis(7));
        if (expired != null) {
            for (File file : expired) file.delete();
        }

        File file = new File(directory, "route-" + UUID.randomUUID() + ".gpx");
        try (FileOutputStream output = new FileOutputStream(file)) {
            XmlSerializer xml = Xml.newSerializer();
            xml.setOutput(output, StandardCharsets.UTF_8.name());
            xml.startDocument("UTF-8", true);
            xml.setPrefix("", GPX);
            xml.startTag(GPX, "gpx").attribute(null, "version", "1.1")
                    .attribute(null, "creator", "Maps Intent Bridge");
            xml.startTag(GPX, "trk");
            xml.startTag(GPX, "name").text(name).endTag(GPX, "name");
            xml.startTag(GPX, "trkseg");
            for (MapsRoutes.Point point : points) {
                xml.startTag(GPX, "trkpt")
                        .attribute(null, "lat", Double.toString(point.lat()))
                        .attribute(null, "lon", Double.toString(point.lon()))
                        .endTag(GPX, "trkpt");
            }
            xml.endTag(GPX, "trkseg").endTag(GPX, "trk").endTag(GPX, "gpx");
            xml.endDocument();
        } catch (IOException | RuntimeException error) {
            file.delete();
            throw error;
        }
        return new Uri.Builder().scheme("content").authority(context.getPackageName() + ".gpx")
                .appendPath(file.getName()).build();
    }

    private File file(Uri uri) {
        List<String> segments = uri.getPathSegments();
        if (segments.size() != 1 || !segments.get(0).matches("route-[a-f0-9-]{36}\\.gpx")) {
            throw new IllegalArgumentException("Invalid GPX URI");
        }
        return new File(new File(getContext().getCacheDir(), "gpx"), segments.get(0));
    }

    @Override public boolean onCreate() {
        return true;
    }

    @Override public String getType(Uri uri) {
        file(uri);
        return MIME;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read only");
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        File file = file(uri);
        String[] columns = projection == null
                ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor cursor = new MatrixCursor(columns);
        if (file.isFile()) {
            Object[] values = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                values[i] = switch (columns[i]) {
                    case OpenableColumns.DISPLAY_NAME -> file.getName();
                    case OpenableColumns.SIZE -> file.length();
                    default -> null;
                };
            }
            cursor.addRow(values);
        }
        return cursor;
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }

    @Override public int delete(Uri uri, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }
}
