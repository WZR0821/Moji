import AVFoundation
import Speech

/// Speech to text for the home page's quick-entry field. The words go straight
/// into the field, after what is already there; Moji itself sends nothing.
/// Recognition stays on the device whenever the device can do it.
@MainActor
final class SpeechCapture: ObservableObject {
    @Published private(set) var isListening = false

    /// Stops this long after the last new words.
    static let silence: Duration = .seconds(2)
    /// Stops this long after starting when nothing has been heard at all.
    static let firstWords: Duration = .seconds(8)
    static let limit: Duration = .seconds(30)

    private let recognizer = SFSpeechRecognizer(locale: Locale(identifier: "zh-CN"))
    private let engine = AVAudioEngine()
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?
    private var quietTimer: Task<Void, Never>?
    private var limitTimer: Task<Void, Never>?
    private var base = ""
    private var write: ((String) -> Void)?
    private var isStarting = false

    /// The microphone button is only drawn when this is true.
    var isAvailable: Bool { recognizer?.isAvailable == true }

    /// Starts listening, or stops when already listening. `write` receives the
    /// whole field text each time the words change.
    func toggle(appendingTo text: String, write: @escaping (String) -> Void, denied: @escaping () -> Void) {
        if isListening { stop(); return }
        guard !isStarting else { return }
        isStarting = true
        Task {
            defer { isStarting = false }
            guard await Self.authorize() else { denied(); return }
            start(appendingTo: text, write: write)
        }
    }

#if DEBUG
    /// The listening look for the photographed 说一句添加 screen: the words
    /// arrive one by one and the microphone stays on. Nothing is recorded.
    func simulateForQA(text: String, write: @escaping (String) -> Void) {
        isListening = true
        Task {
            var shown = ""
            for character in text {
                try? await Task.sleep(for: .milliseconds(160))
                guard isListening else { return }
                shown.append(character)
                write(shown)
            }
        }
    }
#endif

    func stop() {
        guard isListening || request != nil else { return }
        quietTimer?.cancel(); quietTimer = nil
        limitTimer?.cancel(); limitTimer = nil
        if engine.isRunning { engine.stop() }
        engine.inputNode.removeTap(onBus: 0)
        request?.endAudio()
        task?.cancel()
        request = nil
        task = nil
        write = nil
        if isListening { isListening = false }
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    private func start(appendingTo text: String, write: @escaping (String) -> Void) {
        guard let recognizer, recognizer.isAvailable else { return }
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.record, mode: .measurement, options: .duckOthers)
            try session.setActive(true, options: .notifyOthersOnDeactivation)
        } catch {
            return
        }
        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        guard format.channelCount > 0, format.sampleRate > 0 else {
            try? session.setActive(false, options: .notifyOthersOnDeactivation)
            return
        }
        let request = SFSpeechAudioBufferRecognitionRequest()
        request.shouldReportPartialResults = true
        request.addsPunctuation = true
        if recognizer.supportsOnDeviceRecognition { request.requiresOnDeviceRecognition = true }
        input.removeTap(onBus: 0)
        input.installTap(onBus: 0, bufferSize: 1024, format: format, block: Self.tap(feeding: request))
        engine.prepare()
        do {
            try engine.start()
        } catch {
            input.removeTap(onBus: 0)
            try? session.setActive(false, options: .notifyOthersOnDeactivation)
            return
        }
        self.request = request
        self.base = text
        self.write = write
        isListening = true
        task = recognizer.recognitionTask(with: request, resultHandler: Self.handler { [weak self] words, finished in
            self?.receive(words, finished: finished)
        })
        waitForQuiet(Self.firstWords)
        limitTimer = Task { [weak self] in
            try? await Task.sleep(for: Self.limit)
            guard !Task.isCancelled else { return }
            self?.stop()
        }
    }

    private func receive(_ words: String?, finished: Bool) {
        guard isListening else { return }
        if let words {
            var spoken = words.trimmingCharacters(in: .whitespacesAndNewlines)
            while let last = spoken.last, "。.".contains(last) { spoken.removeLast() }
            write?(base + spoken)
            waitForQuiet(Self.silence)
        }
        if finished { stop() }
    }

    private func waitForQuiet(_ duration: Duration) {
        quietTimer?.cancel()
        quietTimer = Task { [weak self] in
            try? await Task.sleep(for: duration)
            guard !Task.isCancelled else { return }
            self?.stop()
        }
    }

    // The audio tap and the recognition callback run off the main thread, so
    // they are made outside the main actor and only hop back with the words.

    nonisolated private static func tap(feeding request: SFSpeechAudioBufferRecognitionRequest) -> AVAudioNodeTapBlock {
        { buffer, _ in request.append(buffer) }
    }

    nonisolated private static func handler(
        _ deliver: @escaping @MainActor (String?, Bool) -> Void
    ) -> (SFSpeechRecognitionResult?, Error?) -> Void {
        { result, error in
            let words = result?.bestTranscription.formattedString
            let finished = error != nil || result?.isFinal == true
            DispatchQueue.main.async { MainActor.assumeIsolated { deliver(words, finished) } }
        }
    }

    private static func authorize() async -> Bool {
        let speech: SFSpeechRecognizerAuthorizationStatus = await withCheckedContinuation { done in
            SFSpeechRecognizer.requestAuthorization { done.resume(returning: $0) }
        }
        guard speech == .authorized else { return false }
        return await AVAudioApplication.requestRecordPermission()
    }
}
