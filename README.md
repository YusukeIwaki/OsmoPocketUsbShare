# OsmoPocket USB Share

Osmo Pocket 4をUSB（OTG・マスストレージ）でAndroidに接続し、写真・動画を取り込んで、動画は2Kに圧縮しつつ `DCIM/OsmoPocketUsbShare` へ書き出すアプリです。書き出し先はGoogleフォトアプリが見える場所なので、同期はGoogleフォト側に任せます。

- アプリ名: OsmoPocket USB Share
- パッケージ: `io.yusukeiwaki.osmopocketusbshare`
- 技術: Jetpack Compose / Navigation Compose / Material 3 / Media3 Transformer（MediaCodecハードウェア変換）

## 画面と操作

| 画面 | 内容 |
| --- | --- |
| 待機 | USB未接続時は「USBでOsmo Pocketをさしてください」のみ |
| 一覧 | グリッド／リスト切替。リストはサムネイル・ファイル名・サイズ／撮影日時（Material 3 ListItem） |
| 選択 | 長押しで複数選択。選択0件で自動的に選択モード解除。選択中は戻る操作で選択だけ解除 |
| 詳細 | 左右スワイプで移動。撮影日時・ファイルサイズを必ず表示。メニューから1件エクスポート |
| エクスポート状況 | データベース（SQLite）の状態を表示。待機中／処理中はキャンセル可能 |

## エクスポート

- 写真: 変換なしでそのままコピー。
- 動画: 長辺2560（2K、拡大なし）・HEVC・自動ビットレート（`slim_videos.py` の式を移植）・AAC。HDR（PQ/HLG）は圧縮せず元動画をコピー。DJIのデータトラックは含めません。
- 処理はフォアグラウンドサービスが1件ずつ実行し、アプリを閉じても継続します。状態はSQLiteに保存され、画面はそれを表示するだけです。
- キャンセル時は一時ファイルを削除し、未完成のMediaStoreエントリも残しません。プロセスが強制終了された場合の一時ファイルも、次回サービス起動時に掃除します。

USBフォルダを一度選ぶと、そのフォルダと読み取り許可を保存し、アプリの再起動・USBの再接続後も自動で読み込みます。旧バージョンで許可済みの場合は、最後に許可したフォルダを引き継ぎます。読み込めた場合はフォルダ選択を表示せず、フォルダが見つからない・権限がない・写真や動画がない場合に別のフォルダを選択できます。USB未接続時にも保存先は保持します。

マウント完了の通常通知が届かないUSBストレージでも、保存したフォルダのプロバイダの変更通知で自動的に読み直します。

選択前はMediaStoreのUSBボリュームと、直接読み取れるリムーバブルボリュームを自動検索します。フォルダ選択が必要な場合は、前回のフォルダまたはUSB内のDCIMを起点にシステムの選択画面を開きます。

## ビルドとテスト

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew :app:assembleDebug
# 単体テスト: フォルダの保存・引き継ぎ・切断／再接続・権限失効など
./gradlew :app:testDebugUnitTest
# 実機の設定・履歴を変更しないフォルダ選択UIテスト
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.yusukeiwaki.osmopocketusbshare.RememberedFolderUiTest
# USBを外したテスト専用端末／エミュレータで実行（既存のフォルダ許可・履歴を初期化）
./gradlew :app:connectedDebugAndroidTest
```

`RememberedFolderTest`（単体テスト11件）で保存先の引き継ぎ・再利用・切断後の再試行・権限失効などを、`RememberedFolderUiTest`（実機UIテスト6件）でフォルダ選択の表示条件を確認します。

既存のInstrumentation Test（`OsmoPocketUiTest`）では、未接続表示・グリッド／リスト切替・選択解除・詳細スワイプとメタデータ・戻る遷移・写真／動画エクスポート・キャンセル時の後片付けを自動確認しています。証跡は [test_report.html](test_report.html) を参照してください。

## 動画変換の評価

`slim_videos.py` との差分評価（SSIM、実機出力の実測を含む）は [evaluation/results.md](evaluation/results.md) にあります。要点: 実機出力SSIM 0.9916（基準0.97超）、スケーリングフィルタ差は0.001〜0.005。一方で端末経路では10bit素材が8bit出力になる点と、難素材（`0006` 相当）では端末でSSIMゲートを行わない点が既知の制限です。

## 紹介サイト

```bash
cd website && npm install && npm run build && npm run preview
```

`website/` にAstro製の紹介サイト（トップ／見る／USB取り込み／2K圧縮／エクスポート）があります。
