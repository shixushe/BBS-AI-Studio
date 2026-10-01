"""BBS AI Studio game-debugging MCP server (copilot spec section 12.4).

Tools: mc_ping / mc_log_tail / mc_screenshot / mc_command / mc_chat / mc_ui.
MCP stdio transport (newline-delimited JSON-RPC 2.0), zero third-party
dependencies - ctypes for input injection and window capture, PIL for the
PNG encode.

Seamless debugging (user-invisible): every input call restores the previously
foreground window when it finishes, and mc_command accepts batch sequences -
"seq:click:958,246;;wait:0.5;;/aiui ai" - executed under a single focus.

Input model: the game window is focused, then keys are injected as scan
codes (GLFW key actions) and text as VK_PACKET unicode (chat text).
"""

import json
import os
import urllib.parse
import sys
import time
import ctypes
import ctypes.wintypes as wt

try:
    ctypes.windll.shcore.SetProcessDpiAwareness(2)
except Exception:
    pass

GAME_DIR = r"F:\mc\.minecraft\versions\BBS FS"
LOG_PATH = os.path.join(GAME_DIR, "logs", "latest.log")
WINDOW_TITLEFragment = "minecraft"

user32 = ctypes.windll.user32


class KEYBDINPUT(ctypes.Structure):
    _fields_ = [("wVk", wt.WORD), ("wScan", wt.WORD), ("dwFlags", wt.DWORD),
                ("time", wt.DWORD), ("dwExtraInfo", ctypes.POINTER(wt.ULONG))]


class MOUSEINPUT(ctypes.Structure):
    _fields_ = [("dx", wt.LONG), ("dy", wt.LONG), ("mouseData", wt.DWORD),
                ("dwFlags", wt.DWORD), ("time", wt.DWORD), ("dwExtraInfo", ctypes.POINTER(wt.ULONG))]


class INPUTUNION(ctypes.Union):
    _fields_ = [("ki", KEYBDINPUT), ("mi", MOUSEINPUT)]


class INPUT(ctypes.Structure):
    _fields_ = [("type", wt.DWORD), ("union", INPUTUNION)]


INPUT_KEYBOARD = 1
INPUT_MOUSE = 0
KEYEVENTF_SCANCODE = 0x0008
KEYEVENTF_KEYUP = 0x0002
KEYEVENTF_UNICODE = 0x0004
MOUSEEVENTF_ABSOLUTE = 0x8000
MOUSEEVENTF_LEFTDOWN = 0x0002
MOUSEEVENTF_LEFTUP = 0x0004

SCAN = {
    "escape": 0x01, "1": 0x02, "2": 0x03, "3": 0x04, "4": 0x05, "5": 0x06,
    "6": 0x07, "7": 0x08, "8": 0x09, "9": 0x0A, "0": 0x0B, "minus": 0x0C,
    "tab": 0x0F, "q": 0x10, "w": 0x11, "e": 0x12, "r": 0x13, "t": 0x14,
    "y": 0x15, "u": 0x16, "i": 0x17, "o": 0x18, "p": 0x19, "enter": 0x1C,
    "ctrl": 0x1D, "a": 0x1E, "s": 0x1F, "d": 0x20, "f": 0x21, "g": 0x22,
    "h": 0x23, "j": 0x24, "k": 0x25, "l": 0x26, "shift": 0x2A, "z": 0x2C,
    "x": 0x2D, "c": 0x2E, "v": 0x2F, "b": 0x30, "n": 0x31, "m": 0x32,
    "slash": 0x35, "space": 0x39, "backspace": 0x0E, "f1": 0x3B, "f4": 0x3E,
}


def _key(scan, up=False):
    flags = KEYEVENTF_SCANCODE | (KEYEVENTF_KEYUP if up else 0)
    item = INPUT(type=INPUT_KEYBOARD)
    item.union.ki = KEYBDINPUT(0, scan, flags, 0, None)
    SendInput(1, ctypes.byref(item), ctypes.sizeof(INPUT))


SendInput = user32.SendInput


