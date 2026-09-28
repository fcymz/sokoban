<script setup lang="ts">
import { computed } from 'vue'
import type { GameState } from '../api/types'

/**
 * 棋盘渲染。
 *
 * 后端给的是“一维下标 + 墙/目标点/箱子/玩家”，这里按 width 摊成网格。
 * 配对模式下箱子与目标点带编号（1、2、3…），箱子颜色与它自己的目标点一致，
 * 已就位的箱子显示为绿色。
 */
const props = defineProps<{ state: GameState }>()

/** 配对编号对应的颜色，与目标点、箱子共用。 */
const PAIR_COLORS = ['#4fd1a5', '#7aa2ff', '#ff9f6e', '#e879f9', '#fbbf24', '#38bdf8']

interface Cell {
  index: number
  wall: boolean
  goal: boolean
  goalLabel: number
  box: boolean
  boxLabel: number
  boxOnTarget: boolean
  player: boolean
}

const cells = computed<Cell[]>(() => {
  const level = props.state.level
  const total = level.width * level.height
  const wallSet = new Set(level.walls)
  const goalSet = new Set(level.goals)
  const boxAt = new Map<number, number>()
  props.state.boxes.forEach((cell, i) => boxAt.set(cell, i))

  const result: Cell[] = []
  for (let index = 0; index < total; index++) {
    const boxIndex = boxAt.get(index)
    result.push({
      index,
      wall: wallSet.has(index),
      goal: goalSet.has(index),
      goalLabel: level.goalLabels[index] ?? 0,
      box: boxIndex !== undefined,
      boxLabel: boxIndex !== undefined ? (props.state.boxLabels[boxIndex] ?? 0) : 0,
      boxOnTarget: boxIndex !== undefined ? Boolean(props.state.boxOnTarget[boxIndex]) : false,
      player: props.state.player === index,
    })
  }
  return result
})

/** 棋盘按格子数自适应大小。 */
const boardStyle = computed(() => {
  const level = props.state.level
  return {
    gridTemplateColumns: `repeat(${level.width}, var(--cell))`,
    gridTemplateRows: `repeat(${level.height}, var(--cell))`,
    '--cell': `${Math.max(22, Math.min(46, Math.floor(620 / Math.max(level.width, level.height))))}px`,
  }
})

/** 玩家朝向对应的箭头。 */
const playerArrow = computed(() => {
  switch (props.state.facing) {
    case 'UP':
      return '▲'
    case 'DOWN':
      return '▼'
    case 'LEFT':
      return '◀'
    default:
      return '▶'
  }
})

function colorOf(label: number): string {
  if (label <= 0) {
    return PAIR_COLORS[0]
  }
  return PAIR_COLORS[(label - 1) % PAIR_COLORS.length]
}
</script>

<template>
  <div class="board-wrap">
    <div class="board" :style="boardStyle">
      <div
        v-for="cell in cells"
        :key="cell.index"
        class="cell"
        :class="{
          wall: cell.wall,
          floor: !cell.wall,
          goal: cell.goal && !cell.box,
        }"
      >
        <span
          v-if="cell.goal"
          class="goal-mark"
          :class="{ paired: state.level.paired }"
          :style="{ '--pair': colorOf(cell.goalLabel) }"
        >
          <template v-if="state.level.paired && cell.goalLabel > 0">{{ cell.goalLabel }}</template>
        </span>

        <span
          v-if="cell.box"
          class="box"
          :class="{ placed: state.level.paired ? cell.boxOnTarget : cell.goal }"
          :style="{ '--pair': colorOf(cell.boxLabel) }"
        >
          <template v-if="state.level.paired && cell.boxLabel > 0">{{ cell.boxLabel }}</template>
        </span>

        <span v-if="cell.player" class="player">{{ playerArrow }}</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.board-wrap {
  display: flex;
  justify-content: center;
}

.board {
  display: grid;
  gap: 2px;
  padding: 10px;
  background: #0e1220;
  border-radius: 14px;
  box-shadow: 0 18px 40px rgba(0, 0, 0, 0.45);
}

.cell {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 5px;
}

.cell.floor {
  background: #20263c;
}

.cell.wall {
  background: #0a0d18;
  box-shadow: inset 0 0 0 1px #171d31;
}

.cell.goal {
  background: #242c46;
}

.goal-mark {
  position: absolute;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 62%;
  height: 62%;
  border-radius: 3px;
  color: var(--pair);
  font-size: 0.72rem;
  font-weight: 700;
  border: 1px dashed currentColor;
  opacity: 0.85;
}

.goal-mark.paired {
  border-style: solid;
  border-width: 2px;
  opacity: 0.95;
}

.box {
  position: absolute;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 78%;
  height: 78%;
  border-radius: 7px;
  font-size: 0.8rem;
  font-weight: 800;
  color: #101423;
  background: #ffcf6b;
  box-shadow: 0 3px 0 rgba(0, 0, 0, 0.35);
}

.box.placed {
  background: #4fd1a5;
}

.box:not(.placed) {
  background: color-mix(in srgb, var(--pair) 75%, #ffffff 25%);
}

.player {
  position: absolute;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 84%;
  height: 84%;
  border-radius: 50%;
  background: #5b8cff;
  color: #0b0f1e;
  font-size: 0.7rem;
  box-shadow: 0 0 0 3px rgba(91, 140, 255, 0.25);
}
</style>
