// Draws the 1024px app icon PNG: a rounded orange square with a ">_" prompt.
import AppKit

let rep = NSBitmapImageRep(
  bitmapDataPlanes: nil, pixelsWide: 1024, pixelsHigh: 1024, bitsPerSample: 8, samplesPerPixel: 4,
  hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
NSGraphicsContext.saveGraphicsState()
NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)

NSColor(srgbRed: 0xd9 / 255, green: 0x77 / 255, blue: 0x57 / 255, alpha: 1).setFill()
NSBezierPath(roundedRect: NSRect(x: 100, y: 100, width: 824, height: 824), xRadius: 185, yRadius: 185).fill()

let prompt = NSBezierPath()
prompt.lineWidth = 70
prompt.lineCapStyle = .round
prompt.lineJoinStyle = .round
prompt.move(to: NSPoint(x: 320, y: 660))
prompt.line(to: NSPoint(x: 480, y: 512))
prompt.line(to: NSPoint(x: 320, y: 364))
prompt.move(to: NSPoint(x: 550, y: 364))
prompt.line(to: NSPoint(x: 710, y: 364))
NSColor.white.setStroke()
prompt.stroke()

NSGraphicsContext.restoreGraphicsState()
try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: CommandLine.arguments[1]))
