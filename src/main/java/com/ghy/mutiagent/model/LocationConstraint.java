package com.ghy.mutiagent.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 用户提出的“某地附近”约束。坐标解析成功后由候选召回和发布检查确定性执行，
 * 不把地理距离判断交给模型猜测。
 */
@Data
public class LocationConstraint {
    public static final String RESOLVED = "RESOLVED";
    public static final String UNRESOLVED = "UNRESOLVED";

    private String anchorName;
    private Double lng;
    private Double lat;
    /** “附近”的默认半径，单位公里。 */
    private double radiusKm = 3.0;
    /** ATTRACTION / FOOD / HOTEL；为空表示三个通道都适用。 */
    private List<String> scopes = new ArrayList<>();
    private String status;
    private String sourceText;
}
