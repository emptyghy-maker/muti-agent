package com.ghy.mutiagent.context.baseline;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.ghy.mutiagent.observability.collection.PayloadSanitizer;
import com.ghy.mutiagent.trace.Redactor;

/** 对脱敏且键排序后的 JSON 计算稳定 SHA-256；Hash 只用于比较，不承担鉴权。 */
public final class StableContextHasher {

    private final ObjectMapper stableMapper;

    public StableContextHasher(ObjectMapper mapper) {
        this.stableMapper = mapper.copy()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public String canonical(Object value) {
        try {
            return Redactor.mask(stableMapper.writeValueAsString(value));
        } catch (Exception e) {
            return Redactor.mask(String.valueOf(value));
        }
    }

    public String hash(Object value) {
        return PayloadSanitizer.sha256(canonical(value));
    }

    /** 已完成稳定序列化时直接计算 Hash，避免对大 Context 再序列化一次。 */
    public String hashCanonical(String canonical) {
        return PayloadSanitizer.sha256(canonical == null ? "" : canonical);
    }
}
