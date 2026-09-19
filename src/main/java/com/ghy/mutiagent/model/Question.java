package com.ghy.mutiagent.model;

import lombok.Data;

import java.util.List;

/**
 * 问询问题：text 为问题文案，options 为前端渲染成按钮的快捷选项（可点击或自由输入）。
 */
@Data
public class Question {
    private String field;
    private String text;
    private List<String> options;
}
