"""MaiBot 插件自检 + 端到端协议测试。

它会做四件事：

1. 用 ``py_compile`` 检查所有 Python 文件语法；
2. 按 Host 侧 ``ManifestValidator`` 的真实规则校验 ``_manifest.json``（字段、正则、禁止多余字段）；
3. 用 ``tools/stub_sdk`` 里签名一致的假 SDK 加载 ``plugin.py``，模拟 Runner 的组件发现，
   检查生命周期方法、配置模型、组件声明是否完整；
4. 起一个真的 WebSocket 服务端（就是插件用的那份代码），用一个手写的 WebSocket 客户端
   扮演 Minecraft 模组，跑一遍完整链路：握手 → 上报聊天 → 麦麦侧调用工具 → 模组收到动作并回传结果。

第 4 步是重点：它验证的是模组与插件之间真实的线上协议，而不是「代码看起来对」。

用法：
    <python> tools/check_plugin.py
"""

from __future__ import annotations

import asyncio
import base64
import hashlib
import json
import os
import py_compile
import re
import struct
import sys
import time
import traceback
from pathlib import Path
from typing import Any, Optional

ROOT = Path(__file__).resolve().parent.parent
PLUGIN_DIR = ROOT / "minecraft-bridge"
STUB_SDK = ROOT / "tools" / "stub_sdk"
#: 插件目录里的子包名（插件自己把它插进 sys.path，见 plugin.py 开头）
PLUGIN_PACKAGE = "mcai_bridge"

# ---------------------------------------------------------------------------
#  刻意**不**把插件目录放进 sys.path。
#
#  MaiBot 的 PluginLoader 加载插件时，加进 sys.path 的是「插件目录的父目录」
#  而不是插件目录本身（plugin_loader.py:575-579）。如果这里的自检偷偷把插件
#  目录加进去，就会掩盖「插件没有自己处理 sys.path」这一类 bug —— 而这类 bug
#  的表现正是用户在 MaiBot 插件页上看到的 "No module named 'mcai_bridge'"。
#
#  所以 sys.path 里只放 stub_sdk（模拟 Runner 环境里已安装的 maibot_sdk），
#  真正的加载在 check_plugin_load() 里按 Runner 的方式做。
# ---------------------------------------------------------------------------
sys.path.insert(0, str(STUB_SDK))

# 控制台编码兜底：这个自检会把**模组回传的原文**打出来，里面有 ✓ 之类的符号，
# 而 Windows 控制台默认是 GBK —— 直接 print 会抛 UnicodeEncodeError，
# 把整个端到端测试从中间掐断（表现为「突然只剩后面几条检查」）。
# 所以强制 UTF-8 输出，编不出来的字符退化成 ?。
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace")  # type: ignore[union-attr]
    except (AttributeError, ValueError):  # pragma: no cover - 老解释器/被重定向
        pass

PASSED: list[str] = []
FAILED: list[str] = []


def ok(label: str, detail: str = "") -> None:
    PASSED.append(label)
    print(f"  [PASS] {label}" + (f"  {detail}" if detail else ""))


def bad(label: str, detail: str = "") -> None:
    FAILED.append(label)
    print(f"  [FAIL] {label}" + (f"  {detail}" if detail else ""))


def check(condition: bool, label: str, detail: str = "") -> bool:
    if condition:
        ok(label, detail)
    else:
        bad(label, detail)
    return condition


# ============================================================================
# 1. 语法检查
# ============================================================================


def check_syntax() -> None:
    print("\n== 1. Python 语法检查 ==")
    files = sorted(p for p in PLUGIN_DIR.rglob("*.py"))
    if not files:
        bad("找到插件源码", f"{PLUGIN_DIR.name} 下没有 .py 文件")
        return
    for path in files:
        label = str(path.relative_to(ROOT))
        try:
            source = path.read_text(encoding="utf-8")
        except UnicodeDecodeError as exc:
            bad("UTF-8 编码", f"{label}: {exc}")
            continue
        except OSError as exc:
            bad("读取源文件", f"{label}: {exc}")
            continue
        try:
            # compile() 只做语法检查，不写 .pyc，避免 Windows 上 os.devnull 不是普通文件的问题
            compile(source, str(path), "exec")
            ok("语法", label)
        except SyntaxError as exc:
            bad("语法", f"{label}:{exc.lineno}: {exc.msg}")


# ============================================================================
# 2. Manifest 校验（规则来自 Host 的 ManifestValidator）
# ============================================================================

SEMVER = re.compile(r"^\d+\.\d+\.\d+$")
PLUGIN_ID = re.compile(r"^[A-Za-z0-9_]+(?:[.-][A-Za-z0-9_]+)+$")
HTTP_URL = re.compile(r"^https?://.+$")
PACKAGE_NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*$")
HEX_COLOR = re.compile(r"^#[0-9A-Fa-f]{6}$")
ICON_NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_-]*$")

ALLOWED_TOP = {
    "manifest_version", "id", "version", "name", "description", "author", "license", "urls",
    "host_application", "sdk", "capabilities", "i18n", "dependencies", "llm_providers",
    "plugin_type", "type", "display", "changelog",
}
ALLOWED_URLS = {"repository", "homepage", "documentation", "issues"}
ALLOWED_I18N = {"default_locale", "locales_path", "supported_locales"}
ALLOWED_AUTHOR = {"name", "url"}
ALLOWED_VERSION_RANGE = {"min_version", "max_version"}
ALLOWED_ICON = {"type", "value", "fallback", "background"}
ALLOWED_DISPLAY = {"icon"}
ALLOWED_DEPS_PLUGIN = {"type", "id", "version_spec"}
ALLOWED_DEPS_PKG = {"type", "name", "version_spec"}

# Host capabilities/registry.py 里注册过的能力名（精确匹配才生效）
KNOWN_CAPABILITIES = {
    "send.text", "send.emoji", "send.image", "send.forward", "send.hybrid", "send.command", "send.custom",
    "llm.generate", "llm.generate_with_tools", "llm.embed", "llm.transcribe_audio", "llm.get_available_models",
    "config.get", "config.get_plugin", "config.get_all",
    "database.query", "database.save", "database.get", "database.delete", "database.count",
    "chat.get_all_streams", "chat.get_group_streams", "chat.get_private_streams", "chat.open_session",
    "chat.get_stream_by_group_id", "chat.get_stream_by_user_id",
    "message.get_by_time", "message.get_by_time_in_chat", "message.get_by_id", "message.get_recent",
    "message.count_new", "message.build_readable",
    "maisaka.context.append", "maisaka.proactive.trigger",
    "person.get_id", "person.get_value", "person.get_id_by_name",
    "emoji.get_by_description", "emoji.get_random", "emoji.get_count", "emoji.get_emotions",
    "emoji.get_all", "emoji.get_info", "emoji.register", "emoji.delete",
    "frequency.get_current_talk_value", "frequency.set_adjust", "frequency.get_adjust",
    "tool.get_definitions",
    "api.call", "api.get", "api.list", "api.replace_dynamic",
    "component.get_all_plugins", "component.get_plugin_info", "component.get_plugin_config_schema",
    "component.update_plugin_config", "component.list_loaded_plugins", "component.list_registered_plugins",
    "component.enable", "component.disable", "component.load_plugin", "component.unload_plugin",
    "component.reload_plugin",
    "knowledge.search",
    "statistics.local.models", "statistics.local.model_trend", "statistics.local.token_trend",
    "statistics.local.token_distribution", "statistics.local.message_trend", "statistics.local.tool_trend",
    "statistics.local.online_time_trend",
    "render.html2png",
}


