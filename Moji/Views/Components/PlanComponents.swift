import SwiftUI
import UIKit

enum PlanLayout {
    /// Standard breathing room for full-screen card and list content.
    static let pageHorizontalPadding: CGFloat = 18
}

struct IMESafeTextField: UIViewRepresentable {
    let placeholder: String
    @Binding var text: String
    @Binding var isFocused: Bool
    var keepsFocusOnSubmit = false
    var accessibilityIdentifier: String?
    var onSubmit: (() -> Void)?
    var textStyle: UIFont.TextStyle = .body

    func makeCoordinator() -> Coordinator {
        Coordinator(parent: self)
    }

    func makeUIView(context: Context) -> UITextField {
        let textField = UITextField(frame: .zero)
        textField.delegate = context.coordinator
        textField.placeholder = placeholder
        textField.text = text
        textField.font = .preferredFont(forTextStyle: textStyle)
        textField.adjustsFontForContentSizeCategory = true
        textField.textColor = .label
        textField.tintColor = .label
        textField.backgroundColor = .clear
        textField.clearButtonMode = .whileEditing
        textField.returnKeyType = .done
        textField.accessibilityIdentifier = accessibilityIdentifier
        textField.addTarget(
            context.coordinator,
            action: #selector(Coordinator.editingChanged(_:)),
            for: .editingChanged
        )
        return textField
    }

    func updateUIView(_ textField: UITextField, context: Context) {
        context.coordinator.parent = self
        textField.placeholder = placeholder
        textField.accessibilityIdentifier = accessibilityIdentifier
        textField.font = .preferredFont(forTextStyle: textStyle)

        // Never replace marked text while a Chinese/Japanese IME is composing candidates.
        if textField.markedTextRange == nil, textField.text != text {
            textField.text = text
        }

        if isFocused, !textField.isFirstResponder {
            DispatchQueue.main.async {
                guard textField.window != nil, !textField.isFirstResponder else { return }
                textField.becomeFirstResponder()
            }
        } else if !isFocused, textField.isFirstResponder {
            textField.resignFirstResponder()
        }
    }

    final class Coordinator: NSObject, UITextFieldDelegate {
        var parent: IMESafeTextField

        init(parent: IMESafeTextField) {
            self.parent = parent
        }

        @objc func editingChanged(_ textField: UITextField) {
            // Publish the visible value, but updateUIView will leave marked text untouched.
            parent.text = textField.text ?? ""
        }

        func textFieldDidBeginEditing(_ textField: UITextField) {
            parent.isFocused = true
        }

        func textFieldShouldEndEditing(_ textField: UITextField) -> Bool {
            // Opening a DatePicker can resign the title field while a Chinese
            // candidate is still marked. Commit that visible candidate before
            // UIKit tears down the composition session, otherwise the title can
            // appear to disappear at random.
            if textField.markedTextRange != nil {
                textField.unmarkText()
            }
            parent.text = textField.text ?? ""
            return true
        }

        func textFieldDidEndEditing(_ textField: UITextField) {
            parent.text = textField.text ?? ""
            parent.isFocused = false
        }

        func textFieldShouldReturn(_ textField: UITextField) -> Bool {
            if textField.markedTextRange != nil {
                textField.unmarkText()
            }
            parent.text = textField.text ?? ""
            if !parent.keepsFocusOnSubmit {
                textField.resignFirstResponder()
            }
            let action = parent.onSubmit
            DispatchQueue.main.async {
                action?()
            }
            return true
        }
    }
}

enum IMETextInput {
    @MainActor
    static func commit(_ action: @escaping () -> Void) {
        UIApplication.shared.sendAction(
            #selector(UIResponder.resignFirstResponder),
            to: nil,
            from: nil,
            for: nil
        )
        // Resigning commits the selected IME candidate and updates SwiftUI first.
        DispatchQueue.main.async {
            action()
        }
    }
}

struct InkPaperCardShape: Shape {
    var cornerRadius: CGFloat = 14

