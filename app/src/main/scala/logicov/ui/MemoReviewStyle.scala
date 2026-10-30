package logicov.ui

object MemoReviewStyle {
  val css: String = """
    .lc-memo{font:14px/1.65 Inter,Arial,sans-serif;color:#26334d;background:#fff;border:1px solid #e0e5ef;border-radius:14px;box-shadow:0 8px 32px #17255408;overflow:hidden;display:flex;flex-direction:column}
    .lc-memo *{box-sizing:border-box}
    .lc-memo header{padding:20px 28px;border-bottom:1px solid #e0e5ef;background:#fff;display:flex;gap:18px;align-items:center;flex-wrap:wrap}
    .lc-memo header strong{font-size:19px;color:#25245a}.lc-memo header p{width:100%;margin:0;color:#67728a;font-size:13px}
    .lc-memo .memo-layout{display:grid;grid-template-columns:230px minmax(0,1fr);min-height:0;flex:1}
    .lc-memo nav{background:#f8f9fe;padding:20px 12px;overflow:auto;border-right:1px solid #e0e5ef}
    .lc-memo a{color:#245cda;text-decoration:none}.lc-memo nav a{display:block;padding:10px 12px;border-radius:7px;font-size:13px}.lc-memo nav a:hover{background:#eaf0ff}
    .lc-memo article{overflow:auto;padding:16px 38px 50px;scroll-behavior:smooth}
    .lc-memo article>section{padding:10px 0 30px;border-bottom:1px solid #e0e5ef;scroll-margin-top:15px}
    .lc-memo article header{display:block;padding:12px 0 22px}.lc-memo article h1{font-size:25px;color:#25245a}.lc-memo .cover{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));border:1px solid #e0e5ef;border-radius:8px;overflow:hidden;margin:20px 0}.lc-memo .cell{padding:14px;background:#f5f7ff;border-bottom:1px solid #e0e5ef}.lc-memo .label{display:block;font-size:11px;text-transform:uppercase;color:#67728a}.lc-memo .value{font-weight:600;color:#25245a}
    .lc-memo h2{color:#25245a;font-size:22px;margin:22px 0 14px}.lc-memo h3{font-size:16px;color:#343467;margin:22px 0 10px}
    .lc-memo p{white-space:pre-line}.lc-memo table{width:100%;border-collapse:collapse;font-size:12px;margin:20px 0;table-layout:fixed;overflow-wrap:break-word}
    .lc-memo caption{text-align:left;font-weight:600;color:#25245a;margin-bottom:8px}.lc-memo th{background:#eff2fb;color:#30335e;text-align:left}
    .lc-memo td,.lc-memo th{padding:10px 8px;border-bottom:1px solid #e4e8f0;vertical-align:top}.lc-memo tr:nth-child(even){background:#fafbfe}
    .lc-memo .evidence,.lc-memo .note{font-size:12px;color:#68738a}.lc-memo pre{white-space:pre-wrap;overflow-wrap:anywhere;font-size:12px}
    .lc-memo button{font:600 13px Inter,Arial,sans-serif;color:#285cd6;background:white;border:1px solid #d9e1f2;border-radius:7px;padding:10px 14px;cursor:pointer;margin:4px 8px 4px 0}
    .lc-memo button:hover{background:#eff4ff}.lc-memo button:disabled{opacity:.45;cursor:default}.lc-memo button.primary{background:#2563eb;color:white;border-color:#2563eb}
    .lc-memo button:focus-visible,.lc-memo a:focus-visible{outline:3px solid #93b4ff;outline-offset:2px}
    .lc-memo .section-actions{margin-top:20px}.lc-memo .inline-editor{padding:20px;background:#f7f9ff;border:1px solid #cfdcff;border-radius:10px;margin-top:12px}
    .lc-memo input,.lc-memo select{display:block;width:100%;padding:10px;border:1px solid #cad4e6;border-radius:7px;background:white;font:14px Arial;margin:6px 0 14px;color:#26334d}
    .lc-memo label{display:block;font-size:12px;font-weight:600}.lc-memo .prose-editor{min-height:160px;white-space:pre-wrap;background:white;border:1px solid #cad4e6;border-radius:8px;padding:14px;line-height:1.8;margin-bottom:12px}
    .lc-memo .shared-value{background:#e9efff;color:#214db3;border-radius:4px;padding:1px 4px}.lc-memo details{margin-top:18px;padding:14px;background:#f8faff;border-radius:8px}.lc-memo summary{cursor:pointer;color:#285cd6;font-weight:600}
    .lc-memo .submit-bar{border-top:1px solid #dce3f0;padding:12px 24px;background:#f8faff;display:flex;align-items:center;gap:16px}.lc-memo .submit-bar label{flex:1}.lc-memo .badge{background:#e8efff;color:#245cda;padding:4px 12px;border-radius:20px}
    @media(max-width:700px){.lc-memo .memo-layout{grid-template-columns:1fr}.lc-memo nav{max-height:150px;border-bottom:1px solid #ddd}.lc-memo article{padding:12px 16px}.lc-memo .submit-bar{display:block;max-height:220px;overflow:auto}}
  """
}
