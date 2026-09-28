<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import * as api from './api/client'
import type { Direction, LevelInfo, SaveSlot } from './api/types'
import GameBoard from './components/GameBoard.vue'
import SaveSlots from './components/SaveSlots.vue'
import { useGame } from './composables/useGame'

/**
 * 应用外壳：主菜单 / 游戏 / 无尽模式 / 存读档 四个界面。
 *
 * 界面切换只在前端做，游戏状态始终以后端返回的快照为准。
 */
type Screen = 'menu' | 'endless' | 'game' | 'slots'

const game = useGame()

const screen = ref<Screen>('menu')
const slots = ref<SaveSlot[]>([])
const levels = ref<LevelInfo[]>([])
const seedInput = ref('')
const notice = ref('')
/** 从存档页返回时该回到哪个界面 */
const slotsReturn = ref<Screen>('menu')
/** 当前的提示/状态文案 */
const status = computed(() => game.message.value)

/** 无尽模式跳关作弊的输入缓冲（上下左右左右上下）。 */
const CHEAT: Direction[] = ['UP', 'DOWN', 'LEFT', 'RIGHT', 'LEFT', 'RIGHT', 'UP', 'DOWN']
const cheatBuffer: Direction[] = []

onMounted(async () => {
  try {
    levels.value = await api.listLevels()
    slots.value = await api.listSaves()
  } catch (e) {
    notice.value = e instanceof Error ? e.message : String(e)
  }
  window.addEventListener('keydown', onKeyDown)
})

onUnmounted(() => {
  window.removeEventListener('keydown', onKeyDown)
})

/** 键盘操作：方向键/WASD 移动，U 撤销，R 重来，H 提示，Esc 返回。 */
function onKeyDown(event: KeyboardEvent) {
  if (screen.value === 'slots') {
    if (event.key === 'Escape') {
      leaveSlots()
    }
    return
  }
  if (screen.value !== 'game') {
    return
  }
  const map: Record<string, Direction> = {
    ArrowUp: 'UP',
    ArrowDown: 'DOWN',
    ArrowLeft: 'LEFT',
    ArrowRight: 'RIGHT',
    w: 'UP',
    s: 'DOWN',
    a: 'LEFT',
    d: 'RIGHT',
    W: 'UP',
    S: 'DOWN',
    A: 'LEFT',
    D: 'RIGHT',
  }
  const dir = map[event.key]
  if (dir) {
    event.preventDefault()
    if (game.autoPlaying.value) {
      game.stopAutoPlay()
      game.setMessage('已中断自动演示')
      return
    }
    checkCheat(dir)
    void game.step(dir)
    return
  }
  if (event.key === 'u' || event.key === 'U') {
    void game.undoStep()
  } else if (event.key === 'r' || event.key === 'R') {
    void game.restart()
  } else if (event.key === 'h' || event.key === 'H') {
    void game.showHint()
  } else if (event.key === 'Escape') {
    void backToMenu()
  }
}

/** 上下左右左右上下：打开无尽模式跳关。 */
function checkCheat(dir: Direction) {
  cheatBuffer.push(dir)
  if (cheatBuffer.length > CHEAT.length) {
    cheatBuffer.shift()
  }
  if (cheatBuffer.length === CHEAT.length
    && cheatBuffer.every((value, i) => value === CHEAT[i])) {
    cheatBuffer.length = 0
    if (!game.state.value?.endlessSkip) {
      void game.toggleEndlessSkip()
    }
  }
}

async function refreshSlots() {
  try {
    slots.value = await api.listSaves()
  } catch (e) {
    notice.value = e instanceof Error ? e.message : String(e)
  }
}

/** 当前会话里“这一局有没有改动过还没存”。 */
function hasUnsavedProgress(): boolean {
  const s = game.state.value
  return Boolean(s && !s.won && s.steps > 0)
}

async function startNew() {
  notice.value = ''
  await game.startNew()
  if (game.playing.value) {
    screen.value = 'game'
  }
}

