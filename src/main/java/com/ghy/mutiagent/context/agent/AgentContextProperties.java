package com.ghy.mutiagent.context.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** 第三、第四阶段上下文配置。默认影子构造，任何角色均不直接切换模型入参。 */
@Component
@ConfigurationProperties(prefix = "context.optimization")
public class AgentContextProperties {

    private boolean enabled;
    private boolean shadowEnabled = true;
    private String policyVersion = "context-budget-v1";
    private String rolloutRoles = "";
    private int maxSectionBytes = 1_048_576;
    private CandidateK candidateK = new CandidateK();

    public AgentContextMode modeFor(AgentContextRole role) {
        if (enabled && rolloutRoleSet().contains(role.name())) {
            return AgentContextMode.ENRICHED_ROLLOUT;
        }
        return shadowEnabled ? AgentContextMode.ENRICHED_SHADOW : AgentContextMode.LEGACY_COMPATIBLE;
    }

    public boolean isActive(AgentContextRole role) {
        return modeFor(role) != AgentContextMode.LEGACY_COMPATIBLE;
    }

    public int candidateLimit(AgentContextRole role) {
        return switch (role) {
            case ATTRACTION -> candidateK.attraction;
            case FOOD -> candidateK.food;
            case HOTEL -> candidateK.hotel;
            default -> Integer.MAX_VALUE;
        };
    }

    private Set<String> rolloutRoleSet() {
        if (rolloutRoles == null || rolloutRoles.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(rolloutRoles.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isShadowEnabled() { return shadowEnabled; }
    public void setShadowEnabled(boolean shadowEnabled) { this.shadowEnabled = shadowEnabled; }
    public String getPolicyVersion() { return policyVersion; }
    public void setPolicyVersion(String policyVersion) { this.policyVersion = policyVersion; }
    public String getRolloutRoles() { return rolloutRoles; }
    public void setRolloutRoles(String rolloutRoles) { this.rolloutRoles = rolloutRoles; }
    public int getMaxSectionBytes() { return maxSectionBytes; }
    public void setMaxSectionBytes(int maxSectionBytes) { this.maxSectionBytes = maxSectionBytes; }
    public CandidateK getCandidateK() { return candidateK; }
    public void setCandidateK(CandidateK candidateK) { this.candidateK = candidateK == null ? new CandidateK() : candidateK; }

    public static class CandidateK {
        private int attraction = 12;
        private int food = 16;
        private int hotel = 8;

        public int getAttraction() { return attraction; }
        public void setAttraction(int attraction) { this.attraction = positive(attraction, 12); }
        public int getFood() { return food; }
        public void setFood(int food) { this.food = positive(food, 16); }
        public int getHotel() { return hotel; }
        public void setHotel(int hotel) { this.hotel = positive(hotel, 8); }
        private static int positive(int value, int fallback) { return value > 0 ? value : fallback; }
    }
}
