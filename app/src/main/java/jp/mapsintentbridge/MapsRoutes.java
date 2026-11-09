package jp.mapsintentbridge;

import android.net.Uri;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;

final class MapsRoutes {
    private static final Pattern LINK = Pattern.compile("https?://[^\\s<>\"]+");
    private static final Pattern PREVIEW = Pattern.compile(
            "<link\\b[^>]*\\bhref=[\"'](/maps/preview/directions\\?[^<>\"']+)[\"']",
            Pattern.CASE_INSENSITIVE);
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final int MAX_BYTES = 8 * 1024 * 1024;

    record Point(double lat, double lon) {}
    record Route(String road, String distance, String duration, List<Point> points) {
        String label() {
            return String.join("  ·  ", road, distance, duration);
        }
    }
    record Result(String destination, List<Route> routes) {}
    private record Page(URL url, String body) {}

    static Result load(String sharedText) throws IOException, JSONException {
        URL link = findLink(sharedText);
        Page directions = fetch(link, false);
        if (!directions.url().getPath().matches("/maps/dir(?:/.*)?")) {
            throw new IOException("Google マップの「経路」から共有してください。");
        }
        Matcher preview = PREVIEW.matcher(directions.body());
        if (!preview.find()) {
            throw new IOException("経路データが見つかりません。出発地と目的地が入った経路を共有してください。");
        }
        String path = preview.group(1).replace("&amp;", "&");
        return parse(fetch(new URL(directions.url(), path), true).body());
    }

    private static URL findLink(String sharedText) throws IOException {
        Matcher matcher = LINK.matcher(sharedText);
        while (matcher.find()) {
            Uri uri = Uri.parse(matcher.group());
            if (allowedHost(uri.getHost()) && uri.getUserInfo() == null && uri.getPort() == -1) {
                return new URL(uri.buildUpon().scheme("https").fragment(null).build().toString());
            }
        }
        throw new IOException("共有内容に Google マップの経路リンクがありません。");
    }

    private static boolean allowedHost(String host) {
        if (host == null) return false;
        return switch (host.toLowerCase(Locale.ROOT)) {
            case "maps.app.goo.gl", "goo.gl", "google.com", "www.google.com", "maps.google.com",
                    "google.co.jp", "www.google.co.jp", "maps.google.co.jp" -> true;
            default -> false;
        };
    }

    private static Page fetch(URL url, boolean preview) throws IOException {
        for (int redirect = 0; redirect <= 5; redirect++) {
            if (!"https".equals(url.getProtocol()) || !allowedHost(url.getHost())
                    || url.getUserInfo() != null || url.getPort() != -1
                    || (preview && !"/maps/preview/directions".equals(url.getPath()))) {
                throw new IOException("Google マップのリンクを開けませんでした。");
            }
            HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
            try {
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(12000);
                connection.setReadTimeout(12000);
                connection.setRequestProperty("User-Agent", USER_AGENT);
                connection.setRequestProperty("Accept-Language", "ja,en;q=0.8");
                int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) break;
                    url = new URL(url, location);
                    continue;
                }
                if (status != 200) {
                    throw new IOException("Google マップから経路を取得できませんでした（HTTP " + status + "）。");
                }
                try (InputStream input = connection.getInputStream();
                     ByteArrayOutputStream body = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (body.size() + count > MAX_BYTES) throw new IOException("経路データが大きすぎます。");
                        body.write(buffer, 0, count);
                    }
                    return new Page(url, new String(body.toByteArray(), StandardCharsets.UTF_8));
                }
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("共有リンクの転送先を取得できませんでした。");
    }

    // The Maps web preview provides one geometry per route, in route order.
    // Each axis starts with an absolute E7 coordinate followed by signed E7 deltas.
    private static Result parse(String body) throws IOException, JSONException {
        if (body.startsWith(")]}'")) body = body.substring(4);
        JSONArray response = new JSONArray(body.trim()).getJSONArray(0);
        JSONArray descriptions = response.getJSONArray(1);
        JSONArray geometries = response.getJSONArray(7);
        if (descriptions.length() != geometries.length() || geometries.length() == 0) {
            throw new IOException("経路と座標を対応付けられませんでした。");
        }
        List<Route> routes = new ArrayList<>();
        for (int i = 0; i < geometries.length(); i++) {
            JSONArray summary = descriptions.getJSONArray(i).getJSONArray(0);
            String road = summary.optString(1, "").trim();
            if (road.isEmpty()) road = "経路 " + (i + 1);
            String distance = displayValue(summary, 2);
            String duration = displayValue(summary, 3);
            routes.add(new Route(road, distance, duration, points(geometries.getJSONArray(i))));
        }
        JSONArray stops = response.getJSONArray(0);
        String address = stops.getJSONArray(stops.length() - 1)
                .getJSONArray(0).getJSONArray(0).optString(0, "");
        String destination = address.split("[,、]", 2)[0].trim();
        return new Result(TextUtils.isEmpty(destination) ? "Google マップの経路" : destination, routes);
    }

    private static String displayValue(JSONArray summary, int index) {
        JSONArray value = summary.optJSONArray(index);
        return value == null ? "—" : value.optString(1, "—");
    }

    private static List<Point> points(JSONArray geometry) throws IOException, JSONException {
        JSONArray latitudes = geometry.getJSONArray(0);
        JSONArray longitudes = geometry.getJSONArray(1);
        int count = latitudes.length();
        if (count != longitudes.length() || count < 2) {
            throw new IOException("経路の座標が不足しています。");
        }
        List<Point> points = new ArrayList<>(count);
        long lat = 0;
        long lon = 0;
        for (int i = 0; i < count; i++) {
            lat += latitudes.getInt(i);
            lon += longitudes.getInt(i);
            if (Math.abs(lat) > 900_000_000L || Math.abs(lon) > 1_800_000_000L) {
                throw new IOException("経路の座標が無効です。");
            }
            points.add(new Point(lat / 1e7, lon / 1e7));
        }
        return points;
    }
}
