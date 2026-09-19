package com.ghy.mutiagent.model;

import lombok.Data;

/** B 方案：疲劳超载草稿裁决请求（confirm=true 发布行程 / false 返回景点调整） */
@Data
public class FatigueConfirmRequest {
    private Boolean confirm;
}
