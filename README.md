<p align="center">
  <img src="assets/banner.svg" alt="AiTranslate" width="100%">
</p>

<p align="center">
  <a href="https://github.com/ALingqing/AiTranslate/actions/workflows/ci.yml"><img src="https://github.com/ALingqing/AiTranslate/actions/workflows/ci.yml/badge.svg" alt="Build Status"></a>
  <a href="https://github.com/ALingqing/AiTranslate/releases"><img src="https://img.shields.io/github/v/release/ALingqing/AiTranslate?display_name=tag" alt="Release"></a>
  <img src="https://img.shields.io/badge/license-MIT-blue.svg" alt="License">
  <img src="https://img.shields.io/badge/Paper-1.18%2B-2ea44f.svg" alt="Paper">
  <img src="https://img.shields.io/badge/Java-17%2B-orange.svg" alt="Java">
</p>

# AiTranslate

AiTranslate 是一个面向 Paper 服务端的 Minecraft 多语言 AI 翻译插件。玩家用任意语言在公屏聊天，其他玩家会自动看到自己语言的译文；聊天中的物品名称与介绍（lore）也会被翻译。默认对接 OpenAI 兼容接口，内置 64 种语言的提示词与国旗选择菜单。

## 特性

- 按玩家语言分发：每位玩家看到自己客户端语言（或菜单里手动选择语言）的译文，发送者自己看原文
- 64 种内置语言：覆盖简体中文、繁体中文、英语、日语、韩语、法语、德语、西班牙语、葡萄牙语、俄语、阿拉伯语、印地语、越南语、泰语、印尼语等
- 自动检测：跟随玩家客户端语言（locale）自动匹配；也可在国旗菜单或命令中手动指定
- 保留格式：翻译保留颜色代码与组件结构；聊天前缀与名字格式沿用服务器当前渲染器，兼容其他聊天插件
- 物品翻译：聊天内展示物品（hover）的名称与 lore 一并翻译
- 默认语言可跳过：可让默认语言（中文）的观众直接看原文以省费用（默认关闭）
- 智能跳过：纯数字、纯符号、纯链接自动跳过；可按语言配置正则，跳过“原文已经是该语言”的消息
- 缓存与限流：内存翻译缓存 + 并发上限，避免打爆中转接口
- 多翻译源自动切换：默认 `openai`（质量最好），失败或未配置时自动尝试百度翻译开放平台、有道智云（国内低延迟）
- 异步处理：AI 请求全部在线程池中执行，不阻塞服务器主线程，超时自动回退原文

## 工作原理

1. 监听 `AsyncChatEvent`
2. 收集除发送者外所有观众需要的目标语言（去重）
3. 为每种语言并发调用 OpenAI 兼容接口翻译
4. 用 `ChatRenderer` 按观众逐个渲染：观众的语言有译文则用译文，否则用原文
5. 翻译结果按语言写入内存缓存，重复消息直接命中

## 安装

1. 从 Releases 下载 `ai-translate-plugin-x.y.z.jar`
2. 放入服务端 `plugins/` 目录
3. 启动服务器（Paper 1.18+）
4. 编辑 `plugins/AiTranslate/config.yml`，填入 `openai.api-key`
5. 执行 `/aitr reload` 或重启服务器

插件使用 Paper 专有事件（AsyncChatEvent / ChatRenderer），不支持纯 Spigot/CraftBukkit；Purpur 等 Paper 分支可以正常使用。

## 快速开始

1. 在 ai.furry.vg 控制台获取 API Key，填入配置文件
2. 用 `/aitr models` 查询 Key 可用的模型 ID（文档要求：模型 ID 以接口返回为准，不要猜）
3. 把可用模型填入 `openai.model`（默认 `openai/gpt-5.6-luna`，免费）
4. 玩家执行 `/aitr` 打开语言菜单，或 `/aitr auto` 跟随客户端语言

## 命令与权限

| 命令 | 说明 | 权限 |
| --- | --- | --- |
| `/aitr` | 打开语言选择菜单（国旗界面） | `aitr.use`（默认所有玩家） |
| `/aitr lang <语言id>` | 手动指定目标语言 | `aitr.use` |
| `/aitr auto` | 恢复跟随客户端语言 | `aitr.use` |
| `/aitr reload` | 重载配置与语言库 | `aitr.reload`（默认 OP） |
| `/aitr models` | 查询当前 Key 可用模型 | `aitr.reload` |
| `/aitr setskull <语言id>` | 手持头颅设为该语言国旗图标 | `aitr.reload` |
| `/aitr status` | 查看插件状态（Key、模型、你的语言解析、缓存等） | 所有玩家 |
| `/aitr test [文本]` | 立即测试一次翻译接口，显示译文或失败原因 | `aitr.reload` |

所有子命令均支持 Tab 补全：第一级参数自动列出可用子命令，`/aitr lang`、`/aitr setskull` 后会自动补全语言 id。

## 配置说明

### config.yml

