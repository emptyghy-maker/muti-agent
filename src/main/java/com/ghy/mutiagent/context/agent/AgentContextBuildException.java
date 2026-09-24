package com.ghy.mutiagent.context.agent;

/** 上下文合同无法安全构建时的明确失败。 */
public class AgentContextBuildException extends RuntimeException {
    private final String code;

    public AgentContextBuildException(String code) {
        super(code);
        this.code = code;
    }

    public AgentContextBuildException(String code, Throwable cause) {
        super(code, cause);
        this.code = code;
    }

    public String code() { return code; }
}
