package jp.mapsintentbridge;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.content.res.ColorStateList;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int SAVE_GPX = 1;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService mapWorker = Executors.newSingleThreadExecutor();
    private LinearLayout content;
    private MapsRoutes.Result result;
    private Uri gpx;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            String savedUri = state.getString("gpx");
            if (savedUri != null) {
                gpx = Uri.parse(savedUri);
                showGpx(null, false);
                return;
            }
        }
        Intent intent = getIntent();
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

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (gpx != null) state.putString("gpx", gpx.toString());
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        mapWorker.shutdownNow();
        super.onDestroy();
    }

    private void load(String text) {
        screen("経路を取得中");
        line("Google マップの共有リンクを開いています。");
        worker.execute(() -> {
            try {
                MapsRoutes.Result loaded = MapsRoutes.load(text);
                onScreen(() -> {
                    result = loaded;
                    showRoutes();
                });
            } catch (JSONException error) {
                onScreen(() -> loadError(text, "Google マップの経路データ形式に対応できませんでした。"));
            } catch (IOException error) {
                onScreen(() -> loadError(text, error.getLocalizedMessage()));
            }
        });
    }

    private void loadError(String text, String message) {
        screen("経路を取得できませんでした");
        line(message == null ? "通信状態と共有リンクをご確認ください。" : message);
        button("再試行", () -> load(text));
    }

    private void showRoutes() {
        gpx = null;
        screen(result.destination());
        line("共有リンクの経路候補を地図で確認できます。");
        RouteMapView map = new RouteMapView(this, result.routes(), mapWorker);
        Button[] candidates = new Button[result.routes().size()];
        Button send = new Button(this);
        send.setEnabled(false);
        send.setText("経路を選択してください");
        for (int i = 0; i < candidates.length; i++) {
            int index = i;
            MapsRoutes.Route route = result.routes().get(i);
            candidates[i] = button(RouteMapView.number(i) + " " + route.label(), () -> {
                for (int j = 0; j < candidates.length; j++) {
                    candidates[j].setSelected(j == index);
                    candidates[j].setBackgroundTintList(j == index
                            ? ColorStateList.valueOf(0xffdceaff) : null);
                }
                map.select(index);
                send.setText(RouteMapView.number(index) + " を GPX にして開く");
                send.setEnabled(true);
                send.setOnClickListener(view -> createGpx(route));
            });
            candidates[i].setTextColor(RouteMapView.color(i));
        }
        int mapHeight = Math.round(260 * getResources().getDisplayMetrics().density);
        content.addView(map, ViewGroup.LayoutParams.MATCH_PARENT, mapHeight);
        content.addView(send, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (candidates.length == 1) candidates[0].performClick();
    }

    private void createGpx(MapsRoutes.Route route) {
        screen("GPX を作成中");
        String name = result.destination() + "（" + route.road() + "）";
        worker.execute(() -> {
            try {
                Uri created = GpxProvider.write(this, name, route.points());
                onScreen(() -> {
                    gpx = created;
                    showGpx(route, true);
                });
            } catch (IOException | RuntimeException error) {
                onScreen(() -> {
                    showRoutes();
                    toast("GPX を作成できませんでした。");
                });
            }
        });
    }

    private void showGpx(MapsRoutes.Route route, boolean open) {
        screen("GPX を作成しました");
        if (route != null) line(route.label() + "（" + route.points().size() + " 点）");
        button("GPX を開く", this::openGpx);
        button("GPX をファイルに保存", this::saveGpx);
        if (result != null) button("経路の選択に戻る", this::showRoutes);
        if (open) openGpx();
    }

    private void openGpx() {
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
        } catch (ActivityNotFoundException error) {
            toast("アプリの選択画面を開けませんでした。ファイルとして保存できます。");
        }
    }

    private void saveGpx() {
        Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(GpxProvider.MIME)
                .putExtra(Intent.EXTRA_TITLE, "Maps-route.gpx");
        try {
            startActivityForResult(create, SAVE_GPX);
        } catch (ActivityNotFoundException error) {
            toast("ファイルの保存画面を開けませんでした。");
        }
    }

    @Override protected void onActivityResult(int request, int code, Intent data) {
        super.onActivityResult(request, code, data);
        if (request != SAVE_GPX || code != RESULT_OK || data == null || data.getData() == null || gpx == null) {
            return;
        }
        Uri source = gpx;
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
            onScreen(() -> toast("GPX を保存しました。"));
        });
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
        TextView heading = line(title);
        heading.setTextSize(24);
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
