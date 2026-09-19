package com.ghy.mutiagent.service.eval;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

/**
 * 实验记录器（O1.2）：把实验过程逐行写入 runs.jsonl / attempts.jsonl / assertions.jsonl。
 *
 * - run 行 = operation/run 级（一条逻辑执行实例）；
 * - attempt 行 = 物理 provider 调用级（失败也有一行，禁止重跑覆盖首次失败）；
 * - assertion 行 = 判据结果（由带评判的执行器写入）；
 * - 每行一个 JSON 对象，追加写入、写后即刷新（进程中断也不丢已写行）。
 */
public final class ExperimentRecorder {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ExperimentRecorder() {
    }

    /** 三流 sink：run/attempt/assertion 三类记录 */
    public interface Sink {
        void writeRun(Map<String, Object> row);

        void writeAttempt(Map<String, Object> row);

        void writeAssertion(Map<String, Object> row);
    }

    /** 文件 sink：<dir>/runs.jsonl、attempts.jsonl、assertions.jsonl（目录自动创建） */
    public static Sink fileSink(Path dir) throws IOException {
        Files.createDirectories(dir);
        return new Sink() {
            private BufferedWriter runs;
            private BufferedWriter attempts;
            private BufferedWriter assertions;

            private BufferedWriter writer(String name) throws IOException {
                return Files.newBufferedWriter(dir.resolve(name), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }

            private synchronized void write(BufferedWriter w, Map<String, Object> row) {
                try {
                    w.write(JSON.writeValueAsString(row));
                    w.write("\n");
                    w.flush();
                } catch (IOException e) {
                    throw new RuntimeException("实验记录写入失败", e);
                }
            }

            @Override
            public void writeRun(Map<String, Object> row) {
                try {
                    if (runs == null) {
                        runs = writer("runs.jsonl");
                    }
                    write(runs, row);
                } catch (IOException e) {
                    throw new RuntimeException("实验记录写入失败", e);
                }
            }

            @Override
            public void writeAttempt(Map<String, Object> row) {
                try {
                    if (attempts == null) {
                        attempts = writer("attempts.jsonl");
                    }
                    write(attempts, row);
                } catch (IOException e) {
                    throw new RuntimeException("实验记录写入失败", e);
                }
            }

            @Override
            public void writeAssertion(Map<String, Object> row) {
                try {
                    if (assertions == null) {
                        assertions = writer("assertions.jsonl");
                    }
                    write(assertions, row);
                } catch (IOException e) {
                    throw new RuntimeException("实验记录写入失败", e);
                }
            }
        };
    }
}
