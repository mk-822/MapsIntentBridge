# Maps Intent Bridge

Google マップの「経路」の共有メニューから起動し、共有リンクの経路候補を GPX に変換して対応アプリで開く Android アプリです。

Android 6.0（API 23）以降が対象です。経路の取得と背景地図の表示にはインターネット接続が必要です。

## 使い方

1. Google マップで出発地と目的地を指定します。自転車で走る場合は自転車モードを選びます。
2. 経路画面の「共有」から **Maps Intent Bridge** を選びます。
3. ①②③の候補を選び、下の地図で線形を確認します。「GPX にして開く」を押します。
4. Android の選択画面から GPX を開くアプリを選びます。

ファイルとして保存する場合は、経路を選んで「① を GPX ファイルに保存」のような番号付きのボタンを押し、Android の保存画面で保存先を指定します。

## 経路と地図

共有 Intent の短縮リンクを解決し、Google マップのウェブプレビューが返す候補の座標列を使用します。共有時に選択していた候補を確実に特定できないため、複数候補から選択します。ウェブ側の再計算によって候補や距離が変わる場合があります。ウェブプレビューは公開 API ではないため、応答形式が変わると取得できなくなる可能性があります。

背景地図は [OpenStreetMap](https://www.openstreetmap.org/copyright) です。表示範囲の地図画像を OpenStreetMap から取得します。背景画像が取得できないときも経路の線は表示します。

GPX 1.1 のトラックをアプリの一時領域に作成します。MIME タイプは `application/gpx+xml` です。選択したアプリには、読み取り権限付きの `content://` URI を `ACTION_VIEW` で渡します。対応アプリが見つからない場合も、GPX はファイルに保存できます。

## ビルド

JDK 17 と Android SDK Platform 35 を用意してください。Android Studio でプロジェクトを開くか、`ANDROID_HOME` または `local.properties` の `sdk.dir` で SDK を指定して、リポジトリ直下で Gradle Wrapper を実行します。

```powershell
.\gradlew.bat assembleDebug lintDebug
```

macOS・Linux では `./gradlew assembleDebug lintDebug` を実行します。

USB デバッグが有効な端末へインストールするには、Android SDK Platform-Tools の `adb` を使います。

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- [Google マップの経路共有](https://support.google.com/maps/answer/7101463?co=GENIE.Platform%3DAndroid&hl=ja)