def _unicode(ch):
    for flags in (KEYEVENTF_UNICODE, KEYEVENTF_UNICODE | KEYEVENTF_KEYUP):
        item = INPUT(type=INPUT_KEYBOARD)
        item.union.ki = KEYBDINPUT(0, ord(ch), flags, 0, None)
        SendInput(1, ctypes.byref(item), ctypes.sizeof(INPUT))


def press(key_name, hold=0.02):
    scan = SCAN.get(key_name.lower())
    if scan is None:
        raise ValueError("unknown key: " + key_name)
    _key(scan)
    time.sleep(hold)
    _key(scan, up=True)


def find_game_window():
    """Return (hwnd, rect) of the Minecraft window, or (0, None)."""
    result = []

    @ctypes.WINFUNCTYPE(ctypes.c_bool, wt.HWND, wt.LPARAM)
    def callback(hwnd, _):
        length = user32.GetWindowTextLengthW(hwnd)
        if length:
            buffer = ctypes.create_unicode_buffer(length + 1)
            user32.GetWindowTextW(hwnd, buffer, length + 1)
            if WINDOW_TITLEFragment in buffer.value.lower() and user32.IsWindowVisible(hwnd):
                rect = wt.RECT()
                user32.GetWindowRect(hwnd, ctypes.byref(rect))
                result.append((hwnd, (rect.left, rect.top, rect.right, rect.bottom)))
        return True

    user32.EnumWindows(callback, 0)
    return result[0] if result else (0, None)


def focus_game():
    hwnd, rect = find_game_window()
    if not hwnd:
        return False

    for _ in range(4):
        if not user32.IsIconic(hwnd):
            break
        user32.ShowWindow(hwnd, 9)  # SW_RESTORE
        time.sleep(0.35)

    # Reliable foreground: borrow the foreground thread's input queue
    foreground = user32.GetForegroundWindow()
    tid_foreground = user32.GetWindowThreadProcessId(foreground, None)
    tid_target = user32.GetWindowThreadProcessId(hwnd, None)
    user32.AttachThreadInput(tid_foreground, tid_target, True)
    user32.SetForegroundWindow(hwnd)
    user32.AttachThreadInput(tid_foreground, tid_target, False)
    user32.BringWindowToTop(hwnd)
    time.sleep(0.3)

    return not user32.IsIconic(hwnd)


def type_text(text):
    for ch in text:
        _unicode(ch)
        time.sleep(0.01)


def click_at(x, y):
    if not focus_game():
        raise RuntimeError("game window could not be restored")
    hwnd, rect = find_game_window()
    if not hwnd:
        raise RuntimeError("game window not found")

    screen_x = rect[0] + x
    screen_y = rect[1] + y
    width = user32.GetSystemMetrics(0)
    height = user32.GetSystemMetrics(1)
    ax = int(screen_x * 65535 / width)
    ay = int(screen_y * 65535 / height)

    down = INPUT(type=INPUT_MOUSE)
    down.union.mi = MOUSEINPUT(ax, ay, 0, MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_LEFTDOWN, 0, None)
    up = INPUT(type=INPUT_MOUSE)
    up.union.mi = MOUSEINPUT(ax, ay, 0, MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_LEFTUP, 0, None)
    SendInput(1, ctypes.byref(down), ctypes.sizeof(INPUT))
    time.sleep(0.05)
    SendInput(1, ctypes.byref(up), ctypes.sizeof(INPUT))


def screenshot(path=None):
    # Seamless path: the game renders its own screenshot
    in_game = http_call("/ping")

    if in_game is not None:
        query = "?path=" + urllib.parse.quote(path) if path else ""
        result = http_call("/screenshot" + query, timeout=20.0)

        if result and "path" in result:
            return {"via": "http", **result}

    if not focus_game():
        raise RuntimeError("game window could not be restored")

    hwnd, rect = find_game_window()
    if not hwnd:
        raise RuntimeError("game window not found")

    time.sleep(0.4)

    import PIL.ImageGrab

    image = PIL.ImageGrab.grab(bbox=rect)

    if path is None:
        folder = os.path.join(GAME_DIR, "ai_shots")
        os.makedirs(folder, exist_ok=True)
        path = os.path.join(folder, "shot_%d.png" % int(time.time() * 1000))

    os.makedirs(os.path.dirname(path), exist_ok=True)
    image.save(path)

    return {"path": path, "size": [image.width, image.height]}


