import test from 'node:test'
import assert from 'node:assert/strict'

import { channelStatusFromSnapshot, isChannelTerminal } from '../src/utils/channelStatus.js'

test('轮询快照只提取通道状态，不携带服务端旧的景点选择', () => {
  const snapshot = {
    stage: 'ATTRACTIONS',
    selectedAttractionIds: [],
    attractionCandidates: [{ attractionId: 1 }],
    channelStatus: { ATTRACTION: 'READY', FOOD: 'READY', HOTEL: 'RUNNING' }
  }

  const status = channelStatusFromSnapshot(snapshot)

  assert.deepEqual(status, { ATTRACTION: 'READY', FOOD: 'READY', HOTEL: 'RUNNING' })
  assert.equal('selectedAttractionIds' in status, false)
  status.FOOD = 'LOCAL_CHANGE'
  assert.equal(snapshot.channelStatus.FOOD, 'READY')
})

test('READY 与 SKIPPED 都是轮询终态，RUNNING 和缺失状态不是', () => {
  assert.equal(isChannelTerminal('READY'), true)
  assert.equal(isChannelTerminal('SKIPPED'), true)
  assert.equal(isChannelTerminal('RUNNING'), false)
  assert.equal(isChannelTerminal(undefined), false)
})

test('没有合法 channelStatus 时不更新页面状态', () => {
  assert.equal(channelStatusFromSnapshot(null), null)
  assert.equal(channelStatusFromSnapshot({}), null)
  assert.equal(channelStatusFromSnapshot({ channelStatus: [] }), null)
})
