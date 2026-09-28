# 推箱子前端（Vue 3 + Vite + TypeScript）

这是推箱子的**独立前端项目**：只负责渲染棋盘和收集键盘输入，
所有游戏规则、关卡生成、求解器都在后端（Spring Boot）里，通过 `/api/**` 的 JSON 接口交互。

## 目录结构

```
src/
  api/types.ts            与后端 dto 一一对应的类型定义
  api/client.ts           所有 REST 调用的封装（含错误文案翻译）
  composables/useGame.ts  一局游戏的前端状态：转发操作、接收快照、自动演示
  components/GameBoard.vue  棋盘渲染（墙/目标点/箱子/玩家，配对编号着色）
  components/SaveSlots.vue  存档槽列表
  App.vue                 主菜单 / 游戏 / 无尽模式 / 存读档 四个界面
```

## 开发

前端需要后端一起跑。先在仓库根目录启动后端：

```bash
mvn spring-boot:run          # 默认监听 8080
```

再启动前端：

```bash
cd frontend
npm install
npm run dev                  # 默认监听 5173
```

浏览器打开 <http://localhost:5173> 即可。

`vite.config.ts` 里已经把 `/api` 代理到 `http://127.0.0.1:8080`，
所以前端代码只写相对路径，不需要处理跨域，也不需要配置后端地址。

## 构建

```bash
npm run build                # 产出 dist/（含 vue-tsc 类型检查）
npm run preview              # 本地预览构建结果
```

构建产物是纯静态文件，可以交给 Nginx 等静态服务器托管；
只要把 `/api` 反向代理到后端，前后端就完全解耦、可以各自独立部署与扩缩容。

## 操作方式

| 按键 | 作用 |
|---|---|
| 方向键 / WASD | 移动、推箱 |
| U | 撤销一步 |
| R | 重来本关 |
| H | 提示：自动演示一条解法 |
| Esc | 返回主菜单 |

在游戏中依次按 `↑ ↓ ← → ← → ↑ ↓`（上下左右左右上下）可以打开无尽模式跳关。
