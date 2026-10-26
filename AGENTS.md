# AGENTS.md — TVBox API15 移植（海信 VIDAA_TV）

面向 AI/协作者的项目约束与已踩实的坑。**动手前先读完**。

## 1. 环境与构建（唯一正确姿势）

- 目标机：海信 LED47K600X3D，Android 4.0.4 / API15 / dalvik（**不是 ART**），1GB RAM。
- `adb` 只有 `keyevent`，**没有 `input tap` / uiautomator**；视频层 MTK 平台 `screencap` 抓不到（截图白屏 ≠ 播放失败，务必看 logcat 佐证）。
- 构建（workdir 必须是仓库根 `E:\work\tvbox\box`，否则报 `Project 'app' not found in root project`）：

```powershell
$env:JAVA_HOME='E:\work\tvbox\jdk11\jdk-11.0.2'
$env:GRADLE_USER_HOME='E:\work\tvbox\ghome'
& 'E:\work\tvbox\gradle-6.7.1\bin\gradle.bat' :app:assembleRelease --no-daemon
```

- gradle 输出是 **UTF-16**，读日志要 `encoding='utf-16'`，否则全乱码看不到 `error:`。

## 2. ★ 判断「源能不能播」必须看分段文件头，不能靠后缀

已实测的反例（这些坑都吃过）：

| 现象 | 真因 |
|---|---|
| m3u8 有 50 个分段、proxy 日志一直在拉、播放器白屏 | **百度资源的 m3u8 指向 JPEG 图片序列**（`index0.jpeg`…，首字节 `ffd8ffe0` = JPEG SOI，每段 2 秒）——伪 HLS，任何播放器都不可能出画 |
| 分段编号在推进但永远出不来 | **最大资源 CDN 只有 2.9 KB/s**，而码率 `2000k_1080` 需要 ~250 KB/s，永远追不上 |
| 片名能搜到、点进去什么都没有 | 量子/非凡/暴风/极速/光速/电影天堂/豪华返回的是 `share/xxx` 网页链接，**不是 m3u8 也不是 mp4** |
| API 返回 18 字节 / 403 | 牛牛/快车/丫丫/闪电/天涯 = 空响应；无尽/索尼 = 403 |

**正确判据**：拉第一个分段 → 看文件头
- `0x47` → 真 TS
- `ftyp` → fMP4
- `FLV` → FLV
- `ffd8ff` → JPEG（**假 HLS，不可播**）
- `89 50 4e 47` → PNG（假 HLS）

并且同时测速率：**低于 ~80 KB/s 基本等于不可播**（常见码率 2000kbps ≈ 250 KB/s）。

**当前唯一实测可播的纯 API 源 = 虎牙资源**（`https://www.huyaapi.com/api.php/provide/vod`，真 TS + AES-128，248~414 KB/s），已在 `assets/configs/api_only.json` 里排首位。

### ★ 探测源存活必须用「搜索接口」，列表接口没有判别力

`SourceHealthChecker` 第一版用列表 `ac=videolist&pg=1` 探测，**20 个源全部通过**
（HTTP 200、体积 3~50 万字节都正常）——完全测不出问题。换成搜索
`ac=detail&wd=小猪` 后立刻分出 **10 可用 / 10 失效**，与人工实测一致：

| 形态 | 站点 |
|---|---|
| 搜索接口返回 **6~57 字节空壳**（HTTP 200） | 牛牛 / 快车 / 丫丫 / 闪电 / 天涯 / 茅台 / 新浪 |
| **403** | 无尽 / 索尼 |
| 接口通但固定关键字**搜不到** | 暴风 |
| 可用 | 虎牙 / 量子 / 非凡 / 百度 / 极速 / 光速 / 电影天堂 / 最大 / 豪华 / 建安 |

判据：非 2xx → `HTTP403`；体积 < 200 → `空响应`；无 `vod_id`/`list` → `格式异常`；
耗时 > 5s → `超时`；> 2.5s → `慢Ns`；无 `vod_id` → `无此片`（**仍算可用**，只是词没资源）。

## 3b. 点播跨源自动换源（2026-10-26 新增）

直播有一整套自动救场（换源→换频道→软解兜底），点播原本只有 `autoRetry()`
（**本源内**重试 2 次 + 切播放器）——源本身不可播时永远救不回来。

