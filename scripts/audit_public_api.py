"""核实 sokoban 包里每个 public 成员是否真的有人调用。

与上一版的区别：只统计「项目源码」里的调用点（排除 node_modules / target），
并且把定义行本身排除，避免把声明当成调用；同时报告调用来自哪个文件，
方便判断是「生产路径」还是「仅自检」。

用法：python scripts/audit_public_api.py
"""
import re
import os
import glob

ROOT = '.'
SKIP_DIRS = ('node_modules', 'target', '.git', 'dist')


def java_files():
    out = []
    for base, dirs, files in os.walk(ROOT):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        for name in files:
            if name.endswith('.java'):
                # 统一成 / 分隔并去掉 os.walk('.') 带来的 ./ 前缀，
                # 这样键和 glob('src/main/...') 的写法一致
                rel = os.path.join(base, name).replace('\\', '/')
                if rel.startswith('./'):
                    rel = rel[2:]
                out.append(rel)
    return out


FILES = java_files()
SOURCES = {}
for path in FILES:
    with open(path, encoding='utf-8') as handle:
        SOURCES[path] = handle.read().splitlines()

SIG = re.compile(
    r'^\s+public\s+(?:static\s+)?(?:final\s+)?[\w<>\[\],.\s]+\s+(\w+)\s*\(')
SKIP_NAMES = {'toString', 'equals', 'hashCode', 'main', 'values', 'ordinal'}


def call_sites(name, defining_file, defining_line):
    """找出所有调用点，排除定义行本身。"""
    pattern = re.compile(r'(?<![\w.])' + re.escape(name) + r'\s*\(')
    # 同时匹配 类名.方法名( 形式
    pattern2 = re.compile(r'\.' + re.escape(name) + r'\s*\(')
    hits = []
    for path, lines in SOURCES.items():
        for idx, line in enumerate(lines, start=1):
            if path == defining_file and idx == defining_line:
                continue
            if line.strip().startswith('*') or line.strip().startswith('//'):
                continue
            if pattern.search(line) or pattern2.search(line):
                hits.append((path, idx, line.strip()))
    return hits


PKG = 'src/main/java/com/ruoyi/sokoban'
report = {}
for raw_path in sorted(glob.glob(PKG + '/*.java')):
    path = raw_path.replace('\\', '/')
    cls = os.path.basename(path)[:-5]
    lines = SOURCES[path]
    entries = []
    for idx, line in enumerate(lines, start=1):
        m = SIG.match(line)
        if not m:
            continue
        name = m.group(1)
        if name in SKIP_NAMES or name == cls:
            continue
        entries.append((name, idx))
    if not entries:
        continue
    rows = []
    for name, line in entries:
        hits = call_sites(name, path, line)
        prod = [h for h in hits if '/web/' in h[0].replace('\\', '/')]
        own = [h for h in hits if h[0] != path
               and '/web/' not in h[0].replace('\\', '/') and '/test/' not in h[0].replace('\\', '/')]
        test = [h for h in hits if '/test/' in h[0].replace('\\', '/')]
        rows.append((name, line, prod, own, test))
    report[cls] = rows

print('=' * 78)
dead_total = 0
for cls, rows in report.items():
    dead = [r for r in rows if not r[2] and not r[3] and not r[4]]
    only_test = [r for r in rows if not r[2] and not r[3] and r[4]]
    inner_only = [r for r in rows if not r[2] and r[3]]
    print(f'\n{cls}.java  （public 方法 {len(rows)} 个）')
    if inner_only:
        print('  仅包内调用（生产内部用，未直接暴露给 web）：'
              + ', '.join(f'{n}' for n, _, _, _, _ in inner_only))
    if only_test:
        print('  仅自检调用：' + ', '.join(f'{n}' for n, _, _, _, _ in only_test))
    if dead:
        print('  >> 完全无调用：' + ', '.join(f'{n}(L{l})' for n, l, _, _, _ in dead))
        dead_total += len(dead)
    if not inner_only and not only_test and not dead:
        print('  全部被 web 层直接调用')
print('\n' + '=' * 78)
print(f'完全无调用的 public 方法共 {dead_total} 个')
