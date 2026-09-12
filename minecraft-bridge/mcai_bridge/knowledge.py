"""经验库：把「这样做是对的」写进数据库，之后能查出来照着做。

为什么要有它：麦麦每次会话都是新的，踩过的坑不会自己记住 ——
「这台服务器装了 FTB Essentials，/sethome base 设家、/home base 回家」这种事，
教一次就该一直有效；「合成前要先有工作台，有现成的就直接走过去用」也是。

设计上的几条取舍：

* **用 sqlite3**（Python 自带）而不是 JSON 文件：要按关键词查、要记命中次数、
  要能并发读写，JSON 每次全量重写容易写坏。零第三方依赖这条底线也守住了。
* **存的是「话」，不是「动作序列」**：动作序列由 `mc_script` 负责存；
  这里存的是**给 LLM 看的经验**（什么情况、该怎么做、为什么），
  让它自己判断要不要照做 —— 环境一变，硬编码的序列反而是坑。
* **带来源与置信度**：`source` 区分「人教的 / 自己试出来的 / 预置的」，
  `score` 是命中次数 —— 查的时候按「人教的 > 自己试的 > 预置」和命中次数排。
"""

from __future__ import annotations

import os
import sqlite3
import time
from typing import Any, Optional

# 预置经验：模组自带的「已知正确行为」。清空数据库后会自动补回来。
SEED: list[tuple[str, str, str, str]] = [
    (
        "ftbessentials",
        "FTB Essentials（服务器常装）：传送与实用命令",
        "这台服务器装了 [FTBE] FTB Essentials 时可以用这些命令（走 mc_command）：\n"
        "· 家：/sethome <名字> 设家、/home <名字> 回家、/delhome <名字> 删掉、/listhomes 列出来\n"
        "· 传送：/spawn 回出生点、/back 回上一个位置、/rtp 随机传送、/warp <名> 去地标\n"
        "· 玩家之间：/tpa <玩家> 请求过去、/tpahere <玩家> 请对方过来、/tpaccept 接受\n"
        "· 作弊类（不一定有权限）：/heal 回血、/fly 飞行、/god 无敌、/invsee <玩家> 看背包\n"
        "· 杂项：/hat 把手上的东西戴到头上、/trashcan 垃圾桶、/kickme 把自己踢下线\n"
        "**注意**：这些都是服务端命令，单人存档/没装这个模组时不存在；\n"
        "没有权限时游戏会回「未知命令」或「权限不足」，那种回复要记下来别再试。",
        "seed",
    ),
    (
        "craft",
        "合成需要工作台时，先用世界里已有的",
        "要合成需要 3x3 的东西（面包、工具、盔甲…）时：先用 mc_scan_blocks 找 crafting_table，\n"
        "走到它旁边再 mc_craft —— 模组会自己走过去用它，不用自己新放一张。\n"
        "附近真的没有才去合成一张工作台（4 个木板，2x2 就能做）再放下。",
        "seed",
    ),
    (
        "food",
        "没有食物时的取食顺序",
        "饿肚子又没吃的，按这个顺序试（mc_forage 会自己做这些）：\n"
        "① 干草块（hay_block）→ 拆成 9 小麦 → 3 小麦合成 1 面包（最快）\n"
        "② 打牛/猪/鸡/羊 → 生肉（生鸡肉会食物中毒，最好用熔炉烤熟）\n"
        "③ 翻宝箱（村庄/地牢/废弃矿井常有面包和熟肉）\n"
        "④ 长期方案：种小麦、养牛",
        "seed",
    ),
    (
        "baritone",
        "装了 Baritone 就让它寻路和挖矿",
        "装了 Baritone 时，mc_move_to / mc_mine_blocks 会自动转交给它（结果里 via=baritone，\n"
        "实际发的是 #goto / #mine 聊天指令）。想强制用模组自带的 A* 寻路就加 via=native。\n"
        "Baritone 的进度看 mc_task_status 里的 baritone 字段；想停它要 mc_stop（会顺手发 #stop）。",
        "seed",
    ),
]


