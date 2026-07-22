import Foundation
import UIKit
import SwiftUI
import CoreImage

// =============================================================================
// ImageCropper — iOS native image editor
// =============================================================================
//
// A configurable, fully-native editor (SwiftUI) with three modes:
//   • Crop   — freehand drag / pinch-zoom / rotate behind a circle/rect mask,
//              a live preset selector, and draggable Zoom / Rotate rulers.
//   • Adjust — Brightness / Contrast / Saturation via draggable rulers (live).
//   • Filter — one-tap presets that set those three at once.
//
// Layout (top → bottom): image area · sub-tool tabs · ruler · [Cancel | crop /
// adjust / filter icons | Done]. On "Done" the crop is rendered and the colour
// adjustments are baked in (CoreImage), then the file path is returned via the
// `ImageCropped` event.
// =============================================================================

// MARK: - Bridge function

enum ImageCropperFunctions {
    class Open: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            let config = CropConfig(parameters)
            DispatchQueue.main.async { ImageCropperPresenter.shared.present(config: config) }
            return [:]
        }
    }
}

// MARK: - Config

/// A crop preset the user can switch to live.
struct CropPreset: Identifiable {
    let key: String, label: String, shape: String
    let aspectRatio: CGFloat
    var id: String { key }
}

struct CropConfig {
    let path: String
    let shape: String
    let aspectRatio: CGFloat
    let tools: [String]
    let modes: [String]
    let presets: [CropPreset]
    let outputSize: Int
    let id: String?

    init(_ p: [String: Any]) {
        path = p["path"] as? String ?? ""
        shape = (p["shape"] as? String) == "circle" ? "circle" : "rect"
        let ratio = (p["aspectRatio"] as? NSNumber)?.doubleValue ?? 1.0
        aspectRatio = CGFloat(ratio > 0 ? ratio : 1.0)
        tools = (p["tools"] as? [String])?.filter { ["zoom", "rotate"].contains($0) } ?? ["zoom", "rotate"]
        let requested = (p["modes"] as? [String])?.filter { ["crop", "adjust", "filter"].contains($0) } ?? []
        modes = requested.isEmpty ? ["crop", "adjust", "filter"] : requested
        outputSize = (p["outputSize"] as? NSNumber)?.intValue ?? 1024
        id = p["id"] as? String
        presets = ((p["presets"] as? [[String: Any]]) ?? []).map {
            CropPreset(key: $0["key"] as? String ?? "", label: $0["label"] as? String ?? "",
                       shape: ($0["shape"] as? String) == "circle" ? "circle" : "rect",
                       aspectRatio: CGFloat(max(0.01, ($0["aspectRatio"] as? NSNumber)?.doubleValue ?? 1.0)))
        }
    }
}

/// A colour filter preset — brightness / contrast / saturation in −100…100.
struct ColorFilter: Identifiable {
    let name: String
    let brightness: Double, contrast: Double, saturation: Double
    var id: String { name }

    static let all: [ColorFilter] = [
        ColorFilter(name: "Original", brightness: 0, contrast: 0, saturation: 0),
        ColorFilter(name: "Vivid", brightness: 3, contrast: 18, saturation: 40),
        ColorFilter(name: "Mono", brightness: 0, contrast: 12, saturation: -100),
        ColorFilter(name: "Noir", brightness: -6, contrast: 38, saturation: -100),
        ColorFilter(name: "Soft", brightness: 8, contrast: -14, saturation: -8),
        ColorFilter(name: "Punch", brightness: -2, contrast: 26, saturation: 24),
    ]
}

// MARK: - Events

private enum CropEvents {
    static let cropped = "Vipertecpro\\ImageCropper\\Events\\ImageCropped"
    static let cancelled = "Vipertecpro\\ImageCropper\\Events\\CropCancelled"
}

// MARK: - Presenter

final class ImageCropperPresenter {
    static let shared = ImageCropperPresenter()
    private var hosting: UIViewController?

