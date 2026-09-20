package com.ghy.mutiagent.service.validation;

import com.ghy.mutiagent.model.WebFoodCandidate;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 网搜美食入库校验：长度/价格区间/控制字符/脚本与注释载荷阻断（防恶意写入） */
class WebFoodValidatorTest {

    private static WebFoodCandidate w(String name, String cuisine, String price,
                                      String address, String why) {
        WebFoodCandidate c = new WebFoodCandidate();
        c.setName(name);
        c.setCuisine(cuisine);
        c.setAvgPrice(new BigDecimal(price));
        c.setAddress(address);
        c.setWhy(why);
        return c;
    }

    @Test
    void normalItemPasses() {
        List<String> reasons = WebFoodValidator.validate(
                w("湖畔餐厅", "本地菜", "80", "园区金鸡湖", "湖边环境好"));
        assertTrue(reasons.isEmpty(), "正常条目应通过：" + reasons);
    }

    @Test
    void rejectsScriptAndMarkupPayloads() {
        assertFalse(WebFoodValidator.validate(
                w("<script>alert(1)</script>", "本地菜", "80", "园区", "x")).isEmpty());
        assertFalse(WebFoodValidator.validate(
                w("正常店", "<img src=x>", "80", "园区", "x")).isEmpty());
        assertFalse(WebFoodValidator.validate(
                w("正常店", "本地菜", "80", "园区", "onclick=alert(1)")).isEmpty());
    }

    @Test
    void rejectsSqlCommentAndKeywordPayloads() {
        assertFalse(WebFoodValidator.validate(
                w("正常店--", "本地菜", "80", "园区", "x")).isEmpty());
        assertFalse(WebFoodValidator.validate(
                w("正常店", "本地菜", "80", "园区", "select * from t_user")).isEmpty());
        assertFalse(WebFoodValidator.validate(
                w("正常店", "本地菜", "80", "园区", "drop table t_restaurant")).isEmpty());
    }

    @Test
    void rejectsOutOfRangeAndMissingPrice() {
        WebFoodCandidate noPrice = w("正常店", "本地菜", "0", "园区", "x");
        noPrice.setAvgPrice(null);
        assertFalse(WebFoodValidator.validate(noPrice).isEmpty());
        assertFalse(WebFoodValidator.validate(
                w("正常店", "本地菜", "99999", "园区", "x")).isEmpty());
        assertFalse(WebFoodValidator.validate(
                w("正常店", "本地菜", "-5", "园区", "x")).isEmpty());
    }

    @Test
    void rejectsControlCharsAndBlankName() {
        assertFalse(WebFoodValidator.validate(
                w("店\u0000名", "本地菜", "80", "园区", "x")).isEmpty());
        assertFalse(WebFoodValidator.validate(
                w(" ", "本地菜", "80", "园区", "x")).isEmpty());
    }

    @Test
    void allowsOptionalAddressAndWhy() {
        assertTrue(WebFoodValidator.validate(
                w("正常店", "本地菜", "80", "", "")).isEmpty());
    }
}
