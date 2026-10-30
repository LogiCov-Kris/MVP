package logicov.ui

object MemoReviewStyle {
  val css: String = """
    :root{--memo-surface:#fff;--memo-text:#26334d;--memo-border:#e0e5ef;--memo-heading:#25245a;--memo-muted:#67728a;--memo-soft:#eff3fc;--memo-stripe:#fafbfe;--memo-link:#245cda}
    :root[data-theme="dark"]{--memo-surface:#1a2233;--memo-text:#e5eaf4;--memo-border:#42506a;--memo-heading:#d7ddff;--memo-muted:#aab8ce;--memo-soft:#253148;--memo-stripe:#1e293c;--memo-link:#91b4ff}

    .lc-memo{font:14px/1.65 Inter,Arial,sans-serif;color:var(--memo-text);background:var(--memo-surface);border:1px solid var(--memo-border);border-radius:14px;box-shadow:0 8px 32px #17255408;overflow:hidden;display:flex;flex-direction:column}
    .lc-memo *{box-sizing:border-box}
    .lc-memo header{padding:20px 28px;border-bottom:1px solid var(--memo-border);background:var(--memo-surface);display:flex;gap:18px;align-items:center;flex-wrap:wrap}
    .lc-memo header strong{font-size:19px;color:var(--memo-heading)}.lc-memo header p{width:100%;margin:0;color:var(--memo-muted);font-size:13px}
    .lc-memo .memo-layout{display:grid;grid-template-columns:230px minmax(0,1fr);min-height:0;flex:1}
    .lc-memo nav{background:var(--memo-soft);padding:20px 12px;overflow:auto;border-right:1px solid var(--memo-border)}
    .lc-memo a{color:var(--memo-link);text-decoration:none}.lc-memo nav a{display:block;padding:10px 12px;border-radius:7px;font-size:13px}.lc-memo nav a:hover{background:var(--memo-soft)}
    .lc-memo article{overflow:auto;padding:16px 38px 50px;scroll-behavior:smooth}
    .lc-memo article>section{padding:10px 0 30px;border-bottom:1px solid var(--memo-border);scroll-margin-top:15px}
    .lc-memo article header{display:block;padding:12px 0 22px}.lc-memo article h1{font-size:25px;color:var(--memo-heading)}.lc-memo .cover{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));border:1px solid var(--memo-border);border-radius:8px;overflow:hidden;margin:20px 0}.lc-memo .cell{padding:14px;background:var(--memo-soft);border-bottom:1px solid var(--memo-border)}.lc-memo .label{display:block;font-size:11px;text-transform:uppercase;color:var(--memo-muted)}.lc-memo .value{font-weight:600;color:var(--memo-heading)}
    .lc-memo h2{color:var(--memo-heading);font-size:22px;margin:22px 0 14px}.lc-memo h3{font-size:16px;color:var(--memo-heading);margin:22px 0 10px}
    .lc-memo p{white-space:pre-line}.lc-memo table{width:100%;border-collapse:collapse;font-size:12px;margin:20px 0;table-layout:fixed;overflow-wrap:break-word}
    .lc-memo caption{text-align:left;font-weight:600;color:var(--memo-heading);margin-bottom:8px}.lc-memo th{background:var(--memo-soft);color:var(--memo-heading);text-align:left}
    .lc-memo td,.lc-memo th{padding:10px 8px;border-bottom:1px solid var(--memo-border);vertical-align:top}.lc-memo tr:nth-child(even){background:var(--memo-stripe)}
    .lc-memo .evidence,.lc-memo .note{font-size:12px;color:var(--memo-muted)}.lc-memo pre{white-space:pre-wrap;overflow-wrap:anywhere;font-size:12px}
    .lc-memo button{font:600 13px Inter,Arial,sans-serif;color:var(--memo-link);background:var(--memo-surface);border:1px solid var(--memo-border);border-radius:7px;padding:10px 14px;cursor:pointer;margin:4px 8px 4px 0}
    .lc-memo button:hover{background:var(--memo-soft)}.lc-memo button:disabled{opacity:.45;cursor:default}.lc-memo button.primary{background:#2563eb;color:white;border-color:#2563eb}
    .lc-memo button:focus-visible,.lc-memo a:focus-visible{outline:3px solid #93b4ff;outline-offset:2px}
    .lc-memo .section-actions{margin-top:20px}.lc-memo .inline-editor{padding:20px;background:var(--memo-soft);border:1px solid var(--memo-border);border-radius:10px;margin-top:12px}
    .lc-memo input,.lc-memo select{display:block;width:100%;padding:10px;border:1px solid var(--memo-border);border-radius:7px;background:var(--memo-surface);font:14px Arial;margin:6px 0 14px;color:var(--memo-text)}
    .lc-memo label{display:block;font-size:12px;font-weight:600}.lc-memo .prose-editor{min-height:160px;white-space:pre-wrap;background:var(--memo-surface);border:1px solid var(--memo-border);border-radius:8px;padding:14px;line-height:1.8;margin-bottom:12px}
    .lc-memo .shared-value{background:var(--memo-soft);color:var(--memo-link);border-radius:4px;padding:1px 4px}.lc-memo details{margin-top:18px;padding:14px;background:var(--memo-soft);border-radius:8px}.lc-memo summary{cursor:pointer;color:var(--memo-link);font-weight:600}
    .lc-memo .submit-bar{border-top:1px solid var(--memo-border);padding:12px 24px;background:var(--memo-soft);display:flex;align-items:center;gap:16px}.lc-memo .submit-bar label{flex:1}.lc-memo .badge{background:var(--memo-soft);color:var(--memo-link);padding:4px 12px;border-radius:20px}
    @media(max-width:700px){.lc-memo .memo-layout{grid-template-columns:1fr}.lc-memo nav{max-height:150px;border-bottom:1px solid var(--memo-border)}.lc-memo article{padding:12px 16px}.lc-memo .submit-bar{display:block;max-height:220px;overflow:auto}}
  """
}
