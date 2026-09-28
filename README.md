# 推箱子 · Sokoban（前后端分离）

一个完整的推箱子游戏：**Spring Boot REST 后端** + **Vue 3 独立前端**。

游戏内核（关卡、规则、随机无尽关卡生成、求解器）是一套不依赖任何界面框架的纯 Java 代码，
被后端直接复用；前端只负责渲染和收集输入，通过 `/api/**` 拿 JSON 快照。
两者可以各自独立构建、独立部署。

```
┌────────────────────────┐        ┌──────────────────────────────────────────┐
│  frontend/  Vue 3+Vite │  JSON  │  Spring Boot 3.4.2 (JDK 17)              │
│  只渲染 + 键盘输入      │ ←────→ │  Controller → Service → 游戏内核          │
│  （独立构建、独立部署）  │ /api   │  com.ruoyi.web   /  com.ruoyi.sokoban    │
└────────────────────────┘        └──────────────────────────────────────────┘
```

## 环境要求

| 组件 | 版本 |
|---|---|
| JDK | 17+（Spring Boot 3.4.2 要求） |
| Maven | 3.8+ |
| Node.js | 18+（开发前端时需要；只跑后端则不需要） |

## 先解决 JDK 版本问题（重要）

这台机器的**系统级 `JAVA_HOME` 指向 JDK 8**，而系统级变量会压住用户级同名变量，
所以直接在终端敲 `mvn` 会用 JDK 8 编译并失败。系统级变量需要管理员权限才能改，
本项目不碰它，改用仓库内的便捷脚本把 `JAVA_HOME` 指向 JDK 17：

```powershell
# 方式一：用脚本执行 maven（推荐）
.\mvn17.ps1 spring-boot:run             # 启动后端
.\mvn17.ps1 -DskipTests package         # 打包
.\mvn17.ps1 -v                          # 看 maven 用的哪个 JDK

# 方式二：把 JDK 17 应用到当前终端会话，之后这个终端里直接敲 mvn 就行
. .\mvn17.ps1 -Only
mvn spring-boot:run
```

如果提示「禁止运行脚本」，加 `-ExecutionPolicy Bypass`：

```powershell
powershell -ExecutionPolicy Bypass -File .\mvn17.ps1 spring-boot:run
```

JDK 17 的路径写在 `scripts/jdk.ps1` 里的 `$env:SOKOBAN_JDK_HOME`，换机器只改这一处。
脚本只修改当前进程的环境变量，不动机器上的任何全局配置，因此不会影响别的 Java 8 项目
（比如 ruoyi）。

在 IDE 里跑的话，把项目的 Project SDK 设为 JDK 17 即可。

## 一键启动（推荐）

根本不用管上面那些细节，直接：

```powershell
.\start-dev.ps1
```

或者在资源管理器里**双击 `start-dev.cmd`**。脚本会自动完成：

1. 检查 JDK 17 / Node / npm；
2. 首次运行时装前端依赖（`npm install`）；
3. 后端源码有改动就打一次 jar（已是最新则跳过）；
4. 拉起后端 `java -jar`（:8080）与前端 `vite`（:5173）；
5. 探活两端并确认 `/api` 代理连通；
6. 自动打开浏览器到 <http://localhost:5173>。

可选参数：

| 参数 | 作用 |
|---|---|
| `-NoBrowser` | 只启动，不打开浏览器 |
| `-Rebuild` | 强制重新打后端 jar |
| `-SkipInstall` | 跳过 npm install 检查 |

停止服务（双击 `stop-dev.cmd` 亦可）：

```powershell
.\stop-dev.ps1
```

日志写在 `target/dev-logs/`（`backend.log`、`frontend.log` 及其 `.err`），
启动失败时脚本会直接把日志尾部打出来；两个服务的 PID 记在 `target/dev-logs/dev-pids.txt`。

> 为什么脚本里用 `java -jar` 和 `node .../vite.js`，而不是 `mvn spring-boot:run` 和 `npm run dev`？
> 后两者都会再 fork 一层子进程，脚本拿到的 PID 不是真正干活的进程，停止时容易留下孤儿进程占着端口。

## 手动启动（想自己控制时）

### 后端

```powershell
.\mvn17.ps1 spring-boot:run
```

默认监听 <http://localhost:8080>。也可以先打包再运行：

```powershell
.\mvn17.ps1 -DskipTests package
java -jar target/test-1.0-SNAPSHOT.jar
```

### 前端

```bash
cd frontend
npm install
npm run dev
```

浏览器打开 <http://localhost:5173>。
开发服务器已把 `/api` 代理到 8080（见 `frontend/vite.config.ts`），无需处理跨域。

生产构建：

