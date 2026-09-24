package com.ghy.mutiagent.context.agent;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.ghy.mutiagent.observability.collection.PayloadSanitizer;
import com.ghy.mutiagent.trace.Redactor;
import org.springframework.stereotype.Component;

/** 稳定序列化与内容指纹；数组顺序保留，Map/对象字段名稳定排序。 */
@Component
public class AgentContextSerializer {

    private final ObjectMapper mapper;

    public AgentContextSerializer(ObjectMapper source) {
        this.mapper = source.copy()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public String serialize(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new AgentContextBuildException("CONTEXT_SERIALIZATION_FAILED", e);
        }
    }

    public String semanticHash(Object value) {
        return hashSerialized(serialize(value));
    }

    public String hashSerialized(String serialized) {
        return PayloadSanitizer.sha256(Redactor.mask(serialized == null ? "" : serialized));
    }
}