async function startEndless() {
  notice.value = ''
  await game.startNew(seedInput.value.trim() || undefined, true)
  if (game.playing.value) {
    screen.value = 'game'
  } else {
    notice.value = game.message.value
  }
}

function randomEndless() {
  seedInput.value = ''
  void startEndless()
}

async function continueGame() {
  notice.value = ''
  await refreshSlots()
  const newest = slots.value
    .filter((s) => s.exists)
    .sort((a, b) => b.savedAt - a.savedAt)[0]
  if (!newest) {
    notice.value = '还没有任何存档，请先「新的开始」'
    return
  }
  if (await game.loadFromSlot(newest.slot)) {
    screen.value = 'game'
  }
}

function openSlots(from: Screen) {
  slotsReturn.value = from
  void refreshSlots()
  screen.value = 'slots'
}

function leaveSlots() {
  screen.value = slotsReturn.value === 'game' && game.playing.value ? 'game' : 'menu'
}

async function onLoadSlot(slot: number) {
  if (await game.loadFromSlot(slot)) {
    screen.value = 'game'
  }
}

async function onSaveSlot(slot: number) {
  const id = game.state.value?.sessionId
  if (!id) {
    return
  }
  try {
    slots.value = await api.saveToSlot(id, slot)
    notice.value = `已存入第 ${slot + 1} 个存档`
  } catch (e) {
    notice.value = e instanceof Error ? e.message : String(e)
  }
}

async function onDeleteSlot(slot: number) {
  try {
    slots.value = await api.deleteSlot(slot)
    notice.value = `已删除第 ${slot + 1} 个存档`
  } catch (e) {
    notice.value = e instanceof Error ? e.message : String(e)
  }
}

/** 返回主菜单（有未存档的进度时先确认）。 */
async function backToMenu() {
  if (hasUnsavedProgress()) {
    if (!window.confirm('还没有存档，确定退出吗？')) {
      return
    }
  }
  game.clear()
  cheatBuffer.length = 0
  seedVisible.value = false
  await refreshSlots()
  screen.value = 'menu'
}

function quit() {
  if (hasUnsavedProgress()) {
    if (!window.confirm('还没有存档，确定退出吗？')) {
      return
    }
  }
  game.clear()
  cheatBuffer.length = 0
  seedVisible.value = false
  screen.value = 'menu'
  notice.value = '已退出游戏（网页里可以直接关闭标签页）'
}

/** 是否在游戏内展开显示种子（默认不显示，玩家主动点按钮才看）。 */
const seedVisible = ref(false)

async function copySeed() {
  const code = game.state.value?.seedCode
  if (!code) {
    return
  }
  try {
    await navigator.clipboard.writeText(code)
    game.setMessage(`种子已复制：${code}`)
  } catch {
    game.setMessage(`种子：${code}（复制失败，请手动选中）`)
  }
}

/** 展开/收起种子显示。 */
function toggleSeed() {
  seedVisible.value = !seedVisible.value
}
</script>

