#!/usr/bin/env bash

set -Eeuo pipefail

# ===== 常用配置：平时只需要修改这里 =====
DEFAULT_DEVICE="192.168.0.112:5555"
DEFAULT_DIRECTION="left-right" # left-right=左右分屏，top-bottom=上下分屏
#DEFAULT_DIRECTION="top-bottom"
DEFAULT_COMPONENT="top.eiyooooo.easycontrol.app.byd/top.eiyooooo.easycontrol.app.MainActivity"
DEFAULT_PARTNER_COMPONENT="com.android.settings/.Settings" # 另一个窗格；留空则保留当前前台应用
DEFAULT_SPLIT_RATIO=50 # 易控所在第一窗格占屏幕的百分比，建议 30-70
# ======================================

ADB_BIN="${ADB_BIN:-adb}"
DEVICE="${EASYCONTROL_DEVICE:-$DEFAULT_DEVICE}"
DIRECTION="${EASYCONTROL_SPLIT_DIRECTION:-$DEFAULT_DIRECTION}"
COMPONENT="${EASYCONTROL_COMPONENT:-$DEFAULT_COMPONENT}"
PARTNER_COMPONENT="${EASYCONTROL_PARTNER_COMPONENT:-$DEFAULT_PARTNER_COMPONENT}"
SPLIT_RATIO="${EASYCONTROL_SPLIT_RATIO:-$DEFAULT_SPLIT_RATIO}"
SCREEN_SIZE_OVERRIDE=""
DRY_RUN=0

log() {
    printf '[EasyControl Split] %s\n' "$*"
}

warn() {
    printf '[EasyControl Split] 警告：%s\n' "$*" >&2
}

die() {
    printf '[EasyControl Split] 错误：%s\n' "$*" >&2
    exit 1
}

usage() {
    cat <<'EOF'
启动易控车机版分屏

用法：
  ./scripts/start_easycontrol_split.sh [选项]

选项：
  -d, --device 设备地址        ADB 设备，例如 192.168.0.112:5555
  -m, --direction 分屏方向     left-right/lr/左右 或 top-bottom/tb/上下
  -r, --ratio 百分比           易控第一窗格占比，范围 20-80，默认 50
      --partner 组件名         另一个窗格，例如 com.android.settings/.Settings
      --keep-current           不启动搭档应用，保留当前前台应用
      --component 组件名       覆盖易控组件名
      --screen-size 宽x高      覆盖 wm size 结果，例如 2048x1080
      --dry-run                只打印命令，不连接或操作设备
  -h, --help                   显示帮助

示例：
  # 左右分屏，使用默认设备
  ./scripts/start_easycontrol_split.sh --direction left-right

  # 上下分屏，临时指定设备
  ./scripts/start_easycontrol_split.sh --direction top-bottom --device 192.168.0.112:5555

  # 保留当前前台应用作为另一个窗格
  ./scripts/start_easycontrol_split.sh -m lr -d 192.168.0.112:5555 --keep-current

  # 不操作设备，先检查将要执行的命令
  ./scripts/start_easycontrol_split.sh -m tb --screen-size 2048x1080 --dry-run

说明：
  易控固定放在第一窗格：左右分屏时在左侧，上下分屏时在上方。
  Android/车机 ROM 可能禁止横屏上下分屏；脚本会尝试调整并报告真实结果。
EOF
}

require_value() {
    local option="$1"
    local value="${2:-}"
    [[ -n "$value" ]] || die "$option 缺少参数"
}

normalize_direction() {
    local value
    value="$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')"
    case "$value" in
        left-right|left_right|lr|horizontal|左右|横向)
            printf 'left-right\n'
            ;;
        top-bottom|top_bottom|tb|vertical|上下|纵向)
            printf 'top-bottom\n'
            ;;
        *)
            return 1
            ;;
    esac
}

print_command() {
    local argument
    printf '  '
    for argument in "$@"; do
        printf '%q ' "$argument"
    done
    printf '\n'
}

