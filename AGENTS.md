# 墨记（Moji）开发规则

## 构建保护规则（适用于所有 AI 代理）

- 这台 Intel Mac 已多次在密集构建和模拟器运行期间触发 `WindowServer` watchdog，导致整个用户会话回到登录界面。这是 P0 级开发环境风险。
- 同一时间只跑一个构建。iOS 构建、Gradle 构建和模拟器测试不得并行，也不得与本机其他项目的构建同时进行；开始前先确认没有别的构建在跑。
- 每个验证阶段只尝试一次。失败后先阅读并定位错误，不得自动重试、循环构建或清理后重跑。
- 只有用户在当前任务中明确同意后才运行模拟器，且只启动一个指定设备。`Scripts/verify.sh` 默认只编译、不启动模拟器；设置 `MOJI_IOS_DESTINATION` 后它会执行 `xcodebuild test` 并启动模拟器，同样要先征得同意。
- 不得执行 `simctl erase all`、`simctl delete all`、针对所有设备的 shutdown，也不得终止 `WindowServer` 或 CoreSimulator 服务。
- 出现屏幕卡顿、黑屏或显示延迟时立即停止当前验证，不得继续重试。
- 未实际运行的构建或测试不得声称通过。