<template>
  <div class="app">
    <header class="topbar">
      <h1>推箱子 · Sokoban</h1>
      <span class="backend">后端 Spring Boot REST API　·　前端 Vue 3 + Vite</span>
    </header>

    <!-- 主菜单 -->
    <section v-if="screen === 'menu'" class="panel menu">
      <h2>主菜单</h2>
      <div class="menu-buttons">
        <button class="btn primary" @click="startNew">新的开始</button>
        <button class="btn" @click="continueGame">继续游戏</button>
        <button class="btn" @click="screen = 'endless'">无尽模式</button>
        <button class="btn" @click="openSlots('menu')">读档</button>
        <button class="btn danger" @click="quit">退出</button>
      </div>
      <p v-if="notice" class="notice">{{ notice }}</p>
      <details class="levels">
        <summary>全部内置关卡（共 {{ levels.length }} 关）</summary>
        <ul>
          <li v-for="item in levels" :key="item.index">
            {{ item.title }} · {{ item.shortProgress }}
          </li>
        </ul>
      </details>
    </section>

    <!-- 无尽模式开局 -->
    <section v-else-if="screen === 'endless'" class="panel">
      <h2>无尽模式</h2>
      <p class="hint">
        随机生成、保证有解；每 5 层地图扩大一格，层数越高地图越大越复杂；
        第 21 层起每层 3 个箱子，难度按最短解步数挑选。
        留空即随机开局，也可以填一个 8 位种子复现同一套地图。
      </p>
      <label class="field">
        <span>种子（可留空）</span>
        <input v-model="seedInput" type="text" placeholder="例如 7K3M-9QPZ" maxlength="12" />
      </label>
      <div class="menu-buttons row">
        <button class="btn primary" @click="randomEndless">随机开始</button>
        <button class="btn" @click="startEndless">用这个种子开始</button>
        <button class="btn ghost" @click="screen = 'menu'">返回主菜单</button>
      </div>
      <p v-if="notice" class="notice">{{ notice }}</p>
    </section>

    <!-- 游戏 -->
    <section v-else-if="screen === 'game' && game.state.value" class="panel game">
      <div class="hud">
        <div class="hud-left">
          <strong>{{ game.state.value.level.title }}</strong>
          <span class="meta">{{ game.state.value.shortProgress }}</span>
          <span v-if="game.state.value.endless" class="tag">
            无尽第 {{ game.state.value.endlessNumber }} 层
          </span>
          <span v-if="game.state.value.endlessSkip" class="tag cheat">作弊已开</span>
        </div>
        <div class="hud-right">
          <span>步数 {{ game.state.value.steps }}</span>
          <span>推箱 {{ game.state.value.pushes }}</span>
          <span>可撤销 {{ game.state.value.undoCount }}</span>
        </div>
      </div>

      <p v-if="game.state.value.won" class="banner ok">通关！按「下一关」继续。</p>
      <p v-else-if="game.state.value.deadlocked" class="banner bad">
        已经不可能通关了：按 U 撤销，或按 R 重来本关
      </p>

      <GameBoard :state="game.state.value" />

      <div class="controls">
        <button class="btn small" @click="game.undoStep()">撤销 (U)</button>
        <button class="btn small" @click="game.restart()">重来本关 (R)</button>
        <button class="btn small" @click="game.showHint()">提示演示 (H)</button>
        <button class="btn small" @click="game.shiftLevel(-1)">上一关</button>
        <button class="btn small primary" @click="game.shiftLevel(1)">下一关</button>
        <button class="btn small" @click="toggleSeed()">
          {{ seedVisible ? '隐藏种子' : '查看种子' }}
        </button>
        <button class="btn small" @click="openSlots('game')">存档 / 读档</button>
        <button class="btn small ghost" @click="backToMenu()">返回主菜单 (Esc)</button>
      </div>

      <!-- 种子默认不显示，玩家点「查看种子」才展开 -->
      <div v-if="seedVisible" class="seedbox">
        <span class="seedline">当前种子：<code>{{ game.state.value.seedCode }}</code></span>
        <button class="btn small ghost" @click="copySeed()">复制</button>
      </div>

      <p class="status">{{ status }}</p>
    </section>

    <!-- 存读档 -->
    <section v-else-if="screen === 'slots'" class="panel">
      <div class="hud">
        <h2>存档 / 读档</h2>
        <button class="btn small ghost" @click="leaveSlots()">
          {{ slotsReturn === 'game' && game.playing.value ? '返回游戏 (Esc)' : '返回主菜单 (Esc)' }}
        </button>
      </div>
      <p class="hint">
        共 8 个槽位，槽位 1 是自动存档槽。保存的是「当前局面」的玩家与箱子位置，
        读档可以接着玩，而不是回到关卡开头。
      </p>
      <SaveSlots
        :slots="slots"
        :can-save="game.playing.value"
        @load="onLoadSlot"
        @save="onSaveSlot"
        @delete="onDeleteSlot"
      />
      <p v-if="notice" class="notice">{{ notice }}</p>
    </section>
  </div>
</template>