is_error_output() {
    local output="$1"
    grep -Eqi \
        'Error:|Error type|Exception|SecurityException|Unknown option|Unknown command|not supported|does not exist' \
        <<< "$output"
}

run_checked() {
    local description="$1"
    shift
    local output
    local status

    log "$description"
    print_command "$ADB_BIN" -s "$DEVICE" "$@"
    if output="$("$ADB_BIN" -s "$DEVICE" "$@" 2>&1)"; then
        status=0
    else
        status=$?
    fi

    if [[ $status -ne 0 ]] || is_error_output "$output"; then
        [[ -n "$output" ]] && printf '%s\n' "$output" >&2
        return 1
    fi

    [[ -n "$output" ]] && printf '%s\n' "$output"
    return 0
}

try_resize() {
    local description="$1"
    shift
    local output
    local status

    log "$description"
    print_command "$ADB_BIN" -s "$DEVICE" "$@"
    if output="$("$ADB_BIN" -s "$DEVICE" "$@" 2>&1)"; then
        status=0
    else
        status=$?
    fi

    if [[ $status -ne 0 ]] || is_error_output "$output"; then
        [[ -n "$output" ]] && printf '%s\n' "$output" >&2
        return 1
    fi

    [[ -n "$output" ]] && printf '%s\n' "$output"
    return 0
}

detect_screen_size() {
    local output
    local detected

    if [[ -n "$SCREEN_SIZE_OVERRIDE" ]]; then
        printf '%s\n' "$SCREEN_SIZE_OVERRIDE"
        return 0
    fi

    if ! output="$("$ADB_BIN" -s "$DEVICE" shell wm size 2>&1)"; then
        printf '%s\n' "$output" >&2
        return 1
    fi

    # wm size 同时存在 Physical/Override 时，以最后出现的 Override 为准。
    detected="$(printf '%s\n' "$output" | grep -Eo '[0-9]+x[0-9]+' | tail -n 1)"
    [[ -n "$detected" ]] || return 1
    printf '%s\n' "$detected"
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        -d|--device)
            require_value "$1" "${2:-}"
            DEVICE="$2"
            shift 2
            ;;
        -m|--direction|--mode)
            require_value "$1" "${2:-}"
            DIRECTION="$2"
            shift 2
            ;;
        -r|--ratio)
            require_value "$1" "${2:-}"
            SPLIT_RATIO="$2"
            shift 2
            ;;
        --partner)
            require_value "$1" "${2:-}"
            PARTNER_COMPONENT="$2"
            shift 2
            ;;
        --keep-current)
            PARTNER_COMPONENT=""
            shift
            ;;
        --component)
            require_value "$1" "${2:-}"
            COMPONENT="$2"
            shift 2
            ;;
        --screen-size)
            require_value "$1" "${2:-}"
            SCREEN_SIZE_OVERRIDE="$2"
            shift 2
            ;;
        --dry-run)
            DRY_RUN=1
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            die "未知参数：$1（使用 --help 查看帮助）"
            ;;
    esac
done

RAW_DIRECTION="$DIRECTION"
if ! DIRECTION="$(normalize_direction "$RAW_DIRECTION")"; then
    die "不支持的分屏方向：${RAW_DIRECTION}；可选 left-right 或 top-bottom"
fi

case "$SPLIT_RATIO" in
    ''|*[!0-9]*)
        die "分屏比例必须是 20-80 之间的整数"
        ;;
esac
if ((SPLIT_RATIO < 20 || SPLIT_RATIO > 80)); then
    die "分屏比例必须在 20-80 之间"
fi

