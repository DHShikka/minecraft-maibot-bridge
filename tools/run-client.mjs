// 启动 PCL 装的那个 Forge 客户端，用于真实自检。
//
//   node tools/run-client.mjs              # 普通启动（停在标题界面）
//   node tools/run-client.mjs --selftest    # 带自检入口，自动创建超平坦世界并进去
//
// 配套：另开一个终端跑 `python tools/live_server.py`，它会起真实插件服务端，
// 等客户端进世界后自动下发一串动作（合成木镐等）并检查结果。
//
// 为什么不用 .cmd：中文路径在 cmd 的代码页下会被写坏，node 的 spawn 走 Unicode API 没这问题。
// 为什么不 detached：detached+unref 会被外层命令结束时的进程树清理杀掉，
// 游戏会在加载完纹理后静默消失。这里前台持有，等它自己退出。
import { readFileSync, mkdirSync, existsSync, readdirSync, copyFileSync, rmSync, openSync } from 'node:fs';
import { join } from 'node:path';
import { spawn, execFileSync } from 'node:child_process';

const root = process.cwd();
const game = join(root, '我的世界本体');
const versionId = '1.20.1-Forge_47.4.23';
const versionDir = join(game, 'versions', versionId);
const json = JSON.parse(readFileSync(join(versionDir, `${versionId}.json`), 'utf8'));
const jdk = join(root, '.toolchain', 'jdk', 'jdk-17.0.2');
const runDir = join(root, '.research', 'run');
const nativesDir = join(runDir, 'natives');
const logPath = join(runDir, 'stdout.log');
const selftest = process.argv.includes('--selftest');

mkdirSync(runDir, { recursive: true });
mkdirSync(nativesDir, { recursive: true });

const nameToPath = (name) => {
  const [group, artifact, version] = name.split(':');
  return `${group.replace(/\./g, '/')}/${artifact}/${version}/${artifact}-${version}.jar`;
};

// ---- 1. natives：解压 + 平铺（LWJGL 3.3 的 jar 里是 windows/x64/... 嵌套结构）
const wantNatives = json.libraries.filter((l) => /natives-windows\.jar$/.test(l.downloads?.artifact?.path || ''));
for (const lib of wantNatives) {
  const jar = join(game, 'libraries', lib.downloads.artifact.path);
  if (!existsSync(jar)) continue;
  try {
    execFileSync(join(jdk, 'bin', 'jar.exe'), ['xf', jar], { cwd: nativesDir, stdio: 'ignore' });
  } catch { /* 缺 dll 会在游戏日志里体现 */ }
}
const flatten = (dir) => {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, entry.name);
    if (entry.isDirectory()) flatten(p);
    else if (entry.name.endsWith('.dll') && dir !== nativesDir) {
      copyFileSync(p, join(nativesDir, entry.name));
    }
  }
};
flatten(nativesDir);
const dllCount = readdirSync(nativesDir).filter((f) => f.endsWith('.dll')).length;
console.log(`natives: ${dllCount} 个 dll 已就绪 -> ${nativesDir}`);

// ---- 2. classpath
const libs = json.libraries.filter((lib) => {
  if (!lib.rules) return true;
  return lib.rules.some((r) => r.action === 'allow' && (!r.os || r.os.name === 'windows' || r.os.arch));
});
const cpEntries = [];
for (const lib of libs) {
  const p = lib.downloads?.artifact?.path
    ? join(game, 'libraries', lib.downloads.artifact.path)
    : join(game, 'libraries', nameToPath(lib.name));
  if (existsSync(p)) cpEntries.push(p);
}
const versionJar = join(versionDir, `${versionId}.jar`);
if (existsSync(versionJar)) cpEntries.push(versionJar);