    func present(config: CropConfig) {
        guard let image = UIImage(contentsOfFile: config.path), let rootVC = Self.top() else {
            fire(CropEvents.cancelled, ["id": config.id as Any]); return
        }
        let view = EditorView(
            image: image, config: config,
            onCancel: { [weak self] in self?.dismiss(); self?.fire(CropEvents.cancelled, ["id": config.id as Any]) },
            onDone: { [weak self] state in
                DispatchQueue.global(qos: .userInitiated).async {
                    let out = CropRenderer.render(image: image, state: state, config: config)
                    DispatchQueue.main.async {
                        self?.dismiss()
                        if let path = out { self?.fire(CropEvents.cropped, ["path": path, "id": config.id as Any]) }
                        else { self?.fire(CropEvents.cancelled, ["id": config.id as Any]) }
                    }
                }
            }
        )
        let host = UIHostingController(rootView: view)
        host.modalPresentationStyle = .fullScreen
        host.view.backgroundColor = .systemBackground
        hosting = host
        rootVC.present(host, animated: true)
    }

    private func dismiss() { hosting?.dismiss(animated: true); hosting = nil }
    private func fire(_ event: String, _ payload: [String: Any]) {
        var clean: [String: Any] = [:]
        for (k, v) in payload where !(v is NSNull) { clean[k] = v }
        LaravelBridge.shared.send?(event, clean)
    }
    private static func top() -> UIViewController? {
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
            .first { $0.activationState == .foregroundActive }
        var top = scene?.windows.first { $0.isKeyWindow }?.rootViewController
        while let p = top?.presentedViewController { top = p }
        return top
    }
}

// MARK: - Transform snapshot

struct CropState {
    var scale: CGFloat, rotationDeg: Double, offset: CGSize
    var fitScale: CGFloat, viewport: CGSize
    var shape: String, aspectRatio: CGFloat
    var brightness: Double, contrast: Double, saturation: Double
}

// MARK: - Editor

private struct EditorView: View {
    let image: UIImage
    let config: CropConfig
    let onCancel: () -> Void
    let onDone: (CropState) -> Void

    // Crop transform
    @State private var scale: CGFloat = 1
    @State private var rotationDeg: Double = 0
    @State private var offset: CGSize = .zero
    @GestureState private var pinch: CGFloat = 1
    @GestureState private var spin: Angle = .zero
    @GestureState private var drag: CGSize = .zero
    @State private var shape: String
    @State private var aspectRatio: CGFloat

    // Colour adjust (−100…100)
    @State private var brightness: Double = 0
    @State private var contrast: Double = 0
    @State private var saturation: Double = 0

    // Mode + sub-tools
    @State private var mode = "crop"            // crop | adjust | filter
    @State private var cropSub = "zoom"          // zoom | rotate
    @State private var adjustSub = "brightness"  // brightness | contrast | saturation
    @State private var showDiscard = false
    @State private var stageSize: CGSize = .zero // measured image-area size (drives Done geometry)

    private let accent = Color(red: 0.92, green: 0.47, blue: 0.18)

    init(image: UIImage, config: CropConfig, onCancel: @escaping () -> Void, onDone: @escaping (CropState) -> Void) {
        self.image = image; self.config = config; self.onCancel = onCancel; self.onDone = onDone
        _shape = State(initialValue: config.shape)
        _aspectRatio = State(initialValue: config.aspectRatio)
        _cropSub = State(initialValue: config.tools.first ?? "zoom")
        _mode = State(initialValue: config.modes.first ?? "crop")
    }

    private var edited: Bool {
        scale != 1 || rotationDeg != 0 || offset != .zero || brightness != 0 || contrast != 0 || saturation != 0
    }