[[ "$COMPONENT" == */* ]] || die "易控组件格式错误，应为 包名/Activity"
PACKAGE="${COMPONENT%%/*}"

if [[ -n "$SCREEN_SIZE_OVERRIDE" ]] && ! [[ "$SCREEN_SIZE_OVERRIDE" =~ ^[0-9]+x[0-9]+$ ]]; then
    die "屏幕尺寸格式错误，应为 宽x高，例如 2048x1080"
fi

if [[ $DRY_RUN -eq 1 ]]; then
    SCREEN_SIZE="${SCREEN_SIZE_OVERRIDE:-2048x1080}"
    log "DRY RUN：不会连接或操作设备；模拟屏幕尺寸 $SCREEN_SIZE"
else
    command -v "$ADB_BIN" >/dev/null 2>&1 || die "找不到 adb，请先安装 Android Platform Tools"

    if [[ "$DEVICE" == *:* ]]; then
        log "连接 ADB 设备 ${DEVICE}"
        if ! CONNECT_OUTPUT="$("$ADB_BIN" connect "$DEVICE" 2>&1)"; then
            printf '%s\n' "$CONNECT_OUTPUT" >&2
            die "ADB 网络连接失败"
        fi
        printf '%s\n' "$CONNECT_OUTPUT"
        if grep -Eqi 'cannot|failed|refused|unable' <<< "$CONNECT_OUTPUT"; then
            die "ADB 网络连接失败"
        fi
    fi

    if ! DEVICE_STATE="$("$ADB_BIN" -s "$DEVICE" get-state 2>/dev/null)" || [[ "$DEVICE_STATE" != "device" ]]; then
        die "设备不可用：${DEVICE}；请确认无线调试和 adb connect 状态"
    fi

    if ! PACKAGE_PATH="$("$ADB_BIN" -s "$DEVICE" shell pm path "$PACKAGE" 2>&1)" || [[ "$PACKAGE_PATH" != package:* ]]; then
        printf '%s\n' "$PACKAGE_PATH" >&2
        die "设备上没有安装 $PACKAGE"
    fi

    SCREEN_SIZE="$(detect_screen_size)" || die "无法读取设备屏幕尺寸，可用 --screen-size 手动指定"
fi

if ! [[ "$SCREEN_SIZE" =~ ^[0-9]+x[0-9]+$ ]]; then
    die "无法解析屏幕尺寸：$SCREEN_SIZE"
fi

SCREEN_WIDTH="${SCREEN_SIZE%x*}"
SCREEN_HEIGHT="${SCREEN_SIZE#*x}"
SPLIT_X=$((SCREEN_WIDTH * SPLIT_RATIO / 100))
SPLIT_Y=$((SCREEN_HEIGHT * SPLIT_RATIO / 100))

if [[ "$DIRECTION" == "left-right" ]]; then
    PRIMARY_BOUNDS=(0 0 "$SPLIT_X" "$SCREEN_HEIGHT")
    SECONDARY_BOUNDS=("$SPLIT_X" 0 "$SCREEN_WIDTH" "$SCREEN_HEIGHT")
    DIRECTION_CN="左右"
else
    PRIMARY_BOUNDS=(0 0 "$SCREEN_WIDTH" "$SPLIT_Y")
    SECONDARY_BOUNDS=(0 "$SPLIT_Y" "$SCREEN_WIDTH" "$SCREEN_HEIGHT")
    DIRECTION_CN="上下"
fi

PRIMARY_TEXT="${PRIMARY_BOUNDS[0]},${PRIMARY_BOUNDS[1]},${PRIMARY_BOUNDS[2]},${PRIMARY_BOUNDS[3]}"
SECONDARY_TEXT="${SECONDARY_BOUNDS[0]},${SECONDARY_BOUNDS[1]},${SECONDARY_BOUNDS[2]},${SECONDARY_BOUNDS[3]}"

log "设备：${DEVICE}"
log "模式：${DIRECTION_CN}分屏；易控窗格 ${PRIMARY_TEXT}；另一窗格 ${SECONDARY_TEXT}"

if [[ $DRY_RUN -eq 1 ]]; then
    if [[ "$DEVICE" == *:* ]]; then
        print_command "$ADB_BIN" connect "$DEVICE"
    fi
    print_command "$ADB_BIN" -s "$DEVICE" get-state
    print_command "$ADB_BIN" -s "$DEVICE" shell pm path "$PACKAGE"
    if [[ -n "$PARTNER_COMPONENT" ]]; then
        print_command "$ADB_BIN" -s "$DEVICE" shell am start -W -n "$PARTNER_COMPONENT"
    fi
    print_command "$ADB_BIN" -s "$DEVICE" shell am start -W --windowingMode 3 -n "$COMPONENT"
    print_command "$ADB_BIN" -s "$DEVICE" shell cmd activity stack resize-docked-stack \
        "${PRIMARY_BOUNDS[@]}" "${PRIMARY_BOUNDS[@]}"
    print_command "$ADB_BIN" -s "$DEVICE" shell am stack resize-docked-stack \
        "${PRIMARY_BOUNDS[@]}" "${PRIMARY_BOUNDS[@]}"
    print_command "$ADB_BIN" -s "$DEVICE" shell dumpsys activity activities
    log "DRY RUN 完成"
    exit 0
fi

if [[ -n "$PARTNER_COMPONENT" ]]; then
    run_checked "启动另一窗格：$PARTNER_COMPONENT" \
        shell am start -W -n "$PARTNER_COMPONENT" || die "搭档应用启动失败，可改用 --keep-current"
    sleep 1
else
    log "保留当前前台应用作为另一窗格"
fi

run_checked "把易控启动到第一分屏窗格" \
    shell am start -W --windowingMode 3 -n "$COMPONENT" || \
    die "ROM 不接受 split-screen-primary（windowingMode 3）启动命令"
sleep 1

RESIZE_OK=0
if try_resize "尝试通过 cmd activity 调整分屏方向和比例" \
    shell cmd activity stack resize-docked-stack \
    "${PRIMARY_BOUNDS[@]}" "${PRIMARY_BOUNDS[@]}"; then
    RESIZE_OK=1
elif try_resize "cmd activity 不可用，回退到旧版 am stack 命令" \
    shell am stack resize-docked-stack \
    "${PRIMARY_BOUNDS[@]}" "${PRIMARY_BOUNDS[@]}"; then
    RESIZE_OK=1
else
    warn "ROM 不支持 resize-docked-stack，无法强制修改分屏方向或比例"
fi
sleep 1

if ! ACTIVITY_DUMP="$("$ADB_BIN" -s "$DEVICE" shell dumpsys activity activities 2>&1)"; then
    printf '%s\n' "$ACTIVITY_DUMP" >&2
    die "无法读取 Activity 状态"
fi

if ! grep -Fq "$PACKAGE" <<< "$ACTIVITY_DUMP"; then
    die "Activity 状态中没有找到 ${PACKAGE}，易控可能没有成功启动"
fi

log "已找到易控任务，下面是设备返回的相关状态："
printf '%s\n' "$ACTIVITY_DUMP" | awk -v package="$PACKAGE" '
    index($0, package) && blocks < 3 {
        remaining = 8
        blocks++
    }
    remaining > 0 && printed < 24 {
        print "  " $0
        remaining--
        printed++
    }
'

if grep -Eqi \
    'windowingMode=(3|4|split-screen|multi-window)|mWindowingMode=(3|4)' \
    <<< "$ACTIVITY_DUMP"; then
    log "Activity 状态包含分屏/多窗口标记"
else
    warn "未从 dumpsys 中识别到通用分屏标记，请以车机实际画面为准"
fi

if [[ $RESIZE_OK -eq 1 ]]; then
    log "${DIRECTION_CN}分屏尺寸命令已被系统接受"
elif [[ "$DIRECTION" == "top-bottom" && "$SCREEN_WIDTH" -gt "$SCREEN_HEIGHT" ]]; then
    warn "当前是横屏且 ROM 拒绝尺寸命令，系统很可能仍会使用左右分屏"
fi

log "执行完成"
