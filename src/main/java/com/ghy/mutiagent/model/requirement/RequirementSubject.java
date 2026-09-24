package com.ghy.mutiagent.model.requirement;

/** O2：需求作用对象。第一版完整验收午餐、晚餐、小吃与餐饮自理。 */
public enum RequirementSubject {
    BREAKFAST,
    LUNCH,
    DINNER,
    /** 餐食类型：小吃/夜宵，与午餐、晚餐时间窗正交。 */
    SNACK,
    /** 餐食类型：非小吃的正餐，与午餐、晚餐时间窗正交。 */
    MAIN_MEAL,
    SNACK_ALLOWED,
    NO_FOOD,
    LUNCH_RESTAURANT,
    DINNER_RESTAURANT,
    DISH,
    UNKNOWN
}
