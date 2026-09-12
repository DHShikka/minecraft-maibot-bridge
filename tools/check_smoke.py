"""跨语言冒烟测试的断言部分。

跑法（两步，需要并行）：

1. ``python tools/bridge_smoke_server.py``   起真实插件服务端，等 Java 客户端来连；
2. 用 ``BridgeSmokeTest`` 去连它（连法与真实模组一致，用的就是模组里那份 WsClient）：

       javac/javatest 编好后：
       java BridgeSmokeTest ws://127.0.0.1:<port>/mc .research/smoke-java.json

   端口写在 ``.research/smoke-port.txt`` 里。

两份产物都落盘后，用本脚本断言：

    python tools/check_smoke.py

验证的是「Java 手写 WebSocket 客户端 ↔ Python asyncio 服务端」的真实互通性 ——
握手、状态回传、动作往返、事件上报、关闭握手，全都走真实字节流，而不是各自的单测。
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SERVER_FILE = ROOT / ".research" / "smoke-server.json"
JAVA_FILE = ROOT / ".research" / "smoke-java.json"

# 控制台编码兜底：下面会打 ↔ 这类符号，Windows 控制台默认是 GBK，
# 直接 print 会抛 UnicodeEncodeError 把测试从中间掐断。
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace")  # type: ignore[union-attr]
    except (AttributeError, ValueError):  # pragma: no cover
        pass

passed = 0
failures: list[str] = []


def check(condition: bool, name: str, detail: str = "") -> None:
    global passed
    if condition:
        passed += 1
        print(f"  [PASS] {name}")
    else:
        failures.append(name)
        print(f"  [FAIL] {name}  {detail}")


def main() -> int:
    for path in (SERVER_FILE, JAVA_FILE):
        if not path.exists():
            print(f"缺少 {path.relative_to(ROOT)}。请先跑 bridge_smoke_server.py 与 BridgeSmokeTest。")
            return 2

    server = json.loads(SERVER_FILE.read_text("utf-8"))
    java = json.loads(JAVA_FILE.read_text("utf-8"))

    print("== 跨语言冒烟：Java 客户端 ↔ Python 服务端 ==")

    # ---- Java 侧：连上了、握手过了、动作都处理了
    check(java.get("opened") is True, "Java 客户端连上了服务端")
    check(java.get("helloAck") is True, "Java 客户端收到 hello_ack 且 ok=true",
          str(java.get("helloAckError")))
    handled = int(java.get("actionsHandled") or 0)
    check(handled >= 3, "Java 客户端处理了服务端下发的动作", f"actionsHandled={handled}")

    params = java.get("actionParams") or {}
    move = params.get("move_to") or {}
    check(move.get("x") == 7 and move.get("y") == 64 and move.get("z") == 9,
          "动作参数跨语言原样送达（中文/数字都没变）", json.dumps(move, ensure_ascii=False))
    check((params.get("chat") or {}).get("message") == "这是一条出站测试消息",
          "UTF-8 中文参数没被编码弄坏", json.dumps(params.get("chat"), ensure_ascii=False))

    # ---- 服务端侧：握手、状态、往返、事件
    check(server.get("success") is True, "服务端整体成功", str(server.get("error")))
    check(bool(server.get("handshake")), "服务端记下了完整的握手信息",
          str(server.get("handshake"))[:100])
    check(server.get("receivedState") is True, "状态快照从 Java 回传到服务端")
    check(bool(server.get("statePlayerPos")), "状态里的坐标被解析出来了",
          json.dumps(server.get("statePlayerPos"), ensure_ascii=False))
    round_trip = server.get("actionRoundTrip") or {}
    check(bool(round_trip.get("ok")), "动作往返成功（服务端下发 → Java 回执）",
          str(round_trip)[:120])
    check(server.get("receivedChatEvent") is True, "游戏聊天事件从 Java 上报到服务端")

    # ---- 关闭握手：两边都认为关干净了
    check(int(java.get("closeCode") or 0) == 1000, "关闭码是 1000（正常关闭）",
          str(java.get("closeCode")))

    print("=" * 57)
    print(f"通过 {passed} 项，失败 {len(failures)} 项")
    for name in failures:
        print(f"  [FAIL] {name}")
    print("=" * 57)
    return 0 if not failures else 1


if __name__ == "__main__":
    sys.exit(main())
