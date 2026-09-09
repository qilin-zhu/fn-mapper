# 飞牛连接器（fn-mapper）

在手机上通过 HTTPS 代理的 CONNECT 隧道，把飞牛 fnOS 的端口安全地映射到手机本地。不依赖 ADB / Frida / root，纯 Android Socket 实现。

## 功能

- **端口映射**：通过已有 HTTP/HTTPS 代理建立 CONNECT 隧道，将飞牛端口（如 5666）映射到手机 `127.0.0.1`，随时随地像访问本机一样访问 NAS
- **三页启动器**：左右滑动切换「映射」/「飞牛影视」/「飞牛音乐」三页，每页一键打开对应 App
- **端口一键导入**：配合 fnos-scanner 扫描 NAS 端口，扫码或填写在线地址即可自动获取端口清单（默认并入 5666、5667）
- **端口列表编辑器**：每个端口显示对应服务（应用名 / Docker 容器名），支持新增、编辑、删除
- **局域网访问**：开启后监听 `0.0.0.0`，同网段设备可通过手机 IP 直连映射端口
- **自动恢复**：上次开启映射后，再次打开 App 自动启动映射

## 快速开始

### 1. 配置映射

打开 App → 右上角齿轮进入映射设置，填写：

| 字段 | 说明 | 示例 |
| --- | --- | --- |
| 代理地址 | HTTP/HTTPS 代理（CONNECT 隧道出口） | `https://proxy.example.com:8443` |
| 代理账号 / 密码 | 可选 | — |
| 飞牛地址 | 飞牛主机地址，只需 IP | `192.168.31.18` |
| 映射端口 | 要映射的端口，多个用逗号分隔 | `5666, 5667` |
| 在线地址 | fnos-scanner 的端口 JSON 地址（可选） | `http://192.168.31.18:8787/ports.json` |

> 映射规则：每个端口 P 独立映射为 `手机127.0.0.1:P → 飞牛主机:P`（经代理转发）。

### 2. 一键导入端口（可选）

在 NAS 上运行 [fnos-scanner](fnos-scanner/)，扫描应用/Docker 端口后：

- 点设置页的「扫描二维码」扫页面二维码，或
- 在「在线地址」填入 `http://NAS地址:8787/ports.json` 后点「获取端口」

端口清单自动填入，编辑按钮可打开满屏端口列表查看每个端口对应的服务。

### 3. 启动映射

回到首页点「启动映射」即可。开启「局域网访问」后，状态行会显示手机局域网 IP（优先 `192.168.*`），同网段设备可通过该 IP 访问映射端口。

## fnos-scanner（可选组件）

Go 编写的 fnOS 端口扫描器，扫描系统应用与 Docker 容器占用的端口，并生成二维码 / `ports.json` 供 App 一键导入。

```bash
# 在 NAS（linux amd64）上，将 trim-cli 与二进制放入同目录
./start.sh 0.0.0.0:8787
```

浏览器打开 `http://NAS地址:8787` 扫描后即可导出端口清单。

## 构建

```bash
# Android（需要 Android Studio / Gradle）
./gradlew assembleDebug

# fnos-scanner（可选，需 Go 1.21+）
cd fnos-scanner && GOOS=linux GOARCH=amd64 CGO_ENABLED=0 go build -trimpath -o fnos-scanner-linux-amd64 .
```

## 链接

- GitHub：https://github.com/qilin-zhu/fn-mapper
- 作者：https://space.bilibili.com/351478349
