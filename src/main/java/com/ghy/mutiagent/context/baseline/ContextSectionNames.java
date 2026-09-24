package com.ghy.mutiagent.context.baseline;

/** 固定段落名保证不同 Agent、不同版本的上下文可以横向比较。 */
public final class ContextSectionNames {

    public static final String SYSTEM_PROMPT = "SYSTEM_PROMPT";
    public static final String CURRENT_TURN = "CURRENT_TURN";
    public static final String CURRENT_QUESTION = "CURRENT_QUESTION";
    public static final String PREFERENCE = "PREFERENCE";
    public static final String ACTIVE_REQUIREMENTS = "ACTIVE_REQUIREMENTS";
    public static final String UNRESOLVED_REQUIREMENTS = "UNRESOLVED_REQUIREMENTS";
    public static final String DERIVED_POLICY = "DERIVED_POLICY";
    public static final String LOCATION_CONSTRAINT = "LOCATION_CONSTRAINT";
    public static final String CANDIDATE_POOL = "CANDIDATE_POOL";
    public static final String LOCKED_SELECTIONS = "LOCKED_SELECTIONS";
    public static final String SELECTED_ITEMS = "SELECTED_ITEMS";
    public static final String PLAN_DRAFT = "PLAN_DRAFT";
    public static final String VIOLATIONS = "VIOLATIONS";
    public static final String OUTPUT_SCHEMA = "OUTPUT_SCHEMA";

    private ContextSectionNames() {
    }
}