def check_manifest() -> Optional[dict[str, Any]]:
    print("\n== 2. Manifest 校验 ==")
    path = PLUGIN_DIR / "_manifest.json"
    if not path.exists():
        bad("_manifest.json 存在")
        return None
    try:
        manifest = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        bad("_manifest.json 是合法 JSON", str(exc))
        return None
    ok("_manifest.json 是合法 JSON")

    required = ["manifest_version", "id", "version", "name", "description", "author", "license",
                "urls", "host_application", "sdk", "capabilities", "i18n"]
    for key in required:
        check(key in manifest, f"必填字段 {key}")

    check(manifest.get("manifest_version") == 2, "manifest_version == 2",
          f"实际 {manifest.get('manifest_version')!r}")

    pid = manifest.get("id", "")
    check(bool(PLUGIN_ID.match(pid)), "id 符合 ^[A-Za-z0-9_]+(?:[.-][A-Za-z0-9_]+)+$", f"id={pid!r}")

    version = manifest.get("version", "")
    check(bool(SEMVER.match(version)), "version 是严格三段式语义版本", f"version={version!r}")

    extra = set(manifest) - ALLOWED_TOP
    check(not extra, "没有未声明字段", f"多余字段: {sorted(extra)}" if extra else "")

    author = manifest.get("author") or {}
    check(isinstance(author, dict) and set(author) <= ALLOWED_AUTHOR,
          "author 字段合法", f"{author!r}")
    check(bool(HTTP_URL.match(str(author.get("url", "")))), "author.url 是 http(s) URL",
          str(author.get("url")))
    check(bool(str(author.get("name", "")).strip()), "author.name 非空")

    urls = manifest.get("urls") or {}
    check(isinstance(urls, dict) and set(urls) <= ALLOWED_URLS, "urls 字段合法", f"{sorted(urls)}")
    check(bool(HTTP_URL.match(str(urls.get("repository", "")))), "urls.repository 是 http(s) URL",
          str(urls.get("repository")))

    for section in ("host_application", "sdk"):
        value = manifest.get(section) or {}
        lo, hi = str(value.get("min_version", "")), str(value.get("max_version", ""))
        check(isinstance(value, dict) and set(value) <= ALLOWED_VERSION_RANGE,
              f"{section} 字段合法", f"{value!r}")
        check(bool(SEMVER.match(lo)) and bool(SEMVER.match(hi)), f"{section} 版本号合法", f"{lo} ~ {hi}")
        if SEMVER.match(lo) and SEMVER.match(hi):
            check(tuple(int(x) for x in lo.split(".")) <= tuple(int(x) for x in hi.split(".")),
                  f"{section} min <= max")

    caps = manifest.get("capabilities")
    check(isinstance(caps, list) and all(isinstance(c, str) and c.strip() for c in caps or []),
          "capabilities 是非空字符串列表")
    if isinstance(caps, list):
        check(len(caps) == len(set(caps)), "capabilities 没有重复项")
        unknown = [c for c in caps if c not in KNOWN_CAPABILITIES]
        check(not unknown, "capabilities 全部是 Host 真实注册的能力名",
              f"无效（不会生效）: {unknown}" if unknown else "")

    plugin_type = manifest.get("plugin_type", "extension")
    check(plugin_type != "adapter",
          "plugin_type 不是 adapter（放在第三方 plugins/ 目录时 adapter 会被静默跳过）",
          f"plugin_type={plugin_type!r}")

    i18n = manifest.get("i18n") or {}
    check(isinstance(i18n, dict) and set(i18n) <= ALLOWED_I18N, "i18n 字段合法", f"{sorted(i18n)}")
    default_locale = str(i18n.get("default_locale", ""))
    check(bool(default_locale), "i18n.default_locale 非空")
    supported = i18n.get("supported_locales") or []
    check(not supported or default_locale in supported,
          "supported_locales 包含 default_locale", f"{supported}")
    locales_path = str(i18n.get("locales_path", ""))
    if locales_path:
        check((PLUGIN_DIR / locales_path).is_dir(), f"i18n 目录存在: {locales_path}")
        check(not Path(locales_path).is_absolute() and ".." not in locales_path,
              "locales_path 是插件内相对路径")

    display = manifest.get("display")
    if display is not None:
        check(isinstance(display, dict) and set(display) <= ALLOWED_DISPLAY, "display 字段合法")
        icon = (display or {}).get("icon")
        if icon is not None:
            check(isinstance(icon, dict) and set(icon) <= ALLOWED_ICON, "display.icon 字段合法", f"{icon!r}")
            check(icon.get("type") in {"lucide", "emoji", "local"}, "display.icon.type 合法",
                  str(icon.get("type")))
            if icon.get("type") == "local":
                value = str(icon.get("value", ""))
                check(not Path(value).is_absolute() and ".." not in value
                      and Path(value).suffix in {".png", ".jpg", ".jpeg", ".svg", ".webp"},
                      "display.icon 本地图标路径合法", value)
            if icon.get("fallback"):
                check(bool(ICON_NAME.match(str(icon["fallback"]))), "display.icon.fallback 合法")
            if icon.get("background"):
                check(bool(HEX_COLOR.match(str(icon["background"]))), "display.icon.background 是 #RRGGBB")

    for dep in manifest.get("dependencies") or []:
        if not isinstance(dep, dict):
            bad("dependencies 元素是对象", repr(dep))
            continue
        if dep.get("type") == "plugin":
            check(set(dep) <= ALLOWED_DEPS_PLUGIN and bool(PLUGIN_ID.match(str(dep.get("id", "")))),
                  "plugin 依赖字段合法", repr(dep))
        elif dep.get("type") == "python_package":
            check(set(dep) <= ALLOWED_DEPS_PKG and bool(PACKAGE_NAME.match(str(dep.get("name", "")))),
                  "python_package 依赖字段合法", repr(dep))
        else:
            bad("依赖 type 只能是 plugin / python_package", repr(dep))

    # i18n 语言文件本身要是合法 JSON
    for locale_file in sorted((PLUGIN_DIR / "i18n").glob("*.json")):
        try:
            json.loads(locale_file.read_text(encoding="utf-8"))
            ok("i18n 文件合法", locale_file.name)
        except json.JSONDecodeError as exc:
            bad("i18n 文件合法", f"{locale_file.name}: {exc}")

    return manifest


# ============================================================================
# 3. 插件加载 + 组件发现
# ============================================================================


class StubContext:
    """记录所有能力调用的假 PluginContext。"""

    def __init__(self) -> None:
        import logging
        from maibot_sdk import default_logger
        self.logger = default_logger("plugin.mcai_bridge")
        self.plugin_id = "mcai.minecraft-bridge"
        self.paths = type("Paths", (), {"data_dir": Path(os.devnull).parent, "runtime_dir": Path(os.devnull).parent})()
        self.calls: dict[str, list[dict[str, Any]]] = {}
        self.gateway = _GatewayProxy(self)
        self.chat = _ChatProxy(self)
        self.maisaka = _MaisakaProxy(self)
        self.send = _SendProxy(self)

    def record(self, name: str, **payload: Any) -> None:
        self.calls.setdefault(name, []).append(payload)


class _GatewayProxy:
    def __init__(self, ctx: StubContext) -> None:
        self._ctx = ctx

    async def update_state(self, gateway_name: str = "", *, ready: bool = False, platform: str = "",
                           account_id: str = "", scope: str = "", metadata: Optional[dict] = None,
                           **kwargs: Any) -> bool:
        self._ctx.record("gateway.update_state", gateway_name=gateway_name, ready=ready,
                         platform=platform, account_id=account_id, scope=scope)
        return True

    async def route_message(self, gateway_name: str = "", message: Optional[dict] = None,
                            *, route_metadata: Optional[dict] = None,
                            external_message_id: str = "", dedupe_key: str = "",
                            **kwargs: Any) -> bool:
        self._ctx.record("gateway.route_message", gateway_name=gateway_name, message=message,
                         route_metadata=route_metadata,
                         external_message_id=external_message_id, dedupe_key=dedupe_key)
        return True


class _ChatProxy:
    def __init__(self, ctx: StubContext) -> None:
        self._ctx = ctx

    async def open_session(self, platform: str = "", chat_type: str = "", **kwargs: Any) -> dict[str, Any]:
        self._ctx.record("chat.open_session", platform=platform, chat_type=chat_type, **kwargs)
        key = kwargs.get("group_id") or kwargs.get("user_id") or "unknown"
        return {"stream_id": f"stream-{key}", "session_id": f"stream-{key}",
                "chat_type": chat_type, "created": True}

    async def get_stream_by_group_id(self, group_id: str = "", platform: str = "",
                                     **kwargs: Any) -> dict[str, Any]:
        self._ctx.record("chat.get_stream_by_group_id", group_id=group_id, platform=platform)
        return {"stream_id": f"stream-{group_id}", "session_id": f"stream-{group_id}"}

    async def get_stream_by_user_id(self, user_id: str = "", platform: str = "",
                                    **kwargs: Any) -> dict[str, Any]:
        self._ctx.record("chat.get_stream_by_user_id", user_id=user_id, platform=platform)
        return {"stream_id": f"stream-{user_id}", "session_id": f"stream-{user_id}"}


class _ContextProxy:
    def __init__(self, ctx: StubContext) -> None:
        self._ctx = ctx

    async def append(self, stream_id: str = "", segments: Optional[list] = None, *,
                     visible_text: str = "", source_kind: str = "", message_id: str = "",
                     **kwargs: Any) -> dict[str, Any]:
        self._ctx.record("maisaka.context.append", stream_id=stream_id, segments=segments,
                         visible_text=visible_text, source_kind=source_kind)
        return {"success": True, "stream_id": stream_id}


class _ProactiveProxy:
    def __init__(self, ctx: StubContext) -> None:
        self._ctx = ctx

    async def trigger(self, stream_id: str = "", intent: str = "", *, reason: str = "",
                      priority: str = "", metadata: Optional[dict] = None,
                      **kwargs: Any) -> dict[str, Any]:
        self._ctx.record("maisaka.proactive.trigger", stream_id=stream_id, intent=intent,
                         reason=reason, metadata=metadata)
        return {"success": True, "queued": True}


class _MaisakaProxy:
    def __init__(self, ctx: StubContext) -> None:
        self._ctx = ctx
        self.context = _ContextProxy(ctx)
        self.proactive = _ProactiveProxy(ctx)


class _SendProxy:
    def __init__(self, ctx: StubContext) -> None:
        self._ctx = ctx

    async def text(self, text: str = "", stream_id: str = "", **kwargs: Any) -> bool:
        self._ctx.record("send.text", text=text, stream_id=stream_id)
        return True


