#!/bin/bash
# OfferBeacon 暗黑模式弹层适配 · 验收测试（静态检查，全部通过输出 ALL_TESTS_PASSED）
set -u
BASE="D:/OfferBeaconBuild"
HTML="$BASE/app/assets/index.html"
JAVA="$BASE/app/java/com/offerbeacon/app/MainActivity.java"
STYLES="$BASE/app/res/values/styles.xml"
FAIL=0

ok()   { echo "  PASS  $1"; }
fail() { echo "  FAIL  $1"; FAIL=1; }
has()  { grep -q "$2" "$1" && ok "$3" || fail "$3"; }

echo "[T1] 原生壳：暗色主题样式"
has "$STYLES" 'style name="AppTheme.Dark"' "styles.xml 定义 AppTheme.Dark"
has "$STYLES" 'Theme.DeviceDefault.NoActionBar' "AppTheme.Dark 母版为暗色 DeviceDefault"
has "$STYLES" 'style name="AppTheme" parent="@android:style/Theme.DeviceDefault.Light.NoActionBar"' "浅色 AppTheme 未被改动"

echo "[T2] 原生壳：主题桥与持久化"
has "$JAVA" 'addJavascriptInterface(new ThemeBridge(), "OfferBeaconTheme")' "注册 JS 桥 OfferBeaconTheme"
has "$JAVA" '@JavascriptInterface' "桥方法带 @JavascriptInterface 注解"
has "$JAVA" 'R.style.AppTheme_Dark' "运行时切换 AppTheme_Dark"
has "$JAVA" 'KEY_THEME = "ui_theme"' "主题持久化键存在"
if grep -qE 'setForceDark|AlgorithmicDarkening|FORCE_DARK' "$JAVA"; then
  fail "不得开启 forceDark / 算法调暗"
else
  ok "未开启 forceDark / 算法调暗"
fi

echo "[T3] 前端：桥接调用（唯一漏斗 paintTheme）"
N=$(grep -cF 'window.OfferBeaconTheme.setTheme(mode' "$HTML")
[ "$N" -eq 1 ] && ok "paintTheme 内桥接调用恰好出现1次（共 $N）" || fail "桥接调用次数异常（共 $N）"
has "$HTML" "window.OfferBeaconTheme && typeof window.OfferBeaconTheme.setTheme === 'function'" "桥接调用带存在性判断"
grep -qF 'catch (e) { /* ignore */ }' "$HTML" && ok "桥接调用带 try/catch 降级" || fail "桥接调用缺 try/catch 降级"

echo "[T4] 前端：CSS 暗黑保险层（显式配色规则，双路径）"
has "$HTML" ':root\[data-theme="dark"\] select option,' "手动暗色路径：select option 配色规则"
has "$HTML" ':root:where(:not(\[data-theme="light"\])) select option,' "系统暗色路径：select option 配色规则"
has "$HTML" 'input\[type="datetime-local"\]' "日期时间控件暗色兜底规则"

echo "[T5] 前端：关键控件未被破坏（功能与样式零改动抽查）"
for id in qaStatus filterStatus filterSort schedType schedTime; do
  has "$HTML" "id=\"$id\"" "控件存在：#$id"
done
has "$HTML" '\.quick-add input,\s*$' "quick-add 样式仍在" || true
grep -q 'quick-add select' "$HTML" && ok "quick-add select 样式仍在" || fail "quick-add select 样式丢失"
grep -q 'filter-bar select' "$HTML" && ok "filter-bar select 样式仍在" || fail "filter-bar select 样式丢失"
grep -q 'sv-add input, .sv-add select' "$HTML" && ok "sv-add select 样式仍在" || fail "sv-add select 样式丢失"

echo "[T6] 前端：内联 JS 语法检查"
rm -rf "$BASE/build/test_js" && mkdir -p "$BASE/build/test_js"
python3 - "$HTML" <<'PY'
import re, sys
html = open(sys.argv[1], encoding='utf-8').read()
scripts = re.findall(r'<script[^>]*>(.*?)</script>', html, re.S)
body = "\n;\n".join(s for s in scripts if 'src=' not in s[:50])
open(r"D:/OfferBeaconBuild/build/test_js/inline.js", 'w', encoding='utf-8').write(body)
print("scripts:", len(scripts))
PY
if node --check "$BASE/build/test_js/inline.js" 2>"$BASE/build/test_js/js_err.txt"; then
  ok "内联脚本 node --check 语法通过"
else
  fail "内联脚本语法错误：$(cat "$BASE/build/test_js/js_err.txt" | head -3)"
fi
rm -rf "$BASE/build/test_js"


python3 - "$BASE/OfferBeacon.apk" "$HTML" <<'PYEOF'
import zipfile, hashlib, sys
apk = zipfile.ZipFile(sys.argv[1])
src = open(sys.argv[2],'rb').read()
ok = lambda b: "PASS" if b else "FAIL"
import re
checks = [
 ("T7.1 APK 内 index.html 与源文件一致", hashlib.sha256(src).digest()==hashlib.sha256(apk.read('assets/index.html')).digest()),
 ("T7.2 dex 含 ThemeBridge 桥类", b'ThemeBridge' in apk.read('classes.dex')),
 ("T7.3 dex 含 OfferBeaconTheme 桥名", b'OfferBeaconTheme' in apk.read('classes.dex')),
 ("T7.4 资源表含 AppTheme.Dark", b'AppTheme.Dark' in apk.read('resources.arsc')),
 ("T7.5 dex 含主题持久化键 ui_theme", b'ui_theme' in apk.read('classes.dex')),
]
for name, b in checks: print("  %s  %s" % (ok(b), name))
if not all(b for _, b in checks): raise SystemExit(1)
PYEOF
[ $? -eq 0 ] || FAIL=1

echo
if [ "$FAIL" -eq 0 ]; then
  echo "ALL_TESTS_PASSED"
else
  echo "TESTS_FAILED"
  exit 1
fi

echo "[T7] APK 级验证（需先构建，调用方执行 bash build.sh 后运行）"
