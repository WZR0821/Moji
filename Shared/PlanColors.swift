import SwiftUI

enum PlanColorName {
    static let choices = ["ink", "vermilion", "charcoal", "ash", "smoke", "mist"]

    static func color(for name: String) -> Color {
        switch name {
        case "vermilion": return Color.planVermilion
        case "charcoal": return Color(white: 0.24)
        case "ash": return Color(white: 0.38)
        case "smoke": return Color(white: 0.52)
        case "mist": return Color(white: 0.66)
        default: return Color.planPrimary
        }
    }
}

extension RecordCategory {
    var color: Color {
        if self == .study { return .planPrimary }
        if self == .work { return .planSecondary }
        //朱砂 is reserved for urgent and active states. Custom categories use
        //a separate warm graphite so charts do not read every category as an
        //alert.
        return .planCategoryCustom
    }
}

extension Color {
    static let planBackground = Color(
        uiColor: UIColor { traits in
            traits.userInterfaceStyle == .dark
                ? UIColor(red: 0.065, green: 0.061, blue: 0.055, alpha: 1)
                : UIColor(red: 0.968, green: 0.957, blue: 0.929, alpha: 1)
        }
    )
    static let planCard = Color(
        uiColor: UIColor { traits in
            traits.userInterfaceStyle == .dark
                ? UIColor(red: 0.135, green: 0.125, blue: 0.112, alpha: 0.94)
                : UIColor(red: 1.0, green: 0.996, blue: 0.985, alpha: 0.88)
        }
    )
    static let planPrimary = Color(
        uiColor: UIColor { traits in
            traits.userInterfaceStyle == .dark
                ? UIColor(red: 0.93, green: 0.91, blue: 0.86, alpha: 1)
                : UIColor(red: 0.141, green: 0.137, blue: 0.129, alpha: 1)
        }
    )
    static let planSecondary = Color(
        uiColor: UIColor { traits in
            traits.userInterfaceStyle == .dark
                ? UIColor(red: 0.67, green: 0.64, blue: 0.58, alpha: 1)
                : UIColor(red: 0.427, green: 0.412, blue: 0.384, alpha: 1)
        }
    )
    static let planCategoryCustom = Color(
        uiColor: UIColor { traits in
            traits.userInterfaceStyle == .dark
                ? UIColor(red: 0.58, green: 0.55, blue: 0.49, alpha: 1)
                : UIColor(red: 0.54, green: 0.50, blue: 0.45, alpha: 1)
        }
    )
    static let planVermilion = Color(
        uiColor: UIColor { traits in
            traits.userInterfaceStyle == .dark
                ? UIColor(red: 0.72, green: 0.38, blue: 0.33, alpha: 1)
                : UIColor(red: 0.56, green: 0.20, blue: 0.17, alpha: 1)
        }
    )
}

/// Five reusable seal borders. Keeping these as vectors lets the same seal stay
/// crisp in the app, widgets, Live Activities and the Dynamic Island.
enum InkSealStyle: Int, CaseIterable {
    case square
    case doubleSquare
    case round
    case weathered
    case tall

    static func selected(for seed: Int) -> InkSealStyle {
        let index = Int(UInt(bitPattern: seed) % UInt(allCases.count))
        return allCases[index]
    }
}

struct InkSealBorder: View {
    let style: InkSealStyle
    var color: Color = .planVermilion

