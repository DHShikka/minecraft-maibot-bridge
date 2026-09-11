"""``maibot_sdk`` 的最小替身，只用于本地自检。

真正的实现由 MaiBot 的插件 Runner 提供；这里刻意把签名写得和 SDK 2.8.0 完全一致，
这样本地跑一遍就能发现「装饰器参数写错」「handler 名字写错」「生命周期方法漏实现」这类
只有在 Runner 加载时才会暴露的问题。

注意：这不是 SDK 的一部分，也不会被打包进插件目录。
"""

from __future__ import annotations

import inspect
import logging
from collections.abc import Mapping
from typing import Any, Callable, Optional

# 真实 SDK 并不在顶层导出 ToolParameterInfo / ToolParamType —— 必须从 maibot_sdk.types 导入。
# 这里刻意用模块别名而不是 `from .types import ...`，就是为了让「错误地从顶层导入」
# 在本地自检时立刻报 ImportError，而不是等到 Runner 加载插件时才炸。
from . import types as _types

__all__ = [
    "MaiBotPlugin",
    "PluginConfigBase",
    "Field",
    "Tool",
    "Command",
    "API",
    "MessageGateway",
    "HomeCard",
    "HookHandler",
    "EventHandler",
    "Action",
    "LLMProvider",
    "CONFIG_RELOAD_SCOPE_SELF",
    "ON_BOT_CONFIG_RELOAD",
    "ON_MODEL_CONFIG_RELOAD",
]

CONFIG_RELOAD_SCOPE_SELF = "self"
ON_BOT_CONFIG_RELOAD = "bot"
ON_MODEL_CONFIG_RELOAD = "model"

VALID_SCOPE_SUBSCRIPTIONS = {"bot", "model"}
VALID_ROUTE_TYPES = {"send", "receive", "duplex", "recv", "recive"}


# --------------------------------------------------------------------- Field

#: 与真实 SDK 的 config.py 保持一致的常量
_PLUGIN_CONFIG_SECTION_NAME = "plugin"
_PLUGIN_CONFIG_VERSION_FIELD_NAME = "config_version"


class PluginConfigVersionError(ValueError):
    """插件配置版本不合法。与真实 SDK 同名同义。"""


class _FieldInfo:
    __slots__ = ("default", "default_factory", "description", "json_schema_extra")

    def __init__(self, default: Any = None, default_factory: Optional[Callable[[], Any]] = None,
                 description: str = "", json_schema_extra: Optional[dict] = None) -> None:
        self.default = default
        self.default_factory = default_factory
        self.description = description
        self.json_schema_extra = json_schema_extra or {}

    def build(self) -> Any:
        if self.default_factory is not None:
            return self.default_factory()
        return self.default


def Field(default: Any = None, *, default_factory: Optional[Callable[[], Any]] = None,
          description: str = "", json_schema_extra: Optional[dict] = None, **_ignored: Any) -> Any:
    """与 ``maibot_sdk.Field`` 同名同参（真实实现就是 pydantic 的 Field）。"""
    return _FieldInfo(default=default, default_factory=default_factory,
                      description=description, json_schema_extra=json_schema_extra)


def extract_plugin_config_version(config_data: Any) -> str:
    """复刻真实 SDK 的 ``extract_plugin_config_version``。

    这条**必须**复刻，因为它是一个「静默致命」的约束：Runner 的
    ``_prepare_plugin_config_for_version_update()`` 会拿插件的**默认配置**调用它，
    只要默认配置里没有 ``plugin.config_version``，就直接抛
    ``PluginConfigVersionError``，插件以「插件初始化失败」告终 ——
    而 MaiBot 插件页只会显示这一句话，完全看不出原因。

    本地自检如果不复刻这条，就会漏掉这类 bug（我们确实漏过一次）。
    """
    if not isinstance(config_data, Mapping):
        raise PluginConfigVersionError("插件配置内容不是映射，无法读取版本号")
    plugin_section = config_data.get(_PLUGIN_CONFIG_SECTION_NAME)
    if not isinstance(plugin_section, Mapping):
        raise PluginConfigVersionError(
            "插件配置文件缺少 [plugin] 配置节，且必须提供 plugin.config_version 版本号"
        )
    normalized = str(plugin_section.get(_PLUGIN_CONFIG_VERSION_FIELD_NAME) or "").strip()
    if not normalized:
        raise PluginConfigVersionError(
            "插件配置文件缺少 plugin.config_version 版本号，当前版本策略不再兼容无版本配置"
        )
    return normalized


