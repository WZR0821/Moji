import AppIntents

/// 说一句添加 in Shortcuts and Siri. An app may declare only one provider.
struct MojiShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: MojiSayIntent(),
            phrases: [
                "\(.applicationName)说一句",
                "用\(.applicationName)说一句添加"
            ],
            shortTitle: "说一句添加",
            systemImageName: "mic"
        )
    }
}
