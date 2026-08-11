import AppKit
import CoreGraphics
import Foundation

guard CommandLine.arguments.count == 2 else {
    fputs("Usage: swift GenerateIcon.swift <output.png>\n", stderr)
    exit(2)
}

let outputURL = URL(fileURLWithPath: CommandLine.arguments[1])
let size = CGSize(width: 1024, height: 1024)
let colorSpace = CGColorSpaceCreateDeviceRGB()
guard let context = CGContext(
    data: nil,
    width: Int(size.width),
    height: Int(size.height),
    bitsPerComponent: 8,
    bytesPerRow: 0,
    space: colorSpace,
    bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
) else {
    fputs("Unable to create graphics context\n", stderr)
    exit(3)
}

context.setAllowsAntialiasing(true)
let gradientColors = [
    NSColor(red: 0.35, green: 0.20, blue: 0.88, alpha: 1).cgColor,
    NSColor(red: 0.16, green: 0.55, blue: 0.94, alpha: 1).cgColor
] as CFArray
let gradient = CGGradient(colorsSpace: colorSpace, colors: gradientColors, locations: [0, 1])!
context.drawLinearGradient(
    gradient,
    start: CGPoint(x: 0, y: 1024),
    end: CGPoint(x: 1024, y: 0),
    options: []
)

context.setFillColor(NSColor.white.withAlphaComponent(0.16).cgColor)
context.fillEllipse(in: CGRect(x: 610, y: 600, width: 520, height: 520))
context.fillEllipse(in: CGRect(x: -170, y: -130, width: 610, height: 610))

let clockRect = CGRect(x: 222, y: 222, width: 580, height: 580)
context.setStrokeColor(NSColor.white.cgColor)
context.setLineWidth(68)
context.strokeEllipse(in: clockRect.insetBy(dx: 34, dy: 34))

context.setLineCap(.round)
context.setLineJoin(.round)
context.move(to: CGPoint(x: 512, y: 512))
context.addLine(to: CGPoint(x: 512, y: 675))
context.move(to: CGPoint(x: 512, y: 512))
context.addLine(to: CGPoint(x: 642, y: 424))
context.strokePath()

context.setFillColor(NSColor(red: 1.0, green: 0.65, blue: 0.25, alpha: 1).cgColor)
context.fillEllipse(in: CGRect(x: 452, y: 452, width: 120, height: 120))

guard
    let cgImage = context.makeImage(),
    let bitmap = Optional(NSBitmapImageRep(cgImage: cgImage)),
    let pngData = bitmap.representation(using: .png, properties: [:])
else {
    fputs("Unable to encode icon\n", stderr)
    exit(4)
}

try pngData.write(to: outputURL, options: .atomic)
