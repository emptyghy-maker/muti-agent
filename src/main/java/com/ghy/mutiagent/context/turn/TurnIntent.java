package com.ghy.mutiagent.context.turn;

/** 当前一轮用户希望系统执行的动作，与旅行内容需求分开保存。 */
public enum TurnIntent {
    RESTART,
    STEP_BACK,
    COMPLETE_PREFERENCE,
    ANSWER_CURRENT_FIELD,
    UPDATE_PREFERENCE,
    ADD_REQUIREMENT,
    CORRECT_REQUIREMENT,
    REVOKE_REQUIREMENT,
    SKIP_ATTRACTION,
    SKIP_FOOD,
    SKIP_HOTEL,
    EXPAND_CANDIDATES,
    REFINE_CANDIDATES,
    ADJUST_ITINERARY,
    UNKNOWN
}
