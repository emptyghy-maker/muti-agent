package com.ghy.mutiagent.model;

import lombok.Data;

/** 预算超支知情放行裁决请求（confirm=true 超支自付发布 / false 返回美食调整） */
@Data
public class BudgetConfirmRequest {
    private Boolean confirm;
}
