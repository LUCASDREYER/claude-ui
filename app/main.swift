// Claude UI: a native window around the local claude-ui server.
// Starts the server (unless one is already running), shows it in WKWebView, stops it on quit.
import AppKit
import WebKit

let port = ProcessInfo.processInfo.environment["PORT"] ?? "3456"
let serverURL = URL(string: "http://127.0.0.1:\(port)/")!

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate, WKNavigationDelegate, WKUIDelegate, WKScriptMessageHandler {
  private var window: NSWindow!
  private var webView: WKWebView!
  private var server: Process?
  private var loaded = false
  private var stderrText = ""

  func applicationDidFinishLaunching(_ notification: Notification) {
    buildMenu()
    let config = WKWebViewConfiguration()
    config.userContentController.add(self, name: "theme")
    webView = WKWebView(frame: .zero, configuration: config)
    webView.navigationDelegate = self
    webView.uiDelegate = self

    window = NSWindow(
      contentRect: NSRect(x: 0, y: 0, width: 1200, height: 820),
      styleMask: [.titled, .closable, .miniaturizable, .resizable],
      backing: .buffered, defer: false)
    window.title = "Claude UI"
    window.titlebarAppearsTransparent = true
    window.contentView = webView
    window.minSize = NSSize(width: 520, height: 360)
    applyTheme(dark: true)
    window.center()
    window.setFrameAutosaveName("main")
    window.makeKeyAndOrderFront(nil)
    NSApp.activate(ignoringOtherApps: true)

    showMessage("Starting\u{2026}")
    startServer()
  }

  func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { true }

  func applicationWillTerminate(_ notification: Notification) {
    guard let server, server.isRunning else { return }
    server.terminate()
    server.waitUntilExit()
  }

  // MARK: server

  private func startServer() {
    guard let dir = Bundle.main.object(forInfoDictionaryKey: "ClaudeUIProjectDir") as? String else {
      return showMessage("Missing ClaudeUIProjectDir in Info.plist. Rebuild with npm run app.", error: true)
    }
    let p = Process()
    p.executableURL = URL(fileURLWithPath: ProcessInfo.processInfo.environment["SHELL"] ?? "/bin/zsh")
    // Login + interactive shell, so PATH (nvm, Homebrew) matches your terminal for Claude's Bash tool.
    p.arguments = ["-l", "-i", "-c", "./node_modules/.bin/tsc -p . && exec node dist/server/index.js"]
    p.currentDirectoryURL = URL(fileURLWithPath: dir)
    var env = ProcessInfo.processInfo.environment
    env["NO_OPEN"] = "1"
    env["CLAUDE_UI_APP"] = "1"
    p.environment = env

    let out = Pipe(), err = Pipe()
    p.standardOutput = out
    p.standardError = err
    p.standardInput = Pipe() // the server exits when this closes, even if the app crashes
    out.fileHandleForReading.readabilityHandler = { handle in
      let data = handle.availableData
      if data.isEmpty { handle.readabilityHandler = nil; return }
      if String(decoding: data, as: UTF8.self).contains("claude-ui on ") {
        Task { @MainActor [weak self] in self?.load() }
      }
    }
    err.fileHandleForReading.readabilityHandler = { handle in
      let data = handle.availableData
      if data.isEmpty { handle.readabilityHandler = nil; return }
      let text = String(decoding: data, as: UTF8.self)
      Task { @MainActor [weak self] in self?.stderrText += text }
    }
    p.terminationHandler = { proc in
      let status = proc.terminationStatus
      Task { @MainActor [weak self] in self?.serverExited(status) }
    }
    do {
      try p.run()
      server = p
    } catch {
      showMessage("Could not start the server: \(error.localizedDescription)", error: true)
    }
  }

  private func serverExited(_ status: Int32) {
    server = nil
    guard !loaded else { return showStopped(status) }
    // The port may belong to a server started elsewhere (npm start); use it if it answers.
    URLSession.shared.dataTask(with: serverURL) { _, response, _ in
      let up = (response as? HTTPURLResponse)?.statusCode == 200
      Task { @MainActor [weak self] in up ? self?.load() : self?.showStopped(status) }
    }.resume()
  }

  private func showStopped(_ status: Int32) {
    loaded = false
    let tail = stderrText.split(separator: "\n").suffix(30).joined(separator: "\n")
    showMessage("The server stopped (exit \(status)). Cmd+R to restart.\n\n\(tail)", error: true)
  }

  private func load() {
    guard !loaded else { return }
    loaded = true
    webView.load(URLRequest(url: serverURL))
  }

  private func showMessage(_ text: String, error: Bool = false) {
    let escaped = text
      .replacingOccurrences(of: "&", with: "&amp;")
      .replacingOccurrences(of: "<", with: "&lt;")
      .replacingOccurrences(of: ">", with: "&gt;")
    let color = error ? "#e57373" : "#8b919a"
    webView.loadHTMLString("""
      <body style="margin:0;padding:24px;background:#0f1012;color:\(color);font:12.5px/1.5 ui-monospace,Menlo,monospace">
      <pre style="white-space:pre-wrap;margin:0">\(escaped)</pre></body>
      """, baseURL: nil)
  }

  // MARK: web view

  /// Keeps the title bar in step with the page's light/dark theme.
  func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
    guard message.name == "theme", let theme = message.body as? String else { return }
    applyTheme(dark: theme == "dark")
  }

  private func applyTheme(dark: Bool) {
    window.appearance = NSAppearance(named: dark ? .darkAqua : .aqua)
    window.backgroundColor = dark
      ? NSColor(srgbRed: 0x16 / 255, green: 0x18 / 255, blue: 0x1b / 255, alpha: 1)
      : NSColor(srgbRed: 0xf2 / 255, green: 0xf2 / 255, blue: 0xef / 255, alpha: 1)
  }

  /// Links leave the app and open in the default browser.
  func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction,
               decisionHandler: @escaping @MainActor (WKNavigationActionPolicy) -> Void) {
    if action.navigationType == .linkActivated, let url = action.request.url, url.host != "127.0.0.1" {
      NSWorkspace.shared.open(url)
      return decisionHandler(.cancel)
    }
    decisionHandler(.allow)
  }

  func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration,
               for action: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
    if let url = action.request.url { NSWorkspace.shared.open(url) }
    return nil
  }

  // MARK: menu (Edit items are what make Cmd+C / Cmd+V work in the web view)

  @objc private func reload() {
    if server == nil && !loaded { startServer() } else { webView.reload() }
  }
  @objc private func zoomIn() { webView.pageZoom = min(webView.pageZoom + 0.1, 2) }
  @objc private func zoomOut() { webView.pageZoom = max(webView.pageZoom - 0.1, 0.6) }
  @objc private func zoomReset() { webView.pageZoom = 1 }

  private func buildMenu() {
    let main = NSMenu()
    func submenu(_ title: String, _ items: [NSMenuItem]) {
      let item = NSMenuItem()
      let menu = NSMenu(title: title)
      items.forEach(menu.addItem)
      item.submenu = menu
      main.addItem(item)
    }
    func item(_ title: String, _ action: Selector, _ key: String, _ target: AnyObject? = nil,
              mods: NSEvent.ModifierFlags = .command) -> NSMenuItem {
      let i = NSMenuItem(title: title, action: action, keyEquivalent: key)
      i.keyEquivalentModifierMask = mods
      i.target = target
      return i
    }
    submenu("Claude UI", [
      item("Hide Claude UI", #selector(NSApplication.hide(_:)), "h"),
      .separator(),
      item("Quit Claude UI", #selector(NSApplication.terminate(_:)), "q"),
    ])
    submenu("Edit", [
      item("Undo", Selector(("undo:")), "z"),
      item("Redo", Selector(("redo:")), "z", mods: [.command, .shift]),
      .separator(),
      item("Cut", #selector(NSText.cut(_:)), "x"),
      item("Copy", #selector(NSText.copy(_:)), "c"),
      item("Paste", #selector(NSText.paste(_:)), "v"),
      item("Select All", #selector(NSText.selectAll(_:)), "a"),
    ])
    submenu("View", [
      item("Reload", #selector(reload), "r", self),
      .separator(),
      item("Actual Size", #selector(zoomReset), "0", self),
      item("Zoom In", #selector(zoomIn), "=", self),
      item("Zoom Out", #selector(zoomOut), "-", self),
    ])
    submenu("Window", [
      item("Minimize", #selector(NSWindow.performMiniaturize(_:)), "m"),
      item("Close", #selector(NSWindow.performClose(_:)), "w"),
    ])
    NSApp.mainMenu = main
  }
}

MainActor.assumeIsolated {
  let app = NSApplication.shared
  let delegate = AppDelegate()
  app.delegate = delegate
  app.setActivationPolicy(.regular)
  app.run()
}
