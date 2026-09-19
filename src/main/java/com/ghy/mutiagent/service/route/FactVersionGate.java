package com.ghy.mutiagent.service.route;

import com.ghy.mutiagent.model.RouteFact;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * S10 并行事实结果的版本闸：每个异步读取绑定发起时的 constraintRevision，
 * 结果到达时与当前修订号比对——迟到的旧版本结果丢弃，不混入新计划。
 */
public class FactVersionGate {

    private int currentRevision;
    private final List<Integer> committedRevisions = new ArrayList<>();
    private final List<Integer> discardedRevisions = new ArrayList<>();

    public FactVersionGate(int initialRevision) {
        this.currentRevision = initialRevision;
    }

    public synchronized void setCurrentRevision(int revision) {
        this.currentRevision = revision;
    }

    public synchronized int currentRevision() {
        return currentRevision;
    }

    /** 绑定一次异步事实读取：结果到达且版本一致才生效；否则记录丢弃（不静默使用旧版本） */
    public void bind(int boundRevision, CompletableFuture<RouteFact> future,
                     Consumer<RouteFact> commit) {
        future.whenComplete((fact, err) -> {
            if (err != null || fact == null) {
                return;
            }
            synchronized (this) {
                if (boundRevision == currentRevision) {
                    committedRevisions.add(boundRevision);
                    commit.accept(fact);
                } else {
                    discardedRevisions.add(boundRevision);
                }
            }
        });
    }

    public synchronized List<Integer> committedRevisions() {
        return new ArrayList<>(committedRevisions);
    }

    public synchronized List<Integer> discardedRevisions() {
        return new ArrayList<>(discardedRevisions);
    }
}
