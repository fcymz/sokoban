import type { Direction, GameState, HintResult, LevelInfo, SaveSlot } from './types'

/**
 * 后端 REST 接口的封装。
 *
 * 前端不实现任何游戏规则，所有判定都在后端；这里只负责发请求、把错误翻译成
 * 可以直接显示的文案。开发时走 Vite 的 `/api` 代理，生产部署时前端与后端同源，
 * 相对路径都成立，所以这里不写死后端地址。
 */
const BASE = '/api'

/** 后端返回的错误说明（仅本文件内部使用）。 */
interface ApiErrorBody {
  error?: number
  message?: string
}

/** 带后端说明文案的异常。 */
export class ApiError extends Error {
  readonly status: number

  constructor(status: number, message: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let response: Response
  try {
    response = await fetch(`${BASE}${path}`, {
      headers: { 'Content-Type': 'application/json' },
      ...init,
    })
  } catch (e) {
    throw new ApiError(0, `连不上后端服务，请确认后端已经启动（${String(e)}）`)
  }
  if (response.status === 204) {
    return undefined as T
  }
  const text = await response.text()
  const body = text ? (JSON.parse(text) as unknown) : null
  if (!response.ok) {
    const detail = (body as ApiErrorBody | null)?.message
    throw new ApiError(response.status, detail ?? `请求失败（HTTP ${response.status}）`)
  }
  return body as T
}

/** 开一局新游戏。 */
export function createSession(seedCode?: string, endless = false): Promise<GameState> {
  return request<GameState>('/game/sessions', {
    method: 'POST',
    body: JSON.stringify({ seedCode: seedCode ?? null, endless }),
  })
}

/** 走一步。 */
export function move(sessionId: string, dir: Direction): Promise<GameState> {
  return request<GameState>(`/game/sessions/${sessionId}/moves`, {
    method: 'POST',
    body: JSON.stringify({ dir }),
  })
}

/** 撤销一步。 */
export function undo(sessionId: string): Promise<GameState> {
  return request<GameState>(`/game/sessions/${sessionId}/undo`, { method: 'POST' })
}

/** 重玩本关。 */
export function reset(sessionId: string): Promise<GameState> {
  return request<GameState>(`/game/sessions/${sessionId}/reset`, { method: 'POST' })
}

/** 按绝对下标载入关卡。 */
export function loadLevel(sessionId: string, index: number): Promise<GameState> {
  return request<GameState>(`/game/sessions/${sessionId}/level`, {
    method: 'POST',
    body: JSON.stringify({ index }),
  })
}

/** 相对切换关卡。 */
export function changeLevel(sessionId: string, delta: number): Promise<GameState> {
  return request<GameState>(`/game/sessions/${sessionId}/level`, {
    method: 'POST',
    body: JSON.stringify({ delta }),
  })
}

/** 开关无尽模式跳关作弊。 */
export function setEndlessSkip(sessionId: string, unlocked: boolean): Promise<GameState> {
  return request<GameState>(`/game/sessions/${sessionId}/endless-skip`, {
    method: 'POST',
    body: JSON.stringify({ unlocked }),
  })
}

/** 求提示（自动演示用的解法）。 */
export function hint(sessionId: string): Promise<HintResult> {
  return request<HintResult>(`/game/sessions/${sessionId}/hint`, { method: 'POST' })
}

/** 内置关卡列表。 */
export function listLevels(): Promise<LevelInfo[]> {
  return request<LevelInfo[]>('/levels')
}

/** 存档槽列表。 */
export function listSaves(): Promise<SaveSlot[]> {
  return request<SaveSlot[]>('/saves')
}

/** 把当前局面存进某个槽。 */
export function saveToSlot(sessionId: string, slot: number): Promise<SaveSlot[]> {
  return request<SaveSlot[]>(`/saves/${slot}/from/${sessionId}`, { method: 'POST' })
}

/** 读档：用某个槽新建一个会话。 */
export function loadSlot(slot: number): Promise<GameState> {
  return request<GameState>(`/saves/${slot}/load`, { method: 'POST' })
}

/** 删除某个槽。 */
export function deleteSlot(slot: number): Promise<SaveSlot[]> {
  return request<SaveSlot[]>(`/saves/${slot}`, { method: 'DELETE' })
}
