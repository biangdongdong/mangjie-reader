# 盲界阅读

面向视障用户的安卓本地阅读器。单 Activity、View 体系、纯 Java，不用 Compose，不依赖 Gradle 插件。

包名 `com.mangjie.reader`。本仓库对应当前内测版本 **1.1.47 / 523**。

## 它解决什么

读屏用户读书，难点不是「把字放大」，而是三件事：

- **正文要能连续念下去**，并且念到哪儿、下一句是哪句，随时说得清；
- **每个控件都要有可朗读的名称、状态和位置**，焦点移动要有播报；
- **不靠触屏也能走完全流程**——五向键、数字键、软键都能完成主要操作，触屏手势只作补充。

所以这个 App 里没有「视觉上好看」的取舍，只有「读屏能不能听懂」的取舍：自绘列表、自绘光标条、
每个界面元素都单独挂无障碍节点与朗读文本，翻页与朗读共用一套焦点模型。

## 构建

需要：

- JDK 17
- Android SDK：build-tools 33.0.2、platform android-33
- 依赖 jar 已在 `libs/`（json、jsoup、rhino）

```
./build.sh 1.1.46
```

产物在 `dist/盲界阅读-demo-<版本号>.apk`。不带参数时取 `AndroidManifest.xml` 里的 `versionName`。

**签名密钥不在仓库里**（`keystore.jks` 属私有材料，按 GPL-3.0 与安全常识都不该公开）。
`build.sh` 默认读取根目录的 `keystore.jks`，请自备一份，或改成用 debug 密钥签名后再安装。
没有密钥只能编出未签名包，装不上，但源码本身完整可编译。

**离线语音版所需的原生库**（`libs-jni/arm64-v8a/` 下的 `libonnxruntime.so` 与 `libsherpa-onnx-jni.so`，
共约 24 MB）未随仓库分发，普通版构建不需要它们。要复现 `./build.sh <版本> --tts`，
请从 sherpa-onnx 上游取得同名库放回该目录。

## 目录结构

```
src/com/mangjie/reader/
  MainActivity.java        单 Activity 宿主：界面栈、键位、朗读
  App.java                 数据根（私有 files）、导出目录
  a11y/                    读屏判定、无障碍节点、触摸探索
  gesture/                 手势
  input/                   键位与输入
  media/                   字体、图片
  model/                   界面模型（Screen / Item）
  net/                     书源、请求、规则引擎、日志上传
  reader/                  解析、分章、切块、净化、导出
  shelves/                 书架
  speech/                  引擎封装、看门狗、音道预热、点明通道
  tools/                   迁移、线程、工具
  view/                    各界面构建
```

模块化的方针是：新功能按块拆成独立类，落到上面对应子包，不往 `MainActivity` 里继续堆。

## 当前状态

1.1.47 动的是朗读判死的总控制层：窗口按「引擎 + 倍速 + 字数」折算，判据收成一套状态位，
纯标点句跳过不再静默；同时修了订阅源缺协议头点不开的问题。正文朗读的重复播报、
读屏与朗读抢焦点等问题仍未动，重构方案见仓库内 `方案-语音层重构.md`。

## 许可

本项目以 **GNU General Public License v3.0** 授权，见 `LICENSE`。

本项目借鉴并部分等价复现了「开源阅读 Legado」（<https://github.com/LegadoTeam/legado>，
Copyright (C) 2019-2023 gedoor，GPL-3.0）。该地址由原个人仓库
<https://github.com/gedoor/legado> 迁入，两者为同一项目，版权人未变。
上游与本项目的版权声明、对上游的使用方式与修改说明、
第三方组件许可，全部见 `NOTICE`；涉及上游的逐文件清单见 `来源与许可.txt`。
被参照的源文件在文件头保留了来源注释。

本仓库的源码即 GPL-3.0 意义上的「对应源码」，含全部 Java 源码、清单、资源、构建脚本、
依赖 jar 与 JNI 库，按 README 的构建步骤可编译出同一版本。
再分发时请保留许可与来源说明，并以同一许可授权：

```
https://github.com/biangdongdong/mangjie-reader
```

第三方组件随源码一起分发：jsoup（MIT）、Mozilla Rhino（MPL-2.0）、
sherpa-onnx（Apache-2.0）、ONNX Runtime（MIT）。

书源与书籍内容来自第三方网站，与本项目无关；本项目不存储、不分发任何书籍内容。