    var body: some View {
        VStack(spacing: 0) {
            // ---- Title bar: back button + dynamic mode title ----
            ZStack {
                HStack {
                    Button { edited ? (showDiscard = true) : onCancel() } label: {
                        Image(systemName: "chevron.left")
                            .font(.system(size: 18, weight: .semibold))
                            .foregroundColor(.primary).frame(width: 40, height: 40)
                    }
                    Spacer()
                }
                Text(mode == "adjust" ? "Adjust" : (mode == "filter" ? "Filter" : "Crop"))
                    .font(.system(size: 17, weight: .semibold)).foregroundColor(.primary)
            }
            .frame(height: 52).padding(.horizontal, 8)

            // ---- Image area: fills the space between the title bar and the controls.
            // A GeometryReader measures the actual area so the crop frame + image share
            // ONE coordinate space; the size is hoisted to `stageSize` for the Done crop.
            GeometryReader { ig in
                let container = ig.size
                if config.modes.contains("crop") {
                    let viewport = Self.viewport(for: container, ratio: aspectRatio)
                    // COVER the crop frame at user-scale 1 (so there's never black inside).
                    let coverScale = max(viewport.width / image.size.width, viewport.height / image.size.height)
                    let display = CGSize(width: image.size.width * coverScale, height: image.size.height * coverScale)
                    ZStack {
                        Image(uiImage: image)
                            .resizable()
                            .frame(width: display.width, height: display.height)
                            .scaleEffect(scale * pinch)
                            .rotationEffect(.degrees(rotationDeg) + spin)
                            .offset(x: offset.width + drag.width, y: offset.height + drag.height)
                            // Live colour preview (baked into the output on Done).
                            .brightness(brightness / 100 * 0.5)
                            .contrast(1 + contrast / 100)
                            .saturation(max(0, 1 + saturation / 100))

                        CropMask(viewport: viewport, circle: shape == "circle").allowsHitTesting(false)
                    }
                    .frame(width: container.width, height: container.height)
                    .clipped()
                    .contentShape(Rectangle())
                    .gesture(DragGesture().updating($drag) { v, s, _ in s = v.translation }
                        .onEnded { v in
                            offset = clampOffset(CGSize(width: offset.width + v.translation.width,
                                                        height: offset.height + v.translation.height),
                                                 display: display, scale: scale, viewport: viewport)
                        })
                    .simultaneousGesture(MagnificationGesture().updating($pinch) { v, s, _ in s = v }
                        .onEnded { v in
                            scale = min(8, max(1, scale * v))
                            offset = clampOffset(offset, display: display, scale: scale, viewport: viewport)
                        })
                    .simultaneousGesture(RotationGesture().updating($spin) { v, s, _ in s = v }
                        .onEnded { v in
                            rotationDeg += v.degrees
                            offset = clampOffset(offset, display: display, scale: scale, viewport: viewport)
                        })
                    .onAppear { stageSize = container }
                    .onChange(of: container) { stageSize = $0 }
                } else {
                    // Adjust / filter only: show the WHOLE image, no crop frame or gestures.
                    let fit = min(container.width / image.size.width, container.height / image.size.height) * 0.92
                    Image(uiImage: image)
                        .resizable()
                        .frame(width: image.size.width * fit, height: image.size.height * fit)
                        .brightness(brightness / 100 * 0.5)
                        .contrast(1 + contrast / 100)
                        .saturation(max(0, 1 + saturation / 100))
                        .frame(width: container.width, height: container.height)
                        .onAppear { stageSize = container }
                }
            }

            // ---- Bottom controls (wrap content; the image area above takes the slack) ----
            VStack(spacing: 14) {
                    if mode == "crop" && !config.presets.isEmpty { presetStrip }

                    if mode == "filter" {
                        filterStrip
                    } else {
                        subToolTabs
                        ruler()
                    }

                    modeBar()
            }
            .padding(.horizontal, 16).padding(.top, 8).padding(.bottom, 8)
        }
        .background(Color(.systemBackground).ignoresSafeArea())
        .alert("Discard Changes", isPresented: $showDiscard) {
            Button("Cancel", role: .cancel) {}
            Button("Discard", role: .destructive) { onCancel() }
        } message: { Text("Are you sure you want to discard these changes?") }
    }

    // MARK: rows