def check_plugin_load() -> Optional[Any]:
    print("\n== 3. 插件加载与组件发现 ==")
    import importlib.util
    import os as _os

    # ---- 先把解释器环境摆成 Runner 的样子 --------------------------------
    # 移除任何插件目录相关条目；真实 Runner 只有 plugins/ 在 sys.path 上。
    _plugin_dir_norm = _os.path.normpath(str(PLUGIN_DIR))
    sys.path[:] = [p for p in sys.path if _os.path.normpath(p) != _plugin_dir_norm]
    for name in [n for n in sys.modules if n == PLUGIN_PACKAGE or n.startswith(PLUGIN_PACKAGE + ".")]:
        sys.modules.pop(name, None)
    sys.path.insert(0, _os.path.normpath(str(PLUGIN_DIR.parent)))

    check(_plugin_dir_norm not in {_os.path.normpath(p) for p in sys.path},
          "自检环境与 Runner 一致：插件目录不在 sys.path 上（否则会掩盖 import bug）")

    # ---- 完全照搬 PluginLoader._load_single_plugin 的加载方式 -------------
    spec = importlib.util.spec_from_file_location(
        "mcai_bridge_plugin",
        str(PLUGIN_DIR / "plugin.py"),
        submodule_search_locations=[str(PLUGIN_DIR)],
    )
    if spec is None or spec.loader is None:
        bad("导入 plugin.py", "无法创建模块 spec")
        return None
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    try:
        spec.loader.exec_module(module)
        ok("按 Runner 的方式导入 plugin.py（含子包 mcai_bridge）")
    except Exception:
        bad("按 Runner 的方式导入 plugin.py", traceback.format_exc())
        return None

    if not hasattr(module, "create_plugin"):
        bad("导出 create_plugin()")
        return None
    ok("导出 create_plugin()")

    plugin = module.create_plugin()
    from maibot_sdk import collect_components, MaiBotPlugin

    check(isinstance(plugin, MaiBotPlugin), "create_plugin() 返回 MaiBotPlugin 实例")

    # 生命周期方法必须被覆写（SDK 要求三个都实现）
    for method in ("on_load", "on_unload", "on_config_update"):
        impl = getattr(type(plugin), method, None)
        base = getattr(MaiBotPlugin, method, None)
        check(impl is not None and impl is not base, f"实现了 {method}()")

    check(getattr(type(plugin), "config_model", None) is not None, "声明了 config_model")

    # 订阅声明合法性
    try:
        plugin.get_config_reload_subscriptions()
        ok("config_reload_subscriptions 合法")
    except Exception as exc:
        bad("config_reload_subscriptions 合法", str(exc))

    # 配置模型可以实例化并读到默认值
    ctx = StubContext()
    plugin._set_context(ctx)
    try:
        cfg = plugin.config
        check(cfg is not None, "self.config 可用")
        check(isinstance(getattr(cfg, "server", None).port, int), "嵌套配置可读",
              f"port={cfg.server.port}, host={cfg.server.host}, path={cfg.server.path}")
    except Exception:
        bad("self.config 可读", traceback.format_exc())

    # ---- 配置版本约束（静默致命，必须专门验）----------------------------
    # Runner 在激活插件前会执行 runner_main._prepare_plugin_config_for_version_update()，
    # 它调用 SDK 的 extract_plugin_config_version(默认配置)。只要默认配置里没有
    # plugin.config_version 就直接抛 PluginConfigVersionError，插件以「插件初始化失败」
    # 告终 —— 而这在 MaiBot 插件页上只显示一句话，完全看不出原因。
    from maibot_sdk import extract_plugin_config_version, PluginConfigVersionError

    check(getattr(type(plugin), "config_model", None) is not None, "声明了 config_model")
    try:
        default_config = plugin.config.model_dump() if hasattr(plugin.config, "model_dump") \
            else dict(vars(plugin.config))
    except Exception:
        default_config = {}
    check(isinstance(default_config.get("plugin"), dict),
          "默认配置含 [plugin] 配置节（Runner 的版本检查硬性要求）",
          f"顶层键：{sorted(default_config)}")
    try:
        version = extract_plugin_config_version(default_config)
        ok("默认配置通过 plugin.config_version 校验", f"config_version={version!r}")
    except PluginConfigVersionError as exc:
        bad("默认配置通过 plugin.config_version 校验",
            f"{exc}  ← 这会让插件在 MaiBot 里显示「插件初始化失败」")

    # 顺带按 Runner 的真实分支走一遍：全新安装（没有 config.toml）时必须能通过
    if isinstance(default_config.get("plugin"), dict):
        try:
            extract_plugin_config_version(default_config)
            ok("模拟全新安装：默认配置可被 Runner 接受")
        except Exception as exc:  # noqa: BLE001
            bad("模拟全新安装：默认配置可被 Runner 接受", str(exc))
    else:
        bad("模拟全新安装：默认配置可被 Runner 接受", "缺少 [plugin] 节")

    # enabled 开关要被 Runner 的 _is_plugin_enabled 认出来（不存在也应该默认启用，
    # 但我们显式声明了，顺便确认默认值是 True，避免装完是「未激活」状态）
    enabled = (default_config.get("plugin") or {}).get("enabled")
    check(enabled is True, "[plugin].enabled 默认 true（否则插件会被判为未激活）",
          f"enabled={enabled!r}")

    # 组件发现
    components = collect_components(plugin)
    by_type: dict[str, list[dict[str, Any]]] = {}
    for comp in components:
        by_type.setdefault(comp["type"], []).append(comp)

    tools = by_type.get("tool", [])
    commands = by_type.get("command", [])
    apis = by_type.get("api", [])
    gateways = by_type.get("message_gateway", [])
    cards = by_type.get("home_card", [])

    check(len(tools) >= 20, "注册了足够的 @Tool", f"{len(tools)} 个：{sorted(t['name'] for t in tools)}")
    check(len(commands) >= 4, "注册了 @Command", f"{len(commands)} 个")
    check(len(gateways) == 1, "注册了恰好一个 @MessageGateway", f"{[g['name'] for g in gateways]}")
    check(len(apis) >= 3, "注册了 @API", f"{len(apis)} 个")
    check(len(cards) >= 1, "注册了 @HomeCard", f"{len(cards)} 个")

    if gateways:
        gw = gateways[0]
        check(gw.get("route_type") in {"send", "receive", "duplex"}, "网关 route_type 合法",
              str(gw.get("route_type")))
        check(gw.get("platform") == "minecraft", "网关 platform 是小写 minecraft",
              str(gw.get("platform")))
        check(bool(gw.get("name")), "网关有名字", str(gw.get("name")))
        # 出站路由要靠 platform + account_id + scope 精确匹配到本插件的驱动，
        # 装饰器里声明得越完整，越不容易退化到 platform 级通配匹配。
        check(bool(gw.get("account_id")), "网关声明了 account_id（出站路由需要）",
              str(gw.get("account_id")))
        check(bool(gw.get("scope")), "网关声明了 scope（出站路由需要）", str(gw.get("scope")))

    # 工具名唯一 + 参数 schema 能生成
    names = [t["name"] for t in tools]
    check(len(names) == len(set(names)), "工具名唯一")

    # 每个工具都要有 i18n 文案：漏了不会报错，只会在 UI 里显示成光秃秃的键名，
    # 所以必须由自检来盯（mc_craft / mc_recipes 就这么漏过一次）
    i18n_dir = PLUGIN_DIR / "i18n"
    for locale_file in sorted(i18n_dir.glob("*.json")):
        try:
            locale_data = json.loads(locale_file.read_text("utf-8"))
        except Exception as exc:  # noqa: BLE001
            bad(f"i18n 可解析 {locale_file.name}", str(exc))
            continue
        missing = [n for n in names if f"tool.{n}" not in locale_data]
        check(not missing, f"每个工具都有 i18n 文案 {locale_file.stem}", f"缺少：{missing}")

    check_presets(module)

    for tool in tools:
        params = tool.get("parameters")
        if isinstance(params, list):
            for item in params:
                if not hasattr(item, "to_schema"):
                    bad(f"工具 {tool['name']} 的参数类型", repr(item))
            schema_names = [item.name for item in params]
            if len(schema_names) != len(set(schema_names)):
                bad(f"工具 {tool['name']} 参数名唯一", str(schema_names))
        if not (tool.get("brief_description") or tool.get("description")):
            bad(f"工具 {tool['name']} 有描述（LLM 靠它判断何时调用）")

    # 命令正则能编译
    for command in commands:
        pattern = command.get("command_pattern") or ""
        try:
            re.compile(pattern)
            ok(f"命令 {command['name']} 正则合法", pattern)
        except re.error as exc:
            bad(f"命令 {command['name']} 正则合法", str(exc))

    return plugin


# ============================================================================
# 4. 端到端协议测试
# ============================================================================


WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
OP_TEXT, OP_CLOSE, OP_PING, OP_PONG = 0x1, 0x8, 0x9, 0xA


def _encode_frame(opcode: int, payload: bytes) -> bytes:
    """客户端 → 服务端的帧必须带掩码。"""
    mask = os.urandom(4)
    header = bytes([0x80 | opcode])
    n = len(payload)
    if n < 126:
        header += bytes([0x80 | n])
    elif n <= 0xFFFF:
        header += bytes([0x80 | 126]) + struct.pack("!H", n)
    else:
        header += bytes([0x80 | 127]) + struct.pack("!Q", n)
    return header + mask + bytes(b ^ mask[i & 3] for i, b in enumerate(payload))


