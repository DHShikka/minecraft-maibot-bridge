"""按 MaiBot PluginLoader 的真实方式加载插件，用来复现/防止「子包找不到」这类问题。

为什么需要单独一个脚本：最容易漏掉的 bug 恰恰是「加载方式不同导致的 import 失败」。
普通自检为了图方便会把插件目录塞进 ``sys.path``，那是**假**环境 ——
真实的 ``PluginLoader._load_single_plugin``（plugin_loader.py:563-580）是这样的：

.. code-block:: python

    spec = importlib.util.spec_from_file_location(
        module_name, str(plugin_path),
        submodule_search_locations=[str(plugin_dir)],      # 只影响 plugin 自己的子模块
    )
    module = importlib.util.module_from_spec(spec)
    sys.modules[module_name] = module
    with temp_sys_path(src_root):
        with temp_sys_path(plugin_dir.parent):             # ← 注意：是「父目录」
            spec.loader.exec_module(module)

也就是说 ``sys.path`` 上出现的是 ``plugins/``，**不是** ``plugins/<插件名>/``。
所以插件目录里的子包（``mcai_bridge/``）必须由插件自己把它所在目录插进 ``sys.path``，
否则就会报 ``No module named 'mcai_bridge'``。

用法：
    <python> tools/check_loader.py
"""

from __future__ import annotations

import importlib.util
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PLUGIN_DIR = ROOT / "minecraft-bridge"
STUB_SDK = ROOT / "tools" / "stub_sdk"


def normalize(path: str | os.PathLike[str]) -> str:
    return os.path.normpath(str(path))


def main() -> int:
    print("=" * 74)
    print("按 MaiBot PluginLoader 的真实方式加载插件")
    print("=" * 74)

    # ---- 把解释器环境重置成 Runner 的样子 --------------------------------
    sys.path[:] = [p for p in sys.path if normalize(p) not in {normalize(PLUGIN_DIR), normalize(ROOT)}]
    for name in [n for n in sys.modules if n == "mcai_bridge" or n.startswith("mcai_bridge.")]:
        sys.modules.pop(name, None)

    sys.path.insert(0, str(STUB_SDK))
    # 关键：只放「父目录」，与 plugin_loader.py:575-579 完全一致
    sys.path.insert(0, normalize(PLUGIN_DIR.parent))

    on_path = normalize(PLUGIN_DIR) in {normalize(p) for p in sys.path}
    print(f"插件目录:              {PLUGIN_DIR}")
    print(f"插件目录在 sys.path 上? {on_path}   （真实 Runner 里是 False）")
    if on_path:
        print("  !! 测试环境不忠实：插件目录不该在 sys.path 上")
        return 1

    plugin_path = PLUGIN_DIR / "plugin.py"
    print(f"待加载:                {plugin_path}")
    print("-" * 74)

    spec = importlib.util.spec_from_file_location(
        "mcai_bridge_plugin_loadercheck",
        str(plugin_path),
        submodule_search_locations=[str(PLUGIN_DIR)],
    )
    if spec is None or spec.loader is None:
        print("FAIL: 无法创建模块 spec")
        return 1

    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    try:
        spec.loader.exec_module(module)
    except ModuleNotFoundError as exc:
        print(f"FAIL: {type(exc).__name__}: {exc}")
        print()
        print("这就是 MaiBot 插件页上会看到的错误。原因通常是：")
        print(f"  1) {PLUGIN_DIR} 下缺少 mcai_bridge/ 子目录（复制时漏掉了子目录）；")
        print("  2) plugin.py 没有把插件目录本身加进 sys.path（本仓库应已在文件开头处理）。")
        print()
        print(f"当前插件目录实际内容: {sorted(p.name for p in PLUGIN_DIR.iterdir())}")
        return 1
    except Exception as exc:  # noqa: BLE001
        print(f"FAIL: {type(exc).__name__}: {exc}")
        import traceback
        traceback.print_exc()
        return 1

    if not hasattr(module, "create_plugin"):
        print("FAIL: 没有导出 create_plugin()")
        return 1

    try:
        plugin = module.create_plugin()
    except Exception as exc:  # noqa: BLE001
        print(f"FAIL: create_plugin() 抛异常: {type(exc).__name__}: {exc}")
        import traceback
        traceback.print_exc()
        return 1

    from maibot_sdk import collect_components

    components = collect_components(plugin)
    counts: dict[str, int] = {}
    for comp in components:
        counts[comp["type"]] = counts.get(comp["type"], 0) + 1

    print(f"OK: 模块加载成功，create_plugin() 返回 {type(plugin).__name__}")
    print(f"OK: 组件统计 {counts}")
    print()
    print("结论：在 Runner 的真实 sys.path 语义下，插件可以正常加载。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
