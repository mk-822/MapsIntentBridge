package jp.mapsintentbridge;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.net.http.HttpResponseCache;
import android.view.View;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import javax.net.ssl.HttpsURLConnection;

final class RouteMapView extends View {
    private static final int TILE_SIZE = 256;
    private static final int[] COLORS = {0xff1557b0, 0xffa02971, 0xff087861, 0xffa64a00};
    private static final int[] DARK_LABEL_COLORS = {0xff9bc8ff, 0xffffa8d5, 0xff82e6cc, 0xffffbf83};
    private static final String USER_AGENT =
            "MapsIntentBridge/1.0 (+https://github.com/mk-822/MapsIntentBridge)";
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final ExecutorService worker;
    private final float density;
    private final List<List<ProjectedPoint>> lines = new ArrayList<>();
    private final Map<Tile, Bitmap> tiles = new HashMap<>();
    private List<Tile> visibleTiles = Collections.emptyList();
    private Future<?> tileJob;
    private double minX = Double.POSITIVE_INFINITY;
    private double minY = Double.POSITIVE_INFINITY;
    private double maxX = Double.NEGATIVE_INFINITY;
    private double maxY = Double.NEGATIVE_INFINITY;
    private double pixelsPerWorld;
    private double left;
    private double top;
    private int selected = -1;

    private record ProjectedPoint(double x, double y) {}
    private record Tile(int zoom, int x, int y) {}

    RouteMapView(Context context, List<MapsRoutes.Route> routes, ExecutorService worker) {
        super(context);
        this.worker = worker;
        density = getResources().getDisplayMetrics().density;
        setContentDescription("候補経路の地図");
        double referenceLon = routes.get(0).points().get(0).lon();
        for (MapsRoutes.Route route : routes) {
            List<ProjectedPoint> line = new ArrayList<>();
            for (MapsRoutes.Point point : route.points()) {
                double lon = referenceLon + Math.IEEEremainder(point.lon() - referenceLon, 360);
                double lat = Math.max(-85.05112878, Math.min(85.05112878, point.lat()));
                double sin = Math.sin(Math.toRadians(lat));
                double x = (lon + 180) / 360;
                double y = 0.5 - Math.log((1 + sin) / (1 - sin)) / (4 * Math.PI);
                line.add(new ProjectedPoint(x, y));
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
            }
            lines.add(line);
        }
    }

    static String number(int index) {
        return index < 20 ? Character.toString((char) ('①' + index)) : "(" + (index + 1) + ")";
    }

    private static int color(int index) {
        return COLORS[index % COLORS.length];
    }

    static int labelColor(int index, boolean dark) {
        return dark ? DARK_LABEL_COLORS[index % DARK_LABEL_COLORS.length] : color(index);
    }

    void select(int index) {
        selected = index;
        setContentDescription("候補経路の地図" + (index < 0 ? "" : "、" + number(index) + "を選択中"));
        invalidate();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        if (width == 0 || height == 0) return;
        double fit = Math.min(
                Math.max(1, width - 48 * density) / (maxX - minX),
                Math.max(1, height - 48 * density) / (maxY - minY));
        int zoom = 18;
        while (zoom > 0 && TILE_SIZE * (1 << zoom) * density > fit) zoom--;
        int tileCount = 1 << zoom;
        double tilePixels = TILE_SIZE * density;
        pixelsPerWorld = tilePixels * tileCount;
        left = (minX + maxX) * 0.5 * pixelsPerWorld - width * 0.5;
        top = (minY + maxY) * 0.5 * pixelsPerWorld - height * 0.5;
        List<Tile> visible = new ArrayList<>();
        for (int y = Math.max(0, (int) Math.floor(top / tilePixels));
             y < tileCount && y * tilePixels < top + height; y++) {
            for (int x = (int) Math.floor(left / tilePixels);
                 x * tilePixels < left + width; x++) {
                visible.add(new Tile(zoom, x, y));
            }
        }
        visibleTiles = visible;
        tiles.keySet().retainAll(visible);
        if (tileJob != null) tileJob.cancel(true);
        tileJob = worker.submit(() -> {
            for (Tile tile : visible) {
                if (Thread.currentThread().isInterrupted()) return;
                Bitmap bitmap = download(tile);
                if (bitmap != null) post(() -> {
                    if (visibleTiles.contains(tile)) {
                        tiles.put(tile, bitmap);
                        invalidate();
                    }
                });
            }
        });
    }

