// 端到端联调测试：完全走前端开发服务器（Vite :5173）的 /api 代理，
// 也就是浏览器里真实发生的请求路径，验证整条链路而不是只测后端。
import { existsSync, cpSync, rmSync } from 'node:fs'
import { homedir, tmpdir } from 'node:os'
import { join } from 'node:path'

const BASE = 'http://127.0.0.1:5173'

// 这个测试会真的占用存档槽位（无尽模式会自动写自动槽位），
// 所以先把整个存档目录备份起来，跑完原样还原 —— 测试不能冲掉玩家自己的存档。
const saveDir = join(homedir(), '.sokoban-saves')
const saveBackup = join(tmpdir(), 'sokoban-saves-backup-e2e')
const hadSaveDir = existsSync(saveDir)
if (existsSync(saveBackup)) rmSync(saveBackup, { recursive: true, force: true })
if (hadSaveDir) cpSync(saveDir, saveBackup, { recursive: true })

function restoreSaves() {
  if (existsSync(saveDir)) rmSync(saveDir, { recursive: true, force: true })
  if (hadSaveDir) cpSync(saveBackup, saveDir, { recursive: true })
  if (existsSync(saveBackup)) rmSync(saveBackup, { recursive: true, force: true })
}

let failed = 0
function check(name, cond, detail) {
  if (cond) {
    console.log(`  [OK]   ${name}`)
  } else {
    console.log(`  [FAIL] ${name} -- ${detail}`)
    failed++
  }
}

async function call(method, path, body) {
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await res.text()
  const data = text ? JSON.parse(text) : null
  return { status: res.status, data }
}

