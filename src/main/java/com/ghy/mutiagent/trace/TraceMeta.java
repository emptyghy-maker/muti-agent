package com.ghy.mutiagent.trace;

/**
 * S12 操作追踪元数据：operation 启动时绑定的版本与快照标识。
 * 同一 operation 的所有 provider attempt span 共享；SDK 重试是额外 attempt，不得另起版本。
 */
public record TraceMeta(String operationId, Long ownerId, String promptVersion,
                        String modelVersion, Long constraintRevision, String snapshotHash) {
}
