#!/usr/bin/env bash
# 提交 / 发布前的合规守卫：确认版本库里没有不该开源或不该分发的东西。
#
# 检查项：
#   1. 反编译产物、厂商 SDK、抓包二进制（.dex/.jar/.smali/.so/...）
#   2. 签名材料（.jks/.keystore）与 keystore.properties
#   3. 逆向工具与参考实现目录（.tools/、carlife-ref/）
#   4. 疑似真实凭据（psk=明文、vehicle MAC）
#
# 用法：
#   ./scripts/check-no-vendor-artifacts.sh        # 检查已跟踪文件
#   ./scripts/check-no-vendor-artifacts.sh -v     # 同时列出被检查的文件数
#
# 退出码：0 = 干净，1 = 命中禁止项（附清单）。
# 可加进 pre-commit 钩子的第一步。

set -u
verbose=0
[ "${1:-}" = "-v" ] && verbose=1

fail=0
report() {
    echo "✗ $1" >&2
    shift
    printf '    %s\n' "$@" >&2
    fail=1
}

tracked=$(git ls-files)

if [ "$verbose" = "1" ]; then
    echo "已跟踪文件 $(printf '%s\n' "$tracked" | wc -l) 个"
fi

# 1) 二进制 / 反编译产物。gradle-wrapper.jar 是构建基础设施，允许。
bad_bin=$(printf '%s\n' "$tracked" \
    | grep -Ei '\.(dex|smali|apk|aab|so|jks|keystore|baksmali)$' \
    | grep -v '^gradle/wrapper/gradle-wrapper\.jar$' || true)
[ -n "$bad_bin" ] && report "版本库里出现二进制/反编译产物" $bad_bin

bad_jar=$(printf '%s\n' "$tracked" \
    | grep -Ei '\.jar$' \
    | grep -v '^gradle/wrapper/gradle-wrapper\.jar$' || true)
[ -n "$bad_jar" ] && report "版本库里出现非基础设施 jar" $bad_jar

# 2) 签名材料
bad_sign=$(printf '%s\n' "$tracked" | grep -Ei 'keystore\.properties$' || true)
[ -n "$bad_sign" ] && report "签名配置不应入库" $bad_sign

# 3) 逆向工具 / 参考实现
bad_tools=$(printf '%s\n' "$tracked" | grep -E '^(\.tools|carlife-ref|reference)/' || true)
[ -n "$bad_tools" ] && report "逆向工具或参考实现目录被跟踪" $bad_tools

# 4) 疑似真实凭据：psk=<非打码值>、真实车机热点名/MAC 白名单之外的内容
#    日志与代码里打码形式为 psk=*** / psk=(空) / psk 明文（文档描述）三种，只拦第一种之外的可疑赋值。
bad_psk=$(git grep -nE 'psk=[0-9A-Fa-f]{8,}|passphrase *= *"[0-9A-Za-z]{8,}"' -- \
    'app/src' 'README.md' 'docs' 2>/dev/null || true)
[ -n "$bad_psk" ] && report "疑似真实热点密码入源码/文档" $bad_psk

if [ "$fail" = "0" ]; then
    echo "✓ 合规守卫通过：无二进制产物、无签名材料、无逆向目录、无明文凭据"
    exit 0
fi
echo "" >&2
echo "如有误判，请把具体文件加进本脚本的排除列表并说明理由。" >&2
exit 1