# ------------------------------------------------------------- 配置基类


class PluginConfigBase:
    """极简版强类型配置：按注解 + Field 默认值构造实例。"""

    def __init__(self, **overrides: Any) -> None:
        for name, info in self._collect_fields().items():
            if name in overrides:
                setattr(self, name, overrides[name])
            else:
                setattr(self, name, info.build())

    @classmethod
    def _collect_fields(cls) -> dict[str, _FieldInfo]:
        fields: dict[str, _FieldInfo] = {}
        # 注意：Python 3.10+ 访问 cls.__annotations__ 会「惰性创建」并写回类字典，
        # 所以必须先快照 vars(klass)，否则遍历中会抛 dictionary changed size during iteration。
        for klass in reversed(cls.__mro__):
            attributes = list(vars(klass).items())
            annotations = dict(klass.__dict__.get("__annotations__", {}))
            for name, value in attributes:
                if name.startswith("__"):
                    continue
                if isinstance(value, _FieldInfo):
                    fields[name] = value
                elif name in annotations and name not in fields:
                    fields[name] = _FieldInfo(default=value)
            for name in annotations:
                if name not in fields and not name.startswith("__"):
                    fields[name] = _FieldInfo(default=getattr(cls, name, None))
        return fields

    def model_dump(self, *, mode: str = "python", **_kwargs: Any) -> dict[str, Any]:
        """复刻 pydantic 的 ``model_dump()``（递归把嵌套配置节转成 dict）。

        真实 SDK 的 ``build_plugin_default_config()`` 就是
        ``config_class().model_dump(mode="python")``，Runner 拿到的默认配置是**纯 dict**。
        自检里如果要模拟 Runner 的配置版本检查，就必须有同样的序列化，
        否则嵌套节还是对象、``config["plugin"]`` 取不到字典，检查就形同虚设。
        """
        result: dict[str, Any] = {}
        for name in self._collect_fields():
            result[name] = _dump_value(getattr(self, name, None), mode)
        return result

    def model_dump_json(self, **_kwargs: Any) -> str:
        import json as _json
        return _json.dumps(self.model_dump(), ensure_ascii=False)


def _dump_value(value: Any, mode: str) -> Any:
    if isinstance(value, PluginConfigBase):
        return value.model_dump(mode=mode)
    if isinstance(value, Mapping):
        return {k: _dump_value(v, mode) for k, v in value.items()}
    if isinstance(value, (list, tuple, set)):
        return [_dump_value(v, mode) for v in value]
    return value


# ------------------------------------------------------------ 组件装饰器


def _mark(handler: Callable[..., Any], kind: str, name: str, **meta: Any) -> Callable[..., Any]:
    existing = getattr(handler, "__maibot_components__", None)
    if existing is None:
        existing = []
        setattr(handler, "__maibot_components__", existing)
    existing.append({"type": kind, "name": name, **meta})
    return handler


def Tool(name: str, description: str = "", brief_description: str = "", detailed_description: str = "",
         parameters: Any = None, **metadata: Any) -> Callable[[Callable[..., Any]], Callable[..., Any]]:
    if not isinstance(name, str) or not name:
        raise ValueError("Tool 的 name 必须是非空字符串")
    if parameters is not None and not isinstance(parameters, (list, dict)):
        raise ValueError(f"Tool({name}) 的 parameters 必须是 list[ToolParameterInfo] 或 dict")
    if isinstance(parameters, list):
        for item in parameters:
            if not isinstance(item, _types.ToolParameterInfo):
                raise ValueError(f"Tool({name}) 的 parameters 列表里出现了非 ToolParameterInfo 元素: {item!r}")
            if not isinstance(item.param_type, _types.ToolParamType):
                raise ValueError(f"Tool({name}) 的参数 {item.name} 的 param_type 类型不对: {item.param_type!r}")

    def decorator(handler: Callable[..., Any]) -> Callable[..., Any]:
        if not inspect.iscoroutinefunction(handler):
            raise ValueError(f"Tool({name}) 的处理函数 {handler.__name__} 必须是 async 方法")
        return _mark(handler, "tool", name, description=description,
                     brief_description=brief_description, detailed_description=detailed_description,
                     parameters=parameters, **metadata)

    return decorator