def log_tail(lines=200):
    try:
        with open(LOG_PATH, "r", encoding="utf-8", errors="replace") as handle:
            content = handle.readlines()
        return "".join(content[-int(lines):])
    except FileNotFoundError:
        return "(log file not found - game not running?)"


def game_state():
    hwnd, rect = find_game_window()
    tail = log_tail(40)

    return {
        "game_running": bool(hwnd) and not user32.IsIconic(hwnd),
        "window_rect": rect,
        "recent": tail.splitlines()[-5:] if tail else [],
    }



HTTP_BASE = "http://127.0.0.1:17878"


def http_call(path, body=None, timeout=12.0):
    """Call the in-game debug server; returns parsed JSON or None (offline)."""
    import urllib.request

    try:
        if body is None:
            request = urllib.request.Request(HTTP_BASE + path)
        else:
            request = urllib.request.Request(
                HTTP_BASE + path,
                data=json.dumps(body).encode("utf-8"),
                headers={"Content-Type": "application/json"},
                method="POST")

        with urllib.request.urlopen(request, timeout=timeout) as response:
            return json.loads(response.read().decode("utf-8"))
    except Exception:
        return None


def run_action(command):
    """One atomic action (shared by single commands and sequences)."""
    command = command.strip()
    if not command:
        return {"skipped": True}

    if command.startswith("click:"):
        x, y = command.split(":", 1)[1].split(",")
        click_at(int(x), int(y))
        time.sleep(0.4)
        return {"done": command}

    if command.startswith("key:"):
        focus_game()
        press(command.split(":", 1)[1].strip())
        time.sleep(0.4)
        return {"done": command}

    if command.startswith("wait:"):
        time.sleep(min(5.0, float(command.split(":", 1)[1])))
        return {"done": command}

    focus_game()
    if command.startswith("/"):
        press("slash")
    else:
        press("t")
    time.sleep(0.35)
    type_text(command.lstrip("/"))
    time.sleep(0.2)
    press("enter")
    time.sleep(0.5)
    return {"done": command}


def tool_mc_command(args):
    command = str(args.get("command", "")).strip()
    if not command:
        return {"error": "empty command"}

    # Seamless path: the in-game HTTP debug server (no focus changes at all)
    in_game = http_call("/ping")

    if in_game is not None:
        if command.startswith("/"):
            result = http_call("/command", {"command": command}, timeout=20.0)

            if result is not None:
                return {"via": "http", "result": result, "state": game_state()}
        # OS fallback below for keys/clicks (render-thread actions)
        pass

    # Sequence mode: one focus, previous foreground restored at the end
    if command.startswith("seq:"):
        previous = user32.GetForegroundWindow()
        steps = [step for step in command[4:].split(";;") if step.strip()]
        results = []

        for step in steps:
            results.append(run_action(step))

        user32.SetForegroundWindow(previous)

        return {"done": steps, "results": results, "state": game_state()}

    result = run_action(command)
    result["state"] = game_state()

    return result


def tool_mc_chat(args):
    message = str(args.get("message", ""))
    focus_game()
    press("t")
    time.sleep(0.35)
    type_text(message)
    time.sleep(0.2)
    press("enter")
    time.sleep(0.3)
    return {"done": message, "state": game_state()}


UI_PANELS = {
    "dashboard": None,
    "film": "film",
    "ai": "ai",
    "capture": "capture",
    "creative": "creative",
}


def tool_mc_ui(args):
    panel = str(args.get("panel", "") or "").strip().lower()
    close = bool(args.get("close", False))

    if close:
        focus_game()
        press("escape")
        time.sleep(0.3)
        return {"done": "escape", "state": game_state()}

    if panel == "dashboard":
        focus_game()
        press("0")
        time.sleep(0.8)
        return {"done": "dashboard (key 0)", "state": game_state()}

    name = UI_PANELS.get(panel)
    if name is None:
        return {"error": "unknown panel: %s (known: %s)" % (panel, ", ".join(list(UI_PANELS) + ["close"]))}

    return tool_mc_command({"command": "seq:wait:0.3;;/aiui " + name})