    var body: some View {
        Canvas { context, size in
            let unit = min(size.width, size.height)
            let lineWidth = max(0.8, unit * 0.055)
            let outer = CGRect(
                x: lineWidth,
                y: lineWidth,
                width: size.width - lineWidth * 2,
                height: size.height - lineWidth * 2
            )
            let ink = GraphicsContext.Shading.color(color.opacity(0.88))

            switch style {
            case .square:
                var path = Path()
                path.move(to: CGPoint(x: outer.minX + unit * 0.08, y: outer.minY))
                path.addLine(to: CGPoint(x: outer.maxX - unit * 0.04, y: outer.minY + unit * 0.02))
                path.addLine(to: CGPoint(x: outer.maxX, y: outer.minY + unit * 0.09))
                path.addLine(to: CGPoint(x: outer.maxX - unit * 0.01, y: outer.maxY - unit * 0.03))
                path.addLine(to: CGPoint(x: outer.maxX - unit * 0.08, y: outer.maxY))
                path.addLine(to: CGPoint(x: outer.minX + unit * 0.03, y: outer.maxY - unit * 0.01))
                path.addLine(to: CGPoint(x: outer.minX, y: outer.maxY - unit * 0.10))
                path.addLine(to: CGPoint(x: outer.minX + unit * 0.01, y: outer.minY + unit * 0.04))
                path.closeSubpath()
                context.stroke(path, with: ink, style: StrokeStyle(lineWidth: lineWidth, lineCap: .round, lineJoin: .round))

            case .doubleSquare:
                let corner = CGSize(width: unit * 0.08, height: unit * 0.08)
                var outerPath = Path()
                outerPath.addRoundedRect(in: outer, cornerSize: corner)
                context.stroke(outerPath, with: ink, lineWidth: lineWidth)

                let inner = outer.insetBy(dx: unit * 0.12, dy: unit * 0.12)
                var innerPath = Path()
                innerPath.addRoundedRect(
                    in: inner,
                    cornerSize: CGSize(width: unit * 0.035, height: unit * 0.035)
                )
                context.stroke(innerPath, with: .color(color.opacity(0.45)), lineWidth: max(0.55, lineWidth * 0.58))

            case .round:
                var ring = Path(ellipseIn: outer.insetBy(dx: unit * 0.015, dy: unit * 0.015))
                context.stroke(
                    ring,
                    with: ink,
                    style: StrokeStyle(lineWidth: lineWidth * 1.08, lineCap: .round, dash: [unit * 1.95, unit * 0.06])
                )
                ring = Path(ellipseIn: outer.insetBy(dx: unit * 0.11, dy: unit * 0.11))
                context.stroke(ring, with: .color(color.opacity(0.28)), lineWidth: max(0.5, lineWidth * 0.48))

            case .weathered:
                var path = Path()
                path.addRoundedRect(
                    in: outer,
                    cornerSize: CGSize(width: unit * 0.12, height: unit * 0.07)
                )
                context.stroke(
                    path,
                    with: ink,
                    style: StrokeStyle(
                        lineWidth: lineWidth * 1.12,
                        lineCap: .round,
                        lineJoin: .round,
                        dash: [unit * 0.34, unit * 0.035, unit * 0.16, unit * 0.055]
                    )
                )
                var chip = Path()
                chip.move(to: CGPoint(x: outer.minX + unit * 0.14, y: outer.maxY - unit * 0.02))
                chip.addLine(to: CGPoint(x: outer.minX + unit * 0.32, y: outer.maxY))
                context.stroke(chip, with: .color(color.opacity(0.34)), lineWidth: lineWidth * 0.45)

            case .tall:
                // The old narrow rectangle looked stretched beside the square
                // seals. Keep this fifth style distinct, but use a compact oval
                // whose overall footprint still balances with the other marks.
                let oval = outer.insetBy(dx: unit * 0.08, dy: unit * 0.015)
                var outerOval = Path(ellipseIn: oval)
                context.stroke(
                    outerOval,
                    with: ink,
                    style: StrokeStyle(lineWidth: lineWidth, lineCap: .round)
                )
                outerOval = Path(
                    ellipseIn: oval.insetBy(dx: unit * 0.10, dy: unit * 0.10)
                )
                context.stroke(
                    outerOval,
                    with: .color(color.opacity(0.34)),
                    lineWidth: max(0.5, lineWidth * 0.50)
                )
            }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

/// Uses the bundled Jingfeng Zhongshan seal-script face. The font is registered
/// by both the app and widget extension; SwiftUI falls back to the system face
/// if a future glyph is outside the font's coverage.
struct SealScriptText: View {
    let text: String
    let size: CGFloat
    var color: Color = .planVermilion

    private var glyph: some View {
        Text(String(text.prefix(2)))
            .font(.custom("JFZSKSealScript", fixedSize: size).weight(.black))
            .tracking(text.count > 1 ? -size * 0.10 : 0)
            .minimumScaleFactor(0.58)
            .lineLimit(1)
    }

    var body: some View {
        // The bundled seal-script face has a single font weight, so applying
        // `.black` alone does not make its carved strokes visibly heavier.
        // Closely overprint the same glyph, like a slightly ink-heavy stamp.
        ZStack {
            glyph.offset(x: -0.28)
            glyph.offset(x: 0.28)
            glyph.offset(y: -0.22)
            glyph.offset(y: 0.22)
            glyph
        }
            .foregroundStyle(color)
            .scaleEffect(x: 0.94, y: 1.04)
    }
}

/// The number carved on a carried-over plan's seal, and nothing else — the seal
/// says how many days the plan has been waiting, never what the plan is.
enum CarryOverSeal {
    /// Arabic digits, not seal script: this is the one place in the app where
    /// the number has to be read at a glance rather than admired.
    static func text(daysLate: Int) -> String {
        guard daysLate >= 1 else { return "" }
        return daysLate > 99 ? "99+" : "\(daysLate)"
    }

    static func accessibilityLabel(daysLate: Int) -> String {
        "顺延 \(daysLate) 天"
    }

    /// Whole days between the day a plan was scheduled for and the day being
    /// shown; nil when the plan is not late.
    static func daysLate(
        scheduledStart: Date,
        on date: Date,
        calendar: Calendar = .mojiISO
    ) -> Int? {
        let planned = calendar.startOfDay(for: scheduledStart)
        let target = calendar.startOfDay(for: date)
        guard planned < target else { return nil }
        let days = calendar.dateComponents([.day], from: planned, to: target).day ?? 0
        return days >= 1 ? days : nil
    }
}

/// The carried-over stamp: the app's square seal border with a plain number
/// inside. Everything else in the app stamps seal script, but a day count has
/// to be read, not deciphered, so this one carves Arabic digits.
struct CarryOverDaySeal: View {
    let daysLate: Int
    var size: CGFloat = 26
    var animated = true

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var isStamped = false

    var body: some View {
        ZStack {
            InkSealBorder(style: .square)
            Text(CarryOverSeal.text(daysLate: daysLate))
                .font(.system(size: size * 0.52, weight: .heavy, design: .rounded))
                .monospacedDigit()
                .lineLimit(1)
                .minimumScaleFactor(0.5)
                .foregroundStyle(Color.planVermilion)
                .padding(.horizontal, size * 0.16)
        }
        .frame(width: size, height: size)
        .rotationEffect(.degrees(-4))
        .scaleEffect(isStamped ? 1 : 0.70)
        .opacity(isStamped ? 1 : 0)
        .onAppear {
            guard animated, !reduceMotion else {
                isStamped = true
                return
            }
            withAnimation(.spring(response: 0.34, dampingFraction: 0.62)) {
                isStamped = true
            }
        }
        .allowsHitTesting(false)
    }
}
