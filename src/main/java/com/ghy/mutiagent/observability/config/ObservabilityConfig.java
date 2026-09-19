package com.ghy.mutiagent.observability.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghy.mutiagent.observability.collection.DefaultObsRecorder;
import com.ghy.mutiagent.observability.collection.EventBuffer;
import com.ghy.mutiagent.observability.collection.ObsEventSink;
import com.ghy.mutiagent.observability.collection.ObsInstrumentation;
import com.ghy.mutiagent.observability.collection.ObsPayloadStore;
import com.ghy.mutiagent.observability.integration.ObsOperationReconciler;
import com.ghy.mutiagent.observability.persistence.JdbcObsPayloadStore;
import com.ghy.mutiagent.observability.persistence.ObsBatchWriter;
import com.ghy.mutiagent.observability.persistence.ObsEventRowMapper;
import com.ghy.mutiagent.observability.persistence.ObsPayloadRowMapper;
import com.ghy.mutiagent.observability.persistence.ObsRunMapper;
import com.ghy.mutiagent.observability.persistence.ObsSpanMapper;
import com.ghy.mutiagent.observability.persistence.ObsUsageAttemptMapper;
import com.ghy.mutiagent.observability.persistence.OperationProjectionMapper;
import com.ghy.mutiagent.service.ModelPricing;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Observability 装配（开发文档 §7.3）：enabled=false 装配 noop，
 * 缺 obs_ 表不会导致应用无法启动；批写线程独立于 chatStreamExecutor。
 */
@Configuration
public class ObservabilityConfig {

    @Bean
    public EventBuffer obsEventBuffer(ObservabilityProperties props) {
        return new EventBuffer(props.getBuffer().getCapacity());
    }

    @Bean
    public ObsEventSink obsEventSink(EventBuffer buffer,
                                     ObjectProvider<ObsPayloadStore> payloadStore,
                                     ObservabilityProperties props) {
        if (!props.isEnabled() || !props.getCapture().isEnabled()) {
            return ObsEventSink.noop();
        }
        return new DefaultObsRecorder(buffer, payloadStore.getIfAvailable(),
                "trace-service", props.getPayload().getMaxBytes());
    }

    /** 业务薄埋点门面：始终可装配；模块关闭时内部为 noop 记录器 */
    @Bean
    public ObsInstrumentation obsInstrumentation(ObsEventSink obsEventSink,
                                                 ObservabilityProperties props) {
        ObsInstrumentation instrumentation = new ObsInstrumentation(obsEventSink);
        instrumentation.setEnabled(props.isEnabled());
        return instrumentation;
    }

    @Bean
    @ConditionalOnProperty(prefix = "observability", name = "enabled", havingValue = "true")
    public ObsPayloadStore obsPayloadStore(ObsPayloadRowMapper mapper,
                                           ObservabilityProperties props) {
        return new JdbcObsPayloadStore(mapper, props.getRetention().getDays());
    }

    @Bean
    public ObsOperationReconciler obsOperationReconciler(OperationProjectionMapper projection,
                                                         ObsRunMapper runMapper) {
        return new ObsOperationReconciler(projection, runMapper);
    }

    @Bean
    public com.ghy.mutiagent.observability.application.ObsMetricsService obsMetricsService(
            ObsRunMapper runMapper, ObsSpanMapper spanMapper, ObsUsageAttemptMapper attemptMapper) {
        return new com.ghy.mutiagent.observability.application.ObsMetricsService(
                runMapper, spanMapper, attemptMapper);
    }

    @Bean
    public com.ghy.mutiagent.observability.integration.UsageLegacyAdapter obsUsageLegacyAdapter(
            com.ghy.mutiagent.repository.mapper.UsageRecordMapper usageMapper, ModelPricing pricing) {
        return new com.ghy.mutiagent.observability.integration.UsageLegacyAdapter(usageMapper, pricing);
    }

    @Bean
    public com.ghy.mutiagent.observability.application.ObsRunQueryService obsRunQueryService(
            ObsRunMapper runMapper, ObsSpanMapper spanMapper, ObsEventRowMapper eventMapper,
            com.ghy.mutiagent.observability.persistence.ObsAnnotationMapper annotationMapper,
            com.ghy.mutiagent.observability.application.ObsMetricsService metrics,
            com.ghy.mutiagent.observability.integration.UsageLegacyAdapter legacyAdapter,
            ObsOperationReconciler reconciler, ObjectProvider<ObsEventSink> sink, ObjectMapper json) {
        return new com.ghy.mutiagent.observability.application.ObsRunQueryService(
                runMapper, spanMapper, eventMapper, annotationMapper, metrics, legacyAdapter,
                reconciler, sink, json);
    }

