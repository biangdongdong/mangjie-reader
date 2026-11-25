package com.mangjie.reader.model;

/** 一个页面条目：列表行或网格格子的文字与动作键 */
public class Item {
    public final String label;
    /** 动作键。搜索页边搜边插入时行号会整体前移，键里的下标要跟着改，所以不能是 final */
    public String key;

    public Item(String label, String key) {
        this.label = label;
        this.key = key;
    }

    public Item(String label) {
        this(label, "");
    }
}
