import SwiftUI
import UIKit

final class MojiApplicationDelegate: NSObject, UIApplicationDelegate {
    func applicationWillTerminate(_ application: UIApplication) {
        PomodoroLiveActivityController.prepareForTermination()
        Task {
            await PomodoroLiveActivityController.appWillTerminate()
        }
    }
}

@main
struct MojiApp: App {
    @UIApplicationDelegateAdaptor(MojiApplicationDelegate.self)
    private var applicationDelegate
    @StateObject private var store: PlanStore
    @StateObject private var pomodoro: PomodoroEngine
    @Environment(\.scenePhase) private var scenePhase
    @AppStorage(PlanSettingsKeys.appearanceMode, store: SharedPersistence.sharedDefaults)
    private var appearanceModeRaw = AppAppearanceMode.system.rawValue

    init() {
        // The engine holds the store, so both are built here and live for the
        // whole app session. That is what lets the timer keep advancing while
        // the user is on another tab.
        let store = PlanStore()
        _store = StateObject(wrappedValue: store)
        _pomodoro = StateObject(wrappedValue: PomodoroEngine(store: store))

        let tabAppearance = UITabBarAppearance()
        tabAppearance.configureWithOpaqueBackground()
        tabAppearance.backgroundColor = UIColor(Color.planBackground)
        tabAppearance.shadowColor = UIColor(Color.planPrimary.opacity(0.10))

        let tabItemAppearance = UITabBarItemAppearance()
        tabItemAppearance.normal.iconColor = UIColor(Color.planSecondary)
        tabItemAppearance.normal.titleTextAttributes = [
            .foregroundColor: UIColor(Color.planSecondary)
        ]
        tabItemAppearance.selected.iconColor = UIColor(Color.planPrimary)
        tabItemAppearance.selected.titleTextAttributes = [
            .foregroundColor: UIColor(Color.planPrimary)
        ]
        tabAppearance.stackedLayoutAppearance = tabItemAppearance
        tabAppearance.inlineLayoutAppearance = tabItemAppearance
        tabAppearance.compactInlineLayoutAppearance = tabItemAppearance
        UITabBar.appearance().standardAppearance = tabAppearance
        UITabBar.appearance().scrollEdgeAppearance = tabAppearance

        let navigationAppearance = UINavigationBarAppearance()
        navigationAppearance.configureWithTransparentBackground()
        navigationAppearance.shadowColor = .clear
        navigationAppearance.titleTextAttributes = [
            .foregroundColor: UIColor(Color.planPrimary)
        ]
        navigationAppearance.largeTitleTextAttributes = [
            .foregroundColor: UIColor(Color.planPrimary)
        ]
        UINavigationBar.appearance().standardAppearance = navigationAppearance
        UINavigationBar.appearance().compactAppearance = navigationAppearance
        UINavigationBar.appearance().scrollEdgeAppearance = navigationAppearance
    }

    var body: some Scene {
        WindowGroup {
            ContentView(store: store, pomodoro: pomodoro)
                .tint(.planPrimary)
                .preferredColorScheme(preferredColorScheme)
                .task {
                    // A cold launch never fires scenePhase.onChange, so the
                    // first timer settle has to happen here too.
                    pomodoro.applicationDidBecomeActive()
                    await PomodoroLiveActivityController.reconcile()
                    handlePendingPomodoroTerminationRequest()
                }
                .onChange(of: scenePhase) { _, newPhase in
                    switch newPhase {
                    case .active:
                        store.reload()
                        // Settle a phase that ended while the app was away
                        // before anything renders, so the ring never shows a
                        // timer that has actually already finished.
                        pomodoro.applicationDidBecomeActive()
                        Task {
                            await PomodoroLiveActivityController.reconcile()
                        }
                        handlePendingPomodoroTerminationRequest()
                    case .background:
                        Task {
                            await PomodoroLiveActivityController.appDidEnterBackground()
                        }
                    default:
                        break
                    }
                }
        }
    }

    private var preferredColorScheme: ColorScheme? {
        switch AppAppearanceMode(rawValue: appearanceModeRaw) ?? .system {
        case .system: return nil
        case .light: return .light
        case .dark: return .dark
        }
    }

    private func handlePendingPomodoroTerminationRequest() {
        let defaults = SharedPersistence.sharedDefaults
        guard defaults.bool(
            forKey: PomodoroStorageKeys.terminationConfirmationRequested
        ) else { return }
        defaults.set(
            false,
            forKey: PomodoroStorageKeys.terminationConfirmationRequested
        )
        // Give the tab and segment subscriptions one run-loop turn on a cold
        // launch, then show the same keep/discard choice as the in-app button.
        DispatchQueue.main.async {
            NotificationCenter.default.post(name: .mojiOpenPomodoro, object: nil)
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.18) {
                NotificationCenter.default.post(
                    name: .mojiConfirmPomodoroTermination,
                    object: nil
                )
            }
        }
    }
}