    private var presetStrip: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 18) {
                ForEach(config.presets) { p in
                    let on = p.shape == shape && abs(p.aspectRatio - aspectRatio) < 0.001
                    Button {
                        // Switch the frame AND re-centre/re-cover the image for it.
                        shape = p.shape; aspectRatio = p.aspectRatio
                        scale = 1; offset = .zero; rotationDeg = 0
                    } label: {
                        VStack(spacing: 4) {
                            Image(systemName: p.shape == "circle" ? "person.crop.circle" : "crop")
                                .foregroundColor(on ? .green : .primary.opacity(0.7))
                            Text(p.label).font(.system(size: 11)).foregroundColor(on ? .green : .primary.opacity(0.6))
                        }
                    }
                }
            }.padding(.horizontal, 4)
        }
    }

    // Row 1 — feature name tabs for the active mode.
    private var subToolTabs: some View {
        let items = mode == "crop"
            ? config.tools.map { ($0, $0.capitalized) }
            : [("brightness", "Brightness"), ("contrast", "Contrast"), ("saturation", "Saturation")]
        let active = mode == "crop" ? cropSub : adjustSub
        return HStack(spacing: 26) {
            ForEach(items, id: \.0) { key, label in
                Button(label) { if mode == "crop" { cropSub = key } else { adjustSub = key } }
                    .font(.system(size: 14, weight: active == key ? .semibold : .regular))
                    .foregroundColor(active == key ? .primary : .primary.opacity(0.45))
            }
        }
    }

    // Row 2 — ruler bound to the active value.
    @ViewBuilder
    private func ruler() -> some View {
        if mode == "crop" && cropSub == "zoom" {
            RulerSlider(value: Double(scale), range: 1...8, display: String(format: "%.1fx", Double(scale))) { scale = CGFloat($0) }
        } else if mode == "crop" {
            RulerSlider(value: rotationDeg, range: -180...180, display: "\(Int(rotationDeg.rounded()))°") { rotationDeg = $0 }
        } else if adjustSub == "brightness" {
            RulerSlider(value: brightness, range: -100...100, display: label(brightness)) { brightness = $0 }
        } else if adjustSub == "contrast" {
            RulerSlider(value: contrast, range: -100...100, display: label(contrast)) { contrast = $0 }
        } else {
            RulerSlider(value: saturation, range: -100...100, display: label(saturation)) { saturation = $0 }
        }
    }

    private var filterStrip: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 14) {
                ForEach(ColorFilter.all) { f in
                    let on = brightness == f.brightness && contrast == f.contrast && saturation == f.saturation
                    Button {
                        brightness = f.brightness; contrast = f.contrast; saturation = f.saturation
                    } label: {
                        VStack(spacing: 6) {
                            Image(uiImage: image).resizable().scaledToFill()
                                .frame(width: 64, height: 64).clipped().cornerRadius(10)
                                .brightness(f.brightness / 100 * 0.5).contrast(1 + f.contrast / 100)
                                .saturation(max(0, 1 + f.saturation / 100))
                                .overlay(RoundedRectangle(cornerRadius: 10)
                                    .stroke(on ? Color.green : Color.primary.opacity(0.2), lineWidth: on ? 2 : 1))
                            Text(f.name).font(.system(size: 11)).foregroundColor(on ? .green : .primary.opacity(0.7))
                        }
                    }
                }
            }.padding(.horizontal, 4)
        }
    }

    // Row 3 — Cancel | crop / adjust / filter | Done
    private func modeBar() -> some View {
        let viewport = Self.viewport(for: stageSize, ratio: aspectRatio)
        let fitScale = max(viewport.width / image.size.width, viewport.height / image.size.height)
        return HStack {
            Button("Cancel") { edited ? (showDiscard = true) : onCancel() }.foregroundColor(.primary)
            Spacer()
            // Only show the mode switcher when more than one mode is enabled.
            if config.modes.count > 1 {
                HStack(spacing: 26) {
                    if config.modes.contains("crop") { modeIcon("crop", "crop") }
                    if config.modes.contains("adjust") { modeIcon("adjust", "slider.horizontal.3") }
                    if config.modes.contains("filter") { modeIcon("filter", "camera.filters") }
                }
            }
            Spacer()
            Button("Done") {
                onDone(CropState(scale: scale, rotationDeg: rotationDeg, offset: offset, fitScale: fitScale,
                                 viewport: viewport, shape: shape, aspectRatio: aspectRatio,
                                 brightness: brightness, contrast: contrast, saturation: saturation))
            }.foregroundColor(accent).bold()
        }
    }

    private func modeIcon(_ key: String, _ symbol: String) -> some View {
        Button { mode = key } label: {
            Image(systemName: symbol)
                .font(.system(size: 20))
                .foregroundColor(mode == key ? Color(.systemBackground) : .primary.opacity(0.7))
                .frame(width: 46, height: 46)
                .background(Circle().fill(mode == key ? Color.primary : Color.clear))
        }
    }

    private func label(_ v: Double) -> String { "\(v > 0 ? "+" : "")\(Int(v.rounded()))" }

    /// Keep the image covering the crop frame — the edges can never come inside
    /// it (no black), at ANY rotation. The offset is un-rotated into the image's
    /// own axes, clamped against the rotated image's coverage of the (axis-
    /// aligned) viewport, then rotated back.
    private func clampOffset(_ o: CGSize, display: CGSize, scale: CGFloat, viewport: CGSize) -> CGSize {
        let w = display.width * scale
        let h = display.height * scale
        let rad = rotationDeg * .pi / 180
        let cosT = cos(rad), sinT = sin(rad)
        let ac = abs(cosT), asn = abs(sinT)
        // Half-extent of the (rotated) viewport projected onto the image axes.
        let ax = ac * viewport.width / 2 + asn * viewport.height / 2
        let ay = asn * viewport.width / 2 + ac * viewport.height / 2
        let limX = max(0, w / 2 - ax)
        let limY = max(0, h / 2 - ay)
        // u = R(-θ) · offset, clamp, then offset = R(θ) · u.
        let ux = cosT * o.width + sinT * o.height
        let uy = -sinT * o.width + cosT * o.height
        let cux = min(limX, max(-limX, ux))
        let cuy = min(limY, max(-limY, uy))
        return CGSize(width: cosT * cux - sinT * cuy, height: sinT * cux + cosT * cuy)
    }

    static func viewport(for container: CGSize, ratio: CGFloat) -> CGSize {
        let side = min(container.width, container.height) * 0.92
        return ratio >= 1 ? CGSize(width: side, height: side / ratio) : CGSize(width: side * ratio, height: side)
    }
}

