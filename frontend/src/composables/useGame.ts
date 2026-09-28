import { computed, ref } from 'vue'
import * as api from '../api/client'
import type { Direction, GameState } from '../api/types'

/**
 * 一局游戏的前端状态。
 *
 * 这里只保存“后端返回了什么”，并转发操作；所有规则（能不能推、算不算通关、
 * 关卡有没有解锁）都由后端判定，前端不做二次实现，避免两边规则走偏。
 */
export function useGame() {
  const state = ref<GameState | null>(null)
  const message = ref<string>('')
  const busy = ref(false)
  const hintPlan = ref<Direction[]>([])
  const autoPlaying = ref(false)

  /** 是否有一局进行中。 */
  const playing = computed(() => state.value !== null)

  /** 当前关卡是否处于无尽模式。 */
  const endless = computed(() => state.value?.endless ?? false)

  function setMessage(text: string) {
    message.value = text
  }

  /** 统一处理一次后端调用：接管忙碌标记与错误提示。 */
  async function run<T>(action: () => Promise<T>): Promise<T | null> {
    if (busy.value) {
      return null
    }
    busy.value = true
    try {
      return await action()
    } catch (e) {
      setMessage(e instanceof Error ? e.message : String(e))
      return null
    } finally {
      busy.value = false
    }
  }

  function apply(next: GameState) {
    state.value = next
  }

  /** 新的开始（可带种子；可选直接从无尽第 1 层开始）。 */
  async function startNew(seedCode?: string, fromEndless = false) {
    const next = await run(() => api.createSession(seedCode, fromEndless))
    if (next) {
      apply(next)
      setMessage(fromEndless ? `已从无尽第 1 层开始（种子 ${next.seedCode}）` : '新的开始')
    }
  }

  /** 走一步。 */
  async function step(dir: Direction) {
    if (!state.value || state.value.won) {
      return
    }
    const next = await run(() => api.move(state.value!.sessionId, dir))
    if (!next) {
      return
    }
    apply(next)
    if (next.won) {
      setMessage(`通关！用了 ${next.steps} 步、推箱 ${next.pushes} 次`)
    } else if (next.deadlocked) {
      setMessage('已经不可能通关了：按 U 撤销，或按 R 重来本关')
    }
  }

  /** 撤销一步。 */
  async function undoStep() {
    if (!state.value) {
      return
    }
    const next = await run(() => api.undo(state.value!.sessionId))
    if (next) {
      apply(next)
      setMessage('已撤销一步')
    }
  }

  /** 重玩本关。 */
  async function restart() {
    if (!state.value) {
      return
    }
    const next = await run(() => api.reset(state.value!.sessionId))
    if (next) {
      apply(next)
      hintPlan.value = []
      setMessage('已重来本关')
    }
  }

  /** 按绝对下标跳关。 */
  async function gotoLevel(index: number) {
    if (!state.value) {
      return
    }
    const next = await run(() => api.loadLevel(state.value!.sessionId, index))
    if (next) {
      apply(next)
      hintPlan.value = []
      setMessage(next.level.title)
    }
  }

  /** 上一关 / 下一关。 */
  async function shiftLevel(delta: number) {
    if (!state.value) {
      return
    }
    const next = await run(() => api.changeLevel(state.value!.sessionId, delta))
    if (next) {
      apply(next)
      hintPlan.value = []
      setMessage(next.level.title)
    }
  }

  /** 开关无尽模式跳关作弊。 */
  async function toggleEndlessSkip() {
    if (!state.value) {
      return
    }
    const unlocked = !state.value.endlessSkip
    const next = await run(() => api.setEndlessSkip(state.value!.sessionId, unlocked))
    if (next) {
      apply(next)
      setMessage(unlocked ? '作弊已开启：无尽模式可以随意跳关' : '作弊已关闭')
    }
  }

  let autoTimer: number | null = null

  /** 停掉正在进行的自动演示。 */
  function stopAutoPlay() {
    if (autoTimer !== null) {
      window.clearTimeout(autoTimer)
      autoTimer = null
    }
    autoPlaying.value = false
  }

  /** 逐步播放提示计划。 */
  function playPlan(steps: Direction[]) {
    stopAutoPlay()
    if (steps.length === 0 || !state.value) {
      return
    }
    autoPlaying.value = true
    const sid = state.value.sessionId
    const next = () => {
      if (!autoPlaying.value || steps.length === 0 || !state.value || state.value.sessionId !== sid) {
        stopAutoPlay()
        return
      }
      const dir = steps.shift()!
      void api
        .move(sid, dir)
        .then((s) => {
          apply(s)
          if (steps.length === 0 || s.won) {
            stopAutoPlay()
            setMessage(s.won ? `自动演示完成，共 ${s.steps} 步` : '演示结束')
            return
          }
          autoTimer = window.setTimeout(next, 130)
        })
        .catch((e: unknown) => {
          stopAutoPlay()
          setMessage(e instanceof Error ? e.message : String(e))
        })
    }
    next()
  }

  /** 求提示并自动演示。 */
  async function showHint() {
    if (!state.value) {
      return
    }
    stopAutoPlay()
    const result = await run(() => api.hint(state.value!.sessionId))
    if (!result) {
      return
    }
    apply(result.state)
    if (result.message) {
      setMessage(result.message)
    }
    if (result.plan.length === 0) {
      setMessage(result.message ?? '已经到达终点状态')
      return
    }
    setMessage(`自动演示中：共 ${result.total} 步，按任意方向键可中断`)
    playPlan([...result.plan])
  }

  /** 读档（新会话）。 */
  async function loadFromSlot(slot: number) {
    stopAutoPlay()
    const next = await run(() => api.loadSlot(slot))
    if (next) {
      apply(next)
      hintPlan.value = []
      setMessage(`已读取第 ${slot + 1} 个存档`)
      return true
    }
    return false
  }

  /** 关闭当前会话（离开游戏时调用）。 */
  function clear() {
    stopAutoPlay()
    state.value = null
    message.value = ''
  }

  return {
    state,
    message,
    busy,
    playing,
    endless,
    autoPlaying,
    setMessage,
    startNew,
    step,
    undoStep,
    restart,
    gotoLevel,
    shiftLevel,
    toggleEndlessSkip,
    showHint,
    stopAutoPlay,
    loadFromSlot,
    clear,
  }
}