const main = async () => {
  console.log('== 前端首页确实是被 Vite 服务的 ==')
  const page = await fetch(`${BASE}/`)
  const html = await page.text()
  check('首页返回 200', page.status === 200, `status=${page.status}`)
  check('首页挂载了 #app', html.includes('id="app"'), 'no #app')
  check('首页引用了 src/main.ts', html.includes('/src/main.ts'), 'no main.ts')

  console.log('== 后端接口经前端代理可达 ==')
  const levels = await call('GET', '/api/levels')
  check('GET /api/levels 经代理返回 200', levels.status === 200, `status=${levels.status}`)
  check('内置关卡 10 条', Array.isArray(levels.data) && levels.data.length === 10,
    `len=${levels.data?.length}`)

  console.log('== 玩一局：建档 → 走 → 撤销 → 提示通关 ==')
  const created = await call('POST', '/api/game/sessions', {})
  check('建档 200', created.status === 200, `status=${created.status}`)
  const sid = created.data.sessionId
  check('返回了会话号与棋盘', Boolean(sid) && created.data.level.width > 0, 'no session')

  const moved = await call('POST', `/api/game/sessions/${sid}/moves`, { dir: 'RIGHT' })
  check('移动经代理生效', moved.data.steps === 1, `steps=${moved.data.steps}`)

  const undone = await call('POST', `/api/game/sessions/${sid}/undo`)
  check('撤销经代理生效', undone.data.steps === 0, `steps=${undone.data.steps}`)

  const hint = await call('POST', `/api/game/sessions/${sid}/hint`)
  check('提示返回计划', hint.data.plan.length > 0, `plan=${hint.data.plan.length}`)

  let last = hint.data.state
  for (const dir of hint.data.plan) {
    const step = await call('POST', `/api/game/sessions/${last.sessionId}/moves`, { dir })
    last = step.data
  }
  check('按提示走完通关', last.won === true, `won=${last.won} steps=${last.steps}`)

  console.log('== 无尽模式 + 种子复现 ==')
  const a = await call('POST', '/api/game/sessions', { seedCode: '7K3M-9QPZ', endless: true })
  const b = await call('POST', '/api/game/sessions', { seedCode: '7K3M-9QPZ', endless: true })
  check('直接从无尽第 1 层开始', a.data.endless && a.data.endlessNumber === 1,
    `n=${a.data.endlessNumber}`)
  check('同种子地图一致', JSON.stringify(a.data.level.walls) === JSON.stringify(b.data.level.walls),
    'walls differ')
  check('无尽关卡带配对编号', a.data.level.paired && a.data.boxLabels.some((x) => x > 0),
    `paired=${a.data.level.paired}`)

  console.log('== 存档 / 读档 ==')
  await call('POST', `/api/game/sessions/${sid}/moves`, { dir: 'RIGHT' })
  await call('POST', `/api/game/sessions/${sid}/moves`, { dir: 'UP' })
  const before = (await call('GET', `/api/game/sessions/${sid}`)).data
  const saved = await call('POST', `/api/saves/5/from/${sid}`)
  const slot = saved.data.find((s) => s.slot === 5)
  check('存档写入成功', slot.exists === true, `exists=${slot.exists}`)
  const loaded = await call('POST', '/api/saves/5/load')
  check('读档局面与存档时一致',
    loaded.data.player === before.player
    && JSON.stringify(loaded.data.boxes) === JSON.stringify(before.boxes)
    && loaded.data.steps === before.steps,
    `player ${loaded.data.player}/${before.player}`)
  await call('DELETE', '/api/saves/5')

  console.log('== 已移除的「每关最佳步数」不再出现在接口里 ==')
  const goneScores = await call('GET', '/api/scores')
  check('GET /api/scores 已下线（404）', goneScores.status === 404,
    `status=${goneScores.status}`)
  const levelList = (await call('GET', '/api/levels')).data
  check('关卡列表不再带最佳步数',
    Array.isArray(levelList) && levelList.every((l) => !('bestSteps' in l)),
    'bestSteps 仍在返回体里')
  const anyState = (await call('GET', `/api/game/sessions/${sid}`)).data
  check('对局快照不再带最佳步数', !('bestSteps' in anyState), 'bestSteps 仍在快照里')

  console.log('== 未存档标记：退出时要不要提示就看它 ==')
  const fresh = (await call('POST', '/api/game/sessions', {})).data
  check('刚开的局是干净的', fresh.unsaved === false, `unsaved=${fresh.unsaved}`)
  const freshMoved = (await call('POST', `/api/game/sessions/${fresh.sessionId}/moves`,
    { dir: 'RIGHT' })).data
  check('走一步后变成有未存档改动', freshMoved.unsaved === true,
    `unsaved=${freshMoved.unsaved}`)
  await call('POST', `/api/saves/6/from/${fresh.sessionId}`)
  const afterSave = (await call('GET', `/api/game/sessions/${fresh.sessionId}`)).data
  check('手动存档后标记被清掉', afterSave.unsaved === false, `unsaved=${afterSave.unsaved}`)
  await call('DELETE', '/api/saves/6')

  const autoEndless = (await call('POST', '/api/game/sessions',
    { seedCode: '7K3M-9QPZ', endless: true })).data
  const autoSlot = (await call('GET', '/api/saves')).data.find((s) => s.slot === 0)
  check('无尽模式开局后自动槽位有存档', autoSlot.exists === true, `exists=${autoSlot.exists}`)
  check('自动存档记的是当前无尽层',
    autoSlot.levelIndex === autoEndless.levelIndex,
    `slot=${autoSlot.levelIndex} cur=${autoEndless.levelIndex}`)

  console.log('== 退出游戏 / 关标签页的逻辑确实下发到了浏览器 ==')
  const appSrc = await (await fetch(`${BASE}/src/App.vue`)).text()
  check('退出游戏按钮调用 window.close()', appSrc.includes('window.close()'),
    '没有 window.close()')
  // 注意：Vite 会把单引号转成双引号，这里只按标识符判断
  check('关标签页前按未存档标记拦截', appSrc.includes('beforeunload'),
    '没有注册 beforeunload')
  check('离开页面时用 sendBeacon 兜底存档',
    appSrc.includes('sendBeacon'), '没有 sendBeacon')

  console.log('')
  console.log(`结果：失败 ${failed} 项`)
  restoreSaves()
  process.exit(failed > 0 ? 1 : 0)
}

main().catch((e) => {
  console.error('测试异常：', e)
  restoreSaves()
  process.exit(1)
})