    @Override protected void onDetachedFromWindow() {
        if (tileJob != null) tileJob.cancel(true);
        super.onDetachedFromWindow();
    }

    private Bitmap download(Tile tile) {
        HttpsURLConnection connection = null;
        try {
            if (HttpResponseCache.getInstalled() == null) {
                HttpResponseCache.install(new File(getContext().getCacheDir(), "map-tiles"), 24L * 1024 * 1024);
            }
            int tileCount = 1 << tile.zoom();
            int x = (tile.x() % tileCount + tileCount) % tileCount;
            URL url = new URL("https://tile.openstreetmap.org/" + tile.zoom() + "/" + x + "/" + tile.y() + ".png");
            connection = (HttpsURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(3000);
            if (connection.getResponseCode() != 200) return null;
            try (InputStream input = connection.getInputStream()) {
                return BitmapFactory.decodeStream(input);
            }
        } catch (IOException error) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        canvas.drawColor(0xffe9edf0);
        float tilePixels = TILE_SIZE * density;
        paint.setAlpha(255);
        for (Tile tile : visibleTiles) {
            Bitmap bitmap = tiles.get(tile);
            if (bitmap == null) continue;
            float x = (float) (tile.x() * (double) tilePixels - left);
            float y = (float) (tile.y() * (double) tilePixels - top);
            canvas.drawBitmap(bitmap, null, new RectF(x, y, x + tilePixels, y + tilePixels), paint);
        }

        for (int i = lines.size() - 1; i >= 0; i--) {
            if (i != selected) drawLine(canvas, i, false);
        }
        if (selected >= 0) drawLine(canvas, selected, true);
        for (int i = 0; i < lines.size(); i++) drawNumber(canvas, i);

        String attribution = "© OpenStreetMap contributors";
        paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(10 * density);
        paint.setTextAlign(Paint.Align.LEFT);
        float textLeft = getWidth() - paint.measureText(attribution) - 5 * density;
        paint.setColor(0xeaffffff);
        canvas.drawRect(textLeft - 4 * density, getHeight() - 16 * density, getWidth(), getHeight(), paint);
        paint.setColor(0xff182028);
        canvas.drawText(attribution, textLeft, getHeight() - 4 * density, paint);
    }

    private void drawLine(Canvas canvas, int index, boolean highlighted) {
        List<ProjectedPoint> line = lines.get(index);
        Path path = new Path();
        path.moveTo(x(line.get(0)), y(line.get(0)));
        for (int i = 1; i < line.size(); i++) path.lineTo(x(line.get(i)), y(line.get(i)));
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(Color.WHITE);
        paint.setStrokeWidth((highlighted ? 9 : 6) * density);
        canvas.drawPath(path, paint);
        paint.setColor(color(index));
        if (selected >= 0 && !highlighted) paint.setAlpha(155);
        paint.setStrokeWidth((highlighted ? 6 : 3.5f) * density);
        canvas.drawPath(path, paint);
    }

    private void drawNumber(Canvas canvas, int index) {
        List<ProjectedPoint> line = lines.get(index);
        int pointIndex = (int) ((line.size() - 1) * (index + 1.0) / (lines.size() + 1));
        ProjectedPoint anchor = line.get(pointIndex);
        float x = x(anchor);
        float y = y(anchor);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        canvas.drawCircle(x, y, 12 * density, paint);
        paint.setColor(color(index));
        canvas.drawCircle(x, y, 10 * density, paint);
        paint.setColor(Color.WHITE);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(12 * density);
        canvas.drawText(Integer.toString(index + 1), x, y - (paint.ascent() + paint.descent()) / 2, paint);
    }

    private float x(ProjectedPoint point) {
        return (float) (point.x() * pixelsPerWorld - left);
    }

    private float y(ProjectedPoint point) {
        return (float) (point.y() * pixelsPerWorld - top);
    }
}
