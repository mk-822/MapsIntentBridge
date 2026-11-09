package jp.mapsintentbridge;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class MainActivity extends Activity {
    private static final int SAVE_GPX = 1;
    private static final long LOAD_TIMEOUT_MS = 25_000;
    private static final String TIMEOUT_MESSAGE = "Google マップからの応答が時間内に届きませんでした。";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService routeWorker = Executors.newFixedThreadPool(2);
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService mapWorker = Executors.newSingleThreadExecutor();
    private LinearLayout content;
    private MapsRoutes.Result result;
    private Uri pendingSave;
    private boolean popup;
    private boolean dark;
    private int loadAttempt;

    @Override protected void onCreate(Bundle state) {
        Intent intent = getIntent();
        popup = this instanceof ShareActivity;
        dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        super.onCreate(state);
        if (popup) setFinishOnTouchOutside(true);
        if (state != null) {
            String savedUri = state.getString("pendingSave");
            if (savedUri != null) pendingSave = Uri.parse(savedUri);
        }
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (text != null) {
                load(text.toString());
                return;
            }
        }
        screen("Maps Intent Bridge");
        line("Google マップで経路を検索し、「共有」から Maps Intent Bridge を選んでください。");
        line("共有リンクから経路候補を読み込み、GPX を作成して対応アプリで開けます。");
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        sizePopup();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (pendingSave != null) state.putString("pendingSave", pendingSave.toString());
    }

    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        routeWorker.shutdownNow();
        worker.shutdownNow();
        mapWorker.shutdownNow();
        super.onDestroy();
    }

    private void load(String text) {
        int attempt = ++loadAttempt;
        screen("経路を取得中");
        TextView status = line("Google マップの共有リンクを開いています。");
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        content.addView(progress, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        Future<?> request = routeWorker.submit(() -> {
            try {
                MapsRoutes.Result loaded = MapsRoutes.load(text, () ->
                        onScreen(() -> {
                            if (attempt == loadAttempt) status.setText("経路候補をダウンロードしています。");
                        }));
                finishLoad(attempt, () -> {
                    result = loaded;
                    showRoutes();
                });
            } catch (JSONException error) {
                finishLoad(attempt, () -> loadError(text, "Google マップの経路データ形式に対応できませんでした。"));
            } catch (SocketTimeoutException error) {
                finishLoad(attempt, () -> loadError(text, TIMEOUT_MESSAGE));
            } catch (IOException error) {
                finishLoad(attempt, () -> loadError(text, error.getLocalizedMessage()));
            }
        });
        main.postDelayed(() -> {
            if (attempt != loadAttempt || isFinishing()) return;
            loadAttempt++;
            request.cancel(true);
            loadError(text, TIMEOUT_MESSAGE);
        }, LOAD_TIMEOUT_MS);
    }

    private void finishLoad(int attempt, Runnable render) {
        onScreen(() -> {
            if (attempt != loadAttempt) return;
            main.removeCallbacksAndMessages(null);
            loadAttempt++;
            render.run();
        });
    }

    private void loadError(String text, String message) {
        screen("経路を取得できませんでした");
        line(message == null ? "通信状態と共有リンクをご確認ください。" : message);
        button("再試行", () -> load(text));
    }

    private void showRoutes() {
        screen(result.destination());
        line("経路を選択してください");
        RouteMapView map = new RouteMapView(this, result.routes(), mapWorker);
        Button[] candidates = new Button[result.routes().size()];
        Button send = new Button(this);
        Button save = new Button(this);
        send.setEnabled(false);
        send.setText("GPX にして開く");
        save.setEnabled(false);
        save.setText("GPX ファイルに保存");
        for (int i = 0; i < candidates.length; i++) {
            int index = i;
            MapsRoutes.Route route = result.routes().get(i);
            candidates[i] = button(RouteMapView.number(i) + " " + route.label(), () -> {
                for (int j = 0; j < candidates.length; j++) {
                    candidates[j].setSelected(j == index);
                    candidates[j].setBackgroundTintList(j == index
                            ? ColorStateList.valueOf(dark ? 0xff293f58 : 0xffdceaff) : null);
                }
                map.select(index);
                send.setText(RouteMapView.number(index) + " を GPX にして開く");
                save.setText(RouteMapView.number(index) + " を GPX ファイルに保存");
                send.setEnabled(true);
                save.setEnabled(true);
                send.setOnClickListener(view -> createGpx(route, candidates, send, save, false));
                save.setOnClickListener(view -> createGpx(route, candidates, send, save, true));
            });
            candidates[i].setTextColor(RouteMapView.labelColor(i, dark));
        }
        int mapHeight = Math.round(260 * getResources().getDisplayMetrics().density);
        content.addView(map, ViewGroup.LayoutParams.MATCH_PARENT, mapHeight);
        content.addView(send, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        content.addView(save, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (candidates.length == 1) candidates[0].performClick();
    }

    private void createGpx(MapsRoutes.Route route, Button[] candidates, Button send, Button save,
                           boolean saveToFile) {
        setRouteControlsEnabled(candidates, send, save, false);
        String name = result.destination() + "（" + route.road() + "）";
        worker.execute(() -> {
            try {
                Uri created = GpxProvider.write(this, name, route.points());
                onScreen(() -> {
                    setRouteControlsEnabled(candidates, send, save, true);
                    if (saveToFile) saveGpx(created);
                    else openGpx(created);
                });
            } catch (IOException | RuntimeException error) {
                onScreen(() -> {
                    setRouteControlsEnabled(candidates, send, save, true);
                    toast("GPX を作成できませんでした。");
                });
            }
        });
    }

    private static void setRouteControlsEnabled(Button[] candidates, Button send, Button save, boolean enabled) {
        for (Button candidate : candidates) candidate.setEnabled(enabled);
        send.setEnabled(enabled);
        save.setEnabled(enabled);
    }

    private void openGpx(Uri gpx) {
        Intent view = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(gpx, GpxProvider.MIME)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        view.setClipData(ClipData.newRawUri("GPX", gpx));
        if (view.resolveActivity(getPackageManager()) == null) {
            toast("GPX を開けるアプリが見つかりません。ファイルとして保存できます。");
            return;
        }
        try {
            startActivity(Intent.createChooser(view, "GPX を開くアプリを選択"));
            finish();
        } catch (ActivityNotFoundException error) {
            toast("アプリの選択画面を開けませんでした。ファイルとして保存できます。");
        }
    }

    private void saveGpx(Uri gpx) {
        Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(GpxProvider.MIME)
                .putExtra(Intent.EXTRA_TITLE, "Maps-route.gpx");
        try {
            pendingSave = gpx;
            startActivityForResult(create, SAVE_GPX);
        } catch (ActivityNotFoundException error) {
            pendingSave = null;
            toast("ファイルの保存画面を開けませんでした。");
        }
    }

    @Override protected void onActivityResult(int request, int code, Intent data) {
        super.onActivityResult(request, code, data);
        if (request != SAVE_GPX) return;
        Uri source = pendingSave;
        pendingSave = null;
        if (code != RESULT_OK) {
            finish();
            return;
        }
        if (data == null || data.getData() == null || source == null) {
            toast("GPX を保存できませんでした。");
            return;
        }
        Uri destination = data.getData();
        worker.execute(() -> {
            try (InputStream input = getContentResolver().openInputStream(source);
                 OutputStream output = getContentResolver().openOutputStream(destination, "w")) {
                if (input == null || output == null) throw new IOException();
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                output.flush();
            } catch (IOException | SecurityException error) {
                onScreen(() -> toast("GPX を保存できませんでした。"));
                return;
            }
            String location = savedLocation(destination);
            onScreen(() -> {
                toast("保存先 " + location);
                finish();
            });
        });
    }

    private String savedLocation(Uri destination) {
        String authority = destination.getAuthority();
        if (DocumentsContract.isDocumentUri(this, destination)) {
            String id = DocumentsContract.getDocumentId(destination);
            if ("com.android.externalstorage.documents".equals(authority) && id.startsWith("primary:")) {
                return "内部ストレージ/" + id.substring("primary:".length());
            }
            if ("com.android.providers.downloads.documents".equals(authority) && id.startsWith("raw:")) {
                return id.substring("raw:".length());
            }
        }

        String name = null;
        try (Cursor cursor = getContentResolver().query(destination,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
        } catch (RuntimeException ignored) {
            // Some providers do not expose the display name.
        }
        ProviderInfo provider = authority == null ? null : getPackageManager().resolveContentProvider(authority, 0);
        if (provider != null && name != null) return provider.loadLabel(getPackageManager()) + "/" + name;
        return name != null ? name : destination.toString();
    }

    private void onScreen(Runnable action) {
        runOnUiThread(() -> {
            if (!isFinishing() && !isDestroyed()) action.run();
        });
    }

    private void screen(String title) {
        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(20 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding, padding, padding);
        scroll.addView(content);
        setContentView(scroll);
        sizePopup();
        TextView heading = line(title);
        heading.setTextSize(24);
    }

    private void sizePopup() {
        if (!popup) return;
        int screenWidth = getResources().getConfiguration().screenWidthDp;
        float density = getResources().getDisplayMetrics().density;
        int width = Math.round(Math.min(560, screenWidth * 0.95f) * density);
        getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private TextView line(String value) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(16);
        text.setPadding(0, 0, 0, 12);
        content.addView(text);
        return text;
    }

    private Button button(String label, Runnable action) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(label);
        button.setOnClickListener(view -> action.run());
        content.addView(button, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        return button;
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}