    @Bean
    public com.ghy.mutiagent.observability.application.ObsPayloadQueryService obsPayloadQueryService(
            ObsPayloadRowMapper payloadMapper, ObsRunMapper runMapper) {
        return new com.ghy.mutiagent.observability.application.ObsPayloadQueryService(
                payloadMapper, runMapper);
    }

    @Bean
    public com.ghy.mutiagent.observability.application.ObsAuditService obsAuditService(
            com.ghy.mutiagent.observability.persistence.ObsAuditRowMapper auditMapper) {
        return new com.ghy.mutiagent.observability.application.ObsAuditService(auditMapper);
    }

    @Bean
    public com.ghy.mutiagent.observability.application.ObsTaskService obsTaskService(
            com.ghy.mutiagent.observability.persistence.ObsOptimizationTaskMapper taskMapper,
            com.ghy.mutiagent.observability.persistence.ObsChangeRevisionMapper revisionMapper,
            com.ghy.mutiagent.observability.persistence.ObsRunLinkMapper linkMapper,
            com.ghy.mutiagent.observability.persistence.ObsAnnotationMapper annotationMapper,
            ObsRunMapper runMapper) {
        return new com.ghy.mutiagent.observability.application.ObsTaskService(
                taskMapper, revisionMapper, linkMapper, annotationMapper, runMapper);
    }

    @Bean
    public com.ghy.mutiagent.observability.application.ObsExperimentService obsExperimentService(
            com.ghy.mutiagent.observability.persistence.ObsExperimentMapper experimentMapper,
            com.ghy.mutiagent.observability.persistence.ObsExperimentResultMapper resultMapper) {
        return new com.ghy.mutiagent.observability.application.ObsExperimentService(
                experimentMapper, resultMapper);
    }

    @Bean
    public com.ghy.mutiagent.observability.application.ObsExportService obsExportService(
            ObsRunMapper runMapper, ObsEventRowMapper eventMapper,
            com.ghy.mutiagent.observability.persistence.ObsAuditRowMapper auditMapper) {
        return new com.ghy.mutiagent.observability.application.ObsExportService(
                runMapper, eventMapper, auditMapper);
    }

    @Bean
    public com.ghy.mutiagent.observability.lifecycle.ObsRetentionJob obsRetentionJob(
            ObsPayloadRowMapper payloadMapper) {
        return new com.ghy.mutiagent.observability.lifecycle.ObsRetentionJob(payloadMapper);
    }

    /** TraceService 出口接线：默认 noop，开启后接真实记录器（不改变 TraceService 签名与原语义） */
    @Bean
    public ObsWiring obsWiring(com.ghy.mutiagent.trace.TraceService traceService,
                               ObsEventSink obsEventSink) {
        traceService.setObsEventSink(obsEventSink);
        return new ObsWiring();
    }

    public record ObsWiring() {
    }

    @Bean
    @ConditionalOnProperty(prefix = "observability.capture", name = "enabled", havingValue = "true")
    public ObsBatchWriter obsBatchWriter(ObsRunMapper runMapper, ObsSpanMapper spanMapper,
                                         ObsEventRowMapper eventMapper,
                                         ObsUsageAttemptMapper attemptMapper,
                                         ObjectMapper json, ModelPricing modelPricing) {
        return new ObsBatchWriter(runMapper, spanMapper, eventMapper, attemptMapper,
                json, modelPricing);
    }

    /** 独立批写线程（不用 chatStreamExecutor）：拉取缓冲 → 批写 → 缺口标记 */
    @Bean(destroyMethod = "shutdownNow")
    @ConditionalOnProperty(prefix = "observability.capture", name = "enabled", havingValue = "true")
    public ScheduledExecutorService obsBatchScheduler(EventBuffer buffer,
                                                      ObsBatchWriter writer,
                                                      ObservabilityProperties props) {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "obs-batch");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(() -> {
            try {
                var batch = buffer.drain(props.getBatch().getSize());
                if (!batch.isEmpty()) {
                    writer.write(batch);
                }
                long gaps = buffer.takeGapCount();
                if (gaps > 0) {
                    writer.markGap(java.util.List.of("GLOBAL-GAP:" + gaps));
                }
            } catch (RuntimeException ignored) {
                // 批写线程异常只计数，不重试业务
            }
        }, 500, Math.max(100, props.getBatch().getIntervalMs()), TimeUnit.MILLISECONDS);
        return executor;
    }
}