```bash
cd frontend
npm run build          # 产出 frontend/dist，是纯静态文件
```

把 `dist` 交给 Nginx 等静态服务器，并把 `/api` 反向代理到后端，前后端就能各自独立部署。

## 游戏玩法

| 按键 | 作用 |
|---|---|
| 方向键 / WASD | 移动、推箱 |
| U | 撤销一步 |
| R | 重来本关 |
| H | 提示：自动演示一条解法 |
| Esc | 返回主菜单 |

- **前 10 关**内置，可自由进出，不用通关就能去下一关。
- **第 10 关的下一关**直接进入**无尽第 1 层**；从无尽第 1 层起，必须通过当前层才能进下一层。
- **无尽模式**每 5 层地图横竖各扩大一格；第 21 层起每层固定 3 个箱子，
  难度按求解器算出的最短解步数挑选。
- 无尽关卡支持**种子复现**：界面上可以查看/复制 8 位种子，也可以输入种子重新生成同一套地图。
- 在游戏中依次按 `↑ ↓ ← → ← → ↑ ↓`（上下左右左右上下）可打开无尽模式跳关。
- **存档**共 8 个槽位（槽位 1 是自动存档槽），保存的是当前局面（玩家与箱子位置），
  读档可以接着玩，而不是回到关卡开头。

## REST 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/game/sessions` | 开一局（可带 `seedCode`、`endless`） |
| GET | `/api/game/sessions/{id}` | 读取当前局面 |
| DELETE | `/api/game/sessions/{id}` | 结束会话 |
| POST | `/api/game/sessions/{id}/moves` | 走一步 `{"dir":"UP"}` |
| POST | `/api/game/sessions/{id}/undo` | 撤销一步 |
| POST | `/api/game/sessions/{id}/reset` | 重玩本关 |
| POST | `/api/game/sessions/{id}/level` | 跳关 `{"index":2}` 或 `{"delta":1}` |
| POST | `/api/game/sessions/{id}/endless-skip` | 开关跳关作弊 |
| POST | `/api/game/sessions/{id}/hint` | 求一条解法（自动演示用） |
| GET | `/api/levels` | 内置关卡列表 |
| GET | `/api/saves` | 存档槽列表 |
| POST | `/api/saves/{slot}/from/{id}` | 把当前局面存入某槽 |
| POST | `/api/saves/{slot}/load` | 读档（返回新会话） |
| DELETE | `/api/saves/{slot}` | 删除某槽 |

错误响应统一为 `{"error": 状态码, "message": "给玩家看的说明"}`：
会话不存在 → 404，关卡未解锁 → 403，参数不合法 → 400。

## 代码结构

```
src/main/java/com/ruoyi/
  SokobanApplication.java       Spring Boot 启动类
  sokoban/                      游戏内核（纯 Java，无界面依赖）
    Level.java                    关卡定义（配对规则、目标点编号）
    Levels.java                   10 个内置关卡
    SokobanGame.java              规则、撤销、解锁门控、死局判定
    Campaign.java                 内置关卡 + 无尽关卡（按种子复现）
    EndlessGenerator.java         无尽关卡生成（保证有解 + 难度挑选）
    Solver.java                   求解器（提示用，尽量短）
    SeedCode.java / SaveManager.java / SaveSlot.java / SaveData.java
  web/                          REST 层
    GameSessionService.java       会话与存档编排，规则判定集中在这里
    GameController.java           /api/game/**
    LevelController.java          /api/levels、/api/saves
    GameMapper.java + dto/        内核对象 → 前端 DTO
    WebConfig.java                CORS
    ApiExceptionHandler.java      统一错误响应

src/test/java/com/ruoyi/sokoban/
  SokobanSelfTest.java          内核自检（无第三方依赖，直接 run main）

frontend/                       Vue 3 + Vite + TypeScript 前端
mvn17.ps1                       用 JDK 17 跑 maven 的便捷脚本（见上）
scripts/
  jdk.ps1                       本项目需要的 JDK 路径（换机器改这里）
  api-test.ps1                  后端接口实测（curl 打真实 HTTP）
  e2e-test.mjs                  走前端代理的端到端联调
  brace_check.py                改完 Java 后校验花括号是否配平（可选开发工具）
```

## 测试

```powershell
# 1) 内核自检（248 项断言）
.\mvn17.ps1 -DskipTests package
java -cp target/classes:target/test-classes com.ruoyi.sokoban.SokobanSelfTest

# 2) 后端接口实测（需要后端已启动在 8080）
powershell -ExecutionPolicy Bypass -File scripts\api-test.ps1

# 3) 端到端联调（需要后端 8080 + 前端 5173 都在跑）
node scripts\e2e-test.mjs
```
