package com.ghy.mutiagent.model.requirement;

/** O2：单条需求相对于某个计划版本的验收结论。 */
public enum FulfillmentStatus {
    SATISFIED,
    VIOLATED,
    UNVERIFIABLE,
    NOT_EVALUATED,
    STALE
}
