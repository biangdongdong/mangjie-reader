package com.mangjie.reader.view;

import com.mangjie.reader.model.Item;
import java.util.ArrayList;
import java.util.List;

/**
 * 界面说明的集中存放处：各页行名里的括号解释都收在这里，
 * 界面上只留功能名，读屏用户到「帮助 → 界面说明」逐条听。
 */
public final class HelpDoc {

    private HelpDoc() {}

    public static List<Item> interfaceItems() {
        List<Item> items = new ArrayList<Item>();
        items.add(new Item("书籍详情：封面、简介、字数", "sec"));
        items.add(new Item("换源：用别的书源找这本书", "sec"));
        items.add(new Item("换搜索范围：全部/分组/单源", "sec"));
        items.add(new Item("精准搜索：只留书名作者含关键词的", "sec"));
        items.add(new Item("看全部订阅的最新：各源抓几篇汇总", "sec"));
        items.add(new Item("订阅源管理：导入、启停", "sec"));
        items.add(new Item("导入订阅源：选文件", "sec"));
        items.add(new Item("导入书源：选文件", "sec"));
        items.add(new Item("分组管理：新建、改名、删除、加书", "sec"));
        items.add(new Item("选择分组：列出所有分组", "sec"));
        items.add(new Item("添加网址：粘贴网页地址加入书架", "sec"));
        items.add(new Item("书架管理：批量移出收藏", "sec"));
        items.add(new Item("日志：最近的运行记录", "sec"));
        items.add(new Item("恢复默认分组：本地、网络", "sec"));
        items.add(new Item("卸载旧版：会一并清掉它的数据", "sec"));
        items.add(new Item("导出备份到存储卡：设置 + 书源 + 规则", "sec"));
        items.add(new Item("上传备份到服务器：设置、书源、书架、进度一起走", "sec"));
        items.add(new Item("从服务器取回备份：换手机后恢复书架和进度", "sec"));
        items.add(new Item("版式设置：只影响阅读页正文，改完回到正文即生效", "sec"));
        items.add(new Item("手势方案：只在本应用里生效；读屏开着时手势归读屏，应用不抢", "sec"));
        return items;
    }
}
