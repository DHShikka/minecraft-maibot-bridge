"""Minecraft ↔ MaiBot 桥接核心库。

对外主要暴露：

* :class:`~mcai_bridge.bridge.BridgeManager` —— 连接与会话管理（WebSocket 服务端）
* :class:`~mcai_bridge.bridge.BridgeSession` —— 单台 Minecraft 客户端的会话
* :class:`~mcai_bridge.bridge.ActionError` —— 动作下发/执行失败的统一异常
* :mod:`mcai_bridge.protocol` —— 线协议常量与报文构造
* :mod:`mcai_bridge.ws` —— 零依赖的 asyncio WebSocket 服务端实现

注意：这个包**不能**被插件直接 ``import mcai_bridge`` 之外的方式假定可导入。
``plugin.py`` 开头会把插件目录插进 ``sys.path``，原因见那里的注释。
"""

from .bridge import ActionError, BridgeManager, BridgeSession
from . import protocol

__all__ = ["ActionError", "BridgeManager", "BridgeSession", "protocol"]
__version__ = "1.0.0"
