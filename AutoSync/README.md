# AutoSync —— 服务器配置自动同步 GitHub 插件

在 **翼龙面板（Pterodactyl）** 且 **禁止执行 Linux 指令 / git** 的环境下，把服务器配置文件自动同步到 GitHub 仓库。
纯 Java 实现，通过 GitHub REST API 通信，**不需要 shell、git、cron、系统权限**。

## 功能

- 每 N 分钟（默认 5 分钟）自动**双向同步**指定目录
- 本地改动 → 推送 GitHub；仓库有而本地没有的文件 → 拉取到本地
- 排除录像等大文件目录（`replay/` 等）
- 支持 `/autosync now` 手动立即同步、`/autosync reload` 重载配置

## 构建

需要 JDK 21 + Maven 3.9+：

```bash
mvn -B package
```

产物在 `target/AutoSync-1.0.0.jar`（已内置 Kotlin 标准库与 Gson，服务器无需额外依赖）。

> GitHub Actions 已配置（`.github/workflows/build.yml`），推送到 `AutoSync/**` 自动构建并上传产物。

## 安装

1. 把 `AutoSync-1.0.0.jar` 上传到翼龙面板文件管理器的 `plugins/` 目录
2. 重启服务器，插件会生成 `plugins/AutoSync/config.yml`
3. 编辑 `config.yml`：

```yaml
github:
  owner: "ChenRay-team"      # GitHub 用户名/组织
  repo: "Server-MCPR"        # 仓库名
  token: ""                  # 你的 PAT（见下）
  branch: "main"

sync:
  remote-folder: "ISeeYou"        # 仓库里的目录
  local-folder: "ISeeYou"         # 服务器上的目录（相对服务器根）
  interval-minutes: 5             # 自动同步间隔（分钟）
  sync-on-enable: true
  sync-on-disable: true
  exclude:
    - "replay"
    - "instant"
    - "suspicious"
```

4. 控制台执行 `/autosync now` 测试同步

## 生成 PAT（Personal Access Token）

1. 打开 https://github.com/settings/tokens → **Generate new token (classic)**
2. 勾选 **`repo`** 权限
3. 生成后复制，填到 `config.yml` 的 `github.token`
4. **不要把 token 提交到仓库！**（仓库本身是公开的，token 只会写在服务器本地配置里）

## 说明

- 双向同步的冲突策略：**本地优先**——本地有就推送，远程独有才拉取
- 只同步文本配置文件，`exclude` 里排除的目录（如录像）不上传
- 出错时日志会打印 `[AutoSync] 同步失败`，打开 `debug: true` 可看详细堆栈
