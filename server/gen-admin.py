import json, pathlib
here = pathlib.Path(__file__).parent
html = (here / "admin" / "index.html").read_text(encoding="utf-8")
js = (here / "admin" / "app.js").read_text(encoding="utf-8")
(here / "src" / "adminUi.ts").write_text("export const ADMIN_HTML = " + json.dumps(html, ensure_ascii=False) + ";\nexport const ADMIN_JS = " + json.dumps(js, ensure_ascii=False) + ";\n", encoding="utf-8")
