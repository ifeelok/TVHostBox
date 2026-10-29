# TVHostBox · 电视Host管家

适配 **移动魔百盒 CM311-1sa（Android 9，已 root）** 的电视端 hosts 管理工具。
遥控器全程可操作，零第三方依赖，APK 体积小巧。

![CI](https://github.com/ifeelok/TVHostBox/actions/workflows/android.yml/badge.svg)

## 功能

| 功能 | 说明 |
|---|---|
| **Hosts 管理** | 列出 `/system/etc/hosts` 全部条目，选中即可删除；支持手动添加、自动备份、从备份恢复 |
| **扫码配置** | 电视端显示二维码，手机扫码后进入卡片式配置页：增删单条、一键替换、全文编辑（内置 HTTP 服务，端口 `18123`） |
| **CF 优选** | 从 Cloudflare 官方 IPv4 网段采样 → TCP 443 并发延迟测试 → HTTPS 下载测速 → 筛出前 8 名 |
| **优选应用** | 把选中的优选 IP 写为指定域名的解析（写进 hosts，Emby 直连等场景立即可用） |

## 工作原理

- 修改的是系统级 `/system/etc/hosts`，在 `getaddrinfo` 阶段生效，**优先于一切 DNS**；
  因此与 CMFA（Clash Meta）共存时，配合 `IP-CIDR,...,DIRECT,no-resolve` 规则即可让
  Emby 等域名走优选 IP 直连。
- 写入流程：自动备份原文件到 `/sdcard/hostbox/` → `mount -o rw,remount` →
  覆盖 → `chmod 644` → 回读校验。写入失败不会破坏原文件。
- 扫码配置：App 在盒子 `0.0.0.0:18123` 起一个 HTTP 服务，二维码内容即
  `http://<盒子IP>:18123`；手机浏览器打开后可查看条目列表、逐条删除、
  填写「IP + 域名」写入、或展开全文编辑。手机端只发指令，写入仍由盒子端完成，
  所以每次改动都会照常备份到 `/sdcard/hostbox/`。

## 安装

到 [Releases](../../releases) 下载最新 APK（或从 Actions 的 artifact 下载），然后：

```bash
adb connect <盒子IP>:5555
adb install TVHostBox-<版本号>.apk
```

> 需要 root。首次写入 hosts 时 su 会弹授权（如有），允许即可。
> 从 v1.0.0（临时签名版）升级需先 `adb uninstall tv.hostbox.app`；v1.0.1 起签名固定，之后可正常覆盖升级。

## 使用

1. 打开 App，顶部三个页签：**Hosts 管理 / 扫码配置 / CF 优选**。
2. **删除某条 host**：Hosts 管理页 → 选中条目 → OK 键 → 确认删除。
3. **手机配置**：扫码配置页 → 手机扫屏幕上的二维码 → 在网页里点条目删除、
   填 IP 和域名写入、或展开「高级 · 全文编辑」批量改 → 保存即生效（自动备份）。
4. **CF 优选**：CF 优选页 → 「开始优选」→ 等待两轮测速 → 选中一个结果 →
   输入要应用的域名（如 `emby-proxy.example.com`）→ 写入。

## 自己编译

推送任意 commit 到 `main` 分支即触发 GitHub Actions 自动编译，
APK 在构建结果的 Artifacts 里；打 `v*` 标签会同时创建 Release 并附上 APK。

本地编译（可选）：

```bash
# 需要 JDK 17 + Android SDK
gradle assembleRelease
```

## 目录结构

```
app/src/main/java/tv/hostbox/app/
├── MainActivity.java    # TV 三页签 UI（遥控器可操作）
├── HostsManager.java    # hosts 解析 / 校验 / root 写入 / 备份恢复
├── RootShell.java       # su 命令执行（零依赖版 libsu）
├── ConfigServer.java    # 手机扫码配置的 HTTP 服务（含 /api/state /api/set /api/delete）
└── CfOptimizer.java     # Cloudflare 优选（延迟 + 下载测速）
app/src/main/assets/mobile.html          # 手机端配置页（自成一体，不依赖任何 CDN）
app/src/main/java/io/nayuki/qrcodegen/   # QR 码生成（Nayuki, MIT）
```

## 兼容性

- 已验证目标：魔百盒 CM311-1sa-ZG2 / Android 9 / armeabi-v7a+（通用字节码）
- 任何 **已 root、可 remount /system** 的 Android 5.0+ 设备理论上都可用
- Android TV / 手机均可安装，UI 按横屏电视设计

## 免责声明

本项目仅用于管理自有设备的本地域名解析。请遵守当地法律法规使用。

## License

[MIT](./LICENSE)（含内嵌的 [Nayuki QR-Code-generator](https://github.com/nayuki/QR-Code-generator)，MIT）