链路：`PlayFragment.errorWithRetry` 重试耗尽 → post `RefreshEvent.TYPE_PLAY_FAILED`
→ `DetailActivity.onPlayFailed()` → 复用已有「快速搜索」（`tvQuickSearch.performClick()`）
→ 结果列表点一下就是 `loadDetail(video.id, video.sourceKey)` 跨源切换。

两个必须注意的点：
- **只处理 `showPreview`**（详情页内预览/全屏预览）。独立 `PlayActivity` 时不弹框，
  因为那时详情页不在前台，弹 Dialog 会错位。
- **换源必须对齐集数**：新源集数/命名常不同（还可能带全角空格）。`applyPendingSeries()`
  按「集名完全相同（去空格/全角空格）→ 集名互相包含 → 原序号（夹到范围）→ 第 0 集」
  对齐，否则换源后会从第 1 集重播。


## 3. ★ 改 `api_only.json` 只许调顺序，绝不可重编号 key

`key`（api00…api19）会作为 `sourceKey` 入库（历史记录/收藏/VodInfo 都按它关联）。
**把新站插到首位再整体重编号 → 历史记录与站点错位**（点历史记录会打开别的站的内容）。
正确做法：只改数组顺序，key 保持不变（本仓已按此把 api09 虎牙移到首位）。

## 4. ★ dalvik 严格校验静态初始化；ART 会放过

`Bogus static initialization: value type N in field type Lxxx;` → `VM aborting` → `SIGSEGV deadd00d`。

- 根因：smali 里 `.field private static final e:Ljava/util/HashMap; = ""`（对象类型字段配了 String 初值）。
- 修法：baksmali 反编译 → 删非法初值 → 在 `<clinit>` 补 `new HashMap` / `sput-object` → smali 重打包 → 替换 jar 内 `classes.dex`。
- **发版静态校验**（每次都跑）：dex 必须是 `035` 且能被 `dexdump -d` 完整解析：
  `E:\work\tvbox\sdk\build-tools\30.0.2\dexdump.exe -d <dex>`
- ⚠️ 校验脚本**不能拿 `ERROR` / `Failure` 当失败关键字**：dex 里到处是 `onError` 方法名，必然误报。要匹配 `dexdump:` / `Can't open` / `not a valid dex` 这类真正的失败输出。

## 5. 「解析配置失败」= 裸 get / 空数组越界（重要根因）

实证堆栈：

```
java.lang.IndexOutOfBoundsException: Invalid index 0, size is 0
  at com.google.gson.JsonArray.get
```

配置里 `parses` / `lives` / `rules` 是**空数组 `[]`** 时，解析仍按「至少一个元素」访问；异常被吞，用户只看到「解析配置失败」，标题已切到新站但首页空白。

**加固模式**（`DefaultConfig` 已提供，新代码一律用它们，不要再裸 `get`）：

```java
DefaultConfig.safeJsonString(obj, key, defaultVal)  // 对象/数组会 toString()，缺字段给默认值
DefaultConfig.safeJsonInt(obj, key, defaultVal)
DefaultConfig.safeJsonArray(obj, key)               // 缺失/非数组 → 空 Array（不返回 null）
DefaultConfig.safeJsonObject(obj, key)              // 缺失/非对象 → null
```

同时：取数组元素前判 `isJsonObject()`；`get(0)` 前判 `size() > 0`；`Hawk` 里存的索引（如 `LIVE_GROUP_INDEX`）使用前必须**双向**校验（`< 0 || >= size`，原来只防了 `>size-1`，负数照样越界）。

## 6. ★ 别信「已加保护」——要确认上限/判断真的被执行

判例：`EpgUtil.EPG_CACHE_LIMIT = 2000` 声明了但**从未被引用**，静态 map 照样无上限增长（假修复）。改任何"加保护"的代码，改完 `grep` 一次常量名确认真的有使用点。

## 7. 详情页遥控焦点不可靠 → 用菜单键当稳定入口