def Command(name: str, description: str = "", pattern: str = "",
            aliases: Optional[list[str]] = None, **metadata: Any) -> Callable[[Callable[..., Any]], Callable[..., Any]]:
    if not isinstance(name, str) or not name:
        raise ValueError("Command 的 name 必须是非空字符串")
    if pattern:
        import re
        try:
            re.compile(pattern)
        except re.error as exc:
            raise ValueError(f"Command({name}) 的正则 pattern 无效: {exc}") from exc

    def decorator(handler: Callable[..., Any]) -> Callable[..., Any]:
        if not inspect.iscoroutinefunction(handler):
            raise ValueError(f"Command({name}) 的处理函数 {handler.__name__} 必须是 async 方法")
        return _mark(handler, "command", name, description=description,
                     command_pattern=pattern, aliases=list(aliases or []), **metadata)

    return decorator


def API(name: str, description: str = "", version: str = "1", public: bool = False,
        **metadata: Any) -> Callable[[Callable[..., Any]], Callable[..., Any]]:
    def decorator(handler: Callable[..., Any]) -> Callable[..., Any]:
        if not inspect.iscoroutinefunction(handler):
            raise ValueError(f"API({name}) 的处理函数 {handler.__name__} 必须是 async 方法")
        return _mark(handler, "api", name, description=description, version=version,
                     public=public, **metadata)

    return decorator


def MessageGateway(route_type: str, *, name: str = "", description: str = "", platform: str = "",
                   protocol: str = "", account_id: str = "", scope: str = "",
                   **metadata: Any) -> Callable[[Callable[..., Any]], Callable[..., Any]]:
    if route_type not in VALID_ROUTE_TYPES:
        raise ValueError(f"MessageGateway 的路由类型不合法: {route_type!r}（应为 send/receive/duplex）")
    if route_type in {"recv", "recive"}:
        route_type = "receive"

    def decorator(handler: Callable[..., Any]) -> Callable[..., Any]:
        if not inspect.iscoroutinefunction(handler):
            raise ValueError("MessageGateway 的处理函数必须是 async 方法")
        return _mark(handler, "message_gateway", name or handler.__name__,
                     route_type=route_type, description=description, platform=platform,
                     protocol=protocol, account_id=account_id, scope=scope, **metadata)

    return decorator


def HomeCard(name: str, title: str, content: Any = "", *, description: str = "", link_url: str = "",
             link_label: str = "", icon: str = "", width: str = "medium", order: int = 1000,
             **metadata: Any) -> Callable[[Callable[..., Any]], Callable[..., Any]]:
    if width not in {"small", "medium", "large", "wide", "full"}:
        raise ValueError(f"HomeCard({name}) 的 width 不合法: {width!r}")

    def decorator(handler: Callable[..., Any]) -> Callable[..., Any]:
        return _mark(handler, "home_card", name, title=title, content=content,
                     description=description, link_url=link_url, link_label=link_label,
                     icon=icon, width=width, order=order, **metadata)

    return decorator


def HookHandler(hook: str, *, name: str = "", description: str = "", mode: str = "blocking",
                order: str = "normal", timeout_ms: int = 0, error_policy: str = "skip",
                **metadata: Any) -> Callable[[Callable[..., Any]], Callable[..., Any]]:
    def decorator(handler: Callable[..., Any]) -> Callable[..., Any]:
        return _mark(handler, "hook_handler", name or handler.__name__, hook=hook, **metadata)

    return decorator


def EventHandler(name: str, description: str = "", event_type: str = "on_message",
                 intercept_message: bool = False, weight: int = 0,
                 **metadata: Any) -> Callable[[Callable[..., Any]], Callable[..., Any]]:
    def decorator(handler: Callable[..., Any]) -> Callable[..., Any]:
        return _mark(handler, "event_handler", name, event_type=event_type, **metadata)

    return decorator


