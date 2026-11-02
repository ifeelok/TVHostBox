# 逻辑测试

电视上的操作没法自动化，但「算错了」这件事可以。这里放的是**纯逻辑测试**：
不连盒子、不需要 Android Studio，在电脑上一条命令跑完。

```sh
./tools/tests/run.sh
```

需要 JDK + Android SDK 的 `android.jar`（脚本会自动在 `ANDROID_SDK_ROOT` /
`ANDROID_HOME` / `~/Library/Android/sdk` 里找）。

## 为什么不用 JUnit

这个项目是零依赖的：没有 AndroidX，也就没有 `androidTest` 能跑的地方。
测试都是带 `main()` 的普通类，编译时把 App 的 `app/src/main/java` 挂在 `sourcepath`
上，**直接调真方法**，不复制一份逻辑来测。

> 这条是踩过坑才立下的：早期测试是把 `upsertText` 的逻辑复制一份去测，
> 结果「复制品全绿、真代码里的 `replaced` 漏赋值」，重复行一直没被发现。

## 各管什么

| 测试 | 覆盖 |
|---|---|
| `HostsRealTest` | hosts 文本变换：改 IP / 删域名 / 同行多域名 / 域名校验 |
| `NoteTest` | 行尾注释：位置+延迟+速度怎么拼、写进去怎么读回来、重复写不多行 |
| `BackupTest` | 备份命名（v 序号）、排序、显示文案、改名净化、查看内容排版 |
| `WriteTest` | 写入/恢复脚本（交给真 `/bin/sh` 在临时目录跑，含软链接分支）、写操作门禁 |
| `RawTest` | GitHub raw 加速：挑最快（`-1` 必须排除）、注释文案、撤销时认不认自己的条目 |
| `HostCheckTest` | hosts 体检：按 IP 合并、报告顺序（不通在前）、一行文案 |
| `UiTest` | 纯 UI 计算：UI 缩放、二维码尺寸、三步说明排不排得下 |
| `GeoTest` | CF 网段归属、12MB 分片 |

## 两条约定

**能纯函数就别碰 IO。** 需要 root 的部分（真写 `/system/etc/hosts`）拆成
「脚本字符串构造器 + 纯文本变换」，测试拿真 shell 在临时目录里跑，逻辑部分直接调。

**断言要能变红。** 加完测试顺手做个变异（把实现改坏、看测试是否失败），
杀不掉的断言等于没写。
