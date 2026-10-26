#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""按根因扫描：成员级可变集合「只增不清」

背景（2026-10-26 实锤）：本仓出现过多处「第一次正常、操作一次后列表越滚越长」的 bug，
根因统一是——成员级 List/Map 在「重建 UI / 重新加载」的函数里只有 add/put，没有 clear：

  - ApiConfig.parseJson 不清 sourceBeanList   → 切源后站点累积（3→23→35）
  - ApiConfig.parseJson 不清 searchSourceBeanList → 搜索永远用旧快照
  - HomeActivity.initViewPager 不清 fragments → 栏目翻倍（6→12→18→24）
  - DetailActivity.searchData 不去重追加     → 多源搜索结果重复

用法：
    python tools/scan_member_collections.py            # 全部列出（含已确认无害的）
    python tools/scan_member_collections.py --strict   # 只输出需要人工复核的疑点

判据说明（重要，避免误报）：
  用缩进而不是花括号做解析 —— Java 代码里方法体缩进恒为 8 空格，成员声明缩进 4 空格。
  自研括号深度解析会被字符串/注释/正则里的 '{' 带偏（v1/v2 均因此全漏）。

已知「无害」形态（人工已核实，无需修改）：
  - 构造函数/单次 init 里填充的常量表（TrackInfo.addAudio、RemoteServer、Trans、SearchKeyboard）
  - 有 containsKey 守卫的按 key 幂等缓存（JarLoader.*、ApiConfig.liveChannelEpgInfoList）
  - put/remove 成对出现的交互状态（CheckboxSearchAdapter.mCheckedSources）
  - 显式去重后追加（DetailActivity.searchData、LivePlayActivity.channelGroupPasswordConfirmed）
"""
import io
import os
import re
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..',
                    'app', 'src', 'main', 'java')

COLL_RE = (r'(?:List|ArrayList|LinkedList|Map|HashMap|LinkedHashMap|Set|HashSet|'
           r'LinkedHashSet|ConcurrentHashMap|SparseArray|ArrayMap|TreeMap|Vector|'
           r'CopyOnWriteArrayList|ArrayDeque)')

FIELD_RE = re.compile(
    r'^( {4})((?:private|public|protected)\s+)?(static\s+)?(final\s+)?'
    r'(?:' + COLL_RE + r')\s*(?:<[^;]*>)?\s+(\w+)\s*(?:=[^;]*)?;\s*$'
)
METHOD_RE = re.compile(
    r'^( {4})((?:public|private|protected|static|final|synchronized|abstract|native|'
    r'strictfp|@\w+(?:\([^)]*\))?)\s+)*'
    r'[\w<>\[\],\.\?\s]+\s+(\w+)\s*\([^;]*\)?\s*(\{|throws\s+[\w\s,\.]+)?\s*$'
)
SKIP_NAMES = {'if', 'for', 'while', 'switch', 'catch', 'synchronized',
              'return', 'new', 'else'}


def scan_file(path):
    try:
        lines = io.open(path, encoding='utf-8').read().splitlines()
    except Exception:
        return []

    fields = {}
    for i, l in enumerate(lines):
        m = FIELD_RE.match(l)
        if m and not m.group(3):          # 排除 static
            fields[m.group(5)] = i
    if not fields:
        return []

    decls = []
    for i, l in enumerate(lines):
        if not l.startswith('    ') or l.startswith('     '):
            continue
        s = l.strip()
        if not s or s.startswith(('//', '*', '/*', 'return ', 'throw ', 'else', '}')):
            continue
        if '(' not in s:
            continue
        m = METHOD_RE.match(l)
        if not m:
            continue
        name = m.group(3)
        if name in SKIP_NAMES:
            continue
        if s.endswith(';') and '{' not in s:
            continue
        decls.append((i, name))
    if not decls:
        return []

    out = []
    for k, (start, name) in enumerate(decls):
        end = decls[k + 1][0] - 1 if k + 1 < len(decls) else len(lines) - 1
        txt = '\n'.join(lines[start:end + 1])
        for fname, fline in fields.items():
            add_re = re.compile(r'\b' + re.escape(fname) +
                                r'\s*\.\s*(add|addAll|put|putAll|offer|push)\s*\(')
            if not add_re.search(txt):
                continue
            clear_re = re.compile(r'\b' + re.escape(fname) +
                                  r'\s*(?:\.\s*(?:clear|removeAll|retainAll)\s*\(|=[^=])')
            out.append({
                'field': fname, 'field_line': fline + 1,
                'method': name, 'start': start + 1, 'end': end + 1,
                'has_clear': bool(clear_re.search(txt)),
            })
    return out


def main():
    strict = '--strict' in sys.argv
    rows = []
    for dp, dn, fn in os.walk(ROOT):
        for f in fn:
            if not f.endswith('.java'):
                continue
            p = os.path.join(dp, f)
            for r in scan_file(p):
                r['file'] = os.path.relpath(p, ROOT).replace('\\', '/')
                rows.append(r)

    bad = [r for r in rows if not r['has_clear']]
    ok = [r for r in rows if r['has_clear']]

    print('=== 成员集合 add/put 但同方法内无 clear：%d 处 ===' % len(bad))
    print('（多数是构造/单次初始化/带守卫的幂等缓存，需人工判定；'
          '重点关注「重建 UI 或重新加载」类方法）')
    print()
    print('%-50s %-20s %-26s %s' % ('文件', '成员字段', '方法', '行范围'))
    print('-' * 120)
    for r in sorted(bad, key=lambda x: (x['file'], x['field'])):
        print('%-50s %-20s %-26s L%d-%d' % (
            r['file'], '%s@%d' % (r['field'], r['field_line']),
            r['method'], r['start'], r['end']))

    if not strict:
        print()
        print('=== 已有 clear（参考，确认这些是正确写法）：%d 处 ===' % len(ok))
        for r in sorted(ok, key=lambda x: (x['file'], x['field'])):
            print('  %-50s %-20s %s' % (r['file'], r['field'], r['method']))

    return 0


if __name__ == '__main__':
    sys.exit(main())
