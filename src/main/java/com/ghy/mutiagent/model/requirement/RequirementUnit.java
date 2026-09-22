package com.ghy.mutiagent.model.requirement;

/** O2：数量的业务单位，防止把餐次、候选店和菜品数量混用。 */
public enum RequirementUnit {
    MEAL_OCCASION,
    CANDIDATE_COUNT,
    DISH_COUNT,
    BOOLEAN
}