def Action(name: str, description: str = "", **metadata: Any) -> Callable[[Callable[..., Any]], Callable[..., Any]]:
    def decorator(handler: Callable[..., Any]) -> Callable[..., Any]:
        return _mark(handler, "tool", name, **metadata)

    return decorator


def LLMProvider(client_type: str, *, name: str = "", description: str = "",
                version: str = "1.0.0", **metadata: Any) -> Callable[[Callable[..., Any]], Callable[..., Any]]:
    def decorator(handler: Callable[..., Any]) -> Callable[..., Any]:
        return _mark(handler, "llm_provider", name or client_type, client_type=client_type)

    return decorator


# ------------------------------------------------------------- 插件基类


class MaiBotPlugin:
    """与真实 SDK 同名同约定的基类（只实现自检需要的部分）。"""

    config_model: Any = None
    config_reload_subscriptions: Any = ()

    def __init__(self) -> None:
        self.ctx: Any = None
        self.config: Any = None
        self._dynamic_apis: dict[str, Any] = {}

    # -- 生命周期：真实 SDK 要求必须实现，这里显式声明以便自检能发现遗漏
    async def on_load(self) -> None:
        raise NotImplementedError("插件必须实现 on_load()")

    async def on_unload(self) -> None:
        raise NotImplementedError("插件必须实现 on_unload()")

    async def on_config_update(self, scope: str, config_data: dict[str, Any], version: str) -> None:
        raise NotImplementedError("插件必须实现 on_config_update()")

    # -- Runner 注入
    def _set_context(self, ctx: Any) -> None:
        self.ctx = ctx
        if self.config_model is not None:
            self.config = self.config_model()

    def get_plugin_config_data(self) -> dict[str, Any]:
        return {}

    def get_config_reload_subscriptions(self) -> tuple[str, ...]:
        raw = self.config_reload_subscriptions
        if isinstance(raw, str):
            raise ValueError("config_reload_subscriptions 不能直接传字符串，必须用元组/列表")
        values = tuple(raw or ())
        for value in values:
            if value not in VALID_SCOPE_SUBSCRIPTIONS:
                raise ValueError(f"不支持的订阅范围: {value!r}（只允许 'bot' 与 'model'）")
        return values

    def build_config_schema(self, plugin_id: str = "", plugin_name: str = "",
                            plugin_version: str = "") -> dict[str, Any]:
        if self.config_model is None:
            return {}
        return {"plugin_id": plugin_id, "plugin_name": plugin_name,
                "plugin_version": plugin_version, "model": self.config_model.__name__}

    # -- 动态 API
    def register_dynamic_api(self, name: str, handler: Callable[..., Any], *, description: str = "",
                             version: str = "1", public: bool = False, handler_name: str = "",
                             **metadata: Any) -> None:
        self._dynamic_apis[name] = {"handler": handler, "version": version, "public": public}

    def unregister_dynamic_api(self, name: str, *, version: str = "1") -> None:
        self._dynamic_apis.pop(name, None)

    def clear_dynamic_apis(self) -> None:
        self._dynamic_apis.clear()

    async def sync_dynamic_apis(self, *, offline_reason: str = "") -> None:
        return None


# ------------------------------------------------------------------ 组件扫描


def collect_components(plugin: MaiBotPlugin) -> list[dict[str, Any]]:
    """模拟 Runner 的组件发现逻辑：扫描类上被装饰器标记的方法。"""
    found: list[dict[str, Any]] = []
    for klass in type(plugin).__mro__:
        for attr_name, attr in vars(klass).items():
            marks = getattr(attr, "__maibot_components__", None)
            if not marks:
                continue
            for mark in marks:
                entry = dict(mark)
                entry["handler_name"] = attr_name
                entry["handler"] = getattr(plugin, attr_name)
                found.append(entry)
    return found


def default_logger(name: str) -> logging.Logger:
    logger = logging.getLogger(name)
    if not logger.handlers:
        handler = logging.StreamHandler()
        handler.setFormatter(logging.Formatter("%(levelname)-5s %(name)s | %(message)s"))
        logger.addHandler(handler)
        logger.setLevel(logging.INFO)
    return logger