TOOLS = [
    {"name": "mc_ping",
     "description": "检查游戏是否在运行，返回玩家/世界/当前界面快照",
     "inputSchema": {"type": "object", "properties": {}, "required": []}},
    {"name": "mc_log_tail",
     "description": "读取游戏日志 logs/latest.log 的尾部，用于排查崩溃与报错",
     "inputSchema": {"type": "object", "properties": {"lines": {"type": "integer", "default": 200}}, "required": []}},
    {"name": "mc_screenshot",
     "description": "抓取当前游戏画面并保存为 PNG，返回保存路径。path 省略时自动命名到工作目录",
     "inputSchema": {"type": "object", "properties": {"path": {"type": "string", "default": ""}}, "required": []}},
    {"name": "mc_command",
     "description": "向游戏发送指令/按键/点击/序列。'/xxx'=聊天指令；'key:0'=按键；'click:x,y'=点击窗口相对坐标；'wait:秒'=等待；'seq:a;;b'=一次焦点内批量执行",
     "inputSchema": {"type": "object", "properties": {"command": {"type": "string"}}, "required": ["command"]}},
    {"name": "mc_chat",
     "description": "以玩家身份发送一条普通聊天消息",
     "inputSchema": {"type": "object", "properties": {"message": {"type": "string"}}, "required": ["message"]}},
    {"name": "mc_ui",
     "description": "程序化打开游戏内界面：panel=dashboard/ai/capture/creative/film；close=True 关闭当前界面",
     "inputSchema": {"type": "object", "properties": {"panel": {"type": "string", "default": ""}, "section": {"type": "string", "default": ""}, "guide": {"type": "boolean", "default": False}, "settings": {"type": "boolean", "default": False}, "close": {"type": "boolean", "default": False}}, "required": []}},
]

HANDLERS = {
    "mc_ping": lambda args: game_state(),
    "mc_log_tail": lambda args: log_tail(int(args.get("lines", 200))),
    "mc_screenshot": lambda args: screenshot(args.get("path") or None),
    "mc_command": tool_mc_command,
    "mc_chat": tool_mc_chat,
    "mc_ui": tool_mc_ui,
}


def reply(request_id, result=None, error=None):
    message = {"jsonrpc": "2.0", "id": request_id}
    if error is not None:
        message["error"] = error
    else:
        message["result"] = result
    sys.stdout.write(json.dumps(message, ensure_ascii=False) + "\n")
    sys.stdout.flush()


def serve():
    for raw in sys.stdin:
        raw = raw.strip()
        if not raw:
            continue
        try:
            request = json.loads(raw)
        except ValueError:
            continue

        method = request.get("method", "")
        request_id = request.get("id")

        if method == "initialize":
            reply(request_id, {
                "protocolVersion": "2024-11-05",
                "capabilities": {"tools": {}},
                "serverInfo": {"name": "bbs-ai-mc", "version": "2.1.0"},
            })
        elif method == "notifications/initialized":
            pass
        elif method == "tools/list":
            reply(request_id, {"tools": TOOLS})
        elif method == "tools/call":
            params = request.get("params", {})
            name = params.get("name", "")
            arguments = params.get("arguments", {}) or {}
            handler = HANDLERS.get(name)
            if handler is None:
                reply(request_id, {"error": {"code": -32601, "message": "unknown tool " + name}})
                continue
            try:
                result = handler(arguments)
                reply(request_id, {"content": [{"type": "text", "text": json.dumps(result, ensure_ascii=False)}]})
            except Exception as exception:
                reply(request_id, {"content": [{"type": "text", "text": "ERROR: %r" % (exception,)}], "isError": True})
        elif request_id is not None:
            reply(request_id, {"error": {"code": -32601, "message": "unknown method " + method}})


if __name__ == "__main__":
    serve()
