import SwiftUI
import UIKit
import UniformTypeIdentifiers
import YantraCore

/// Yantra as somewhere to share to. The task exists before the sheet draws; the sheet is an
/// acknowledgement with one control — change the list — and dismisses itself after 3.2 s.
final class ShareViewController: UIViewController {
    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .clear
        let host = UIHostingController(rootView: ShareSheet(context: extensionContext, items: extensionContext?.inputItems as? [NSExtensionItem] ?? []))
        host.view.backgroundColor = .clear
        addChild(host)
        view.addSubview(host.view)
        host.view.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            host.view.leadingAnchor.constraint(equalTo: view.leadingAnchor), host.view.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            host.view.topAnchor.constraint(equalTo: view.topAnchor), host.view.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])
        host.didMove(toParent: self)
    }
}

struct ShareSheet: View {
    let context: NSExtensionContext?
    let items: [NSExtensionItem]
    @State private var title = ""
    @State private var listName = "Inbox"
    @State private var taskId: String?
    @State private var picking = false
    @State private var lists: [(String, String)] = []

    var body: some View {
        let p = SharedPalette()
        VStack {
            Spacer()
            VStack(alignment: .leading, spacing: 10) {
                if picking {
                    Text("MOVE TO").font(SharedFace.mono(10.5, bold: true)).kerning(1.4).foregroundStyle(p.dim)
                    ForEach(lists, id: \.0) { id, name in
                        Button { move(to: id, name: name) } label: {
                            HStack { Text(name.isEmpty ? "Untitled list" : name).font(SharedFace.text(15, bold: true)).foregroundStyle(p.ink); Spacer(); if name == listName { Image(systemName: "checkmark").foregroundStyle(p.accent) } }
                                .padding(.vertical, 8)
                        }.buttonStyle(.plain)
                    }
                } else {
                    Text("Added to \(listName)").font(SharedFace.text(16, bold: true)).foregroundStyle(p.ink)
                    Text(title).font(SharedFace.text(13)).foregroundStyle(p.secondary).lineLimit(2)
                    HStack {
                        Button("Change list") { picking = true }.font(SharedFace.text(13, bold: true)).foregroundStyle(taskId == nil ? p.dim : p.accent).disabled(taskId == nil)
                        Spacer()
                        Button("Done") { finish() }.font(SharedFace.text(13, bold: true)).foregroundStyle(p.secondary)
                    }
                }
            }
            .padding(18)
            .background(RoundedRectangle(cornerRadius: 18).fill(p.surface))
            .padding(16)
        }
        .task { await capture() }
        .task { try? await Task.sleep(for: .seconds(3.2)); if !picking { finish() } }
    }

    private func capture() async {
        var text: String? = nil, url: URL? = nil, image: Data? = nil, subject: String? = nil
        for item in items {
            subject = subject ?? item.attributedTitle?.string
            for provider in item.attachments ?? [] {
                if provider.hasItemConformingToTypeIdentifier(UTType.url.identifier), let u = try? await provider.loadItem(forTypeIdentifier: UTType.url.identifier) as? URL { url = u }
                else if provider.hasItemConformingToTypeIdentifier(UTType.plainText.identifier), let s = try? await provider.loadItem(forTypeIdentifier: UTType.plainText.identifier) as? String { text = s }
                else if provider.hasItemConformingToTypeIdentifier(UTType.image.identifier) {
                    if let u = try? await provider.loadItem(forTypeIdentifier: UTType.image.identifier) as? URL { image = try? Data(contentsOf: u) }
                    else if let img = try? await provider.loadItem(forTypeIdentifier: UTType.image.identifier) as? UIImage { image = img.jpegData(compressionQuality: 0.85) }
                }
            }
        }
        let body = text ?? url?.absoluteString
        // Title: subject, else the first line of the text (120 chars), else "Picture". Deliberately
        // not run through the capture grammar — a web page's words are not a command.
        let t = (subject?.trimmingCharacters(in: .whitespaces)).flatMap { $0.isEmpty ? nil : $0 }
            ?? body?.split(separator: "\n").first.map { String($0.prefix(120)) }
            ?? (image != nil ? "Picture" : nil)
        guard let t else { finish(); return }
        title = t
        let (store, writer) = AppGroup.openWorkspace()
        let ix = WorkspaceIndex.read(store)
        lists = ix.children(of: nil).filter { $0.type == NodeType.list }.map { ($0.id, inlinePlain($0.title ?? "")) }
        guard let inbox = ix.node(systemKey: SystemKey.inbox) else { finish(); return }
        do {
            let id = try writer.addBlock(to: inbox.id, type: NodeType.task, text: t)
            if let b = body, b != t { _ = try writer.addBlock(to: id, type: NodeType.paragraph, text: b) }
            if let image, let down = ImageImport.downscale(image) {
                let imgId = UUID().uuidString.lowercased()
                store.writeImage(imgId, down)
                try writer.editPage(id, change: .structural) { var p = $0; p.blocks.append(.image(uri: imgId)); return p }
            }
            taskId = id
        } catch { }
    }

    private func move(to listId: String, name: String) {
        guard let taskId else { return }
        let (_, writer) = AppGroup.openWorkspace()
        try? writer.moveTask(taskId, toList: listId)
        listName = name
        picking = false
        finish()
    }

    private func finish() { context?.completeRequest(returningItems: nil) }
}

/// Long edge 2048, JPEG 85, EXIF gone by re-encoding — a photo carries GPS, and committing one to a
/// shared repository publishes where you were.
enum ImageImport {
    static func downscale(_ data: Data, maxEdge: CGFloat = 2048) -> Data? {
        guard let img = UIImage(data: data) else { return nil }
        let scale = min(1, maxEdge / max(img.size.width, img.size.height))
        let size = CGSize(width: img.size.width * scale, height: img.size.height * scale)
        let r = UIGraphicsImageRenderer(size: size)
        let out = r.image { _ in img.draw(in: CGRect(origin: .zero, size: size)) }
        return out.jpegData(compressionQuality: 0.85)
    }
}