// MARK: - Crop mask

private struct CropMask: View {
    let viewport: CGSize
    let circle: Bool
    var body: some View {
        GeometryReader { geo in
            let rect = CGRect(x: (geo.size.width - viewport.width) / 2, y: (geo.size.height - viewport.height) / 2,
                              width: viewport.width, height: viewport.height)
            ZStack {
                Path { p in
                    p.addRect(CGRect(origin: .zero, size: geo.size))
                    if circle { p.addEllipse(in: rect) } else { p.addRect(rect) }
                }.fill(Color.black.opacity(0.55), style: FillStyle(eoFill: true))

                GridLines().stroke(Color.white.opacity(0.4), lineWidth: 1)
                    .frame(width: rect.width, height: rect.height)
                    .clipShape(circle ? AnyShape(Circle()) : AnyShape(Rectangle()))
                    .position(x: rect.midX, y: rect.midY)

                (circle ? AnyShape(Circle()) : AnyShape(Rectangle()))
                    .stroke(Color.white, lineWidth: 2)
                    .frame(width: rect.width, height: rect.height)
                    .position(x: rect.midX, y: rect.midY)
            }
        }
    }
}

private struct GridLines: Shape {
    func path(in rect: CGRect) -> Path {
        var p = Path()
        for i in 1...2 {
            let x = rect.width * CGFloat(i) / 3
            p.move(to: CGPoint(x: x, y: 0)); p.addLine(to: CGPoint(x: x, y: rect.height))
            let y = rect.height * CGFloat(i) / 3
            p.move(to: CGPoint(x: 0, y: y)); p.addLine(to: CGPoint(x: rect.width, y: y))
        }
        return p
    }
}

private struct AnyShape: Shape {
    private let maker: (CGRect) -> Path
    init<S: Shape>(_ shape: S) { maker = { shape.path(in: $0) } }
    func path(in rect: CGRect) -> Path { maker(rect) }
}

// MARK: - Ruler slider

private struct RulerSlider: View {
    let value: Double
    let range: ClosedRange<Double>
    let display: String
    let onChange: (Double) -> Void

    private var span: Double { range.upperBound - range.lowerBound }
    private var fraction: Double { span == 0 ? 0.5 : min(1, max(0, (value - range.lowerBound) / span)) }

    var body: some View {
        VStack(spacing: 8) {
            Text(display).foregroundColor(.green).font(.system(size: 14, weight: .semibold))
            HStack(spacing: 14) {
                Button { onChange(clamp(value - span / 60)) } label: { icon("minus") }
                track
                Button { onChange(clamp(value + span / 60)) } label: { icon("plus") }
            }
        }
    }

    private var track: some View {
        GeometryReader { geo in
            let w = geo.size.width, n = 40
            HStack(spacing: (w - CGFloat(n) * 2) / CGFloat(n - 1)) {
                ForEach(0..<n, id: \.self) { i in
                    let f = Double(i) / Double(n - 1)
                    Capsule().fill(f <= fraction ? Color.green : Color.primary.opacity(0.25))
                        .frame(width: 2, height: i % 5 == 0 ? 20 : 11)
                }
            }
            .frame(width: w, height: 40, alignment: .center)
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0).onChanged { g in
                onChange(range.lowerBound + min(1, max(0, g.location.x / w)) * span)
            })
        }.frame(height: 40)
    }

    private func icon(_ name: String) -> some View {
        Image(systemName: name).foregroundColor(.primary.opacity(0.85))
            .frame(width: 34, height: 34).background(Circle().fill(Color.primary.opacity(0.08)))
    }
    private func clamp(_ v: Double) -> Double { min(range.upperBound, max(range.lowerBound, v)) }
}