// ---- 3. 参数（照抄 json，替换变量）
const vars = {
  natives_directory: nativesDir,
  launcher_name: 'mcai-selftest',
  launcher_version: '1',
  classpath: cpEntries.join(';'),
  classpath_separator: ';',
  library_directory: join(game, 'libraries'),
  version_name: versionId,
  auth_player_name: 'McAiDev',
  game_directory: versionDir,
  assets_root: join(game, 'assets'),
  assets_index_name: json.assetIndex?.id || '5',
  auth_uuid: '00000000000040008000000000000001',
  auth_access_token: '0',
  clientid: '0',
  auth_xuid: '0',
  user_type: 'legacy',
  version_type: 'release',
};
const sub = (s) => String(s).replace(/\$\{(\w+)\}/g, (m, k) => (k in vars ? vars[k] : m));
// 注意：json 里 value 有时是字符串、有时是数组。字符串必须包成单元素数组，
// 否则 for...of 会把 "-XX:HeapDumpPath=..." 逐字符拆开（JVM 报 "Unrecognized option: -"）。
const valueList = (entry) => (Array.isArray(entry.value) ? entry.value : [entry.value]);

const jvm = [];
for (const a of json.arguments.jvm || []) {
  if (typeof a === 'string') jvm.push(sub(a));
  else if (a.rules?.some((r) => r.action === 'allow' && (!r.os || r.os.name === 'windows'))) {
    for (const v of valueList(a)) jvm.push(sub(v));
  }
}
const gameArgs = [];
for (const a of json.arguments.game || []) {
  if (typeof a === 'string') gameArgs.push(sub(a));
}

// ---- 4. 清掉上次的自检存档（否则 createFreshLevel 会撞名）
//      MCAI_KEEP_WORLD=1 时不删：长任务（搭地狱门这种）要接着上次的存档干。
const keepWorld = process.env.MCAI_KEEP_WORLD === '1' || process.env.MCAI_KEEP_WORLD === 'true';
const saveDir = join(versionDir, 'saves', 'mcai-selftest');
if (selftest && !keepWorld && existsSync(saveDir)) {
  rmSync(saveDir, { recursive: true, force: true });
  console.log('已删除上次的自检存档');
} else if (selftest && keepWorld) {
  console.log(existsSync(saveDir) ? '保留存档模式：接着上次的存档继续' : '保留存档模式：还没有存档，会新建一个');
}

// ---- 5. 启动
const args = [
  '-Xmx3G', '-Xms512M',
  '-Dfile.encoding=UTF-8', '-Dsun.stdout.encoding=UTF-8', '-Dsun.stderr.encoding=UTF-8',
  ...(selftest
    ? [
        '-Dmcai.selftest=1',
        '-Dmcai.selftest.quitAfter=' + (process.env.MCAI_QUIT_AFTER || '420'),
        // 默认超平坦；MCAI_TERRAIN=normal 切默认地形（验证下矿这类必须真地形的能力）
        '-Dmcai.selftest.terrain=' + (process.env.MCAI_TERRAIN || 'flat'),
        // 默认开作弊（自检要发东西/召唤怪）；MCAI_CHEATS=false 验证「不作弊」
        '-Dmcai.selftest.cheats=' + (process.env.MCAI_CHEATS || 'true'),
        // 长任务：读旧存档接着玩 + 死亡不掉落（真机会被怪打死，掉光装备等于重来）
        '-Dmcai.selftest.keepWorld=' + (keepWorld ? 'true' : 'false'),
        '-Dmcai.selftest.keepInventory=' + (process.env.MCAI_KEEP_INVENTORY || 'false'),
        '-Dmcai.selftest.autoRespawn=' + (process.env.MCAI_AUTO_RESPAWN || 'true'),
      ]
    : []),
  ...jvm, json.mainClass, ...gameArgs,
];
const out = openSync(logPath, 'w');
const child = spawn(join(jdk, 'bin', 'java.exe'), args, {
  cwd: versionDir,
  stdio: ['ignore', out, out],
  windowsHide: true,
});
console.log(`已启动客户端 PID=${child.pid}`);
console.log(`日志：${logPath}`);
console.log(`游戏目录：${versionDir}`);
console.log(`自检模式：${selftest ? '开' : '关'}`);

// 前台等它退出：node 活着，游戏才不会被外层命令结束时连带清掉。
child.on('exit', (code, signal) => {
  console.log(`客户端已退出：code=${code} signal=${signal}`);
  process.exit(code ?? 0);
});
for (const sig of ['SIGINT', 'SIGTERM']) {
  process.on(sig, () => {
    try { child.kill(); } catch { /* ignore */ }
  });
}