class Knowledge:
    """经验库（sqlite）。所有方法都不抛异常 —— 学不到东西也不该把工具调用搞崩。"""

    def __init__(self, path: str) -> None:
        self.path = path
        self._ok = False
        try:
            os.makedirs(os.path.dirname(path), exist_ok=True)
            self._conn = sqlite3.connect(path, check_same_thread=False)
            self._conn.execute(
                """CREATE TABLE IF NOT EXISTS lessons (
                       id      INTEGER PRIMARY KEY AUTOINCREMENT,
                       topic   TEXT NOT NULL,
                       title   TEXT NOT NULL,
                       body    TEXT NOT NULL,
                       source  TEXT NOT NULL DEFAULT 'learned',
                       score   INTEGER NOT NULL DEFAULT 0,
                       created REAL NOT NULL,
                       used    REAL
                   )"""
            )
            self._conn.commit()
            self._ok = True
            self._seed()
        except Exception:  # noqa: BLE001
            self._ok = False

    # ---------------------------------------------------------------- 基础

    @property
    def available(self) -> bool:
        return self._ok

    def _seed(self) -> None:
        """预置经验：只在库是空的时候写一次。"""
        try:
            cur = self._conn.execute("SELECT COUNT(*) FROM lessons")
            if (cur.fetchone() or [0])[0] > 0:
                return
            now = time.time()
            for topic, title, body, source in SEED:
                self._conn.execute(
                    "INSERT INTO lessons (topic, title, body, source, score, created) "
                    "VALUES (?, ?, ?, ?, 0, ?)",
                    (topic, title, body, source, now),
                )
            self._conn.commit()
        except Exception:  # noqa: BLE001
            pass

    def remember(self, topic: str, title: str, body: str,
                 source: str = "learned") -> int:
        """记一条经验，返回它的 id（失败返回 -1）。topic/title 相同时覆盖内容。"""
        if not self._ok:
            return -1
        topic = (topic or "general").strip().lower()[:64]
        title = (title or "").strip()[:200]
        body = (body or "").strip()
        if not title or not body:
            return -1
        try:
            cur = self._conn.execute(
                "SELECT id FROM lessons WHERE topic = ? AND title = ?", (topic, title))
            row = cur.fetchone()
            if row:
                self._conn.execute(
                    "UPDATE lessons SET body = ?, source = ? WHERE id = ?",
                    (body, source, row[0]))
                self._conn.commit()
                return int(row[0])
            cur = self._conn.execute(
                "INSERT INTO lessons (topic, title, body, source, score, created) "
                "VALUES (?, ?, ?, ?, 0, ?)",
                (topic, title, body, source, time.time()))
            self._conn.commit()
            return int(cur.lastrowid or -1)
        except Exception:  # noqa: BLE001
            return -1

    def recall(self, query: str = "", topic: str = "", limit: int = 5,
               count_hit: bool = True) -> list[dict[str, Any]]:
        """按关键词/主题查经验。命中的条目会累加 score（用得越多排得越前）。"""
        if not self._ok:
            return []
        sql = "SELECT id, topic, title, body, source, score FROM lessons"
        where: list[str] = []
        args: list[Any] = []
        if topic.strip():
            where.append("topic = ?")
            args.append(topic.strip().lower())
        if query.strip():
            # 关键词拆开逐个匹配（标题或正文命中任一即可），再按 score 排
            for word in query.strip().split():
                where.append("(title LIKE ? OR body LIKE ? OR topic LIKE ?)")
                like = f"%{word}%"
                args.extend([like, like, like])
        if where:
            sql += " WHERE " + " AND ".join(where)
        sql += (" ORDER BY CASE source WHEN 'human' THEN 0 WHEN 'learned' THEN 1"
                " ELSE 2 END, score DESC, id DESC LIMIT ?")
        args.append(max(1, min(int(limit), 20)))
        try:
            rows = self._conn.execute(sql, args).fetchall()
        except Exception:  # noqa: BLE001
            return []
        if count_hit and rows:
            try:
                ids = [r[0] for r in rows]
                self._conn.executemany(
                    "UPDATE lessons SET score = score + 1, used = ? WHERE id = ?",
                    [(time.time(), i) for i in ids])
                self._conn.commit()
            except Exception:  # noqa: BLE001
                pass
        return [
            {"id": r[0], "topic": r[1], "title": r[2], "body": r[3],
             "source": r[4], "score": r[5]}
            for r in rows
        ]

    def forget(self, lesson_id: int) -> bool:
        if not self._ok:
            return False
        try:
            self._conn.execute("DELETE FROM lessons WHERE id = ?", (int(lesson_id),))
            self._conn.commit()
            return True
        except Exception:  # noqa: BLE001
            return False

    def stats(self) -> dict[str, Any]:
        if not self._ok:
            return {"available": False}
        try:
            total = (self._conn.execute("SELECT COUNT(*) FROM lessons").fetchone() or [0])[0]
            topics = self._conn.execute(
                "SELECT topic, COUNT(*) FROM lessons GROUP BY topic ORDER BY 2 DESC"
            ).fetchall()
            return {"available": True, "path": self.path, "total": total,
                    "topics": [{"topic": t, "count": c} for t, c in topics]}
        except Exception:  # noqa: BLE001
            return {"available": False}


_instances: dict[str, Knowledge] = {}


def open_knowledge(path: str) -> Knowledge:
    """按路径复用同一个实例（插件重载时不要重复开库）。"""
    key = os.path.abspath(path)
    if key not in _instances:
        _instances[key] = Knowledge(key)
    return _instances[key]


def _unused() -> Optional[Knowledge]:  # pragma: no cover - 仅为类型检查留个引用
    return None
