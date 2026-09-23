import SwiftUI

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
