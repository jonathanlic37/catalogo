#!/usr/bin/env python3
"""Genera docs/api.html: Swagger UI con las especificaciones OpenAPI de ambas APIs incrustadas.

Las especificaciones fuente son docs/openapi/*.yaml. Se incrustan como JSON dentro del HTML para
que la página funcione abriéndola directamente (file://), donde el navegador no permite leer
ficheros locales con fetch. Swagger UI se carga desde jsDelivr (requiere conexión).

Uso:  python3 scripts/build-api-docs.py      (tras editar cualquier docs/openapi/*.yaml)
"""
import json
import pathlib

import yaml

ROOT = pathlib.Path(__file__).resolve().parent.parent
SPECS = {
    "consumer": ROOT / "docs/openapi/consumer-api.yaml",
    "producer": ROOT / "docs/openapi/producer-api.yaml",
}
OUT = ROOT / "docs/api.html"
SWAGGER = "https://cdn.jsdelivr.net/npm/swagger-ui-dist@5.17.14"

specs = {k: yaml.safe_load(p.read_text(encoding="utf-8")) for k, p in SPECS.items()}
# Evita que un "</script>" dentro de una descripción cierre el bloque.
payload = json.dumps(specs, ensure_ascii=False).replace("</", "<\\/")

html = f"""<!doctype html>
<html lang="es">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1" />
<title>Catálogo · Referencia de las APIs</title>
<!-- Generado por scripts/build-api-docs.py a partir de docs/openapi/*.yaml. No editar a mano. -->
<link rel="stylesheet" href="{SWAGGER}/swagger-ui.css" />
<style>
  @font-face {{ font-family: 'Outfit'; font-weight: 600; src: url('../auth/themes/catalogo/login/resources/fonts/outfit-600.woff2') format('woff2'); }}
  @font-face {{ font-family: 'Work Sans'; font-weight: 400; src: url('../auth/themes/catalogo/login/resources/fonts/work-sans-400.woff2') format('woff2'); }}
  @font-face {{ font-family: 'Work Sans'; font-weight: 600; src: url('../auth/themes/catalogo/login/resources/fonts/work-sans-600.woff2') format('woff2'); }}
  :root {{ --bg:#F4F6F9; --fg:#001F4F; --muted:#4A5B78; --yellow:#FFD100; --border:rgba(0,31,79,.12); }}
  * {{ box-sizing: border-box; }}
  body {{ margin:0; background:
      radial-gradient(1100px 560px at 88% -12%, rgba(255,209,0,.22), transparent 62%), var(--bg);
    background-attachment: fixed; color:var(--fg); font-family:'Work Sans',system-ui,sans-serif; }}
  header {{ position:sticky; top:0; z-index:10; background:rgba(244,246,249,.78);
    -webkit-backdrop-filter:blur(16px); backdrop-filter:blur(16px); border-bottom:1px solid var(--border); }}
  .bar {{ max-width:1200px; margin:0 auto; padding:.75rem 1rem; display:flex; align-items:center; gap:1rem; flex-wrap:wrap; }}
  .mark {{ width:38px; height:38px; border-radius:12px; background:var(--yellow); display:grid; place-items:center; }}
  h1 {{ font-family:'Outfit',sans-serif; font-size:1.2rem; margin:0; letter-spacing:-.01em; }}
  .sub {{ color:var(--muted); font-size:.8rem; }}
  nav {{ margin-left:auto; display:flex; gap:4px; padding:4px; border:1px solid var(--border); border-radius:12px; background:rgba(255,255,255,.6); }}
  nav button {{ font:600 .9rem 'Work Sans',sans-serif; border:0; border-radius:9px; padding:0 1rem; min-height:40px; cursor:pointer; background:transparent; color:var(--muted); }}
  nav button[aria-pressed="true"] {{ background:var(--yellow); color:var(--fg); }}
  nav button:focus-visible {{ outline:3px solid #B38F00; outline-offset:2px; }}
  main {{ max-width:1200px; margin:1rem auto 3rem; padding:0 1rem; }}
  .card {{ background:rgba(255,255,255,.72); border:1px solid var(--border); border-radius:14px; padding:.5rem 0; }}
  .swagger-ui .topbar {{ display:none; }}
  .swagger-ui, .swagger-ui .info .title, .swagger-ui .opblock-tag {{ font-family:'Work Sans',system-ui,sans-serif; color:var(--fg); }}
  .swagger-ui .info .title {{ font-family:'Outfit',sans-serif; }}
  .swagger-ui .btn.authorize {{ border-color:#B38F00; color:var(--fg); }}
  .swagger-ui .btn.authorize svg {{ fill:var(--fg); }}
  .note {{ color:var(--muted); font-size:.85rem; margin:.25rem 0 1rem; }}
</style>
</head>
<body>
<header>
  <div class="bar">
    <span class="mark" aria-hidden="true">
      <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#001F4F" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="m7.5 4.27 9 5.15"/><path d="M21 8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16Z"/><path d="m3.3 7 8.7 5 8.7-5"/><path d="M12 22V12"/></svg>
    </span>
    <div><h1>Referencia de las APIs</h1><div class="sub">Catálogo distribuido · OpenAPI 3</div></div>
    <nav aria-label="API">
      <button type="button" data-api="consumer" aria-pressed="true">Consumer API</button>
      <button type="button" data-api="producer" aria-pressed="false">Producer API</button>
    </nav>
  </div>
</header>
<main>
  <p class="note">Referencia de solo lectura generada desde <code>docs/openapi/*.yaml</code> (requiere conexión para
  cargar Swagger UI). Para ejecutar peticiones usa <a href="curl.md">docs/curl.md</a> o la colección de Postman:
  abierta como fichero local, esta página no puede llamar a las APIs porque su CORS solo admite el origen de la UI.</p>
  <div class="card"><div id="swagger"></div></div>
</main>
<script src="{SWAGGER}/swagger-ui-bundle.js"></script>
<script>
  const SPECS = {payload};
  function show(api) {{
    document.querySelectorAll('nav button').forEach(b => b.setAttribute('aria-pressed', String(b.dataset.api === api)));
    SwaggerUIBundle({{ spec: SPECS[api], dom_id: '#swagger', deepLinking: false, docExpansion: 'list',
      defaultModelsExpandDepth: 0, supportedSubmitMethods: [] }});
  }}
  document.querySelectorAll('nav button').forEach(b => b.addEventListener('click', () => show(b.dataset.api)));
  show('consumer');
</script>
</body>
</html>
"""
OUT.write_text(html, encoding="utf-8")
print(f"generado {OUT.relative_to(ROOT)} ({len(html) // 1024} KB)")
