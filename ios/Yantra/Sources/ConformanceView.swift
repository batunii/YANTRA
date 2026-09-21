import SwiftUI
import YantraCore

/// The first screen the iOS app has: proof that it reads what the Android app wrote.
///
/// It loads the golden sidecar from `conformance/ink/sidecar.ink` — bytes produced by the Kotlin
/// codec — decodes every stroke with the Swift codec, draws them, and shows whether re-encoding
/// gives the same bytes back. Not a feature; the foundation every feature stands on.
struct ConformanceView: View {
    @State private var result: Result<Loaded, Error>?

    struct Loaded {
        var strokes: [StrokeEnvelope.Envelope]
        var roundTrip: Bool
        var byteCount: Int
        var rankFirst: String
        var clock: String
        var manifest: String?
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                Text("YANTRA · CONFORMANCE")
                    .font(.system(size: 11, weight: .bold, design: .monospaced))
                    .kerning(1.4)
                    .foregroundStyle(Color(red: 0.94, green: 0.40, blue: 0.29))
                Text("Reading what Android wrote")
                    .font(.system(size: 30, weight: .bold))
                    .tracking(-0.6)

                switch result {
                case nil:
                    ProgressView()
                case .failure(let e):
                    Text("Could not load fixtures: \(String(describing: e))")
                        .foregroundStyle(.red)
                case .success(let l):
                    InkPreview(strokes: l.strokes)
                        .frame(height: 220)
                        .background(RoundedRectangle(cornerRadius: 14).fill(Color(white: 0.13)))
                    row("Strokes decoded", "\(l.strokes.count) · \(l.strokes.map(\.points.count).reduce(0, +)) points")
                    row("Sidecar bytes", "\(l.byteCount)")
                    row("Re-encoded byte for byte", l.roundTrip ? "yes" : "NO — drift")
                    row("Rank.first", l.rankFirst)
                    row("sessionClock(7384)", l.clock)
                    if let m = l.manifest {
                        Text("Manifest merge, independent edits").font(.system(size: 12, weight: .bold, design: .monospaced)).kerning(1).foregroundStyle(.secondary)
                        Text(m).font(.system(size: 12, design: .monospaced)).textSelection(.enabled)
                    }
                }
            }
            .padding(22)
        }
        .background(Color(red: 0.10, green: 0.098, blue: 0.09).ignoresSafeArea())
        .foregroundStyle(Color(red: 0.945, green: 0.933, blue: 0.906))
        .preferredColorScheme(.dark)
        .task { result = Result { try load() } }
    }

    private func row(_ k: String, _ v: String) -> some View {
        HStack {
            Text(k).foregroundStyle(.secondary)
            Spacer()
            Text(v).font(.system(.body, design: .monospaced)).monospacedDigit()
        }
        .font(.system(size: 14.5))
    }

    private func load() throws -> Loaded {
        guard let url = Bundle.main.url(forResource: "sidecar", withExtension: "ink", subdirectory: "conformance/ink")
            ?? Bundle.main.url(forResource: "sidecar", withExtension: "ink") else {
            throw CocoaError(.fileNoSuchFile)
        }
        let data = try Data(contentsOf: url)
        let blobs = try InkSidecar.decode(data)
        let strokes = try blobs.map { try StrokeEnvelope.decode($0) }
        let again = InkSidecar.encode(strokes.map { StrokeEnvelope.encode($0) })
        let merged = ManifestMerge.merge(
            base: #"{"formatVersion":1,"name":"tasks","createdAt":1,"epoch":1,"archive_after_days":0}"#,
            local: #"{"formatVersion":1,"name":"tasks","createdAt":1,"epoch":1,"archive_after_days":30}"#,
            remote: #"{"formatVersion":2,"name":"tasks","createdAt":1,"epoch":1,"archive_after_days":0}"#,
            device: "iphone", otherDevice: "android")
        return Loaded(strokes: strokes, roundTrip: again == data, byteCount: data.count,
                      rankFirst: Rank.first, clock: sessionClock(7384), manifest: merged)
    }
}

/// Draws envelopes as polylines: enough to see that the points are the points. The real renderer
/// (pressure-varying width, brush families) comes with the ink screen.
struct InkPreview: View {
    let strokes: [StrokeEnvelope.Envelope]

    var body: some View {
        Canvas { ctx, size in
            let pts = strokes.flatMap(\.points)
            guard let minX = pts.map(\.x).min(), let maxX = pts.map(\.x).max(),
                  let minY = pts.map(\.y).min(), let maxY = pts.map(\.y).max() else { return }
            let w = max(maxX - minX, 1), h = max(maxY - minY, 1)
            let scale = min((size.width - 40) / CGFloat(w), (size.height - 40) / CGFloat(h))
            let ox = (size.width - CGFloat(w) * scale) / 2, oy = (size.height - CGFloat(h) * scale) / 2
            for s in strokes where !s.points.isEmpty {
                var path = Path()
                for (i, p) in s.points.enumerated() {
                    let pt = CGPoint(x: ox + CGFloat(p.x - minX) * scale, y: oy + CGFloat(p.y - minY) * scale)
                    if i == 0 { path.move(to: pt) } else { path.addLine(to: pt) }
                }
                let argb = s.header.color
                let color = Color(red: Double((argb >> 16) & 0xFF) / 255, green: Double((argb >> 8) & 0xFF) / 255,
                                  blue: Double(argb & 0xFF) / 255, opacity: Double((argb >> 24) & 0xFF) / 255)
                // The theme-native inks swap with the theme, as on Android: graphite on paper, chalk on dark.
                let ink = argb == 0xFF23211C ? Color(red: 0.945, green: 0.933, blue: 0.906) : color
                ctx.stroke(path, with: .color(ink), style: StrokeStyle(lineWidth: CGFloat(s.header.size) * 2, lineCap: .round, lineJoin: .round))
                for p in s.points {
                    let pt = CGPoint(x: ox + CGFloat(p.x - minX) * scale, y: oy + CGFloat(p.y - minY) * scale)
                    ctx.fill(Path(ellipseIn: CGRect(x: pt.x - 3, y: pt.y - 3, width: 6, height: 6)), with: .color(Color(red: 0.94, green: 0.40, blue: 0.29)))
                }
            }
        }
    }
}

/// Every mark in the set, at one size, for checking the drawings against the Kotlin by eye.
///
/// Reached with `-route marks`. Not a screen the app offers: an icon sheet is a thing you look at
/// once when porting a drawing language, and a permanent entry for it would be a screen nobody
/// opens twice.
struct MarkSheetView: View {
    @Environment(\.y) private var y
    private let columns = Array(repeating: GridItem(.flexible(), spacing: 6), count: 6)

    var body: some View {
        ScrollView {
            LazyVGrid(columns: columns, spacing: 14) {
                ForEach(YantraMark.allCases, id: \.self) { m in
                    VStack(spacing: 5) {
                        YantraIcon(mark: m, size: YantraIcons.large, tint: y.ink)
                        Text(m.rawValue).font(.system(size: 7.5)).foregroundStyle(y.dim).lineLimit(1)
                    }
                    .frame(height: 46)
                }
            }
            .padding(14)
        }
        .background(y.page.ignoresSafeArea())
    }
}