- 症状：详情页按上键，焦点在剧集网格里循环，到不了顶部按钮行，「全屏」按钮抢不到焦点。
- 根因：`mGridViewFlag`（线路栏）缺 `nextFocusUp`，TV 焦点搜索回退到网格自身。
- 修法：XML 补焦点链（`mGridViewFlag.nextFocusUp=@id/tvPlay`、`tvPlay.nextFocusDown=@id/mGridViewFlag`）+ `DetailActivity.onKeyDown` 用**菜单键直接切全屏**（`onKeyUp` 要吞掉，否则二次触发）。
- 通用口径：**凡是依赖"焦点能走到"的功能，都要给一个不依赖焦点的快捷键兜底**。

## 7b. 弹框数据刷新不要重新 show()

换源弹框里探测结果异步到达后要更新文字。**重建弹框（`dialog.show()`）会丢焦点**，
用户正用遥控器选中某一项时体验极差。正确做法：保存 adapter 引用，只
`adapter.setData(items, select)`（内部 `notifyDataSetChanged`），弹框对象不重建。

## 8. 调试技巧（省大量来回）

- 直接从外部拉起页面（绕开焦点导航）：
  ```powershell
  adb shell am start -n tv.org.eu.bunnyabc/com.github.tvbox.osc.bbox.ui.activity.DetailActivity --es id <vodId> --es sourceKey api09
  ```
  需要先起 HomeActivity 让配置加载完，否则会命中"站点不存在"。
- R8 混淆后堆栈不可读：`app/build/outputs/mapping/release/mapping.txt` 能把
  `DetailActivity.i(SourceFile:14)` 反查成 `loadDetail():849` → `SourceViewModel.getDetail():519` → `SourceBean.getType():54`。**别对着线程名/方法名猜**。
- App 内自带的 okhttp + BC TLS 栈可当探测工具：`adb forward tcp:9978 tcp:9978` 后请求
  `http://127.0.0.1:9978/proxy?go=live&type=m3u8&url=<urlencode>`，能验证"电视侧能不能拉到某个域名"。
- 电视侧 DNS 只有 `223.5.5.5`，且 App 走 DoH；`ping` 域名解析失败**不代表**网络不通。

## 9. TLS / 播放链路

- 系统栈只有 TLS1.0；https 靠 **BC 轻量栈（BcTls）接管全部 okhttp**，上限 **TLS1.2**。
  站点只支持 TLS1.3 时（如早期 `www.huyaapi.com`）电视侧会 `RecordStream.readRecord` 超时。
  排查：`ssl` 强制 `TLSVersion.TLSv1_2` 单测服务端是否可降级。
- 硬解优先：`PLAY_TYPE=0` + `M3U8_PURIFY=true`；https VOD 经 `proxifyM3u8Lines` 改 `127.0.0.1:9978`，**喂流 URL 必须带 `/index.m3u8` 后缀**（MTK format recognizer 否则 32769）。
- `proxifyM3u8Lines` 会把 `#EXT-X-KEY` 的 `URI` 一起改写成本地代理 —— **AES-128 加密 HLS 因此可用**（虎牙就是这种）。

## 10. 内存纪律（1GB 机型，卡死总根源）

- 静态可变集合**必须有上限**，且上限要真的被执行（见 §6）。
- 壁纸解码：缓存命中要**直接 return**，不要 decode 完再赋值（曾因每条 onResume 全量解大图 → `OutOfMemoryError`，点详情即崩）。
- 不要 `appExit()` 自杀进程：Activity 重建（换源/配置变化）会触发 `onDestroy`，原版 `killProcess` 导致"关 APP"；退出走 `doExit()`（双按返回）。
- `HomeActivity` 的 1 秒时钟 `mHandler.postDelayed(this, 1000)` 在 **onPause** 里 `removeCallbacksAndMessages(null)`（销毁前必经 onPause，无需在 onDestroy 重复）。

## 11. 设备侧（只读常识，别乱动）

- `pm disable-user` 被厂商保护包 `android.setting.system.protection` 拦截（rc=137）→ 只能用 `am force-stop` + 开机钩子 `/data/local/system/etc/install-recovery.sh`。
- **`com.hisensecpa.tv` = VIDAA 桌面本体，绝不能 force-stop**（曾误杀导致进不了桌面）。
- 黑屏恢复：`am force-stop com.jamdeo.tv.resources` 然后 `am start -n com.hisensecpa.tv/com.gala.video.app.epg.HomeActivity`。
- DLNA 接收端用 cling 2.1.2（jupnp 需 API21+），默认关闭（`HawkConfig.DLNA_ENABLE=false`）。
