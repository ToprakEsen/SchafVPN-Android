# Windows surumunun arayuzunden (SchafVPN/ui/index.html) Android kopyasini uretir:
# Android koprusu, pencere dugmelerini gizleme, dokunmatik metinler.
# Kullanim: .\scripts\sync-ui.ps1 [-Source ..\vpn\ui\index.html]
param([string]$Source = (Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) "vpn\ui\index.html"))
$ErrorActionPreference = "Stop"
$dest = Join-Path (Split-Path -Parent $PSScriptRoot) "app\src\main\assets\index.html"
$html = [IO.File]::ReadAllText($Source, [Text.Encoding]::UTF8)

function Replace-Once([string]$find, [string]$repl) {
    if (-not $script:html.Contains($find)) { throw "Arayuzde beklenen metin bulunamadi: $find" }
    $script:html = $script:html.Replace($find, $repl)
}

$shim = @'
<title>SchafVPN</title>
<script>
  /* Android koprusu: Windows'taki WebView2 API'sini (chrome.webview) taklit eder */
  (function () {
    var listeners = [];
    window.chrome = window.chrome || {};
    window.chrome.webview = {
      postMessage: function (m) { SchafAndroid.post(JSON.stringify(m)); },
      addEventListener: function (type, cb) { if (type === 'message') listeners.push(cb); }
    };
    window.__schafReceive = function (data) { listeners.forEach(function (cb) { cb({ data: data }); }); };
  })();
</script>
'@

$css = @'

  /* ---- Android ---- */
  html, body { -webkit-tap-highlight-color: transparent; }
  .win button:not(#setBtn), #logBtn, .win-only { display: none !important; }
  .android-only { display: flex !important; }
  .power { cursor: default; }
  .power:hover { color: var(--muted); }
  .power:hover::before { opacity: 0; }
  body[data-state="connected"] .power:hover { color: var(--on); }
  body[data-state="connected"] .power:hover::before { opacity: 1; }
  body[data-state="connecting"] .power:hover { color: var(--busy); }
</style>
'@

Replace-Once '<meta name="viewport" content="width=device-width, initial-scale=1">' '<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">'
Replace-Once '<title>SchafVPN</title>' $shim.TrimEnd()
$i = $html.IndexOf('</style>'); if ($i -lt 0) { throw "</style> bulunamadi" }
$html = $html.Substring(0, $i) + $css.TrimStart("`r","`n").Insert(0, "`n") + $html.Substring($i + '</style>'.Length).TrimStart()
Replace-Once 'Bağlanmak için tıkla' 'Bağlanmak için dokun'
Replace-Once "v2rayN'de sunucuna sağ tıklayıp" "v2rayN / v2rayNG'de sunucuna uzun basıp"
Replace-Once 'Link yalnızca bu bilgisayarda, Windows hesabına özel şifrelenerek saklanır.' 'Link yalnızca bu cihazda, Android anahtar deposuyla şifrelenerek saklanır.'
Replace-Once "'Yeniden başlatıp güncelle'" "'Kurmak için dokun'"

[IO.File]::WriteAllText($dest, $html, (New-Object Text.UTF8Encoding $false))
Write-Host "Arayuz esitlendi: $dest"