// MARK: - Renderer

enum CropRenderer {
    static func render(image: UIImage, state: CropState, config: CropConfig) -> String? {
        // No crop mode → export the WHOLE image (longest edge = outputSize) + colour.
        if !config.modes.contains("crop") {
            let s = CGFloat(config.outputSize) / max(1, max(image.size.width, image.size.height))
            let outSize = CGSize(width: image.size.width * s, height: image.size.height * s)
            let format = UIGraphicsImageRendererFormat(); format.scale = 1
            let full = UIGraphicsImageRenderer(size: outSize, format: format).image { _ in
                image.draw(in: CGRect(origin: .zero, size: outSize))
            }
            let coloured = applyColour(full, state: state)
            var url = FileManager.default.temporaryDirectory
                .appendingPathComponent("cropped_\(Int(Date().timeIntervalSince1970 * 1000)).jpg")
            guard let data = coloured.jpegData(compressionQuality: 0.92) else { return nil }
            do {
                try data.write(to: url)
                var rv = URLResourceValues(); rv.isExcludedFromBackup = true
                try? url.setResourceValues(rv)
                return url.path(percentEncoded: false)
            } catch { return nil }
        }

        let ratio = state.aspectRatio
        let outW: CGFloat, outH: CGFloat
        if ratio >= 1 { outW = CGFloat(config.outputSize); outH = CGFloat(config.outputSize) / ratio }
        else { outH = CGFloat(config.outputSize); outW = CGFloat(config.outputSize) * ratio }

        let k = outW / max(1, state.viewport.width)
        let combined = k * state.scale * state.fitScale
        let format = UIGraphicsImageRendererFormat(); format.scale = 1
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: outW, height: outH), format: format)

        let cropped = renderer.image { ctx in
            let cg = ctx.cgContext
            if state.shape == "circle" { cg.addEllipse(in: CGRect(x: 0, y: 0, width: outW, height: outH)); cg.clip() }
            cg.translateBy(x: outW / 2 + k * state.offset.width, y: outH / 2 + k * state.offset.height)
            cg.rotate(by: state.rotationDeg * .pi / 180)
            cg.scaleBy(x: combined, y: combined)
            let s = image.size
            image.draw(in: CGRect(x: -s.width / 2, y: -s.height / 2, width: s.width, height: s.height))
        }

        // Bake the colour adjustments (brightness / contrast / saturation).
        let final = applyColour(cropped, state: state)

        let fm = FileManager.default
        let ext = state.shape == "circle" ? "png" : "jpg"
        var url = fm.temporaryDirectory.appendingPathComponent("cropped_\(Int(Date().timeIntervalSince1970 * 1000)).\(ext)")
        let data = state.shape == "circle" ? final.pngData() : final.jpegData(compressionQuality: 0.92)
        guard let data else { return nil }
        do {
            try data.write(to: url)
            var rv = URLResourceValues(); rv.isExcludedFromBackup = true
            try? url.setResourceValues(rv)
            return url.path(percentEncoded: false)
        } catch { return nil }
    }

    /// Apply brightness/contrast/saturation with CoreImage (mirrors the live
    /// SwiftUI modifiers). No-op when all three are neutral.
    private static func applyColour(_ image: UIImage, state: CropState) -> UIImage {
        if state.brightness == 0 && state.contrast == 0 && state.saturation == 0 { return image }
        guard let ci = CIImage(image: image),
              let filter = CIFilter(name: "CIColorControls") else { return image }
        filter.setValue(ci, forKey: kCIInputImageKey)
        filter.setValue(state.brightness / 100 * 0.5, forKey: kCIInputBrightnessKey)
        filter.setValue(1 + state.contrast / 100, forKey: kCIInputContrastKey)
        filter.setValue(max(0, 1 + state.saturation / 100), forKey: kCIInputSaturationKey)
        let context = CIContext()
        guard let out = filter.outputImage,
              let cg = context.createCGImage(out, from: ci.extent) else { return image }
        return UIImage(cgImage: cg)
    }
}