class TestWsClient:
    """扮演 Minecraft 模组的极简 WebSocket 客户端。

    有一个后台读取任务：收到任何 action 都自动回 action_result（可配置哪些动作故意失败），
    并把收到的所有报文记录下来，测试用例只需要断言「有没有收到某个动作」。
    """

    def __init__(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        self.reader = reader
        self.writer = writer
        self.messages: list[dict[str, Any]] = []
        self.actions: asyncio.Queue = asyncio.Queue()
        self.auto_respond = True
        self.fail_actions: set[str] = set()
        self.custom_results: dict[str, dict[str, Any]] = {}
        self._reader_task: Optional[asyncio.Task] = None
        self._closed = False

    @classmethod
    async def connect(cls, host: str, port: int, path: str) -> "TestWsClient":
        reader, writer = await asyncio.open_connection(host, port)
        key = base64.b64encode(os.urandom(16)).decode()
        request = (
            f"GET {path} HTTP/1.1\r\n"
            f"Host: {host}:{port}\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {key}\r\n"
            "Sec-WebSocket-Version: 13\r\n"
            "\r\n"
        )
        writer.write(request.encode())
        await writer.drain()
        raw = await asyncio.wait_for(reader.readuntil(b"\r\n\r\n"), timeout=5)
        head = raw.decode("latin-1")
        if "101" not in head.split("\r\n")[0]:
            raise AssertionError(f"握手失败: {head.splitlines()[0]!r}")
        expected = base64.b64encode(hashlib.sha1((key + WS_GUID).encode()).digest()).decode()
        if expected.lower() not in head.lower():
            raise AssertionError("Sec-WebSocket-Accept 校验失败")
        client = cls(reader, writer)
        client._reader_task = asyncio.create_task(client._read_loop())
        return client

    async def _read_loop(self) -> None:
        try:
            while not self._closed:
                opcode, payload = await self._read_frame()
                if opcode == OP_PING:
                    self.writer.write(_encode_frame(OP_PONG, payload))
                    await self.writer.drain()
                    continue
                if opcode == OP_PONG:
                    continue
                if opcode == OP_CLOSE:
                    self._closed = True
                    return
                if opcode != OP_TEXT:
                    continue
                message = json.loads(payload.decode("utf-8"))
                self.messages.append(message)
                if message.get("type") == "action":
                    await self.actions.put(message)
                    if self.auto_respond:
                        action_type = str((message.get("data") or {}).get("action") or "")
                        ok = action_type not in self.fail_actions
                        result = self.custom_results.get(action_type)
                        await self.respond_to_action(message, ok=ok, result=result)
        except (asyncio.IncompleteReadError, ConnectionError, asyncio.CancelledError, OSError):
            self._closed = True
        except Exception:  # noqa: BLE001
            self._closed = True

    async def send_json(self, payload: dict[str, Any]) -> None:
        if self._closed:
            raise AssertionError("连接已关闭，无法发送")
        self.writer.write(_encode_frame(OP_TEXT, json.dumps(payload, ensure_ascii=False).encode("utf-8")))
        await self.writer.drain()

    async def _read_frame(self) -> tuple[int, bytes]:
        header = await self.reader.readexactly(2)
        opcode = header[0] & 0x0F
        masked = bool(header[1] & 0x80)
        length = header[1] & 0x7F
        if length == 126:
            length = struct.unpack("!H", await self.reader.readexactly(2))[0]
        elif length == 127:
            length = struct.unpack("!Q", await self.reader.readexactly(8))[0]
        mask = await self.reader.readexactly(4) if masked else b""
        payload = await self.reader.readexactly(length) if length else b""
        if masked:
            payload = bytes(b ^ mask[i & 3] for i, b in enumerate(payload))
        return opcode, payload

    async def expect_action(self, action_type: str, timeout: float = 10.0) -> dict[str, Any]:
        """等待某个类型的 action 报文，其它类型的动作先暂存到 self._deferred。"""
        deferred = getattr(self, "_deferred", None)
        if deferred is None:
            deferred = []
            self._deferred = deferred
        for index, message in enumerate(deferred):
            if str((message.get("data") or {}).get("action")) == action_type:
                return deferred.pop(index)
        deadline = time.time() + timeout
        while time.time() < deadline:
            try:
                message = await asyncio.wait_for(self.actions.get(),
                                                 timeout=max(0.05, deadline - time.time()))
            except asyncio.TimeoutError:
                break
            if str((message.get("data") or {}).get("action")) == action_type:
                return message
            deferred.append(message)
        raise asyncio.TimeoutError(f"没有收到动作 {action_type}（已收到 {list(self.actions._queue)}）")

    async def wait_for_message(self, msg_type: str, timeout: float = 10.0) -> dict[str, Any]:
        deadline = time.time() + timeout
        while time.time() < deadline:
            for message in list(self.messages):
                if message.get("type") == msg_type:
                    return message
            await asyncio.sleep(0.05)
        raise asyncio.TimeoutError(f"没有收到 {msg_type} 报文")

    async def respond_to_action(self, message: dict[str, Any], *, ok: bool = True,
                                result: Optional[dict[str, Any]] = None) -> None:
        data = message.get("data") or {}
        payload = result if result is not None else {"echo": data.get("action")}
        await self.send_json({
            "v": 1,
            "type": "action_result",
            "id": data.get("actionId"),
            "data": {
                "actionId": data.get("actionId"),
                "action": data.get("action"),
                "ok": ok,
                "result": payload,
                "error": None if ok else "测试用失败",
                "elapsedMs": 12,
            },
        })

    async def close(self) -> None:
        self._closed = True
        if self._reader_task is not None:
            self._reader_task.cancel()
        try:
            self.writer.write(_encode_frame(OP_CLOSE, struct.pack("!H", 1000)))
            await self.writer.drain()
        except Exception:  # noqa: BLE001
            pass
        try:
            self.writer.close()
        except Exception:  # noqa: BLE001
            pass


def check_presets(module: Any) -> None:
    """预设表自检：每个预设都要能展开成一个合法的脚本。

    这一条很值：预设是手写的常量表，写错一个动作名不会有任何提示，
    只会在玩家真的用它的那一刻炸在游戏里。
    """
    presets = getattr(module, "SCRIPT_PRESETS", None)
    if not isinstance(presets, dict) or not presets:
        bad("定义了脚本预设表 SCRIPT_PRESETS")
        return
    ok("定义了脚本预设表 SCRIPT_PRESETS", f"{len(presets)} 个：{list(presets)}")

    declared_actions = set(module.P.ALL_ACTIONS)
    for name, spec in presets.items():
        if not isinstance(spec, dict) or not callable(spec.get("build")):
            bad(f"预设 {name} 结构正确", repr(spec)[:80])
            continue
        if not str(spec.get("summary", "")).strip():
            bad(f"预设 {name} 有一句话说明")
        script, error = module._build_preset(name, {})
        if error or script is None:
            bad(f"预设 {name} 能用默认参数展开", error)
            continue
        steps = script.get("steps")
        if not isinstance(steps, list) or not steps:
            bad(f"预设 {name} 展开出非空 steps")
            continue
        used: set[str] = set()
        module._collect_script_actions(script, used)
        unknown = sorted(a for a in used if a not in declared_actions)
        if unknown:
            bad(f"预设 {name} 只用已定义的动作", f"不认识：{unknown}")
            continue
        shape_error = module._validate_script_shape(script)
        if shape_error:
            bad(f"预设 {name} 通过了结构校验", shape_error)
            continue
        ok(f"预设 {name} 展开合法", f"{len(steps)} 步，用到 {sorted(used)}")

    # 报错质量：写错预设名要给候选，写错参数名要列出支持的参数
    _, error = module._build_preset("wooden_pickaxe2", {})
    check("wooden_pickaxe" in str(error), "预设名写错时给出候选", str(error)[:100])
    _, error = module._build_preset("mine_until", {"counnt": 5})
    check("counnt" in str(error) and "count" in str(error),
          "预设参数写错时列出支持的参数", str(error)[:120])
    _, error = module._build_preset("mine_until", {"count": 9999})
    check("1~200" in str(error), "预设参数超范围时报错", str(error)[:100])
    script, error = module._build_preset("mine_until", {"count": 8})
    check(not error and "while" in str(script),
          "mine_until 展开成条件循环（不是写死 8 次）", str(script)[:120])


async def check_end_to_end(plugin: Any) -> None:
    print("\n== 4. 端到端协议测试（真实 WebSocket）==")

    ctx = StubContext()
    plugin._set_context(ctx)
    plugin.config.server.host = "127.0.0.1"
    plugin.config.server.port = 0  # 让系统分配空闲端口
    plugin.config.chat.force_reply = True
    plugin.config.events.report_events = True

    await plugin.on_load()
    if plugin.bridge is None:
        bad("WebSocket 服务端启动")
        return
    ok("WebSocket 服务端启动", plugin.bridge.url)

    # 网关必须上报 ready=True，否则 Host 既不会注入入站消息也不会路由出站消息
    ready_calls = [c for c in ctx.calls.get("gateway.update_state", []) if c.get("ready")]
    check(bool(ready_calls), "on_load 上报了网关 ready=True")
    if ready_calls:
        check(ready_calls[0].get("platform") == "minecraft", "ready 上报的 platform 是小写 minecraft",
              str(ready_calls[0].get("platform")))
        check(bool(ready_calls[0].get("account_id")), "ready 上报带了 account_id",
              str(ready_calls[0].get("account_id")))

        # 出站路由三级一致：装饰器 / update_state / route_metadata 里的 account_id 与 scope
        # 必须相同，否则「驱动绑定」和「消息路由键」对不上，只能退化到 platform 级匹配。
        from maibot_sdk import collect_components as _collect
        gateways = [c for c in _collect(plugin) if c["type"] == "message_gateway"]
        if gateways:
            decorated_account = str(gateways[0].get("account_id") or "")
            decorated_scope = str(gateways[0].get("scope") or "")
            ready_account = str(ready_calls[0].get("account_id") or "")
            ready_scope = str(ready_calls[0].get("scope") or "")
            check(decorated_account == ready_account,
                  "装饰器的 account_id 与 update_state 一致（出站路由前提）",
                  f"decorator={decorated_account!r} update_state={ready_account!r}")
            check(decorated_scope == ready_scope,
                  "装饰器的 scope 与 update_state 一致（出站路由前提）",
                  f"decorator={decorated_scope!r} update_state={ready_scope!r}")

    client: Optional[TestWsClient] = None
    try:
        client = await TestWsClient.connect("127.0.0.1", plugin.bridge.bound_port, plugin.config.server.path)
        ok("模组侧 WebSocket 握手成功")

        # 握手后插件会自动拉一次状态，用来给麦麦建立初始认知
        client.custom_results["get_state"] = {
            "inWorld": True,
            "player": {"name": "Steve", "dimension": "minecraft:overworld",
                       "pos": {"x": 1.0, "y": 64.0, "z": 2.0},
                       "blockPos": {"x": 1, "y": 64, "z": 2}, "health": 20.0, "maxHealth": 20.0,
                       "food": 20, "gameMode": "survival", "heldItem": None},
            "world": {"timeOfDay": 6000, "isDay": True, "biome": "minecraft:plains", "raining": False},
            "inventory": {"usedSlots": 0, "freeSlots": 36, "slots": []},
        }

        # ---- hello / hello_ack
        await client.send_json({
            "v": 1, "type": "hello", "id": "hello-1",
            "data": {
                "token": "", "protocol": 1,
                "mod": {"version": "1.0.0", "mc": "1.20.1", "forge": "47.4.10"},
                "player": {"name": "Steve", "uuid": "00000000-0000-0000-0000-000000000001",
                           "dimension": "minecraft:overworld"},
                "singleplayer": True, "serverName": "测试世界",
                "serverAddress": "", "worldKey": "sp:测试世界",
                "capabilities": ["chat", "move", "mine"],
            },
        })
        ack = await client.wait_for_message("hello_ack", timeout=5)
        check(bool((ack.get("data") or {}).get("ok")), "收到 hello_ack 且 ok=true")

        state_request = await client.expect_action("get_state", timeout=8)
        check(bool((state_request.get("data") or {}).get("actionId")), "握手后插件主动拉取状态（带 actionId）")

        await asyncio.sleep(0.3)
        check(bool(ctx.calls.get("chat.open_session")), "握手后创建/获取了聊天流",
              json.dumps(ctx.calls.get("chat.open_session", [{}])[0], ensure_ascii=False)[:140])
        check(bool(ctx.calls.get("maisaka.context.append")), "握手后向麦麦追加了上下文")
        state_summaries = [c for c in ctx.calls.get("maisaka.context.append", [])
                           if "当前游戏状态" in str(c.get("visible_text", ""))]
        check(bool(state_summaries), "状态快照被喂给了麦麦（说明工具回传链路通了）")

        # ---- 上报游戏内聊天
        ctx.calls.pop("gateway.route_message", None)
        ctx.calls.pop("maisaka.proactive.trigger", None)
        await client.send_json({
            "v": 1, "type": "event", "id": "ev-1",
            "data": {"kind": "chat", "channel": "chat", "text": "麦麦，去帮我挖点铁",
                     "player": True, "sender": "Alex", "playerName": "Alex",
                     "pos": {"x": 1.0, "y": 64.0, "z": 2.0}, "dimension": "minecraft:overworld"},
        })
        for _ in range(40):
            if ctx.calls.get("gateway.route_message"):
                break
            await asyncio.sleep(0.05)

        routed = ctx.calls.get("gateway.route_message") or []
        if check(bool(routed), "游戏聊天被注入麦麦的对话流"):
            payload = routed[0]
            check(payload.get("gateway_name") == plugin.GATEWAY_NAME, "注入用了正确的网关名",
                  str(payload.get("gateway_name")))
            message = payload.get("message") or {}
            # Host 侧硬性要求：raw_message 必须是列表，否则会直接抛异常
            check(isinstance(message.get("raw_message"), list), "raw_message 是列表（Host 强制要求）",
                  repr(message.get("raw_message"))[:80])
            check(all(isinstance(s, dict) and "type" in s and "data" in s
                      for s in (message.get("raw_message") or [])),
                  "raw_message 的每个元素都是 {type, data}")
            check(message.get("platform") == "minecraft", "platform 是小写 minecraft",
                  str(message.get("platform")))
            check(bool(message.get("message_id")), "带 message_id")
            info = message.get("message_info") or {}
            user = info.get("user_info") or {}
            check(bool(user.get("user_id")) and bool(user.get("user_nickname")),
                  "message_info.user_info 两个字段都非空", json.dumps(user, ensure_ascii=False))
            group = info.get("group_info") or {}
            check(bool(group.get("group_id")) and bool(group.get("group_name")),
                  "group_info 两个字段都非空", json.dumps(group, ensure_ascii=False))
            check("麦麦，去帮我挖点铁" in str(message.get("processed_plain_text")),
                  "processed_plain_text 保留了原文")
            check(payload.get("dedupe_key") == message.get("message_id"), "dedupe_key 与 message_id 一致")

        triggers = ctx.calls.get("maisaka.proactive.trigger") or []
        check(bool(triggers), "玩家说话后唤醒了麦麦主动思考（force_reply）")
        if triggers:
            check(bool(triggers[0].get("stream_id")), "主动唤醒带了 stream_id",
                  str(triggers[0].get("stream_id")))

        # ---- 麦麦要说话 → 出站到游戏
        gateway = getattr(plugin, "minecraft_gateway")
        outbound = await gateway({
            "message_id": "bot-1",
            "timestamp": str(time.time()),
            "platform": "minecraft",
            "message_info": {"user_info": {"user_id": "mc:bot", "user_nickname": "麦麦"},
                             "group_info": {"group_id": "mc:测试世界", "group_name": "测试世界"},
                             "additional_config": {}},
            "raw_message": [{"type": "text", "data": "好，我这就去挖矿！"}],
            "processed_plain_text": "好，我这就去挖矿！",
        }, route={"platform": "minecraft", "account_id": "mc-server-1", "scope": "main"},
            metadata={})
        check(bool(outbound.get("success")), "出站网关把回复发进了游戏",
              json.dumps(outbound, ensure_ascii=False)[:160])

        chat_action = await client.expect_action("chat", timeout=5)
        text = ((chat_action.get("data") or {}).get("params") or {}).get("message")
        check(text == "好，我这就去挖矿！", "chat 动作内容正确", repr(text))

        # ---- 麦麦调用工具 → 模组执行 → 结果回传
        tool_result = await plugin.mc_move_to(x=100, y=64, z=-200)
        check(bool(tool_result.get("success")), "Tool mc_move_to 调用成功",
              json.dumps(tool_result, ensure_ascii=False)[:160])
        move_action = await client.expect_action("move_to", timeout=5)
        params = (move_action.get("data") or {}).get("params") or {}
        check(params.get("x") == 100 and params.get("y") == 64 and params.get("z") == -200,
              "move_to 参数透传正确", json.dumps(params, ensure_ascii=False))
        check(int((move_action.get("data") or {}).get("timeoutMs") or 0) > 0, "action 报文带了超时时间")

        # ---- 任务组（mc_script）：一次下发一整段流程
        client.custom_results["script"] = {
            "ok": True,
            "stepsDone": 4,
            "nodeCount": 4,
            "content": "脚本执行完成（执行了 4 个动作）\n执行记录：\n  ✓ mine_blocks(block=stone, count=3)",
            "log": ["✓ mine_blocks(block=stone, count=3)"],
        }
        script = {
            "name": "挖石头",
            "steps": [
                {"action": "mine_blocks", "params": {"block": "stone", "count": 3}},
                {"action": "craft", "params": {"item": "stone_pickaxe", "count": 1}},
            ],
        }
        script_result = await plugin.mc_script(script=script)
        check(bool(script_result.get("success")), "Tool mc_script 调用成功",
              json.dumps(script_result, ensure_ascii=False)[:160])
        script_action = await client.expect_action("script", timeout=5)
        sent_params = (script_action.get("data") or {}).get("params") or {}
        check(sent_params == script, "script 内容原样透传给模组",
              json.dumps(sent_params, ensure_ascii=False)[:120])
        check("脚本执行完成" in str(script_result.get("content")),
              "mc_script 把模组的执行回执转给 LLM", str(script_result.get("content"))[:100])

        # 脚本自带的时长预算会放宽插件侧的超时，否则长脚本会被提前掐断
        long_script = {"maxDurationMs": 900000,
                       "steps": [{"action": "wait", "params": {"ms": 1000}}]}
        await plugin.mc_script(script=long_script)
        long_action = await client.expect_action("script", timeout=5)
        check(int((long_action.get("data") or {}).get("timeoutMs") or 0) >= 900000,
              "mc_script 按脚本的 maxDurationMs 放宽超时",
              str((long_action.get("data") or {}).get("timeoutMs")))

        # 有些模型会把对象序列化成字符串，两种都要收
        str_result = await plugin.mc_script(script=json.dumps(script, ensure_ascii=False))
        check(bool(str_result.get("success")), "mc_script 接受 JSON 字符串形式的 script")
        await client.expect_action("script", timeout=5)

        # ---- 本地校验：结构错误和动作名拼错都不该发到游戏里（省一次往返）
        sent_before = len([m for m in client.messages if m.get("type") == "action"])
        shape = await plugin.mc_script(script={"steps": [{"foo": 1}]})
        check(not shape.get("success") and "steps[0]" in str(shape.get("content")),
              "mc_script 本地拦下结构错误的脚本", str(shape.get("content"))[:100])
        typo = await plugin.mc_script(script={"steps": [{"action": "mine_blockz"}]})
        check(not typo.get("success") and "mine_blocks" in str(typo.get("content")),
              "mc_script 纠正拼错的动作名", str(typo.get("content"))[:120])
        nested_typo = await plugin.mc_script(script={"steps": [
            {"repeat": 2, "steps": [{"action": "craftt"}]}]})
        check(not nested_typo.get("success") and "craft" in str(nested_typo.get("content")),
              "mc_script 也能发现嵌套在 repeat 里的错动作名",
              str(nested_typo.get("content"))[:120])
        empty = await plugin.mc_script(script={"steps": []})
        check(not empty.get("success"), "mc_script 拒绝空脚本")
        nested = await plugin.mc_script(script={"steps": [
            {"action": "script", "params": {"steps": [{"action": "chat"}]}}]})
        check(not nested.get("success") and "展开" in str(nested.get("content")),
              "mc_script 拒绝嵌套脚本", str(nested.get("content"))[:100])
        sent_after = len([m for m in client.messages if m.get("type") == "action"])
        check(sent_before == sent_after, "校验不通过的脚本不会被下发到游戏")

        # ---- 预设：一条 preset 参数顶一整段脚本，而且要真的能下发
        client.custom_results["script"] = {"ok": True, "stepsDone": 6, "content": "脚本执行完成（执行了 6 个动作）"}
        preset_result = await plugin.mc_script(preset="wooden_pickaxe")
        check(bool(preset_result.get("success")), "mc_script 能用预设代替自己写脚本",
              json.dumps(preset_result, ensure_ascii=False)[:140])
        preset_action = await client.expect_action("script", timeout=5)
        preset_steps = ((preset_action.get("data") or {}).get("params") or {}).get("steps") or []
        check(len(preset_steps) >= 5, "预设展开成了完整的一段流程",
              f"{len(preset_steps)} 步：{[s.get('action') for s in preset_steps]}")
        check(any(str((s.get("params") or {}).get("x", "")).startswith("~")
                  for s in preset_steps if s.get("action") == "place"),
              "预设里的放工作台用了 ~ 相对坐标（脚本没法知道玩家坐标）",
              json.dumps(preset_steps[-3:], ensure_ascii=False)[:160])

        with_params = await plugin.mc_script(preset="mine_until",
                                            preset_params={"item": "oak_log", "count": 5})
        check(bool(with_params.get("success")), "带参数的预设可用")
        with_params_action = await client.expect_action("script", timeout=5)
        loop = ((with_params_action.get("data") or {}).get("params") or {}).get("steps") or [{}]
        check("while" in loop[0], "带参数的预设按参数生成了循环条件",
              json.dumps(loop[0], ensure_ascii=False)[:160])
        check("oak_log" in json.dumps(loop[0], ensure_ascii=False),
              "预设参数真的进了脚本内容", json.dumps(loop[0], ensure_ascii=False)[:160])

        both = await plugin.mc_script(script=script, preset="crafting_table")
        check(not both.get("success") and "只能给一个" in str(both.get("content")),
              "script 和 preset 同时给会被拒绝", str(both.get("content"))[:100])

        bad_preset = await plugin.mc_script(preset="nope_pickaxe")
        check(not bad_preset.get("success") and "wooden_pickaxe" in str(bad_preset.get("content")),
              "不认识的预设名会被纠正", str(bad_preset.get("content"))[:120])

        # ---- 脚本里用了被禁用的动作：本地就拒绝，并点名是哪个
        client.custom_results.pop("script", None)
        plugin.config.safety.deny_actions = ["place"]
        blocked_script = await plugin.mc_script(script={"steps": [
            {"action": "chat", "params": {"message": "你好"}},
            {"action": "place", "params": {"x": 0, "y": 64, "z": 0}},
        ]})
        plugin.config.safety.deny_actions = []
        check(not blocked_script.get("success") and "place" in str(blocked_script.get("content")),
              "mc_script 拦下含禁用动作的脚本", str(blocked_script.get("content"))[:120])

        # ---- 只包含白名单内动作的脚本应该能发出去（白名单管的是脚本实际做的事）
        client.custom_results["script"] = {"ok": True, "stepsDone": 1, "content": "脚本执行完成"}
        plugin.config.safety.allow_actions = ["chat"]
        allowed_script = await plugin.mc_script(script={
            "steps": [{"action": "chat", "params": {"message": "你好"}}]})
        check(bool(allowed_script.get("success")), "白名单只写 chat 时，只含 chat 的脚本仍可下发",
              json.dumps(allowed_script, ensure_ascii=False)[:140])
        await client.expect_action("script", timeout=5)
        whitelist_blocked = await plugin.mc_script(script={
            "steps": [{"action": "mine", "params": {"x": 0, "y": 64, "z": 0}}]})
        plugin.config.safety.allow_actions = []
        client.custom_results.pop("script", None)
        check(not whitelist_blocked.get("success"),
              "白名单外的动作放进脚本里一样被拒", str(whitelist_blocked.get("content"))[:120])

        # ---- 抵御：mod 侧的战术参数要原样传过去
        defend = await plugin.mc_defend(radius=20, max_kills=5, retreat_health=12.5, flee=True)
        check(bool(defend.get("success")), "Tool mc_defend 调用成功",
              json.dumps(defend, ensure_ascii=False)[:140])
        defend_action = await client.expect_action("defend", timeout=5)
        dp = (defend_action.get("data") or {}).get("params") or {}
        check(dp.get("radius") == 20 and dp.get("maxKills") == 5
              and dp.get("retreatHealth") == 12.5 and dp.get("flee") is True,
              "mc_defend 参数映射正确（含布尔与小数）", json.dumps(dp, ensure_ascii=False))
        check("x" not in dp and "z" not in dp,
              "没给驻守点时不下发 x/z（否则模组会以为要守在那个坐标）",
              json.dumps(dp, ensure_ascii=False))

        defend_guard = await plugin.mc_defend(x=100, z=-200)
        check(bool(defend_guard.get("success")), "mc_defend 带驻守点也能调用")
        guard_action = await client.expect_action("defend", timeout=5)
        gp = (guard_action.get("data") or {}).get("params") or {}
        check(gp.get("x") == 100 and gp.get("z") == -200 and "y" not in gp,
              "驻守点：给了 x/z，没给 y 就不带 y（由模组用玩家当前高度）",
              json.dumps(gp, ensure_ascii=False))

        # 半径要夹到模组认的范围里（3~64）
        await plugin.mc_defend(radius=999)
        clamped = await client.expect_action("defend", timeout=5)
        check(((clamped.get("data") or {}).get("params") or {}).get("radius") == 64,
              "半径超范围时夹到 64", str(((clamped.get("data") or {}).get("params") or {}).get("radius")))

        # ---- 桶：装液体 / 倒液体（搭地狱门的关键动作）
        bogus_mode = await plugin.mc_bucket(mode="pour")
        check(not bogus_mode.get("success"), "mc_bucket 拒绝不认识的 mode",
              str(bogus_mode.get("content"))[:100])
        bogus_fluid = await plugin.mc_bucket(mode="fill", fluid="milk")
        check(not bogus_fluid.get("success"), "mc_bucket 拒绝不认识的 fluid",
              str(bogus_fluid.get("content"))[:100])
        no_coords = await plugin.mc_bucket(mode="empty", fluid="lava")
        check(not no_coords.get("success") and "x/y/z" in str(no_coords.get("content")),
              "mc_bucket mode=empty 没给坐标时本地拦下", str(no_coords.get("content"))[:100])

        filled = await plugin.mc_bucket(mode="fill", fluid="Lava", radius=2)
        check(bool(filled.get("success")), "Tool mc_bucket 装液体调用成功",
              json.dumps(filled, ensure_ascii=False)[:140])
        fill_action = await client.expect_action("bucket", timeout=5)
        fp = (fill_action.get("data") or {}).get("params") or {}
        check(fp.get("mode") == "fill" and fp.get("fluid") == "lava" and fp.get("radius") == 4,
              "mc_bucket：fluid 转小写、radius 下限夹到 4、fill 不带 x/y/z",
              json.dumps(fp, ensure_ascii=False))
        check("x" not in fp and "y" not in fp and "z" not in fp,
              "mc_bucket mode=fill 不该带坐标（模组自己找源头）", json.dumps(fp, ensure_ascii=False))

        poured = await plugin.mc_bucket(mode="empty", fluid="water",
                                        x="~", y="~", z="~2")
        check(bool(poured.get("success")), "Tool mc_bucket 倒液体调用成功",
              json.dumps(poured, ensure_ascii=False)[:140])
        pour_action = await client.expect_action("bucket", timeout=5)
        pp = (pour_action.get("data") or {}).get("params") or {}
        check(pp.get("mode") == "empty" and pp.get("x") == "~" and pp.get("z") == "~2",
              "mc_bucket：\"~\" 相对坐标原样透传给模组解析",
              json.dumps(pp, ensure_ascii=False))

        # ---- 熔炼：数量决定超时，别一步就卡满默认超时
        no_item = await plugin.mc_smelt(item="  ")
        check(not no_item.get("success"), "mc_smelt 缺 item 时本地拦下")
        smelted = await plugin.mc_smelt(item="iron_ingot", count=0, radius=6)
        check(bool(smelted.get("success")), "Tool mc_smelt 调用成功",
              json.dumps(smelted, ensure_ascii=False)[:140])
        smelt_action = await client.expect_action("smelt", timeout=5)
        sp = (smelt_action.get("data") or {}).get("params") or {}
        check(sp.get("item") == "iron_ingot" and sp.get("count") == 1 and sp.get("radius") == 6,
              "mc_smelt：count 下限夹到 1，item/radius 原样下发",
              json.dumps(sp, ensure_ascii=False))

        # ---- 阶梯矿道：没有单独的工具，但脚本里必须能用（模组侧实现了 dig_shaft）
        shaft_script = {"name": "挖条矿道", "steps": [{"action": "dig_shaft",
                                                     "params": {"depth": 20}}]}
        shafted = await plugin.mc_script(script=shaft_script)
        check(bool(shafted.get("success")), "mc_script 能下发 dig_shaft（模组侧实现在，只是没单开工具）",
              json.dumps(shafted, ensure_ascii=False)[:140])
        shaft_action = await client.expect_action("script", timeout=5)
        body = json.dumps((shaft_action.get("data") or {}).get("params") or {}, ensure_ascii=False)
        check("dig_shaft" in body and "20" in body, "dig_shaft 的 depth 原样进了脚本", body[:160])

        # ---- Baritone：靠聊天指令驱动（零依赖），最容易错的是 mine 的数量位置
        #
        # 数量 >0 的 mine 会让工具去盯背包（最多等 300 秒），所以这里先用**纯函数**验证
        # 指令拼装，再只用 count=0 的形式走一遍工具调用。
        baritone_mod = sys.modules.get("mcai_bridge_plugin")
        check(baritone_mod is not None and hasattr(baritone_mod, "_baritone_command"),
              "能直接测 Baritone 指令拼装函数")
        if baritone_mod is not None and hasattr(baritone_mod, "_baritone_command"):
            cmd = baritone_mod._baritone_command
            check(cmd("mine", target="dirt", count=64) == "#mine 64 dirt",
                  "Baritone mine 的数量在**方块名前面**（反过来它会报 "
                  "\"Error at argument #2: Expected w\"）",
                  str(cmd("mine", target="dirt", count=64)))
            check(cmd("mine", target="dirt") == "#mine dirt", "不给数量时就是 #mine <方块>")
            check(cmd("mine", target="#minecraft:logs", count=4) == "#mine 4 #minecraft:logs",
                  "方块标签也能挖（#minecraft:logs）")
            check(str(cmd("goto", x=100, y=64, z=-200)) == "#goto 100 64 -200",
                  "goto 三坐标")
            check(str(cmd("goto", x=100, z=-200)) == "#goto 100 -200",
                  "goto 只给 x/z 时不硬塞 y")
            check(str(cmd("goto", target="iron_ore")) == "#goto iron_ore", "goto 按方块名寻路")
            check(str(cmd("stop")) == "#stop", "stop → #stop")
            check(str(cmd("mine")).startswith("!"), "mine 缺 target 时拼装函数返回错误说明")
            check(str(cmd("explore")) == "#explore" and str(cmd("tunnel", count=0)) == "#tunnel 2",
                  "explore/tunnel 的默认值")

        await plugin.mc_baritone(action="mine", target="dirt")
        mine_action = await client.expect_action("chat", timeout=5)
        mine_msg = ((mine_action.get("data") or {}).get("params") or {}).get("message")
        check(mine_msg == "#mine dirt", "mc_baritone 把指令当**聊天**发出去", str(mine_msg))
        check(not str(mine_msg).startswith("/"),
              "Baritone 只拦聊天消息（# 前缀），发成 /指令 就不生效了", str(mine_msg))

        await plugin.mc_baritone(action="goto", x=100, y=64, z=-200)
        goto_action = await client.expect_action("chat", timeout=5)
        goto_msg = ((goto_action.get("data") or {}).get("params") or {}).get("message")
        check(goto_msg == "#goto 100 64 -200", "mc_baritone goto 带三坐标", str(goto_msg))

        await plugin.mc_baritone(action="stop")
        stop_action = await client.expect_action("chat", timeout=5)
        check(((stop_action.get("data") or {}).get("params") or {}).get("message") == "#stop",
              "mc_baritone stop → #stop")

        bad_baritone = await plugin.mc_baritone(action="fly")
        check(not bad_baritone.get("success"), "mc_baritone 拒绝不认识的 action",
              str(bad_baritone.get("content"))[:100])
        no_target = await plugin.mc_baritone(action="mine")
        check(not no_target.get("success") and "target" in str(no_target.get("content")),
              "mc_baritone mine 缺 target 时本地拦下", str(no_target.get("content"))[:100])

        # ---- 新接进来的那批指令（pause/resume/status/wp_*/…）
        #
        # 依据是 v1.10.1 的真机逐条实测（经验库 baritone 那条）：这些指令确实有回话，
        # 可以放心放进白名单。这里只验证「拼出来的指令对不对」+「回话有没有带回来」。
        if baritone_mod is not None and hasattr(baritone_mod, "_baritone_commands"):
            cmds = baritone_mod._baritone_commands
            check(list(cmds("pause")) == ["#pause"] and list(cmds("resume")) == ["#resume"],
                  "pause/resume → #pause / #resume",
                  str(cmds("pause")) + str(cmds("resume")))
            check(list(cmds("status")) == ["#proc", "#eta"],
                  "status 会问两条：在跑什么（#proc）+ 还要多久（#eta）",
                  str(cmds("status")))
            check(list(cmds("wp_save", target="矿洞")) == ["#wp s 矿洞"]
                  and list(cmds("wp_go", target="矿洞")) == ["#wp goto 矿洞"]
                  and list(cmds("wp_list")) == ["#wp l"]
                  and list(cmds("wp_info", target="矿洞")) == ["#wp i 矿洞"]
                  and list(cmds("wp_delete", target="矿洞")) == ["#wp d 矿洞"],
                  "路径点：存/列/查/去/删 都拼对（#wp s|goto|l|i|d）",
                  str([cmds("wp_save", target="a"), cmds("wp_go", target="a"), cmds("wp_list")]))
            check(list(cmds("help", target="goto")) == ["#help goto"]
                  and list(cmds("help")) == ["#help"],
                  "#help 可以带指令名，也可以不带", str(cmds("help", target="goto")))
            check(list(cmds("wp_go"))[0].startswith("!") if isinstance(cmds("wp_go"), str)
                  else str(cmds("wp_go")).startswith("!"),
                  "wp_go 缺 target 时本地拦下", str(cmds("wp_go")))
            check(list(cmds("surface")) == ["#surface"] and list(cmds("farm")) == ["#farm"]
                  and list(cmds("saveall")) == ["#saveall"],
                  "surface/farm/saveall 也拼对了")

        await asyncio.sleep(1.1)
        await plugin.mc_baritone(action="pause")
        pause_action = await client.expect_action("chat", timeout=5)
        check(((pause_action.get("data") or {}).get("params") or {}).get("message") == "#pause",
              "mc_baritone pause 发的是 #pause")

        # 查询类会把 Baritone 的回话带回来 —— 回话才是真正有用的东西。
        # 它的回话是直接打进聊天栏的（不走服务端报文），所以模组从**游戏日志**里捞：
        # baritone_reply 返回「自上次问以来新出现的行」。这里两条来路各测一次。
        client.custom_results["baritone_reply"] = {
            "lines": ["Paused"], "count": 1, "tail": ["v1.10.1", "Paused"],
            "content": "Paused",
        }
        client.custom_results.pop("get_state", None)
        paused_reply = await plugin.mc_baritone(action="pause")
        await client.expect_action("chat", timeout=5)
        check("Paused" in str(paused_reply.get("content")),
              "查询类动作把 Baritone 的回话一起带回给模型（从游戏日志里捞的那条路）",
              str(paused_reply.get("content"))[:160])
        check(paused_reply.get("reply") == "Paused",
              "回话同时放在 reply 字段里（方便上层程序化使用）",
              str(paused_reply.get("reply")))

        # 日志里也没有、事件里也没有 → 如实说没抓到，不能假装成功
        client.custom_results["baritone_reply"] = {"lines": [], "count": 0, "tail": []}
        nothing = await plugin.mc_baritone(action="version")
        await client.expect_action("chat", timeout=5)
        check("没抓到" in str(nothing.get("content")),
              "抓不到回话时如实说没抓到", str(nothing.get("content"))[:160])

        # #wp l 的回话是「可点击列表」，落到日志里只剩分隔线 —— 滤掉，并给出更好的查法
        client.custom_results["baritone_reply"] = {
            "lines": ["--", "--", "--", "<< | >> 1/1"], "count": 4, "tail": [],
        }
        noisy = await plugin.mc_baritone(action="wp_list")
        await client.expect_action("chat", timeout=5)
        ntext2 = str(noisy.get("content"))
        check("分隔线" in ntext2 and "wp_info" in ntext2,
              "回话全是列表分隔线时说清楚，并指出更好的查法", ntext2[:200])
        client.custom_results.pop("baritone_reply", None)

        # 洗回话的那把小刷子：去噪 + 去重（真机上 #wp l 会刷出一屏 "--"）
        if baritone_mod is not None and hasattr(baritone_mod, "_baritone_useful_lines"):
            washed = baritone_mod._baritone_useful_lines(
                ["--", "Paused", "", "  ", "--", "Paused", "<< | >> 1/1",
                 "Click to delete this waypoint", "Error at argument #2: Expected w"])
            check(washed == ["Paused", "Error at argument #2: Expected w"],
                  "Baritone 回话的清洗：滤掉分隔线/翻页/「Click to…」，去重，保留原话",
                  str(washed))

        # ---- Baritone 的状态必须出现在任务状态里
        #
        # 背景：Baritone 干活时不经过模组的动作队列，所以 task_status 会说「空闲」——
        # 麦麦看到「空闲」就会以为没人做事。模组侧有个 BaritoneWatcher 把它的活动
        # 一起报上来，这里断言插件确实把它渲染出来了。
        #
        # 上面已经连着下发了很多动作，先等一下避开限速（限速本身是对的）。
        await asyncio.sleep(1.1)
        client.custom_results["task_status"] = {
            "current": None,
            "queued": [],
            "queueLength": 0,
            "executedTotal": 3,
            "failedTotal": 0,
            "baritone": {
                "command": "#mine 8 oak_log",
                "running": True,
                "elapsedMs": 42000,
                "idleMs": 300,
                "moving": True,
                "note": "Baritone 正在移动",
            },
        }
        status = await plugin.mc_task_status()
        body = str(status.get("content"))
        check("Baritone" in body and "#mine 8 oak_log" in body and "进行中" in body,
              "mc_task_status 把 Baritone 的活一起报出来（不能只说「空闲」）", body[:160])
        check(bool(status.get("baritone")), "mc_task_status 额外给出结构化的 baritone 字段")

        # 它停下来的时候也要说清楚 —— 否则 AI 会一直等
        client.custom_results["task_status"] = {
            "current": None, "queued": [], "queueLength": 0,
            "baritone": {"command": "#mine 8 oak_log", "running": False, "elapsedMs": 60000,
                         "idleMs": 12000, "moving": False, "note": "大概干完了"},
        }
        stopped = await plugin.mc_task_status()
        check("已停" in str(stopped.get("content")),
              "Baritone 停下来时任务状态如实说「已停」", str(stopped.get("content"))[:140])

        client.custom_results["task_status"] = {"current": None, "queued": [], "queueLength": 0}
        idle = await plugin.mc_task_status()
        check("没在指挥它" in str(idle.get("content")),
              "没在用 Baritone 时说明白（而不是含糊的「空闲」）", str(idle.get("content"))[:140])

        # get_state 的渲染里也要有它。
        # 注意：握手时塞进去的那份 get_state 是**后面的检查还要用的 fixture**，
        # 这里必须先存后还，不能像 task_status 那样直接 pop 掉。
        saved_state = client.custom_results.get("get_state")
        client.custom_results["get_state"] = {
            "reason": "request", "inWorld": True,
            "player": {"name": "Steve", "pos": {"x": 0.0, "y": 64.0, "z": 0.0}, "health": 20.0,
                       "maxHealth": 20.0, "food": 20, "dimension": "minecraft:overworld",
                       "gameMode": "survival"},
            "baritone": {"command": "#goto 100 64 -200", "running": True, "elapsedMs": 9000,
                         "moving": True},
        }
        state_with_baritone = await plugin.mc_state()
        check("Baritone" in str(state_with_baritone.get("content")),
              "mc_state 的摘要里也带上 Baritone 状态",
              str(state_with_baritone.get("content"))[:200])
        if saved_state is not None:
            client.custom_results["get_state"] = saved_state
        client.custom_results.pop("task_status", None)

        # ---- AI 托管 + 枪械（模组联动的那两个工具）
        await asyncio.sleep(1.1)
        toggle = await plugin.mc_takeover()
        check(bool(toggle.get("success")), "Tool mc_takeover 调用成功（不带参数 = 切换）",
              json.dumps(toggle, ensure_ascii=False)[:140])
        t_action = await client.expect_action("takeover", timeout=5)
        tp = (t_action.get("data") or {}).get("params") or {}
        check("enabled" not in tp, "mc_takeover 不填参数时不硬塞 enabled（由模组自己切换）",
              json.dumps(tp, ensure_ascii=False))

        await plugin.mc_takeover(enabled=False)
        off = await client.expect_action("takeover", timeout=5)
        check(((off.get("data") or {}).get("params") or {}).get("enabled") is False,
              "mc_takeover(enabled=False) 明确交还控制权",
              json.dumps((off.get("data") or {}).get("params"), ensure_ascii=False))

        reloaded = await plugin.mc_shoot(action="reload")
        check(bool(reloaded.get("success")), "Tool mc_shoot reload 调用成功",
              json.dumps(reloaded, ensure_ascii=False)[:140])
        r_action = await client.expect_action("reload", timeout=5)
        check((r_action.get("data") or {}).get("action") == "reload",
              "mc_shoot(action=reload) 下发的是 reload 动作",
              json.dumps(r_action.get("data"), ensure_ascii=False)[:140])

        shot = await plugin.mc_shoot(action="shoot", target="zombie", ticks=10)
        check(bool(shot.get("success")), "Tool mc_shoot shoot 调用成功",
              json.dumps(shot, ensure_ascii=False)[:140])
        s_action = await client.expect_action("shoot", timeout=5)
        sp = (s_action.get("data") or {}).get("params") or {}
        check(sp.get("ticks") == 10 and sp.get("target") == "zombie",
              "mc_shoot(shoot)：瞄准目标和按住时长都传下去了",
              json.dumps(sp, ensure_ascii=False))

        bad_gun = await plugin.mc_shoot(action="throw")
        check(not bad_gun.get("success"), "mc_shoot 拒绝不认识的 action",
              str(bad_gun.get("content"))[:100])

        # ---- 枪械工作台 + 关界面（TaCZ 工作台那条链路）
        await asyncio.sleep(1.1)
        client.custom_results["gun_smith"] = {
            "mode": "craft", "recipe": "tacz:ak47", "output": "AK47 突击步枪",
            "crafted": 1, "have": 1, "elapsedTicks": 12,
        }
        crafted = await plugin.mc_gun_smith(recipe="tacz:ak47")
        check(bool(crafted.get("success")), "Tool mc_gun_smith 制作调用成功",
              json.dumps(crafted, ensure_ascii=False)[:140])
        g_action = await client.expect_action("gun_smith", timeout=5)
        gp = (g_action.get("data") or {}).get("params") or {}
        check(gp.get("mode") == "craft" and gp.get("recipe") == "tacz:ak47",
              "mc_gun_smith(recipe=...) 下发的是 craft 模式 + 配方 id",
              json.dumps(gp, ensure_ascii=False))
        check("做好了" in str(crafted.get("content")) and "AK47" in str(crafted.get("content")),
              "制作结果渲染成人话（不是丢一坨 JSON 给模型）",
              str(crafted.get("content"))[:120])

        # 枪械工作台的菜单是 0 槽位的：服务端扣了材料，但成品要等下一次背包同步才出现。
        # 模组会回 signal=materials，这种「材料已经扣掉」的结果必须报成成功，不能报成失败，
        # 否则 AI 会以为没做成、然后重发一次制作（白扣一份材料）。
        client.custom_results["gun_smith"] = {
            "mode": "craft", "recipe": "tacz:gun/ak47", "output": "AKM 突击步枪",
            "have": 0, "signal": "materials", "elapsedTicks": 26,
            "note": "材料已经被扣掉（第 26 tick），说明服务端接受了这次制作并已经完成；"
                    "成品要等下一次背包同步才会出现在客户端 —— 过几秒用 mc_inventory 再看一眼。",
        }
        waited = await plugin.mc_gun_smith(recipe="tacz:gun/ak47")
        await client.expect_action("gun_smith", timeout=5)
        wtext = str(waited.get("content"))
        check(bool(waited.get("success")) and "✅" in wtext and "服务端已经把材料扣掉" in wtext,
              "成品还没同步回来时（signal=materials）也报成功并说明原因", wtext[:200])

        client.custom_results["gun_smith"] = {
            "mode": "list", "total": 412, "craftableTotal": 1, "matched": 2,
            "recipes": [
                {"recipe": "tacz:ak47", "output": "AK47 突击步枪", "canCraft": True,
                 "inputs": [{"item": "tacz:steel_ingot", "name": "钢锭", "need": 12, "have": 12}]},
                {"recipe": "tacz:m4a1", "output": "M4A1 卡宾枪", "canCraft": False,
                 "inputs": [{"item": "tacz:steel_ingot", "name": "钢锭", "need": 12, "have": 4}],
                 "missing": "钢锭×8"},
            ],
        }
        listed = await plugin.mc_gun_smith(craftable_only=True, limit=5)
        g_list = await client.expect_action("gun_smith", timeout=5)
        glp = (g_list.get("data") or {}).get("params") or {}
        check(glp.get("mode") == "list" and glp.get("craftable_only") is True
              and glp.get("limit") == 5,
              "mc_gun_smith 不带 recipe = 列配方，craftable_only/limit 都传下去了",
              json.dumps(glp, ensure_ascii=False))
        gtext = str(listed.get("content"))
        check("✅" in gtext and "❌" in gtext and "缺 钢锭×8" in gtext,
              "配方列表渲染出「够/不够 + 缺什么」", gtext[:200])
        check("412" in gtext and "mc_close_screen" in gtext,
              "列表摘要带上总数与「做完记得关界面」的提醒", gtext[:200])

        client.custom_results["close_screen"] = {
            "closed": True, "was": "ChestScreen", "title": "箱子"}
        closed = await plugin.mc_close_screen()
        c_action = await client.expect_action("close_screen", timeout=5)
        check(c_action is not None and "已关掉界面" in str(closed.get("content")),
              "Tool mc_close_screen 关界面并报出原来是哪个界面",
              str(closed.get("content"))[:120])

        client.custom_results["close_screen"] = {"closed": False}
        nothing = await plugin.mc_close_screen()
        await client.expect_action("close_screen", timeout=5)
        check("本来就没有界面" in str(nothing.get("content")),
              "没有界面时 mc_close_screen 也不报错",
              str(nothing.get("content"))[:120])
        client.custom_results.pop("close_screen", None)
        client.custom_results.pop("gun_smith", None)

        client.fail_actions.add("chat")
        failed = await plugin.mc_chat(message="这条会失败")
        client.fail_actions.discard("chat")
        check(not failed.get("success"), "模组回传失败时工具正确报告失败")
        check("测试用失败" in str(failed.get("content")), "错误信息透传给了 LLM",
              str(failed.get("content"))[:100])

        # ---- 右键：界面开没开是「成没成」的硬证据
        #
        # 真机踩过：目标在 6 格外时客户端会回 SUCCESS/PASS，服务端其实把交互包丢了。
        # 所以结果里要报距离，并且用「界面弹出来了」当成功判据。
        await asyncio.sleep(1.1)
        client.custom_results["use_on_block"] = {
            "clickedBlock": "minecraft:chest", "clickedPos": "(3, -60, 8)", "face": "west",
            "result": "SUCCESS", "distance": 1.87, "handItem": "",
            "screenOpened": True, "screen": "ContainerScreen", "screenTitle": "箱子",
            "note": "界面已经弹出来了 —— 这次交互确实生效了。",
        }
        opened = await plugin.mc_use_on_block(x="~2", y="~", z="~")
        check(bool(opened.get("success")) and "界面开了" in str(opened.get("content")),
              "mc_use_on_block 把「界面弹出来了」当成功证据报出来",
              str(opened.get("content"))[:160])
        check("1.87" in str(opened.get("content")),
              "mc_use_on_block 报出点击距离（判断够不够得着靠它）",
              str(opened.get("content"))[:160])

        client.custom_results["use_on_block"] = {
            "clickedBlock": "minecraft:oak_button", "clickedPos": "(3, -60, 8)", "face": "west",
            "result": "SUCCESS", "distance": 6.4, "handItem": "",
            "screenOpened": False,
            "note": "点完之后界面没有弹出来。两种情况都可能有：① 正常 —— 按钮、拉杆、门、耕地这些"
                    "本来就不开界面；② 这次交互服务端根本没理（超过了交互距离…）。",
        }
        no_screen = await plugin.mc_use_on_block(x="~2", y="~", z="~")
        ntext = str(no_screen.get("content"))
        check("6.4" in ntext and "本来就不开界面" in ntext,
              "界面没弹出时不硬报成功，而是把两种可能都说清楚（含距离）", ntext[:200])
        client.custom_results.pop("use_on_block", None)

        # ---- 工具返回内容对 LLM 友好
        #
        # 上面连着下发了几十个动作，会撞到插件的限速（默认每秒 20 个）——
        # 限速本身是**对的**（防止 AI 把游戏刷爆），所以这里等一下再继续。
        await asyncio.sleep(1.1)
        state_tool = await plugin.mc_state()
        check(bool(state_tool.get("success")) and "Steve" in str(state_tool.get("content")),
              "mc_state 返回了可读的状态摘要", str(state_tool.get("content"))[:120])

        # ---- 游戏事件 → 上下文 + 主动提醒
        ctx.calls.pop("maisaka.context.append", None)
        ctx.calls.pop("maisaka.proactive.trigger", None)
        await client.send_json({
            "v": 1, "type": "event", "id": "ev-2",
            "data": {"kind": "death", "playerName": "Steve", "pos": {"x": 4.0, "y": 12.0, "z": 9.0},
                     "killer": "zombie", "killerName": "僵尸"},
        })
        for _ in range(40):
            if ctx.calls.get("maisaka.proactive.trigger"):
                break
            await asyncio.sleep(0.05)
        appended = [c for c in ctx.calls.get("maisaka.context.append", [])
                    if "死了" in str(c.get("visible_text", ""))]
        check(bool(appended), "死亡事件被追加到上下文（含可读描述）")
        death_triggers = ctx.calls.get("maisaka.proactive.trigger") or []
        check(bool(death_triggers), "死亡事件触发了主动提醒（notify_on 默认含 death）")

        # ---- 系统消息不进对话流，只进上下文（避免打断正常聊天）
        before = len(ctx.calls.get("gateway.route_message") or [])
        await client.send_json({
            "v": 1, "type": "event", "id": "ev-3",
            "data": {"kind": "chat", "channel": "system", "text": "Steve 达成了进度 [石器时代]",
                     "player": False, "sender": None},
        })
        await asyncio.sleep(0.3)
        after = len(ctx.calls.get("gateway.route_message") or [])
        check(before == after, "系统消息不会被当成玩家发言注入对话")

        # ---- 过滤麦麦自己的发言，避免自问自答
        before = len(ctx.calls.get("gateway.route_message") or [])
        await client.send_json({
            "v": 1, "type": "event", "id": "ev-4",
            "data": {"kind": "chat", "channel": "chat", "text": "<Steve> 我挖到铁了",
                     "player": True, "sender": "Steve", "playerName": "Steve"},
        })
        await asyncio.sleep(0.3)
        after = len(ctx.calls.get("gateway.route_message") or [])
        check(before == after, "忽略麦麦自己发出的聊天（ignore_own_messages）")

        # ---- 未鉴权 / 协议版本不匹配的保护
        client2 = await TestWsClient.connect("127.0.0.1", plugin.bridge.bound_port, plugin.config.server.path)
        await client2.send_json({"v": 99, "type": "hello", "data": {}})
        rejection = await client2.wait_for_message("hello_ack", timeout=5)
        check(not (rejection.get("data") or {}).get("ok"), "协议版本不匹配时被拒绝",
              str((rejection.get("data") or {}).get("error"))[:60])
        await client2.close()

        # ---- 会话列表
        clients = await plugin.mc_list_clients()
        check(bool(clients.get("success")) and "Steve" in str(clients.get("content")),
              "mc_list_clients 能看到在线的客户端", str(clients.get("content"))[:100])

        # ---- 动作白名单
        plugin.config.safety.deny_actions = ["attack"]
        denied = await plugin._call("attack", {"target": "nearest_hostile"})
        plugin.config.safety.deny_actions = []
        check(not denied.get("success"), "黑名单里的动作被拒绝", str(denied.get("content"))[:80])

        # ---- 没有客户端时的报错
        await client.close()
        await asyncio.sleep(0.3)
        nobody = await plugin.mc_state()
        check(not nobody.get("success"), "没有客户端时工具返回明确错误",
              str(nobody.get("content"))[:100])
        client = None

    except Exception:
        bad("端到端流程", traceback.format_exc())
    finally:
        if client is not None:
            await client.close()
        try:
            await plugin.on_unload()
            ok("on_unload 正常执行")
        except Exception:
            bad("on_unload 正常执行", traceback.format_exc())

    offline = [c for c in ctx.calls.get("gateway.update_state", []) if c.get("ready") is False]
    check(bool(offline), "on_unload 上报了网关离线")


# ============================================================================


async def main() -> int:
    print("=" * 78)
    print("MaiBot Minecraft 插件自检")
    print("=" * 78)
    check_syntax()
    check_manifest()
    plugin = check_plugin_load()
    if plugin is not None:
        await check_end_to_end(plugin)

    print("\n" + "=" * 78)
    print(f"通过 {len(PASSED)} 项，失败 {len(FAILED)} 项")
    if FAILED:
        print("\n失败清单：")
        for item in FAILED:
            print(f"  - {item}")
    print("=" * 78)
    return 1 if FAILED else 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
