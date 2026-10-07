# Moji 1.5.1（41）验证记录

日期：2026-10-07。作者显示名称：Jerry Wong。

## 实际执行范围

本轮重点是展开详情、二级编辑器、三级设置及已有输入内容，不仅检查四个首页。使用隔离的 Debug 测试数据，在 iPhone 16 / iOS 18.2 Simulator 与 Android API 36 Emulator 上采集同一组内容；逻辑宽度均为 393，原始截图均为 1179 × 2556。Debug 数据注入需要显式开启，Release 安装包不包含测试数据文件。

共检查 **59 组双端状态 / 118 张原图**：浅色 45 组，深色 14 组。并排图与 16 张汇总图用于人工逐页检查；不是自动计算“零像素差异”的结果。错误路由、启动空白和未完成转场的截图不计入最终矩阵，关键子页使用 OCR / UI 层级文本校验后重新采集。

| 区域 | 本轮浅色状态（45 组） |
| --- | --- |
| 计划与展开详情 | `plans`、`plan-expanded`、`plan-expanded-bottom`、`plan-inline`、`plan-completed` |
| 计划编辑与三级选项 | `plan-editor`、`plan-new`、`plan-copy`、`plan-editor-advanced` |
| 计划库与模板 | `library`、`templates`、`library-batch` |
| 备忘与转计划 | `memo`、`memo-note`、`memo-checklist`、`memo-convert` |
| 时刻与专注 | `moments`、`moment-past`、`moment-editor`、`moment-advanced`、`focus`、`focus-duration` |
| 回顾与记录 | `review`、`review-week`、`review-month`、`review-records`、`record-editor`、`record-untimed` |
| 周目标与复盘 | `goal`、`reflection`、`next-week` |
| 设置索引与子页 | `settings`、`settings-defaults`、`settings-widgets`、`settings-widgets:0`、`settings-pomodoro`、`settings-notifications`、`settings-appearance`、`settings-permissions`、`settings-backup`、`settings-about` |
| 日历与三级跳转 | `calendar-month`、`calendar-week`、`calendar-day`、`calendar-jump` |

深色 14 组：`plan-expanded`、`plan-editor`、`plan-editor-advanced`、`settings`、`settings-widgets:0`、`settings-pomodoro`、`settings-appearance`、`settings-about`、`moment-advanced`、`focus-duration`、`memo-checklist`、`record-editor`、`goal`、`reflection`。

另执行 Android 快捷编辑短流程：呼出软键盘，修改非空标题，收起键盘后保存，强制结束再启动应用，确认修改仍然存在。此流程不替代全部表单的双端键盘端到端测试。

## 修正与复查结果

- 红色印章按实际字形边界居中，数字及篆书使用共享字体和水墨资源。
- 计划展开详情的最后一项信息改为整行，底部快捷编辑 / 更多设置按钮取消多余底色；补齐返回层级并检查详情底部滚动状态。
- 高级设置、提醒 / 重复 / 日历选项、日期及时间胶囊、开关、分段选择、设置子页和预设编辑页重新对齐。
- Android 周 / 日日历条目由重叠布局改为纵向布局，实际记录与完成计划去重；月 / 周 / 日及年月跳转全部重新采集。
- 双端周目标数字已有输入后仍保留“完成计划（项）”“实际专注（分钟）”标签。
- iOS 备忘编辑器保留可见的“完成”按钮；计划与记录编辑的导航栏使用不透明背景，避免滚动内容透入导航区域。
- 已填写的长备注、清单、未计时记录、复盘、时刻高级设置等状态均在最终截图中检查。

## 有限验证结果

| 检查 | 结果 |
| --- | --- |
| iOS 单元测试 | 126 通过，0 失败 / 跳过 |
| Android 本地交互与日历测试 | 25 通过，0 失败 / 跳过 |
| 共享 core 测试 | 38 通过，0 失败 / 跳过 |
| Android Release Lint | 0 Error、43 Warning、3 Hint |
| Android Debug / Release APK、Release AAB | 构建成功 |
| iOS Simulator Debug / iPhoneOS Release | 构建成功 |
| 安装包 ZIP 完整性 | APK / AAB / IPA CRC 检查通过 |

共 **189 项测试通过**。iOS 最后一次测试后仅调整界面标签 / 布局，并重新构建 Debug 与 Release；没有宣称再次完整执行 126 项测试。Android 的最终测试与打包在最后一次日历 / 标签调整后执行。

Android Lint 剩余提示包括 KTX 建议、Compose 参数 / 装箱建议、系统备份规则及图标建议；并非零警告。AAB 的 JAR 签名校验通过，但原自签名证书没有公共信任链 / 时间戳，且工具存在清单顺序及 POSIX 属性提示，不宣称无警告。

## 包信息与发布来源

- App 与 Widget：1.5.1，build 41；Android：versionName 1.5.1，versionCode 41。
- iOS Bundle ID、Widget ID、App Group 保持原值；Android application ID 为 `com.raydon.moji.android`，schema 13 保持不变。
- Android 使用原 Release 密钥。证书 SHA-256：`34ea9040c305632c4e30881b3cc868942a20648cc24293e8a6e7816904433a97`。
- IPA 为 arm64 未签名包，包含 Widget；没有 provisioning profile / CodeResources 签名，必须自行签名安装。
- 源码归档以实际构建工作区为准，不使用本地旧 HEAD 的 `git archive`。归档中的 `WORKSPACE_PROVENANCE.json` 列出每个源文件的 SHA-256、Git blob 和发布 commit；GitHub 主分支在原远端提交上按非强制、预期 SHA 校验方式更新，保留远端历史文件。
- 密钥、被忽略的本地配置、构建缓存与旧发行包不进入源码归档。Release 包中检查不到 QA fixture 文件。

## 证据与边界

本地原图 / 对照图位于 `build/1.5.1-qa/{ios,android,comparisons}/`，额外输入保存证据位于 `manual/`；发行包中的 visual-qa ZIP 只收录最终矩阵和有效补充证据，不收录临时空白截图。

本轮没有执行全设备 / 全分辨率回归、真机全流程、长时间稳定性 / 性能 / 电量测试，也没有完整执行系统文件夹授权后的自动备份与恢复端到端流程；备份逻辑由源代码检查和有限单元测试覆盖，不据此宣称系统文件授权验证完成。系统权限、通知 / 闹钟、Widget、Live Activities、系统日历及后台运行也没有重新做全量真机验收。

应用自有界面以 iOS 布局为基准，保留系统状态栏、底部导航 / 手势区、系统键盘、权限 / 文件 / 日期选择器等差异。原生字体的字形度量、栅格化及部分换行仍可能不同；最终截图是本次修正的可复核证据，**不构成所有设备、所有状态完全零像素差异的保证**。