| 键 | 默认值 | 说明 |
| --- | --- | --- |
| `openai.base-url` | `https://ai.furry.vg/v1` | OpenAI 兼容接口地址 |
| `openai.api-key` | 空 | API Key，留空则禁用翻译 |
| `openai.model` | `openai/gpt-5.6-luna` | 模型 ID |
| `openai.timeout-ms` | `15000` | 单次请求超时 |
| `openai.max-chars` | `400` | 超过该长度的消息不翻译 |
| `behavior.enabled` | `true` | 总开关 |
| `behavior.translate-items` | `true` | 是否翻译聊天内物品名与 lore |
| `behavior.skip-default-language` | `false` | 目标语言等于默认语言的观众跳过翻译；`true` 省费用但中文玩家看不到外语消息译文 |
| `behavior.max-wait-ms` | `8000` | 单条消息最多等待译文的时间，超时回退原文 |
| `cache.max-size-per-language` | `2000` | 每种语言的缓存条目上限 |
| `threads.max-parallel-requests` | `4` | AI 并发请求上限 |
| `default-language` | `zh_cn` | 兜底语言 |
| `providers.order` | `["openai","baidu","youdao"]` | 翻译源尝试顺序；未配置的源自动跳过 |
| `providers.timeout-ms` | `5000` | 备用源单次请求超时 |
| `baidu.appid` / `baidu.secret` | 空 | 百度翻译开放平台凭证 |
| `youdao.appid` / `youdao.secret` | 空 | 有道智云凭证 |

### languages.yml

`languages.yml` 定义语言菜单与翻译提示词，内置 64 种语言，可自由增删：

| 字段 | 说明 |
| --- | --- |
| `display` | 菜单中显示的语言名 |
| `flag-name` | 菜单占位材质（如 `RED_CONCRETE`） |
| `skull` | 国旗头颅纹理（URL 或 base64），留空回退 `flag-name`；也可用 `/aitr setskull` 生成 |
| `locales` | 匹配的客户端 locale 列表（如 `en_us`、`en_gb`） |
| `skip-regex` | 可选；整条消息匹配该正则时视为已是目标语言，跳过翻译 |
| `prompt` | 该语言的系统提示词，`{text}` 会替换为原文 |

更新插件不会覆盖已存在的 `languages.yml`。要使用新版内置语言库，请先删除插件目录中的 `languages.yml` 再重启服务器。

### 启用百度 / 有道备用翻译源（可选，国内低延迟）

1. 百度：打开 https://fanyi-api.baidu.com 注册并开通“通用文本翻译”，在控制台的“开发者信息”中获取 APP ID 与密钥，填入 `baidu.appid` / `baidu.secret`
2. 有道：打开 https://ai.youdao.com 创建“文本翻译”应用，在“应用管理”中获取应用 ID 与应用密钥，填入 `youdao.appid` / `youdao.secret`
3. 执行 `/aitr reload`，用 `/aitr status` 查看每个源是否已配置，用 `/aitr test 你好` 验证（结果会标注实际生效的翻译源）
4. 翻译时按 `providers.order` 顺序尝试：前一个源失败或未配置会自动切换下一个；不支持的语种会自动跳过该源

注意：只配置百度/有道也能独立工作；免费额度与 QPS 限制以各平台官方文档为准。

## 兼容性与范围

- 公屏聊天：全部翻译。包括经由聊天管线渲染的消息（大部分聊天 / 称号 / 频道类插件）
- 聊天内物品展示：翻译物品自定义名与 lore
- 聊天格式：沿用服务器当前 `ChatRenderer`，尽量保留其他插件设置的前缀与颜色
- 其他插件直接发送的独立提示（不经过聊天事件的 `sendMessage` / 广播、计分板、独立 GUI 文本等）：受服务端事件能力限制无法统一拦截，如需支持需要数据包层拦截方案，欢迎在 Issues 中讨论

## 常见问题

### 为什么聊天没有翻译

按顺序排查：

1. 执行 `/aitr status`：若 `api-key` 显示未配置，翻译完全不工作。编辑 `plugins/AiTranslate/config.yml` 填入 `openai.api-key` 后执行 `/aitr reload`
2. 执行 `/aitr test 你好`：能看到译文说明 AI 接口正常，问题在聊天接收环节；显示失败原因（HTTP 401、模型不存在、超时等）则按提示检查 Key、模型 ID、网络
3. 自己发送的消息自己永远看到原文（设计如此），请用第二个账号或让其他玩家观察
4. 若 `skip-default-language` 为 `true`，中文玩家会跳过翻译；要测试翻译效果请保持 `false`
5. 确认服务端是 Paper 或其分支（Purpur 等），纯 Spigot 不支持
6. 控制台出现“翻译失败: ...”警告时，按警告中的 HTTP 状态与内容处理（插件会输出真实错误）

## 从源码构建

```bash
git clone https://github.com/ALingqing/AiTranslate.git
cd AiTranslate
mvn -B clean package
```

产物：`target/ai-translate-plugin-<version>.jar`

## 自动化构建与发布

仓库配置了两条 GitHub Actions 工作流：

- `ci.yml`：每次 push 到 `main` 或提交 PR 时自动构建，并上传 jar 作为构建产物
- `release.yml`：推送 `v*` 标签（如 `v1.0.0`）时自动按标签版本号构建，并创建 GitHub Release、附带可下载的 jar

发布新版本的流程：

```bash
git tag v1.0.0
git push origin v1.0.0
```

## 品牌资产

- 图标：`assets/logo.svg`
- 横幅：`assets/banner.svg`

## 开源协议

[MIT](LICENSE)
