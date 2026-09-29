<script setup lang="ts">
import { ref } from 'vue'
import type { SaveSlot } from '../api/types'

/**
 * 存档槽列表。
 *
 * 只负责展示与转发点击；真正的读/存/删/转存由父组件调后端完成。
 */

/** 自动存档槽位的下标：它只由系统写入（无尽模式进新层、关标签页兜底）。 */
const AUTO_SLOT = 0

defineProps<{
  slots: SaveSlot[]
  /** 当前是否有进行中的游戏（决定能不能“存入此档”） */
  canSave: boolean
  /** 每行的主操作按钮文案，例如「读取」或「从主菜单读取」 */
  loadLabel?: string
}>()

const emit = defineEmits<{
  (e: 'load', slot: number): void
  (e: 'save', slot: number): void
  (e: 'delete', slot: number): void
  (e: 'move', from: number, to: number): void
}>()

/** 正在把哪个槽位的存档转存出去；null 表示不在转存模式。 */
const transferringFrom = ref<number | null>(null)

function cancelTransfer() {
  transferringFrom.value = null
}

function confirmTransfer(to: number) {
  const from = transferringFrom.value
  if (from === null) {
    return
  }
  transferringFrom.value = null
  emit('move', from, to)
}

function formatTime(savedAt: number): string {
  if (!savedAt) {
    return '—'
  }
  const date = new Date(savedAt)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} `
    + `${pad(date.getHours())}:${pad(date.getMinutes())}`
}
</script>

<template>
  <ul class="slots">
    <li v-for="slot in slots" :key="slot.slot" class="slot" :class="{ empty: !slot.exists }">
      <div class="slot-head">
        <span class="slot-no">槽位 {{ slot.slot + 1 }}</span>
        <span v-if="slot.slot === AUTO_SLOT" class="tag">自动</span>
        <span class="slot-time">{{ formatTime(slot.savedAt) }}</span>
      </div>

      <div v-if="slot.exists" class="slot-body">
        <strong>{{ slot.title }}</strong>
        <span class="meta">
          {{ slot.shortProgress }} · {{ slot.steps }} 步 · 推箱 {{ slot.pushes }} 次
        </span>
        <span class="meta seed">种子 {{ slot.seedCode }}</span>
      </div>
      <div v-else class="slot-body empty-body">暂无存档</div>

      <div class="slot-actions">
        <!-- 转存模式：源槽位提示中，其余槽位都变成落点 -->
        <template v-if="transferringFrom !== null && slot.slot !== transferringFrom">
          <button class="btn small primary" @click="confirmTransfer(slot.slot)">
            转存到这里{{ slot.exists ? '（覆盖）' : '' }}
          </button>
        </template>
        <template v-else-if="transferringFrom !== null">
          <span class="hint-inline">选一个槽位转存过去</span>
          <button class="btn small ghost" @click="cancelTransfer()">取消</button>
        </template>

        <template v-else>
          <button class="btn small primary" :disabled="!slot.exists"
                  @click="emit('load', slot.slot)">
            {{ loadLabel ?? '读取' }}
          </button>
          <!-- 自动存档槽不接受手动存档，否则会被下一次自动存档悄悄覆盖 -->
          <span v-if="slot.slot === AUTO_SLOT" class="hint-inline">不能存入自动栏位</span>
          <button v-else class="btn small" :disabled="!canSave" @click="emit('save', slot.slot)">
            存入此档
          </button>
          <button v-if="slot.slot === AUTO_SLOT && slot.exists" class="btn small"
                  @click="transferringFrom = slot.slot">
            转存到…
          </button>
          <button class="btn small danger" :disabled="!slot.exists"
                  @click="emit('delete', slot.slot)">
            删除
          </button>
        </template>
      </div>
    </li>
  </ul>
</template>

<style scoped>
.slots {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 12px;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
}

.slot {
  background: #171d2e;
  border: 1px solid #232b42;
  border-radius: 12px;
  padding: 14px 16px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.slot.empty {
  opacity: 0.62;
}

.slot-head {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 0.82rem;
  color: #8c96b2;
}

.slot-no {
  font-weight: 700;
  color: #cdd6ef;
}

.tag {
  font-size: 0.7rem;
  padding: 1px 6px;
  border-radius: 5px;
  background: #2a3350;
  color: #9fb0dd;
}

.slot-time {
  margin-left: auto;
}

.slot-body {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.slot-body strong {
  color: #e7ecf7;
  font-size: 0.98rem;
}

.empty-body {
  color: #6b7691;
  font-size: 0.9rem;
}

.meta {
  color: #8c96b2;
  font-size: 0.8rem;
}

.seed {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  letter-spacing: 0.04em;
}

.slot-actions {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
  margin-top: auto;
}

/* 「自动存档，不手动存」这类行内说明 */
.hint-inline {
  color: #6b7691;
  font-size: 0.78rem;
}
</style>
