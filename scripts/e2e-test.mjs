// 端到端联调测试：完全走前端开发服务器（Vite :5173）的 /api 代理，
// 也就是浏览器里真实发生的请求路径，验证整条链路而不是只测后端。
const BASE = 'http://127.0.0.1:5173'

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

  console.log('== 成绩榜（每关最佳步数）==')
  const boardSession = await call('POST', '/api/game/sessions', {})
  const boardSid = boardSession.data.sessionId
  const beforeBoard = await call('GET', '/api/scores')
  check('GET /api/scores 经代理返回 200', beforeBoard.status === 200,
    `status=${beforeBoard.status}`)
  const prevBest = beforeBoard.data.entries.find((e) => e.levelIndex === 0)?.bestSteps ?? -1

  const boardPlan = (await call('POST', `/api/game/sessions/${boardSid}/hint`)).data.plan
  let boardState = boardSession.data
  for (const dir of boardPlan) {
    boardState = (await call('POST', `/api/game/sessions/${boardSid}/moves`, { dir })).data
  }
  check('通关后快照里带回本关最佳步数', boardState.won && boardState.bestSteps > 0,
    `won=${boardState.won} best=${boardState.bestSteps}`)
  check('首次通关时最佳步数等于本次步数', boardState.bestSteps === boardState.steps
    || boardState.bestSteps < boardState.steps,
    `best=${boardState.bestSteps} steps=${boardState.steps}`)

  const afterBoard = (await call('GET', '/api/scores')).data
  const entry = afterBoard.entries.find((e) => e.levelIndex === 0)
  check('成绩榜里出现第 1 关', Boolean(entry), 'no entry for level 0')
  check('成绩榜记录不差于通关前', Boolean(entry) && entry.bestSteps <= boardState.steps,
    `entry=${entry?.bestSteps} steps=${boardState.steps}`)
  check('成绩榜带关卡标题',
    Boolean(entry) && typeof entry.title === 'string' && entry.title.length > 0,
    `title=${entry?.title}`)
  console.log(`  （第 1 关通关前最佳 ${prevBest} → 现在 ${entry?.bestSteps}）`)

  const levelList = (await call('GET', '/api/levels')).data
  check('关卡列表也带上最佳步数', levelList.every((l) => typeof l.bestSteps === 'number'),
    'missing bestSteps')

  console.log('')
  console.log(`结果：失败 ${failed} 项`)
  process.exit(failed > 0 ? 1 : 0)
}

main().catch((e) => {
  console.error('测试异常：', e)
  process.exit(1)
})
