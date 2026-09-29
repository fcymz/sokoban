/**
 * 后端接口的数据结构定义。
 *
 * 这些类型与后端 `com.ruoyi.web.dto` 里的 record 一一对应；
 * 坐标统一用一维下标，前端按 `width` 换算行列。
 */

/** 方向名，与后端 `SokobanGame.Dir` 的枚举名一致。 */
export type Direction = 'UP' | 'DOWN' | 'LEFT' | 'RIGHT'

/** 一关的棋盘信息（作为 {@link GameState.level} 的字段类型使用）。 */
interface LevelDto {
  name: string
  title: string
  width: number
  height: number
  /** 墙的格子下标 */
  walls: number[]
  /** 目标点下标 */
  goals: number[]
  /** 是否启用“箱子与目标点一一对应” */
  paired: boolean
  /** 每个目标点的配对编号（下标即格子，值从 1 开始，0 表示没有编号） */
  goalLabels: number[]
}

/** 一局游戏的完整快照。 */
export interface GameState {
  sessionId: string
  level: LevelDto
  levelIndex: number
  builtInCount: number
  endless: boolean
  endlessNumber: number
  shortProgress: string
  player: number
  boxes: number[]
  boxLabels: number[]
  boxOnTarget: boolean[]
  facing: Direction
  steps: number
  pushes: number
  undoCount: number
  won: boolean
  deadlocked: boolean
  maxUnlocked: number
  canAdvance: boolean
  endlessSkip: boolean
  /** 当前局面是否有改动还没存进任何槽位；退出时要不要提示玩家以它为准 */
  unsaved: boolean
  seedCode: string
}

/** 关卡列表项。 */
export interface LevelInfo {
  index: number
  title: string
  shortProgress: string
  endless: boolean
  endlessNumber: number
  builtIn: boolean
}

/** 存档槽。 */
export interface SaveSlot {
  slot: number
  exists: boolean
  levelIndex: number
  title: string
  shortProgress: string
  steps: number
  pushes: number
  unlocked: number
  seedCode: string
  savedAt: number
}

/** 提示结果。 */
export interface HintResult {
  plan: Direction[]
  total: number
  message: string | null
  state: GameState
}
