# 壊れたファイルの検証

報告から「壊れたファイル」とされたModを、認可された人(または将来の専用サーバー)が検証して確定・却下する仕組み。

## 流れ
1. サーバーは報告(`broken` 項目と、refmapがない・jarが壊れているクラッシュ)から壊れたファイルを集計する。判定がないものは「確認待ち」。
2. 検証者は `crashhunter-verify` で確認待ちを取得し、ハッシュが一致するjarを手元のフォルダかModrinthから取り、中身を調べて結果を送る。
3. 判定はサーバーの `verdicts` に残り(検証者・方法・詳細・日時)、配布データに反映される。
   - 確定: 配布データで `confirmed: true`。構成調整は起動を確かめずに先に入れ替える。
   - 却下: 配布データから外す。
   - 判定できず: 確認待ちに残る(起動による検証が必要なもの)。
4. 管理ページの「検証」タブからも、手動で確定・却下できる(管理用トークン + 管理ページのIP制限)。

## 当面の運用(本人のPCのみ)
- `verifier/verify-local.ps1` を使う。プロジェクトの `.env` の管理用トークンで管理API(`/v1/admin/pending`・`/v1/admin/verdict`)を使うので、管理ページと同じく WAF のIP許可と管理用トークンの両方が必要。判定の検証者は `admin` になる。
- 例: `verify-local.ps1 pending` / `verify-local.ps1 verify emextras@1.5.1 --search <instancesフォルダ>`

## 認可(Cloudflare Access、ほかの人や専用サーバーを加えるとき)
- `/crashhunter/v1/verify/*` は Cloudflare Access のアプリで保護する。Workerも `Cf-Access-Jwt-Assertion` を検証する(`ACCESS_TEAM` と `ACCESS_AUD` が未設定なら 503 で閉じる)。
- 人ごとにサービストークンを発行し、Accessのポリシーで許可する。取り消しはトークンの削除だけで済む。
- 将来の専用サーバーにも、専用のサービストークンを1つ発行する。

## コマンド(`verifier/verify.ps1` から実行)
- `whoami` 認証の確認
- `pending` 確認待ちの一覧
- `verify <modid@version> --search <Prismのinstancesフォルダ>` 1件を検証して送る
- `verify --all --search <フォルダ>` 確認待ちをすべて検証する
- `verify <modid@version> --confirm|--reject --detail <文>` 手動で確定・却下
- `inspect <jar> [--reason NO_REFMAP|BROKEN]` サーバーを使わずに1つのjarを調べる
- `--dry-run` で送信しない
- サービストークンは `~/.crashhunter-verify.env` に `CF_ACCESS_CLIENT_ID=` と `CF_ACCESS_CLIENT_SECRET=` で置く(表示しない・リポジトリに入れない)。

## 自動化(資金ができたら)
- 専用サーバーで `verify --all` を定期実行するだけでよい(同じCLI・同じAPI)。
- 起動による検証は `FileVerifier` を実装して `VerifyRunner` に加える(`core/verify`)。最小構成(対象Mod + 前提Mod + ローダー)で起動し、クラッシュの有無で判定する想定。今は refmap と jar の破損だけを、ファイルの中身で判定する。
