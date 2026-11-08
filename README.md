# LINE External Browser

LINE のメッセージ内リンクを、アプリ内ブラウザではなく**端末のデフォルトブラウザ**で開く LSposed モジュールです。

## 動作確認環境

- OPPO Find N6 (Android 16 / API 36)
- LINE 26.17.1
- LSposed (KernelSU)

## ダウンロード

[Releases ページ](https://github.com/AndroPlus-org/xposed-browser-redirect-line/releases/latest) から最新の APK (`line-external-browser-vX.X.apk` 、v1.0 のみ `app-release.apk`) をダウンロードしてください。

## インストール手順

1. ダウンロードした APK をインストールする
2. LSposed マネージャーで「LINE External Browser」を有効にする
3. スコープで **LINE (`jp.naver.line.android`)** にチェックを入れる
4. LINE を強制停止して起動し直す

## 仕組み

- `Instrumentation.execStartActivity` をフックし、LINE のアプリ内ブラウザ (`com.linecorp.line.iab.browser.impl.InAppBrowserActivity`) の起動要求を、デフォルトブラウザへの `ACTION_VIEW` に差し替えます。
- 上記を経由せずにアプリ内ブラウザが開いた場合は、`onCreate` 直後に URL をデフォルトブラウザへ渡して画面を閉じます。
- `line.me` / `line-apps.com` / `naver.jp` など LINE 自身のドメインは、ログインや設定画面が壊れないようアプリ内ブラウザのまま開きます。

## ビルド

JDK 17 以上と Android SDK (compileSdk 36) が必要です。

```bash
./gradlew assembleRelease
```

APK は `app/build/outputs/apk/release/app-release.apk` に出力されます。

## リリース方法

`v*` 形式のタグを push すると、GitHub Actions が Actions secrets の鍵で署名した APK をビルドし、リリースを作成します。

```bash
git tag v1.1
git push origin v1.1
```

使用する secrets: `SIGNING_KEYSTORE_BASE64` / `SIGNING_STORE_PASSWORD` / `SIGNING_KEY_ALIAS` / `SIGNING_KEY_PASSWORD`
