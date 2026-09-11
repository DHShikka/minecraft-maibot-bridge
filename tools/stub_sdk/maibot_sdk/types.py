"""``maibot_sdk.types`` 的最小替身。"""

from __future__ import annotations

from enum import Enum
from typing import Any, Optional


class ToolParamType(str, Enum):
    """与真实 SDK 的 ToolParamType 取值一致。"""

    STRING = "string"
    INTEGER = "integer"
    NUMBER = "number"
    FLOAT = "number"
    BOOLEAN = "boolean"
    ARRAY = "array"
    OBJECT = "object"


class ToolParameterInfo:
    """与真实 SDK 的 ToolParameterInfo 字段一致。"""

    __slots__ = ("name", "param_type", "description", "required", "enum_values",
                 "default", "items_schema", "properties", "required_properties",
                 "additional_properties")

    def __init__(self, name: str, param_type: ToolParamType = ToolParamType.STRING,
                 description: str = "", required: bool = True,
                 enum_values: Optional[list[Any]] = None, default: Any = None,
                 items_schema: Optional[dict[str, Any]] = None,
                 properties: Optional[dict[str, Any]] = None,
                 required_properties: Optional[list[str]] = None,
                 additional_properties: Any = None) -> None:
        if not isinstance(name, str) or not name:
            raise ValueError("ToolParameterInfo.name 必须是非空字符串")
        if not isinstance(param_type, ToolParamType):
            raise ValueError(f"参数 {name} 的 param_type 必须是 ToolParamType，收到 {param_type!r}")
        self.name = name
        self.param_type = param_type
        self.description = description
        self.required = bool(required)
        self.enum_values = enum_values
        self.default = default
        self.items_schema = items_schema
        self.properties = properties
        self.required_properties = required_properties or []
        self.additional_properties = additional_properties

    def to_schema(self) -> dict[str, Any]:
        schema: dict[str, Any] = {"type": self.param_type.value, "description": self.description}
        if self.enum_values:
            schema["enum"] = list(self.enum_values)
        if self.default is not None:
            schema["default"] = self.default
        return schema

    def __repr__(self) -> str:
        return f"ToolParameterInfo({self.name!r}, {self.param_type.value}, required={self.required})"


class EventType(str, Enum):
    ON_MESSAGE = "on_message"
    ON_LLM_GENERATE = "on_llm_generate"


class HookMode(str, Enum):
    BLOCKING = "blocking"
    OBSERVE = "observe"


class HookOrder(str, Enum):
    EARLY = "early"
    NORMAL = "normal"
    LATE = "late"


class ErrorPolicy(str, Enum):
    SKIP = "skip"
    RAISE = "raise"


class ActivationType(str, Enum):
    ALWAYS = "always"
    KEYWORD = "keyword"
    RANDOM = "random"


class ChatMode(str, Enum):
    NORMAL = "normal"
    FOCUS = "focus"
