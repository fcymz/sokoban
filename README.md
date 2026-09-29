# 推箱子 · Sokoban

一个可以直接玩的中文推箱子游戏：**10 个内置关卡 + 无限随机关卡**，
架构是前后端分离的 —— **Spring Boot REST 后端** + **Vue 3 独立前端**。

游戏的全部规则（能不能推、算不算通关、关卡有没有解锁、怎么生成随机关卡、
怎么求解）都写在后端的一套纯 Java 内核里，前端只做两件事：**把快照画出来**、
**把按键发回去**。所以后端可以脱离前端单独用接口跑通一局，前端也可以单独构建成静态文件。

```
┌──────────────────────────┐   JSON    ┌───────────────────────────────────────────┐
│  frontend/               │  /api/**  │  Spring Boot 3.4.2（JDK 17，:8080）        │
│  Vue 3 + Vite + TS       │ ←───────→ │  web/  REST 层                             │
│  只渲染 + 收键盘（:5173） │           │  sokoban/  游戏内核（纯 Java，无框架依赖）  │
└──────────────────────────┘           └───────────────────────────────────────────┘
```

## 目录

- [特性](#特性)
- [技术栈](#技术栈)
- [架构](#架构)
- [快速开始](#快速开始)
- [配置](#配置)
- [玩法](#玩法)
- [REST API](#rest-api)
- [项目结构](#项目结构)
- [测试](#测试)
- [开发辅助脚本](#开发辅助脚本)
- [常见问题](#常见问题)
- [已知限制](#已知限制)

## 特性

**游戏内容**

- 10 个内置关卡，可自由进出，不需要按顺序通关。
- 第 10 关之后接**无尽模式**：种子随机关卡，**保证有解**；每 5 层地图横竖各扩 1 格
  （10×9 起，上限 24×24），第 21 层起每层固定 3 个箱子。
- 无尽关卡支持**种子复现**：种子是 8 位编码（`XXXX-XXXX`，40 位），
  同一个种子永远生成同一套地图；界面上可查看 / 复制 / 手输种子。
- 关卡难度按**求解器算出的最短解步数**挑选，而不是按解法数量 —— 3 箱随机迷宫的解法数量
  根本数不完，用步数才是可靠指标。

**操作与反馈**

- 键盘操作（方向键 / WASD）、撤销、重来、提示。
- **提示**会返回一条完整解法并在界面上自动演示，中途按任意方向键即可打断。
- **死局检测**：已经不可能通关时立刻提示，避免玩家白推。
- **隐藏的跳关作弊**：游戏中依次按 `↑ ↓ ← → ← → ↑ ↓` 打开无尽模式跳关。
- **存档 / 读档**：8 个槽位，存的是「当前局面」的玩家与箱子位置，读档能接着玩。
- **自动存档槽（1 号）**：无尽模式每次进入新层、以及关标签页时由系统写入，
  **不接受手动存档**（否则下一次自动存档会把它悄悄覆盖掉）；想把某一份留住，
  在存档界面点「转存到…」搬到其它槽位即可，自动槽位本身保持不动。
- **退出与关标签页**：主菜单的「退出」直接关掉标签页；直接关标签页（或刷新）时，
  **没有没存过的改动就直接关，有就先弹浏览器的确认**，确认离开前还会用 `sendBeacon`
  把当前局面兜底存进自动槽位，所以关标签页也不会白推。
- 返回主菜单时，只有存在还没存过的改动才会提示（刚存过档就直接走）。
- 无尽模式必须通过当前层才能进下一层。

## 技术栈

| 层 | 选型 | 说明 |
|---|---|---|
| 后端 | Spring Boot 3.4.2 / Java 17 | `spring-boot-starter-web`；打包成可执行 jar |
| 游戏内核 | 纯 Java 17，无第三方依赖 | 不引用任何 Spring / 界面类，可单独测试 |
| 前端 | Vue 3.5 + Vite 8 + TypeScript 6 | 单页应用，无 UI 组件库，样式为手写 CSS |
| 通信 | REST + JSON | 空字段不序列化（`default-property-inclusion: non_null`） |
| 测试 | 自研无依赖 `main` 自检 + curl 接口实测 + Node 端到端 | 见[测试](#测试) |

## 架构

### 分层与依赖方向

依赖是**单向**的，内核不知道 web 的存在：

```
        ┌──────────────────────────────────────────────┐
        │  web/        Controller → Service → Mapper    │
        │              （会话管理、DTO 转换、错误响应）  │
        └───────────────────┬──────────────────────────┘
                            │ 只能向下依赖
        ┌───────────────────▼──────────────────────────┐
        │  sokoban/    规则 / 关卡 / 生成器 / 求解器     │
        │              （纯 Java，不认识 HTTP 和 Spring）│
        └──────────────────────────────────────────────┘
```

- `sokoban/` 只暴露普通 Java API，`SokobanSelfTest` 直接 `main` 就能跑，不需要起 Spring。
- `web/` 负责把内核对象翻译成 DTO（`GameMapper`），并统一错误响应（`ApiExceptionHandler`）。

### 一次操作的全链路

以「按一下方向键」为例：

1. 前端 `useGame.step('LEFT')` → `POST /api/game/sessions/{id}/moves {"dir":"LEFT"}`；
2. `GameController` 把请求交给 `GameSessionService`；
3. 服务按 `sessionId` 取出内存里的 `SokobanGame`，调用内核走这一步
   （能不能走、是不是推箱、有没有通关、有没有死局，全在这里判定）；
4. `GameMapper.toState(...)` 把内核状态拍成 `GameStateDto`；
5. 前端拿到快照整体替换本地状态，界面重绘。

### 会话模型

- 一局游戏 = 一个**服务端会话**，存在 `GameSessionService` 的 `ConcurrentHashMap` 里，重启即清空。
- 前端不保存任何游戏状态，只保存「后端上一次返回了什么」。
- 好处：规则只有一份实现，前端不可能和服务端算得不一样；代价是每步都要一次 HTTP
  （本地局域网/本机无感）。

## 快速开始

### 环境要求

| 组件 | 版本 |
|---|---|
| JDK | 17 或更高（Spring Boot 3.4 要求） |
| Maven | 3.6+ |
| Node.js | `^20.19.0` 或 `>=22.12.0`（只有开发前端时需要，Vite 8 的硬性要求） |

### 关于本机的 JDK 8 冲突（容易踩）

如果机器上**系统级 `JAVA_HOME` 指向 JDK 8**，直接敲 `mvn` 会用 JDK 8 编译并失败
（系统级变量会压住用户级同名变量，且需要管理员权限才能改）。

本项目不去动全局配置，改用仓库里的脚本把 JDK 17 只应用到当前进程：

```powershell
.\mvn17.ps1 -v                  # 确认 maven 用的是哪个 JDK
.\mvn17.ps1 -DskipTests package # 用 JDK 17 打包
.\mvn17.ps1 spring-boot:run     # 用 JDK 17 起后端

# 想让当前这个终端后面直接敲 mvn：把环境变量导进来
. .\mvn17.ps1 -Only
mvn -v
```

JDK 17 的路径集中在 `scripts/jdk.ps1` 的 `$env:SOKOBAN_JDK_HOME`，换机器只改这一行。
脚本只改当前进程的环境变量，所以不会影响机器上别的 Java 8 项目。
IDE 里只要把 Project SDK 设成 JDK 17 即可（`.idea/misc.xml` 已经指向 17）。

> 提示「禁止运行脚本」时，用
> `powershell -ExecutionPolicy Bypass -File .\mvn17.ps1 package`。

### 方式一：一键启动（推荐）

```powershell
.\start-dev.ps1
```

或者直接在资源管理器里**双击 `start-dev.cmd`**。脚本会依次：

1. 检查 JDK 17 / Node / npm；
2. 首次运行时装前端依赖（`npm install`）；
3. 后端源码有改动就重新打一次 jar（已是最新则跳过）；
4. 拉起后端 `java -jar`（`:8080`）和前端 `vite`（`:5173`）；
5. 探活两端，并确认前端 `/api` 代理能通到后端；
6. 打开浏览器 <http://localhost:5173>。

| 参数 | 作用 |
|---|---|
| `-NoBrowser` | 只启动，不打开浏览器 |
| `-Rebuild` | 强制重新打后端 jar |
| `-SkipInstall` | 跳过 `npm install` 检查 |

停止（双击 `stop-dev.cmd` 亦可）：

```powershell
.\stop-dev.ps1
```

日志在 `target/dev-logs/`（`backend.log`、`frontend.log` 及对应 `.err`），
启动失败时脚本会直接打印日志尾部；两个服务的 PID 记在 `target/dev-logs/dev-pids.txt`。

> **为什么脚本用 `java -jar` 和 `node .../vite.js`，而不是 `mvn spring-boot:run` 与 `npm run dev`？**
> 后两者都会再 fork 一层子进程，脚本拿到的 PID 不是真正在干活的进程，
> 停止时容易留下孤儿进程占着端口，下次启动就会失败。

### 方式二：手动启动

后端（两种等价写法）：

```powershell
.\mvn17.ps1 spring-boot:run

# 或者先打包再运行
.\mvn17.ps1 -DskipTests package
java -jar target/test-1.0-SNAPSHOT.jar
```

前端：

```bash
cd frontend
npm install
npm run dev          # http://localhost:5173
```

`frontend/vite.config.ts` 已经把 `/api` 代理到 `127.0.0.1:8080`，
所以前端代码里只写相对路径 `/api/...`，开发时不需要处理跨域。

### 生产构建与部署

```powershell
# 后端：产出可执行 jar
.\mvn17.ps1 -DskipTests package      # -> target/test-1.0-SNAPSHOT.jar

# 前端：产出纯静态文件
cd frontend
npm run build                        # -> frontend/dist/
```

把 `dist/` 交给 Nginx 之类的静态服务器，再把 `/api` 反向代理到后端 8080，
前后端就各自独立部署了（`WebConfig` 里的 CORS 允许 `localhost` 任意端口，
生产同源部署时也用不到它）。

## 配置

| 配置项 | 位置 | 默认值 |
|---|---|---|
| 后端端口 | `src/main/resources/application.yml` → `server.port` | `8080` |
| 前端端口 / 代理目标 | `frontend/vite.config.ts` | `5173` → `http://127.0.0.1:8080` |
| JDK 17 路径 | `scripts/jdk.ps1` → `SOKOBAN_JDK_HOME` | 本机 JDK 17 安装目录 |
| 关卡解锁进度 | `~/.sokoban-save.properties` | 键 `unlocked`（最高可进关卡下标） |
| 存档槽 | `~/.sokoban-saves/slot-N.properties` | `N = 0…7`，每槽一个文件；`0` 是自动存档槽 |

存档读写**全部静默失败**：目录只读、磁盘写不进去时，游戏只是「记不住进度」，
不会报错也不会刷日志。旧版本存档里的 `best.*` 键会被自动忽略，且不再被写回。

## 玩法

| 按键 | 作用 |
|---|---|
| `↑ ↓ ← →` / `W A S D` | 移动、推箱子 |
| `U` | 撤销一步 |
| `R` | 重来本关 |
| `H` | 提示：自动演示一条解法（按方向键可打断） |
| `Esc` | 返回主菜单 |

规则要点：

- 内置 10 关（下标 `0–9`）可以自由进出，随时点「上一关 / 下一关」。
- 第 10 关的下一关直接进入**无尽第 1 层**（下标 `10`）；此后必须**通过当前层**才能进下一层，
  或使用跳关作弊。
- 无尽模式每 5 层地图横竖各扩 1 格；第 21 层起每层固定 3 个箱子，并按最短解步数挑难度。
- 无尽关卡一律启用「箱子与目标点一一对应」：**每个箱子必须停在自己的目标点上**才算通关，
  箱子压在别的目标点上不算（内置关卡沿用经典规则，压任意目标点即可）。
- 界面默认**不显示种子**，需要时点「查看种子」展开并可一键复制。
- **存档与退出的关系**：后端每次都会在快照里带上 `unsaved`（当前局面有没有改动还没存过）。
  在无尽模式下进入新层会自动存档，所以推进层数之后直接返回主菜单不会被打断；
  只有「推了几步还没存」时才提示。
- **退出行为**：主菜单的「退出」等价于关掉这个标签页；直接在浏览器里关标签页时，
  只有 `unsaved` 为真才会拦一下。浏览器不允许页面自定义关闭时的对话框，
  所以那一下用的是浏览器自带的「离开此网站？」确认框；点「离开」之后，
  页面会在 `pagehide` 里用 `navigator.sendBeacon` 把局面存进自动槽位。

## REST API

基地址：开发环境 `http://localhost:8080`，也可以走前端代理 `http://localhost:5173`。
所有请求与响应都是 JSON（UTF-8）。

### 通用约定

- 会话用 `sessionId`（UUID 字符串）标识，除了 `POST /api/game/sessions` 之外，
  所有游戏接口都要带上它。
- 每个会改变局面的接口都**返回完整快照**，前端整体替换即可，不需要自己推演。
- 错误响应统一为：

  ```json
  { "error": 400, "message": "槽位编号必须在 0 ~ 7 之间" }
  ```

  | 状态码 | 触发场景 |
  |---|---|
  | `400` | 参数不合法：方向名、种子格式、槽位号、请求体不是合法 JSON、路径参数类型不对 |
  | `403` | 关卡未解锁（无尽模式想跳到还没打到的层） |
  | `404` | 会话不存在，或路径不存在 |
  | `405` | 请求方法不对（例如 GET 了一个只支持 POST 的接口） |
  | `500` | 兜底，表示服务端有 bug；响应体只给异常类型和消息，不暴露堆栈 |

### 接口一览

| 方法 | 路径 | 请求体 | 说明 |
|---|---|---|---|
| POST | `/api/game/sessions` | `{"seedCode":"7K3M-9QPZ","endless":true}`（可空） | 开一局；`seedCode` 留空即随机，`endless:true` 直接从无尽第 1 层开始 |
| GET | `/api/game/sessions/{id}` | — | 读取当前局面 |
| DELETE | `/api/game/sessions/{id}` | — | 结束会话，返回 `204` |
| POST | `/api/game/sessions/{id}/moves` | `{"dir":"UP"}` | 走一步（`UP`/`DOWN`/`LEFT`/`RIGHT`） |
| POST | `/api/game/sessions/{id}/undo` | — | 撤销一步 |
| POST | `/api/game/sessions/{id}/reset` | — | 重玩本关 |
| POST | `/api/game/sessions/{id}/level` | `{"index":2}` 或 `{"delta":1}` | 载入指定关卡 / 相对跳关 |
| POST | `/api/game/sessions/{id}/endless-skip` | `{"unlocked":true}` | 开关无尽跳关作弊 |
| POST | `/api/game/sessions/{id}/hint` | — | 求一条解法（返回步骤 + 快照） |
| GET | `/api/levels` | — | 内置关卡列表（10 条） |
| GET | `/api/saves` | — | 存档槽列表（8 条） |
| POST | `/api/saves/{slot}/from/{id}` | — | 把当前局面存进 `slot`；`slot` 为 `0`（自动槽）时返回 `400` |
| POST | `/api/saves/auto/from/{id}` | — | 把当前局面写进自动槽位（关标签页兜底存档走这里） |
| POST | `/api/saves/{from}/copy/{to}` | — | 把 `from` 的存档转存到 `to`；`to` 为 `0` 时返回 `400` |
| POST | `/api/saves/{slot}/load` | — | 读档，返回**新会话**的快照 |
| DELETE | `/api/saves/{slot}` | — | 删除某槽 |

### 数据模型

`GameStateDto`（绝大多数接口的返回值）：

| 字段 | 类型 | 含义 |
|---|---|---|
| `sessionId` | string | 会话编号 |
| `level` | object | 棋盘：`width`/`height`/`walls`/`goals`/`paired`/`goalLabels`/`title`/`name` |
| `levelIndex` | int | 关卡下标（从 0 开始） |
| `builtInCount` | int | 内置关卡数量；`levelIndex >= 它` 即为无尽模式 |
| `endless` / `endlessNumber` | bool / int | 是否无尽模式；无尽层号（从 1 开始） |
| `shortProgress` | string | 进度短文本，如 `3/10`、`无尽 12` |
| `player` | int | 玩家所在格子（一维下标，`y * width + x`） |
| `boxes` | int[] | 各箱子位置，顺序与箱子身份一致 |
| `boxLabels` | int[] | 每个箱子的配对编号（从 1 开始；未配对模式为空数组） |
| `boxOnTarget` | bool[] | 每个箱子是否已停在自己的目标点上 |
| `facing` | string | 玩家朝向 |
| `steps` / `pushes` / `undoCount` | int | 已走步数 / 推箱次数 / 还能撤销多少步 |
| `won` / `deadlocked` | bool | 是否通关 / 是否已确认死局 |
| `maxUnlocked` | int | 已解锁的最高关卡下标 |
| `canAdvance` | bool | 当前是否允许进入下一关 |
| `endlessSkip` | bool | 跳关作弊是否打开 |
| `unsaved` | bool | 当前局面是否有改动还没存进任何槽位；前端退出时是否提示就看它 |
| `seedCode` | string | 种子展示码（`XXXX-XXXX`） |

`POST /hint` 返回 `HintDto`：`plan`（方向数组）、`total`（解法总步数）、
`message`（可能为空的说明）、`state`（求完提示后的快照）。

坐标统一用**一维下标**，前端按 `width` 换算行列；这样两端不会各算一套坐标而对不上。

### 调用示例

```powershell
$base = 'http://localhost:8080'

# 开一局
$s = Invoke-RestMethod -Method Post -Uri "$base/api/game/sessions" `
     -ContentType 'application/json' -Body '{}'
$s.sessionId            # 会话编号
$s.level.title          # 第 1 关 · 入门

# 求一条解法
$h = Invoke-RestMethod -Method Post -Uri "$base/api/game/sessions/$($s.sessionId)/hint"
$h.plan -join ' '       # LEFT LEFT UP RIGHT RIGHT

# 走一步
Invoke-RestMethod -Method Post -Uri "$base/api/game/sessions/$($s.sessionId)/moves" `
     -ContentType 'application/json' -Body '{"dir":"LEFT"}'

# 存档 / 读档
Invoke-RestMethod -Method Post -Uri "$base/api/saves/2/from/$($s.sessionId)"
Invoke-RestMethod -Method Post -Uri "$base/api/saves/2/load"     # 返回新会话

# 结束会话
Invoke-RestMethod -Method Delete -Uri "$base/api/game/sessions/$($s.sessionId)"
```

用 `curl` 时注意 Windows 的 PowerShell 会吃掉内层引号，建议把请求体写进文件再 `--data-binary @file`，
或者直接用前端开发服务器代理：`http://localhost:5173/api/levels`。

## 项目结构

```
src/main/java/com/ruoyi/
  SokobanApplication.java      Spring Boot 启动类
  sokoban/                     游戏内核（纯 Java，不依赖 Spring 与界面）
    Level.java                   关卡定义：墙壁/目标点/箱子、配对规则、目标点编号
    Levels.java                  10 个内置关卡数据
    SokobanGame.java             规则：移动、推箱、撤销、通关判定、解锁门控、死局判定
    Campaign.java                关卡来源 = 内置关卡 + 无尽关卡（按种子复现）
    EndlessGenerator.java        无尽关卡生成：保证有解 + 按最短解步数挑难度
    Solver.java                  求解器：小图走子级 BFS（真正最短步数），大图推箱级 A*
    SeedCode.java                种子编解码（8 位，40 bit）
    SaveManager.java / SaveSlot.java   8 个存档槽的读写
    SaveData.java                关卡解锁进度（只记进度，不记成绩）
  web/                         REST 层
    GameController.java          /api/game/**（会话与操作）
    LevelController.java         /api/levels、/api/saves
    GameSessionService.java      会话编排 + 规则调用 + 存档，业务入口在这里
    GameMapper.java              内核对象 → DTO
    WebConfig.java               CORS
    ApiExceptionHandler.java     统一错误响应
    dto/                         对外数据结构（record）

src/main/resources/application.yml   端口、JSON 序列化、日志级别
src/test/java/com/ruoyi/sokoban/
  SokobanSelfTest.java         内核自检，无第三方依赖，直接 run main

frontend/                      Vue 3 + Vite + TypeScript 前端（详见 frontend/README.md）
  src/api/types.ts               与后端 DTO 一一对应的类型
  src/api/client.ts              所有 REST 调用的封装（含错误文案翻译）
  src/composables/useGame.ts     一局游戏的前端状态：转发操作、接收快照、自动演示
  src/components/GameBoard.vue   棋盘渲染（墙 / 目标点 / 箱子 / 玩家，配对编号着色）
  src/components/SaveSlots.vue   存档槽列表
  src/App.vue                    主菜单 / 无尽模式 / 游戏 / 存读档 四个界面
  vite.config.ts                 端口与 /api 代理

mvn17.ps1 / start-dev.ps1 / stop-dev.ps1（+ .cmd 双击版）   便捷脚本，见上
scripts/
  jdk.ps1                       本项目用的 JDK 路径（换机器改这里）
  api-test.ps1                  后端接口实测（真打 HTTP，70 项断言）
  e2e-test.mjs                  走前端代理的端到端联调（31 项断言）
  brace_check.py                改完 Java 后校验花括号是否配平（可选开发工具）
  audit_public_api.py           核实内核 public 方法有没有人调用（可选开发工具）
```

## 测试

三个层次，覆盖内核 → 接口 → 全链路：

```powershell
# 1) 内核自检：241 项断言（这里的 java 也必须用 JDK 17）
.\mvn17.ps1 -DskipTests package
java -cp target/classes:target/test-classes com.ruoyi.sokoban.SokobanSelfTest

# 2) 后端接口实测：70 项断言（需要后端已启动在 8080）
powershell -ExecutionPolicy Bypass -File scripts\api-test.ps1

# 3) 端到端联调：31 项断言（需要后端 8080 + 前端 5173 都在跑）
node scripts\e2e-test.mjs
```

三者都不依赖第三方测试框架：内核自检是普通的 `main`（失败计数非 0 即退出码非 0），
接口实测用 `curl.exe`，端到端用 Node 原生 `fetch`。

后两个会真的读写存档槽位（包括无尽模式的自动槽位），所以它们**会先把
`~/.sokoban-saves` 整个目录备份到临时目录，跑完再原样还原** —— 测试不会冲掉你自己的存档。

自检覆盖的内容包括：关卡数据、可解性、游戏规则与撤销、解锁门控、存档与旧存档兼容、
求解器与提示接续、无尽模式的尺寸扩张、**每张生成的关卡都真的能通关**、
以及「21 层之后箱子不少于 3 个且不是水关」。

## 开发辅助脚本

| 脚本 | 用途 |
|---|---|
| `.\mvn17.ps1 …` | 用 JDK 17 跑任意 maven 命令 |
| `.\start-dev.ps1` / `.\stop-dev.ps1` | 一键启停前后端 |
| `scripts\brace_check.py` | 批量改 Java 后确认花括号配平 |
| `scripts\audit_public_api.py` | 列出内核 public 方法分别在 web / 包内 / 测试 / 无引用的情况，用来找死代码 |

`mvn17.ps1` 会把参数原样透传给 maven，所以 `-D`、`-B`、`-q` 之类都能直接用。

## 常见问题

**Q：`mvn` 报 `invalid target release: 17` 或 class file version 错误？**
系统 `JAVA_HOME` 是 JDK 8。用 `.\mvn17.ps1` 代替 `mvn`；
直接跑自检时也要用 JDK 17 的 `java`，否则会看到
`UnsupportedClassVersionError: class file version 61.0`。

**Q：`start-dev.ps1` 提示端口被占用？**
上次没退干净。直接跑 `.\stop-dev.ps1` 即可 —— 它会同时清理 PID 记录和
监听 8080/5173 的残留进程（实测约 1 秒）。

**Q：前端起来了，但页面报接口错误？**
先看 `target/dev-logs/backend.log`；确认后端在 `8080` 上活着，
并且 `curl http://127.0.0.1:5173/api/levels` 能返回 JSON（说明 Vite 代理是通的）。

**Q：为什么前端不自己算规则？**
规则只有一份实现（后端内核），前端无法与后端算出不同结果；代价是每步一次 HTTP 请求。

**Q：为什么有时退出会问我「还没有存档」，有时又直接退出？**
看后端的 `unsaved` 标记：只要有改动没落到任何一个槽位里就会提示。手动存档、
无尽模式进入新层触发的自动存档都会把它清掉，所以刚存过档再退出不会被多问一句。

**Q：点了「退出」标签页没关掉？**
浏览器只允许页面关闭「由脚本打开的窗口」以及「历史记录只有一条的标签页」。
本站是单页应用、不做前端路由跳转，正常从地址栏或 `start-dev.ps1` 打开时是可以自己关掉的；
如果浏览器仍然拒绝（例如这个标签页里已经有别的历史记录），界面会给出提示，
这时点标签页上的 × 或按 `Ctrl+W` 即可。

**Q：直接关标签页时我的进度会丢吗？**
不会。`unsaved` 为真时浏览器会先弹一个确认框，而在你确认离开的瞬间，
页面会通过 `navigator.sendBeacon` 把当前局面写进 1 号自动槽位，
下次进来用「继续游戏」就能接着玩。

**Q：为什么 1 号槽位点不了「存入此档」？**
它是自动存档槽，由系统在「无尽模式进入新层」和「关标签页兜底」时写入。
如果允许手动存入，玩家存进去的内容会被下一次自动存档悄悄覆盖掉，
所以手动存档只能选 2~8 号槽位。要把自动存档留住，用「转存到…」搬到别的槽位。

**Q：为什么把「每关最佳步数」删掉了？**
无尽关卡是按种子随机生成的，同一个「无尽第 21 层」在不同种子下是两张完全不同的地图，
跨种子比较步数没有意义，所以这个指标整体下线，存档里只保留解锁进度。

**Q：种子有什么用？**
它是一个 8 位、40 bit 的编码（字母表 `0-9A-Z` 去掉易混的 `I L O U`）。
同一个种子永远生成同一套无尽关卡，用来复现某张地图或跟别人分享。

## 已知限制

- 会话保存在内存里，后端重启后**正在玩的那一局**会丢失（存档槽不受影响）。
- 存档槽编号是 `0–7`，界面上显示为「槽位 1–8」。1 号槽是**自动存档槽**：
  目前只在「无尽模式进入新层」和「关标签页兜底」时写入，既不能手动存入、也不能作为转存目标；
  想长期保留就点「转存到…」搬到 2~8 号槽位。
- 求解器有状态上限（提示 40 万状态、生成期测量 15 万状态）。极高层的超大迷宫
  可能求不出解，此时提示会返回说明文字而不是步骤。
- 没有用户体系：解锁进度和存档按操作系统用户（`user.home`）区分。
