"""检查 Java 文件的花括号配平，并指出第一个异常位置。"""
import re
import sys

path = sys.argv[1]
with open(path, encoding='utf-8') as handle:
    lines = handle.readlines()

depth = 0
in_block_comment = False
for idx, raw in enumerate(lines, start=1):
    line = raw
    # 去掉块注释
    out = []
    i = 0
    while i < len(line):
        if in_block_comment:
            if line.startswith('*/', i):
                in_block_comment = False
                i += 2
            else:
                i += 1
            continue
        if line.startswith('/*', i):
            in_block_comment = True
            i += 2
            continue
        if line.startswith('//', i):
            break
        if line[i] == '"':
            i += 1
            while i < len(line) and line[i] != '"':
                i += 2 if line[i] == '\\' else 1
            i += 1
            continue
        if line[i] == "'":
            i += 1
            while i < len(line) and line[i] != "'":
                i += 2 if line[i] == '\\' else 1
            i += 1
            continue
        out.append(line[i])
        i += 1
    clean = ''.join(out)
    opens = clean.count('{')
    closes = clean.count('}')
    before = depth
    depth += opens - closes
    if depth < 0:
        print(f"第 {idx} 行深度变成 {depth}（之前 {before}）—— 多了一个 }}")
        print(f"  内容: {raw.rstrip()}")
        sys.exit(1)

print(f"最终深度 = {depth}")
if depth != 0:
    print("花括号不配平" if depth > 0 else "闭括号过多")
else:
    print("花括号配平")
