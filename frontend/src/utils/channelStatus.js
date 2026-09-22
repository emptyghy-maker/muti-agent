const TERMINAL_CHANNEL_STATUSES = new Set(['READY', 'SKIPPED'])

/**
 * 候选预热轮询只允许读取 channelStatus，不能把轮询快照的候选、阶段或已选项写回页面。
 * 返回新对象，避免后续本地状态修改污染响应对象。
 */
export function channelStatusFromSnapshot(snapshot) {
  const status = snapshot && snapshot.channelStatus
  if (!status || typeof status !== 'object' || Array.isArray(status)) return null
  return { ...status }
}

/** READY 表示候选已生成；SKIPPED 表示用户明确不需要。两者都会终止该通道轮询。 */
export function isChannelTerminal(status) {
  return TERMINAL_CHANNEL_STATUSES.has(status)
}
