import Foundation
import Vision

// Read-only screenshot validation. Compile once with `xcrun swiftc` on macOS.
// JSON output lets the capture harness reject a launch view or wrong QA route.
var result: [String: [String]] = [:]
for path in CommandLine.arguments.dropFirst() {
    let request = VNRecognizeTextRequest()
    request.recognitionLevel = .accurate
    request.recognitionLanguages = ["zh-Hans", "en-US"]
    request.usesLanguageCorrection = false
    do {
        try VNImageRequestHandler(url: URL(fileURLWithPath: path)).perform([request])
        result[path] = request.results?.compactMap { $0.topCandidates(1).first?.string } ?? []
    } catch {
        fputs("OCR failed for \(path): \(error)\n", stderr)
        exit(1)
    }
}
let data = try JSONSerialization.data(withJSONObject: result, options: [.sortedKeys])
FileHandle.standardOutput.write(data)