    func path(in rect: CGRect) -> Path {
        guard rect.width > 24, rect.height > 20 else {
            return RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                .path(in: rect)
        }

        let radius = min(cornerRadius, min(rect.width, rect.height) * 0.24)
        var path = Path()
        path.move(to: CGPoint(x: rect.minX + radius * 0.92, y: rect.minY + 0.8))
        path.addCurve(
            to: CGPoint(x: rect.maxX - radius * 0.78, y: rect.minY + 0.2),
            control1: CGPoint(x: rect.width * 0.34, y: rect.minY - 0.5),
            control2: CGPoint(x: rect.width * 0.70, y: rect.minY + 1.4)
        )
        path.addQuadCurve(
            to: CGPoint(x: rect.maxX - 0.5, y: rect.minY + radius),
            control: CGPoint(x: rect.maxX + 0.2, y: rect.minY + 0.4)
        )
        path.addCurve(
            to: CGPoint(x: rect.maxX - 0.4, y: rect.maxY - radius * 0.88),
            control1: CGPoint(x: rect.maxX + 0.9, y: rect.height * 0.34),
            control2: CGPoint(x: rect.maxX - 1.1, y: rect.height * 0.72)
        )
        path.addQuadCurve(
            to: CGPoint(x: rect.maxX - radius, y: rect.maxY - 0.3),
            control: CGPoint(x: rect.maxX - 0.1, y: rect.maxY + 0.5)
        )
        path.addCurve(
            to: CGPoint(x: rect.minX + radius * 0.82, y: rect.maxY - 0.7),
            control1: CGPoint(x: rect.width * 0.69, y: rect.maxY + 0.7),
            control2: CGPoint(x: rect.width * 0.31, y: rect.maxY - 1.5)
        )
        path.addQuadCurve(
            to: CGPoint(x: rect.minX + 0.4, y: rect.maxY - radius),
            control: CGPoint(x: rect.minX - 0.4, y: rect.maxY)
        )
        path.addCurve(
            to: CGPoint(x: rect.minX + 0.7, y: rect.minY + radius * 0.94),
            control1: CGPoint(x: rect.minX - 0.8, y: rect.height * 0.69),
            control2: CGPoint(x: rect.minX + 1.2, y: rect.height * 0.31)
        )
        path.addQuadCurve(
            to: CGPoint(x: rect.minX + radius * 0.92, y: rect.minY + 0.8),
            control: CGPoint(x: rect.minX + 0.1, y: rect.minY + 0.2)
        )
        path.closeSubpath()
        return path
    }
}

private struct InkPaperFibers: View {
    var intensity: Double = 1

