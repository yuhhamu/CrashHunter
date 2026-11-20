> [!IMPORTANT]
> **🤖 AIを利用して開発しています / Developed with AI assistance**
>
> CrashHunter の開発(設計・コード・文章)には、AI(Anthropic の Claude)を利用しています。アプリ自体はAIを使わず、Modの一覧やクラッシュの内容をAIサービスに送ることもありません。内容の確認と公開は作者が行っています。
>
> CrashHunter was designed, coded and documented with the help of AI (Anthropic's Claude). The app itself does not use AI and never sends your mod list or crash data to any AI service. The author reviews and publishes every release.

# CrashHunter

> [!NOTE]
> **ベータ版です / Beta.** 0.x の間は、機能や設定の形が変わることがあります。不具合は [Issues](https://github.com/yuhhamu/CrashHunter/issues) へお願いします。

> Minecraft 公式の製品ではありません。Mojang・Microsoft・対応しているランチャーの開発元の承認や関与はありません。 / NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG, MICROSOFT OR THE DEVELOPERS OF SUPPORTED LAUNCHERS.

Mod に対応したサードパーティ製の Minecraft ランチャー向けの、クラッシュ診断・原因Mod特定・修正ツールです。
Modを入れる必要はありません。ランチャーの外から動きます。対応しているランチャーは「動作環境」を見てください。

## できること

- **診断**: クラッシュレポート・`hs_err`・`latest.log`・Mod構成を読み、**原因Mod・原因・解決策**を表示します。
  前提Mod不足、バージョン違い、ローダーが古い、重複、別ローダー用のMod、Javaのバージョン、メモリ不足、Mixin競合、
  壊れたjar、グラフィックドライバー、フリーズなどを見分けます。
- **自動修正**: 前提Modの追加、壊れたjarや合わないバージョンの差し替え、メモリやJavaの設定などを、確認のうえで直します。
  直したあとに実際に起動して確かめ、すべて元に戻せます。
- **原因特定**: ランチャーでゲームを自動で起動し直し、Modを切り替えながら原因Modを確定します。
  2つのModの組み合わせで起きるクラッシュや、前提Modとの関係も考えて探します。複数のゲームを並べて起動し、時間を短くできます。
- **構成調整**: 「できるだけ多くのModを残したまま起動できるようにする」機能です。
  Modごとに「任意・必須・固定」と使ってよいバージョンを決めておくと、原因のModのバージョン変更、Forgeのバージョン変更、
  外してよいModの除外を組み合わせて、起動できる構成を探します。結果は確認してから元のインスタンスに反映します。
- **CrashHunter Database**(任意・初期値はオフ): ほかの利用者が見つけた原因・壊れたファイル・対処法を、診断・原因特定・構成調整で先に使います。
  自分の結果を送って共有することもできます。送る内容は [docs/database-privacy.md](docs/database-privacy.md) を見てください。
- 原因特定・構成調整は**作業コピー**で行うので、元のインスタンスは変わりません。途中で止めても、PCが落ちても、次の起動時に元に戻します。
- 結果のコピーでは、ユーザー名・パス・トークンなどを伏せます。

## 対応

| | 状況 |
|---|---|
| OS | Windows 10/11(64bit) |
| 対応ランチャー | Prism Launcher(インストール版・ポータブル版)。ほかのランチャーは今後対応予定 |
| ローダー | Forge 1.20.1 で確認済み。NeoForge・Fabric・Quilt・旧Forge(1.12以前)のクラッシュも読めます |

## 使い方

1. [リリース](https://github.com/yuhhamu/CrashHunter/releases)から `CrashHunter-Setup-x.y.z.exe` を入れます(ポータブル版はzipを展開して `CrashHunter.exe`)。
2. 初回起動時に利用規約と、データベースを使うかどうかを選びます(あとから設定で変えられます)。
3. Prismは自動で探します。見つからなければ「設定」で `prismlauncher.exe` を選びます。
4. 上の欄でインスタンスを選ぶと、クラッシュの一覧と診断結果が出ます。
5. 原因が分からないときは「原因特定」、Modをなるべく残して起動させたいときは「構成調整」を使います。1回の試行に1〜2分かかります。

新しいバージョンがあると、起動時に画面左下とトレイで知らせます(設定でオフにできます)。

### 公式の配布元と、本物かどうかの確かめ方

CrashHunter の配布元は **https://github.com/yuhhamu/CrashHunter/releases だけ**です。ほかのサイトやミラー、知らない人から受け取ったファイルは使わないでください。

コード署名(Authenticode)はしていないため、初回起動時にWindowsの「PCを保護しました」が出ることがあります。「詳細情報」→「実行」で起動できます。
そのかわり、リリースのファイルは作者の鍵で署名しています。アプリ内の更新は、署名した更新情報(`manifest.json`)で版とハッシュを確かめてから入れ、今より古い版には戻しません。

手で確かめるときは、ダウンロードしたファイルと同じフォルダーに `SHA256SUMS.txt` を置いて、PowerShell で次を実行します。

```powershell
Get-FileHash CrashHunter-Setup-x.y.z.exe -Algorithm SHA256
```

表示された値(小文字にしたもの)が `SHA256SUMS.txt` の同じファイル名の行と一致すれば、配布されたままのファイルです。

`SHA256SUMS.txt` そのものの署名は、OpenSSL 3 があれば次で確かめられます(`SHA256SUMS.txt.sig` は Base64 で書いた Ed25519 署名です)。

```powershell
Set-Content crashhunter-update.pub.pem "-----BEGIN PUBLIC KEY-----`nMCowBQYDK2VwAyEAqbphWCLzOjftvUptjktkQJ+8iQ9v+067jNB3nAbTLBk=`n-----END PUBLIC KEY-----"
[IO.File]::WriteAllBytes("$PWD\SHA256SUMS.sig.bin", [Convert]::FromBase64String((Get-Content SHA256SUMS.txt.sig -Raw).Trim()))
openssl pkeyutl -verify -pubin -inkey crashhunter-update.pub.pem -rawin -in SHA256SUMS.txt -sigfile SHA256SUMS.sig.bin
```

`Signature Verified Successfully` と出れば、作者が署名したものです。公開鍵は上の1行と、アプリの中の値が同じです。
普段の鍵に問題が起きたときだけ、予備の鍵 `MCowBQYDK2VwAyEAmTaxUCUY7QDd6XdcPrPFyPUCiZNx5WtIWFY5Cp3Keqg=` で署名します(`installer/update-key-backup.pub` と同じ値)。

### CurseForge のModの取得

CurseForge からのダウンロードには API キーが必要です。CrashHunter 用のキーは申請中のため、それまでは「設定」で自分のキーを登録したときだけ使えます。
Modrinth からの取得はキーなしで使えます。

### 解決策の追加

`%APPDATA%\CrashHunter\solutions.json` に、組み込みの `solutions.json` と同じ形で書くと、原因ごとの説明と解決策を上書き・追加できます。

## 不具合の報告

[GitHub の Issue](https://github.com/yuhhamu/CrashHunter/issues/new/choose) からお願いします。CrashHunter のログは `%APPDATA%\CrashHunter\logs` にあります。

## ライセンス

All Rights Reserved。使ってよい範囲と禁止事項は [LICENSE](LICENSE) と [利用規約](docs/terms.md) を見てください。改変と、指定の配布元以外での再配布はできません。同梱ソフトウェアは [NOTICE.md](NOTICE.md)。

変更履歴は [CHANGELOG.md](CHANGELOG.md) にあります。

---

# CrashHunter (English)

Crash diagnosis, culprit-mod finder and fixer for instances of third-party Minecraft launchers that support mods (currently Prism Launcher). No mod to install; it works from outside the launcher.

- **Diagnose**: reads crash reports, `hs_err` files, `latest.log` and the mods folder, and shows the **culprit mod, the cause and how to fix it**.
- **Auto-fix**: adds missing dependencies, replaces broken or mismatched jars and adjusts memory or Java, then launches the game to verify. Everything can be undone.
- **Find Cause**: relaunches the game through the launcher while toggling mods to pin down the culprit, including crashes that need two mods together. Several games can run in parallel.
- **Tune Mods**: keeps as many mods as possible and finds a set that launches, by changing culprit versions, the Forge version, or removing optional mods according to your rules.
- **CrashHunter Database** (optional, off by default): uses culprits, broken files and fixes found by other users, and lets you share yours. See [docs/database-privacy.md](docs/database-privacy.md).
- Bisect and Tune Mods run on a work copy; interrupted runs are restored on the next start. Copied reports mask user names, paths and tokens.

Windows 10/11, Prism Launcher (other launchers planned). Verified with Forge 1.20.1; crash files of NeoForge, Fabric, Quilt and legacy Forge are understood too.
Downloading from CurseForge needs an API key; until the CrashHunter key is approved, register your own key in Settings.

Report bugs on [GitHub Issues](https://github.com/yuhhamu/CrashHunter/issues/new/choose). Logs are in `%APPDATA%\CrashHunter\logs`.

## License

All rights reserved. See [LICENSE](LICENSE) and the [terms of use](docs/terms.md) for what is allowed. Modifying it and redistributing it outside the official source are not allowed. Third-party notices: [NOTICE.md](NOTICE.md). Changes: [CHANGELOG.md](CHANGELOG.md).