    var body: some View {
        Canvas { context, size in
            let horizontalCount = max(6, Int(size.height / 26))
            for index in 0..<horizontalCount {
                let fraction = CGFloat(index + 1) / CGFloat(horizontalCount + 1)
                let y = size.height * fraction
                    + CGFloat(sin(Double(index) * 2.13)) * 2.1
                var fiber = Path()
                fiber.move(to: CGPoint(x: -8, y: y))
                fiber.addCurve(
                    to: CGPoint(x: size.width + 8, y: y + CGFloat(cos(Double(index) * 1.7)) * 1.8),
                    control1: CGPoint(x: size.width * 0.30, y: y - 1.8),
                    control2: CGPoint(x: size.width * 0.71, y: y + 2.2)
                )
                context.stroke(
                    fiber,
                    with: .color(Color.planPrimary.opacity(0.010 * intensity)),
                    style: StrokeStyle(
                        lineWidth: index.isMultiple(of: 4) ? 0.48 : 0.24,
                        lineCap: .round
                    )
                )
            }

            let shortFiberCount = max(8, Int(size.width * size.height / 18_000))
            for index in 0..<shortFiberCount {
                let seed = Double(index + 7)
                let x = CGFloat(abs(sin(seed * 12.9898))) * size.width
                let y = CGFloat(abs(cos(seed * 7.233))) * size.height
                let length = CGFloat(4 + abs(sin(seed * 3.17)) * 12)
                var fiber = Path()
                fiber.move(to: CGPoint(x: x, y: y))
                fiber.addLine(
                    to: CGPoint(
                        x: x + length,
                        y: y + CGFloat(sin(seed * 4.31)) * 2.2
                    )
                )
                context.stroke(
                    fiber,
                    with: .color(Color.planPrimary.opacity(0.012 * intensity)),
                    style: StrokeStyle(lineWidth: 0.30, lineCap: .round)
                )
            }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

private struct InkPaperSurfaceModifier: ViewModifier {
    let cornerRadius: CGFloat
    let showsBrushMark: Bool
    let borderColor: Color
    let borderWidth: CGFloat

    func body(content: Content) -> some View {
        content
            .background {
                ZStack {
                    InkPaperCardShape(cornerRadius: cornerRadius)
                        .fill(Color.planCard)
                    InkPaperFibers(intensity: 0.82)
                        .clipShape(InkPaperCardShape(cornerRadius: cornerRadius))
                }
            }
            .overlay {
                InkPaperCardShape(cornerRadius: cornerRadius)
                    .stroke(borderColor, lineWidth: borderWidth)
            }
            .overlay(alignment: .topLeading) {
                if showsBrushMark {
                    InkCardBrushMark()
                        .frame(width: 94, height: 10)
                        .offset(x: 15, y: -1)
                }
            }
            // A quiet ring and a short contact shadow keep the surface tactile
            // without turning every row into a floating card.
            .shadow(color: Color.planPrimary.opacity(0.028), radius: 10, y: 4)
    }
}

struct PlanCardModifier: ViewModifier {
    func body(content: Content) -> some View {
        content
            .padding(16)
            .modifier(
                InkPaperSurfaceModifier(
                    cornerRadius: 14,
                    showsBrushMark: false,
                    borderColor: Color.planPrimary.opacity(0.095),
                    borderWidth: 0.75
                )
            )
    }
}

struct InkCardBrushMark: View {
    var body: some View {
        InkHeaderWashView(
            tint: .planPrimary,
            opacity: 0.16
        )
        .rotationEffect(.degrees(-1.2))
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

struct InkBrushStrike: View {
    let progress: Double
    var tint: Color = .planPrimary
    var opacity = 0.74

    var body: some View {
        GeometryReader { proxy in
            let clamped = min(1, max(0, progress))

            Image("InkTaskStrike")
                .renderingMode(.template)
                .resizable()
                .scaledToFill()
                .foregroundStyle(tint.opacity(opacity))
                .frame(width: proxy.size.width, height: proxy.size.height)
                .clipped()
                .mask(alignment: .leading) {
                    Rectangle()
                        .frame(width: proxy.size.width * clamped)
                }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

struct InkHeaderWashView: View {
    var tint: Color = .planPrimary
    var opacity = 0.14

    var body: some View {
        Image("InkHeaderWash")
            .renderingMode(.template)
            .resizable()
            .scaledToFill()
            .foregroundStyle(tint.opacity(opacity))
            .clipped()
            .allowsHitTesting(false)
            .accessibilityHidden(true)
    }
}

struct InkFocusBloomView: View {
    var tint: Color = .planPrimary
    var opacity = 0.08

    var body: some View {
        Image("InkFocusBloom")
            .renderingMode(.template)
            .resizable()
            .scaledToFit()
            .foregroundStyle(tint.opacity(opacity))
            .clipped()
            .allowsHitTesting(false)
            .accessibilityHidden(true)
    }
}

struct InkStatusBlotView: View {
    var tint: Color = .planPrimary
    var opacity = 0.82
    /// Dots in a row are the worst offender for looking copy-pasted, so this
    /// spins and scales each blot into its own splash.
    var seed: Int = 0

    var body: some View {
        let variant = InkVariant(seed: seed, spread: 2.2)
        Image("InkStatusBlot")
            .renderingMode(.template)
            .resizable()
            .scaledToFit()
            .foregroundStyle(tint.opacity(opacity * variant.opacityScale))
            .clipped()
            .inkVariant(variant)
            .allowsHitTesting(false)
            .accessibilityHidden(true)
    }
}

struct InkProgressStroke: View {
    let progress: Double
    var tint: Color = .planPrimary
    var opacity = 0.82

    var body: some View {
        GeometryReader { proxy in
            let clamped = min(1, max(0, progress))

            ZStack(alignment: .leading) {
                Image("InkThinDivider")
                    .renderingMode(.template)
                    .resizable()
                    .scaledToFill()
                    .foregroundStyle(Color.planPrimary.opacity(0.10))
                    .frame(width: proxy.size.width, height: proxy.size.height)
                    .clipped()

                Image("InkProgressBar")
                    .renderingMode(.template)
                    .resizable()
                    .scaledToFill()
                    .foregroundStyle(tint.opacity(opacity))
                    .frame(width: proxy.size.width, height: proxy.size.height)
                    .clipped()
                    .mask(alignment: .leading) {
                        Rectangle()
                            .frame(width: proxy.size.width * clamped)
                    }
            }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

extension View {
    func planCard() -> some View {
        modifier(PlanCardModifier())
    }

    func inkPaperSurface(
        cornerRadius: CGFloat = 14,
        showsBrushMark: Bool = false,
        borderColor: Color = Color.planPrimary.opacity(0.095),
        borderWidth: CGFloat = 0.75
    ) -> some View {
        modifier(
            InkPaperSurfaceModifier(
                cornerRadius: cornerRadius,
                showsBrushMark: showsBrushMark,
                borderColor: borderColor,
                borderWidth: borderWidth
            )
        )
    }
}

struct InkWashBackground: View {
    @Environment(\.colorScheme) private var colorScheme
    @AppStorage(PlanSettingsKeys.paperTextureEnabled, store: SharedPersistence.sharedDefaults)
    private var paperTextureEnabled = true

    var body: some View {
        GeometryReader { proxy in
            ZStack {
                Color.planBackground

                if paperTextureEnabled {
                    InkPaperFibers(intensity: colorScheme == .dark ? 1.08 : 0.92)
                }

                // The two corner washes were faint enough to disappear on a
                // bright screen, so the page read as plain paper. They are
                // still background — just dark enough to be seen.
                InkFocusBloomView(
                    opacity: colorScheme == .dark ? 0.150 : 0.128
                )
                .frame(
                    width: proxy.size.width * 1.10,
                    height: proxy.size.width * 1.10
                )
                .position(
                    x: proxy.size.width * 0.90,
                    y: proxy.size.height * 0.06
                )

                InkFocusBloomView(
                    opacity: colorScheme == .dark ? 0.108 : 0.092
                )
                .frame(
                    width: proxy.size.width * 0.94,
                    height: proxy.size.width * 0.94
                )
                .rotationEffect(.degrees(24))
                .position(
                    x: proxy.size.width * 0.06,
                    y: proxy.size.height * 0.87
                )
            }
        }
        .ignoresSafeArea()
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

struct InkBrushDivider: View {
    var tint: Color = .planPrimary
    var animated = true
    /// Stacked dividers used to be pixel-identical; a seed makes each one its
    /// own stroke — mirrored, tilted slightly differently, a touch lighter.
    var seed: Int = 0

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var drawProgress = 0.0

    /// A rule wants to look hand-drawn, not crooked, so most of the variation
    /// here comes from mirroring and weight rather than angle.
    private var variant: InkVariant {
        InkVariant(seed: seed, spread: 0.05)
    }

    private var artworkName: String {
        let artworks = [
            "InkThinDivider",
            "InkDividerSweep",
            "InkDividerEcho",
            "InkDividerBroken",
            "InkDividerWash",
            "InkDividerHook",
            "InkDividerThread"
        ]
        return artworks[InkVariant.index(for: seed, count: artworks.count)]
    }

    var body: some View {
        GeometryReader { proxy in
            ZStack {
                // Keep a continuous, quiet ink baseline underneath the varied
                // brush assets so a broken or washed stroke never reads as
                // accidentally covered by the next row or card.
                Image("InkThinDivider")
                    .renderingMode(.template)
                    .resizable()
                    .scaledToFill()
                    .foregroundStyle(tint.opacity(0.14))
                    .frame(width: proxy.size.width, height: proxy.size.height)
                    .clipped()

                Image(artworkName)
                    .renderingMode(.template)
                    .resizable()
                    .scaledToFill()
                    .foregroundStyle(tint.opacity(0.50 * variant.opacityScale))
                    .frame(width: proxy.size.width, height: proxy.size.height)
                    .clipped()
                    .scaleEffect(x: variant.flipsHorizontally ? -1 : 1, y: 1)
                    .mask(alignment: variant.flipsHorizontally ? .trailing : .leading) {
                        Rectangle()
                            .frame(width: proxy.size.width * drawProgress)
                    }
                }
        }
        .frame(height: 7)
        .rotationEffect(variant.rotation + .degrees(-0.5))
        .onAppear {
            guard animated, !reduceMotion else {
                drawProgress = 1
                return
            }
            drawProgress = 0
            withAnimation(.easeOut(duration: 0.72)) {
                drawProgress = 1
            }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
        .zIndex(1)
    }
}

struct InkSealMark: View {
    let character: String
    var size: CGFloat = 34
    var style: InkSealStyle? = nil
    var animated = true
    /// No two stamps land at the same angle.
    var seed: Int = 0
    /// Slow pulse for seals that mark something time-critical.
    var breathes = false

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @AppStorage(PlanSettingsKeys.inkMotionLevel, store: SharedPersistence.sharedDefaults)
    private var inkMotionLevelRaw = InkMotionLevel.full.rawValue
    @State private var isStamped = false
    @State private var breath = 0.0

    private var motionIsEnabled: Bool {
        !reduceMotion && (InkMotionLevel(rawValue: inkMotionLevelRaw) ?? .full) != .off
    }

    private var tilt: Angle {
        .degrees(-3.5 + InkVariant(seed: seed, spread: 0.30).rotation.degrees)
    }

    private var resolvedStyle: InkSealStyle {
        style ?? .selected(for: seed)
    }

    var body: some View {
        ZStack {
            InkSealBorder(style: resolvedStyle)

            SealScriptText(text: character, size: size * 0.45)
                .padding(size * (resolvedStyle == .doubleSquare ? 0.19 : 0.14))
        }
        .frame(width: size, height: size)
        .rotationEffect(tilt)
        .scaleEffect((isStamped ? 1 : 0.70) * (1 + breath * 0.055))
        .opacity(isStamped ? 1 : 0)
        .blur(radius: isStamped ? 0 : 1.8)
        .onAppear {
            guard animated, !reduceMotion else {
                isStamped = true
                startBreathingIfNeeded()
                return
            }
            withAnimation(.spring(response: 0.34, dampingFraction: 0.62)) {
                isStamped = true
            }
            startBreathingIfNeeded()
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }

    /// An urgent seal keeps a faint pulse so the 朱红 reads as "still counting",
    /// rather than as a static decoration.
    private func startBreathingIfNeeded() {
        guard breathes, motionIsEnabled else { return }
        withAnimation(.easeInOut(duration: 2.4).repeatForever(autoreverses: true)) {
            breath = 1
        }
    }
}

/// Deterministic variation for the shared ink artwork.
///
/// There are only a handful of brush bitmaps, and pasting each one at the same
/// angle everywhere made the app look stamped rather than painted. A seed maps
/// to a stable rotation, flip and scale, so the same file reads as a different
/// stroke of the same brush in each place it appears — and stays put across
/// redraws instead of shimmering.
struct InkVariant {
    let rotation: Angle
    let scale: CGFloat
    let flipsHorizontally: Bool
    let opacityScale: Double

    /// Stable across launches, unlike `hashValue`, so a card keeps the same
    /// brush angle every time the user opens the app.
    static func seed(for text: String) -> Int {
        var hash: UInt64 = 0xCBF2_9CE4_8422_2325
        for byte in text.utf8 {
            hash ^= UInt64(byte)
            hash = hash &* 0x0000_0100_0000_01B3
        }
        return Int(bitPattern: UInt(truncatingIfNeeded: hash))
    }

    static func index(for seed: Int, count: Int) -> Int {
        guard count > 0 else { return 0 }
        return Int(UInt(bitPattern: seed) % UInt(count))
    }

    init(seed: Int, spread: Double = 1) {
        // Cheap integer hash: stable, no Foundation randomness.
        var hash = UInt64(bitPattern: Int64(seed &* 2_654_435_761))
        hash ^= hash >> 13
        hash = hash &* 0x9E37_79B9_7F4A_7C15
        hash ^= hash >> 7

        let angleUnit = Double(hash & 0xFF) / 255.0
        let scaleUnit = Double((hash >> 8) & 0xFF) / 255.0
        let opacityUnit = Double((hash >> 16) & 0xFF) / 255.0

        rotation = .degrees((angleUnit * 2 - 1) * 26 * spread)
        scale = CGFloat(1 + (scaleUnit * 2 - 1) * 0.11 * spread)
        flipsHorizontally = (hash >> 24) & 1 == 1
        opacityScale = 1 + (opacityUnit * 2 - 1) * 0.16 * spread
    }
}

extension View {
    /// Applies an `InkVariant` as a rendering transform.
    func inkVariant(_ variant: InkVariant) -> some View {
        scaleEffect(
            x: variant.flipsHorizontally ? -variant.scale : variant.scale,
            y: variant.scale
        )
        .rotationEffect(variant.rotation)
    }
}

struct InkBrushMedallion: View {
    let symbol: String
    var tint: Color = .planPrimary
    var size: CGFloat = 38
    /// Distinguishes this medallion's brush loop from its neighbours'.
    var seed: Int = 0

    var body: some View {
        let variant = InkVariant(seed: seed)
        let artworkNames = [
            "InkIconLoop",
            "InkIconCrescent",
            "InkIconScholarSquare"
        ]
        let artworkName = artworkNames[
            InkVariant.index(for: seed, count: artworkNames.count)
        ]
        return ZStack {
            Image(artworkName)
                .renderingMode(.template)
                .resizable()
                .scaledToFit()
                .foregroundStyle(tint.opacity(0.72 * variant.opacityScale))
                .inkVariant(variant)
            Image(systemName: symbol)
                .font(.system(size: size * 0.34, weight: .semibold))
                .foregroundStyle(tint)
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

private struct InkRevealModifier: ViewModifier {
    let delay: Double

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var isVisible = false

    func body(content: Content) -> some View {
        content
            .opacity(isVisible ? 1 : 0)
            .offset(y: isVisible ? 0 : 9)
            .blur(radius: isVisible ? 0 : 2.5)
            .onAppear {
                guard !reduceMotion else {
                    isVisible = true
                    return
                }
                withAnimation(.easeOut(duration: 0.48).delay(delay)) {
                    isVisible = true
                }
            }
    }
}

extension View {
    func inkReveal(delay: Double = 0) -> some View {
        modifier(InkRevealModifier(delay: delay))
    }

    func inkFormStyle() -> some View {
        scrollContentBackground(.hidden)
            .listSectionSpacing(.custom(12))
            .contentMargins(.top, 8, for: .scrollContent)
    }
}

struct InkEmptyPainting: View {
    let symbol: String

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var progress = 0.0

    var body: some View {
        ZStack {
            InkFocusBloomView(opacity: 0.10)
                .frame(width: 112, height: 72)
                .scaleEffect(0.82 + progress * 0.18)
                .opacity(progress)

            InkBrushMedallion(
                symbol: symbol,
                tint: .planPrimary,
                size: 40,
                seed: InkVariant.seed(for: symbol)
            )
            .opacity(progress)
            .scaleEffect(0.78 + progress * 0.22)
        }
        .frame(width: 140, height: 74)
        .onAppear {
            guard !reduceMotion else {
                progress = 1
                return
            }
            withAnimation(.easeOut(duration: 0.92)) {
                progress = 1
            }
        }
        .accessibilityHidden(true)
    }
}

struct InkBrushRing: View {
    let progress: Double
    var accent: Color = .planPrimary
    var lineWidth: CGFloat = 9
    var displaysRemaining = false
    var showsTrack = true
    var trackOpacity = 0.13
    var showsBrushTip = true

    var body: some View {
        GeometryReader { proxy in
            let diameter = min(proxy.size.width, proxy.size.height)
            let clamped = min(1, max(0, progress))
            let visibleProgress = displaysRemaining ? 1 - clamped : clamped
            let maskWidth = max(diameter * 0.19, lineWidth * 4)

            ZStack {
                if showsTrack {
                    Image("InkTimerRingDirectional")
                        .renderingMode(.template)
                        .resizable()
                        .scaledToFit()
                        .foregroundStyle(Color.planPrimary.opacity(trackOpacity))
                        .scaleEffect(x: -1, y: 1)
                }

                if visibleProgress > 0.001 {
                    Image("InkTimerRingDirectional")
                        .renderingMode(.template)
                        .resizable()
                        .scaledToFit()
                        .foregroundStyle(accent.opacity(0.92))
                        .scaleEffect(x: -1, y: 1)
                        .mask {
                            InkDirectionalRevealMask(
                                progress: visibleProgress,
                                maskWidth: maskWidth,
                                showsDryFibers: showsBrushTip
                            )
                        }
                }
            }
            .rotationEffect(.degrees(3))
            .frame(width: diameter, height: diameter)
            .position(x: proxy.size.width / 2, y: proxy.size.height / 2)
        }
        .accessibilityHidden(true)
    }
}

private struct InkDirectionalRevealMask: View {
    let progress: Double
    let maskWidth: CGFloat
    let showsDryFibers: Bool

    var body: some View {
        Canvas { context, size in
            let diameter = min(size.width, size.height)
            let center = CGPoint(x: size.width / 2, y: size.height / 2)
            let radius = diameter * 0.365
            let startAngle = CGFloat(-84.0 * Double.pi / 180.0)
            // Stop just before the source bitmap's loaded start. The old 346°
            // sweep plus its round cap crossed the remaining paper gap, which
            // made the final frames look like a restart.
            let totalSweep = CGFloat(344.0 * Double.pi / 180.0)
            let finishAngle = startAngle + totalSweep
            let endpointGap = CGFloat(2 * Double.pi) - totalSweep
            // Leave only a very small allowance beyond the authored finish.
            // The loaded start occupies most of this deliberate gap, so using
            // its midpoint still exposes a small second blot near 100%.
            let finishIsolationAngle = finishAngle + endpointGap * 0.12
            let clamped = min(1, max(0, progress))

            // The authored bitmap is mirrored at display time: its loaded start
            // sits at -84° on the upper-right, then the visible stroke advances
            // clockwise to the fine upper-left finish.
            func point(progress: CGFloat, radialOffset: CGFloat = 0) -> CGPoint {
                let angle = startAngle + totalSweep * progress
                let pointRadius = radius + radialOffset
                return CGPoint(
                    x: center.x + cos(angle) * pointRadius,
                    y: center.y + sin(angle) * pointRadius
                )
            }

            func arcPath(
                from start: CGFloat,
                to end: CGFloat,
                radialOffset: CGFloat = 0
            ) -> Path {
                var path = Path()
                let distance = max(0, end - start)
                let segmentCount = max(2, Int(ceil(distance * 180)))
                for segment in 0...segmentCount {
                    let fraction = CGFloat(segment) / CGFloat(segmentCount)
                    let current = start + distance * fraction
                    let sample = point(progress: current, radialOffset: radialOffset)
                    if segment == 0 {
                        path.move(to: sample)
                    } else {
                        path.addLine(to: sample)
                    }
                }
                return path
            }

            func finishIsolationPath() -> Path {
                var path = Path()
                let outerRadius = hypot(size.width, size.height)
                let isolationStart = startAngle + totalSweep * 0.70
                let distance = max(0, finishIsolationAngle - isolationStart)
                let segmentCount = max(8, Int(ceil(distance * 90)))

                path.move(to: center)
                for segment in 0...segmentCount {
                    let fraction = CGFloat(segment) / CGFloat(segmentCount)
                    let angle = isolationStart + distance * fraction
                    path.addLine(
                        to: CGPoint(
                            x: center.x + cos(angle) * outerRadius,
                            y: center.y + sin(angle) * outerRadius
                        )
                    )
                }
                path.closeSubpath()
                return path
            }

            func smoothStep(_ value: CGFloat) -> CGFloat {
                let bounded = min(1, max(0, value))
                return bounded * bounded * (3 - 2 * bounded)
            }

            func drawLiveTail(
                in drawingContext: inout GraphicsContext,
                from start: CGFloat,
                lineWidth: CGFloat
            ) {
                let draw: (inout GraphicsContext) -> Void = { tailContext in
                    tailContext.stroke(
                        arcPath(from: start, to: clamped),
                        with: .color(.white),
                        style: StrokeStyle(
                            lineWidth: lineWidth,
                            lineCap: .round,
                            lineJoin: .round
                        )
                    )
                }

                // The bitmap's loaded start and pointed finish are intentionally
                // close together. Near completion a normal round-capped mask can
                // cross that gap and reveal the loaded start for a second time.
                // Clip only the moving tail to the finish side of the gap; the
                // already-painted body remains untouched.
                if clamped > 0.82 {
                    drawingContext.drawLayer { tailContext in
                        tailContext.clip(to: finishIsolationPath())
                        draw(&tailContext)
                    }
                } else {
                    draw(&drawingContext)
                }
            }

            // A fixed cap over the authored 起笔.
            //
            // This used to be a small ellipse (radius 0.31·maskWidth). Early on
            // the moving tail's own round cap sat at t=0 and covered far more
            // than that, so the painted start looked complete. As the tail
            // advanced, that cap stopped reaching back — around the point where
            // the tip passes two o'clock — and the start collapsed to the small
            // ellipse, visibly biting a crescent out of the 起笔.
            //
            // Drawing the cap as a short, full-width, round-capped stub makes
            // the start coverage identical on every frame, so nothing can
            // retreat. It reaches ~14° back from the start, which stays inside
            // the 16° gap to the authored finish.
            if clamped > 0.001 {
                context.stroke(
                    arcPath(from: 0, to: min(clamped, 0.012)),
                    with: .color(.white),
                    style: StrokeStyle(
                        lineWidth: maskWidth * (showsDryFibers ? 0.96 : 1),
                        lineCap: .round,
                        lineJoin: .round
                    )
                )
            }

            if clamped > 0.001 {
                let tailLength: CGFloat = showsDryFibers ? 0.060 : 0.040
                let transitionStart = max(0, clamped - tailLength)
                let taper = smoothStep((clamped - 0.86) / 0.14)
                let baseTailScale: CGFloat = showsDryFibers ? 0.78 : 0.94
                let finalTailScale: CGFloat = showsDryFibers ? 0.44 : 0.52
                let tailScale = baseTailScale
                    + (finalTailScale - baseTailScale) * taper

                if transitionStart > 0.001 {
                    context.stroke(
                        arcPath(from: 0, to: transitionStart),
                        with: .color(.white),
                        style: StrokeStyle(
                            lineWidth: maskWidth * (showsDryFibers ? 0.96 : 1),
                            lineCap: .butt,
                            lineJoin: .round
                        )
                    )
                }

                if showsDryFibers {
                    drawLiveTail(
                        in: &context,
                        from: max(0, transitionStart - 0.014),
                        lineWidth: maskWidth * tailScale
                    )
                } else {
                    drawLiveTail(
                        in: &context,
                        from: max(0, transitionStart - 0.010),
                        lineWidth: maskWidth * tailScale
                    )
                }
            }
        }
        .allowsHitTesting(false)
    }
}

struct InkCompletionBurst: View {
    let progress: Double

    var body: some View {
        let clamped = min(1, max(0, progress))
        ZStack {
            InkFocusBloomView(opacity: 0.12 * (1 - clamped))
                .scaleEffect(0.25 + clamped * 1.65)
                .blur(radius: 1 + clamped * 5)

            Image("InkIconLoop")
                .renderingMode(.template)
                .resizable()
                .scaledToFit()
                .foregroundStyle(Color.planPrimary.opacity(0.78))
                .scaleEffect(0.44 + clamped * 0.56)
                .opacity(clamped)

            GeometryReader { proxy in
                Image("InkCheckMark")
                    .renderingMode(.template)
                    .resizable()
                    .scaledToFit()
                    .foregroundStyle(Color.planPrimary)
                    .frame(width: proxy.size.width, height: proxy.size.height)
                    .mask(alignment: .leading) {
                        Rectangle()
                            .frame(width: proxy.size.width * clamped)
                    }
            }
            .frame(width: 20, height: 17)
        }
        .opacity(clamped > 0 ? 1 : 0)
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

struct InkGoalSeal: View {
    let progress: Double
    let label: String

    @State private var animatedProgress = 0.0

    var body: some View {
        ZStack {
            InkBrushRing(
                progress: animatedProgress,
                accent: .planPrimary,
                lineWidth: 5
            )
            VStack(spacing: 1) {
                Text("\(Int(min(1, max(0, animatedProgress)) * 100))%")
                    .font(.headline.weight(.semibold))
                    .monospacedDigit()
                    .foregroundStyle(Color.planPrimary)
                Text(label)
                    .font(.system(size: 9, weight: .medium))
                    .foregroundStyle(.secondary)
            }

            if animatedProgress >= 0.995 {
                InkSealMark(character: "成", size: 25, style: .weathered)
                    .offset(x: 29, y: 29)
                    .transition(.scale.combined(with: .opacity))
            }
        }
        .frame(width: 82, height: 82)
        .onAppear {
            animate(to: progress)
        }
        .onChange(of: progress) { _, newValue in
            animate(to: newValue)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(label)，\(Int(progress * 100))%")
    }

    private func animate(to value: Double) {
        animatedProgress = 0
        withAnimation(.easeOut(duration: 0.95)) {
            animatedProgress = min(1, max(0, value))
        }
    }
}

struct SectionHeading: View {
    let title: String
    var actionTitle: String?
    var action: (() -> Void)?

    var body: some View {
        HStack {
            Text(title)
                .font(.title3.weight(.bold))
                .background(alignment: .bottom) {
                    InkHeaderWashView(opacity: 0.10)
                        .frame(height: 11)
                        .offset(y: 4)
                }
            Spacer()
            if let actionTitle, let action {
                Button(actionTitle, action: action)
                    .font(.subheadline.weight(.semibold))
            }
        }
    }
}

struct MetricTile: View {
    let title: String
    let value: String
    let symbol: String
    let tint: Color

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            InkBrushMedallion(
                symbol: symbol,
                tint: tint,
                size: 31,
                seed: InkVariant.seed(for: title)
            )
            Text(value)
                .font(.title3.weight(.bold))
                .monospacedDigit()
            Text(title)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .planCard()
    }
}

struct RecordRow: View {
    let record: TimeRecord

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: record.category.symbolName)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(record.category.color)
                .frame(width: 30)

            VStack(alignment: .leading, spacing: 4) {
                Text(record.title)
                    .font(.body.weight(.semibold))
                    .lineLimit(1)
                Text(record.timingSummaryText)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Spacer(minLength: 8)

            Text(
                record.hasPreciseTime
                    ? DurationText.full(minutes: record.durationMinutes)
                    : "已记录"
            )
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(record.category.color)
                .multilineTextAlignment(.trailing)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
    }
}

struct CountdownCard: View {
    let event: CountdownEvent

    var body: some View {
        TimelineView(.periodic(from: .now, by: 60 * 30)) { context in
            let occurrence = event.occurrenceDate(relativeTo: context.date)
            let count = CountdownDayCalculator.count(
                to: occurrence,
                from: context.date,
                includesToday: event.countsToday
            )
            let isUrgent = event.isUrgent(relativeTo: context.date)
            let countColor: Color = isUrgent ? .planVermilion : .planPrimary
            // A repeating birthday counts down to the next one, but the point
            // of keeping it is also how long it has been. Show both.
            let elapsed = event.elapsedSummaryText(relativeTo: context.date)

            HStack(alignment: .center, spacing: 12) {
                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 5) {
                        Text(event.title)
                            .font(.body.weight(.semibold))
                            .lineLimit(1)
                        if event.isPinned {
                            Image(systemName: "pin.fill")
                                .font(.system(size: 9, weight: .semibold))
                                .foregroundStyle(Color.planVermilion)
                        }
                        if event.isSyncedToCalendar {
                            Image(systemName: "calendar.badge.checkmark")
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                        }
                    }

                    Text(event.dateSummaryText)
                        .font(.caption)
                        .foregroundStyle(.secondary)

                    if let elapsed, !count.isPast {
                        // Only worth a second line when the headline number is
                        // a countdown — a past date already reads as elapsed.
                        Text(elapsed)
                            .font(.caption2)
                            .foregroundStyle(Color.planPrimary.opacity(0.62))
                            .lineLimit(1)
                    }
                }

                Spacer(minLength: 8)

                if isUrgent {
                    InkSealMark(
                        character: "急",
                        size: 25,
                        style: .square,
                        seed: InkVariant.seed(for: event.id.uuidString),
                        breathes: true
                    )
                }

                VStack(alignment: .trailing, spacing: 1) {
                    Text(count.directionText)
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(isUrgent ? Color.planVermilion : Color.secondary)

                    Group {
                        if count.isToday {
                            Text("今天")
                                .font(.title3.weight(.semibold))
                        } else {
                            HStack(alignment: .firstTextBaseline, spacing: 2) {
                                Text("\(count.magnitude)")
                                    .font(.system(size: 27, weight: .semibold, design: .rounded))
                                Text("天")
                                    .font(.caption)
                            }
                            .monospacedDigit()
                        }
                    }
                    // A brushed wash under the number instead of leaving it
                    // floating on bare paper.
                    .background(alignment: .bottom) {
                        InkHeaderWashView(
                            tint: countColor,
                            opacity: isUrgent ? 0.18 : 0.065
                        )
                        .frame(height: 9)
                        .offset(y: 5)
                        .scaleEffect(
                            x: InkVariant(seed: InkVariant.seed(for: event.title)).flipsHorizontally ? -1 : 1,
                            y: 1
                        )
                    }
                }
                .foregroundStyle(countColor)
            }
            .padding(.horizontal, 4)
            .padding(.vertical, 12)
            .background {
                if isUrgent {
                    InkPaperCardShape(cornerRadius: 12)
                        .fill(Color.planCard)
                }
            }
            .overlay {
                if isUrgent {
                    InkPaperCardShape(cornerRadius: 12)
                        .stroke(Color.planVermilion.opacity(0.22), lineWidth: 0.75)
                }
            }
            .overlay(alignment: .bottom) {
                if !isUrgent {
                    InkBrushDivider(
                        animated: false,
                        seed: InkVariant.seed(for: event.id.uuidString)
                    )
                    .opacity(0.22)
                }
            }
            .shadow(
                color: Color.planPrimary.opacity(isUrgent ? 0.028 : 0),
                radius: isUrgent ? 10 : 0,
                y: isUrgent ? 4 : 0
            )
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
        }
    }
}

struct PlanEmptyState: View {
    let symbol: String
    let title: String
    let message: String

    var body: some View {
        VStack(spacing: 10) {
            InkBrushMedallion(
                symbol: symbol,
                tint: .planPrimary,
                size: 42,
                seed: InkVariant.seed(for: title)
            )
            Text(title)
                .font(.headline)
            Text(message)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 24)
        .planCard()
    }
}
