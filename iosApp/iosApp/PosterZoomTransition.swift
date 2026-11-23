import ComposeApp
import SwiftUI
import UIKit

// Fork: "artwork opening". Tapping a poster opens its details page with iOS's own zoom
// transition (iOS 18+): the page grows out of the poster, starting as a copy of that poster,
// and shrinks back into it on Back. The copy stays over the page until the page's backdrop is
// on screen (PosterOpenZoom in Kotlin says when), or for at most a couple of seconds.
//
// Hooks in ContentView.swift (keep them in merges):
// - TabNavigationCoordinator.push: PosterZoomCenter.shared.prepare(...) before the append.
// - TabContentView: a namespace, PosterZoomSourceAnchors over the tab's root page, and
//   .posterZoomDestination(...) on each DetailDestinationView.
// - DetailDestinationView: PosterZoomSourceAnchors over the page (posters on pushed pages).

@available(iOS 16.0, *)
@MainActor
final class PosterZoomCenter: ObservableObject {
    static let shared = PosterZoomCenter()

    struct Entry: Identifiable {
        let wrapperID: UUID
        /// The page the poster was on: nil for a tab's root page.
        let ownerID: UUID?
        let coordinator: ObjectIdentifier
        /// The poster's frame, in window points.
        let frame: CGRect
        let cornerRadius: CGFloat
        let routeKey: String
        let snapshot: UIView?
        let createdAt: Date

        var id: UUID { wrapperID }
    }

    private static let maxEntries = 12
    private static let coverTimeout: TimeInterval = 2.5

    @Published private(set) var entries: [UUID: Entry] = [:]
    @Published private(set) var covered: Set<UUID> = []

    private init() {
        PosterOpenZoom.shared.setArtworkReadyListener { key in
            Task { @MainActor in
                PosterZoomCenter.shared.artworkReady(key)
            }
        }
    }

    /// Called just before [wrapper] is pushed. Does nothing unless it was opened from a poster.
    func prepare(_ wrapper: RouteWrapper, ownerID: UUID?, coordinator: TabNavigationCoordinator) {
        guard #available(iOS 18.0, *) else { return }
        guard let tap = PosterOpenZoom.shared.takeTap(route: wrapper.route),
              let key = PosterOpenZoom.shared.routeKey(route: wrapper.route) else { return }
        let frame = CGRect(x: tap.x, y: tap.y, width: tap.width, height: tap.height)
        guard frame.width > 4, frame.height > 4, let window = Self.keyWindow() else { return }
        guard window.bounds.intersects(frame) else { return }

        // The card may still be in its small press squish, so take the middle 94%.
        let shot = frame.insetBy(dx: frame.width * 0.03, dy: frame.height * 0.03)
        let snapshot = window.resizableSnapshotView(
            from: shot,
            afterScreenUpdates: false,
            withCapInsets: .zero
        )

        entries[wrapper.id] = Entry(
            wrapperID: wrapper.id,
            ownerID: ownerID,
            coordinator: ObjectIdentifier(coordinator),
            frame: frame,
            cornerRadius: CGFloat(tap.cornerRadius),
            routeKey: key,
            snapshot: snapshot,
            createdAt: Date()
        )
        if !PosterOpenZoom.shared.isArtworkReady(routeKey: key) {
            covered.insert(wrapper.id)
            let id = wrapper.id
            Task { @MainActor in
                try? await Task.sleep(nanoseconds: UInt64(Self.coverTimeout * 1_000_000_000))
                PosterZoomCenter.shared.uncover(id)
            }
        }
        prune()
    }

    func entry(for wrapperID: UUID) -> Entry? { entries[wrapperID] }

    func isCovering(_ wrapperID: UUID) -> Bool { covered.contains(wrapperID) }

    func sources(ownedBy ownerID: UUID?, in coordinator: TabNavigationCoordinator) -> [Entry] {
        let tab = ObjectIdentifier(coordinator)
        return entries.values
            .filter { $0.ownerID == ownerID && $0.coordinator == tab }
            .sorted { $0.createdAt < $1.createdAt }
    }

    private func artworkReady(_ key: String) {
        let ids = entries.values.filter { $0.routeKey == key }.map(\.wrapperID)
        guard !ids.isEmpty else { return }
        covered.subtract(ids)
    }

    private func uncover(_ wrapperID: UUID) {
        if covered.contains(wrapperID) { covered.remove(wrapperID) }
    }

    private func prune() {
        guard entries.count > Self.maxEntries else { return }
        let oldest = entries.values.sorted { $0.createdAt < $1.createdAt }
            .prefix(entries.count - Self.maxEntries)
        for entry in oldest {
            entries.removeValue(forKey: entry.wrapperID)
            covered.remove(entry.wrapperID)
        }
    }

    private static func keyWindow() -> UIWindow? {
        let windows = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
        return windows.first { $0.isKeyWindow } ?? windows.first
    }
}

/// Invisible stand-ins at the spot of each tapped poster on a page, for the zoom to start from
/// and return to.
@available(iOS 16.0, *)
struct PosterZoomSourceAnchors: View {
    let ownerID: UUID?
    let coordinator: TabNavigationCoordinator
    let namespace: Namespace.ID
    @ObservedObject private var center = PosterZoomCenter.shared

    var body: some View {
        if #available(iOS 18.0, *) {
            GeometryReader { proxy in
                let origin = proxy.frame(in: .global).origin
                ForEach(center.sources(ownedBy: ownerID, in: coordinator)) { entry in
                    Rectangle()
                        .fill(Color.white.opacity(0.001))
                        .frame(width: entry.frame.width, height: entry.frame.height)
                        .matchedTransitionSource(id: entry.wrapperID, in: namespace) { source in
                            source.clipShape(
                                RoundedRectangle(cornerRadius: entry.cornerRadius, style: .continuous)
                            )
                        }
                        .position(x: entry.frame.midX - origin.x, y: entry.frame.midY - origin.y)
                }
            }
            .ignoresSafeArea()
            .allowsHitTesting(false)
            .accessibilityHidden(true)
        }
    }
}

/// Decided once per page, so the page's view (and its Compose screen) is never rebuilt later.
@available(iOS 16.0, *)
private struct PosterZoomDestination<Content: View>: View {
    let wrapperID: UUID
    let namespace: Namespace.ID
    let content: Content
    @State private var zooms: Bool

    @MainActor
    init(wrapperID: UUID, namespace: Namespace.ID, content: Content) {
        self.wrapperID = wrapperID
        self.namespace = namespace
        self.content = content
        _zooms = State(initialValue: PosterZoomCenter.shared.entry(for: wrapperID) != nil)
    }

    var body: some View {
        if zooms {
            if #available(iOS 18.0, *) {
                content
                    .overlay { PosterZoomCover(wrapperID: wrapperID) }
                    .navigationTransition(.zoom(sourceID: wrapperID, in: namespace))
            } else {
                content
            }
        } else {
            content
        }
    }
}

/// The tapped poster, filling the page, until the page's own artwork shows.
@available(iOS 16.0, *)
private struct PosterZoomCover: View {
    let wrapperID: UUID
    @ObservedObject private var center = PosterZoomCenter.shared

    var body: some View {
        let entry = center.entry(for: wrapperID)
        let visible = entry != nil && center.isCovering(wrapperID)
        ZStack {
            Color.black
            if let entry, let snapshot = entry.snapshot {
                PosterSnapshotFill(
                    snapshot: snapshot,
                    aspect: entry.frame.width / max(entry.frame.height, 1)
                )
            }
            LinearGradient(
                stops: [
                    .init(color: .black.opacity(0.05), location: 0),
                    .init(color: .black.opacity(0.25), location: 0.55),
                    .init(color: .black.opacity(0.7), location: 1),
                ],
                startPoint: .top,
                endPoint: .bottom
            )
        }
        .opacity(visible ? 1 : 0)
        .animation(.easeOut(duration: 0.3), value: visible)
        .ignoresSafeArea()
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

@available(iOS 16.0, *)
private struct PosterSnapshotFill: UIViewRepresentable {
    let snapshot: UIView
    let aspect: CGFloat

    func makeUIView(context: Context) -> AspectFillHostView {
        AspectFillHostView(child: snapshot, aspect: aspect)
    }

    func updateUIView(_ uiView: AspectFillHostView, context: Context) {}
}

/// Shows its one child scaled to cover it, centred, like an aspect-fill image.
final class AspectFillHostView: UIView {
    private let child: UIView
    private let aspect: CGFloat

    init(child: UIView, aspect: CGFloat) {
        self.child = child
        self.aspect = aspect > 0 ? aspect : 2.0 / 3.0
        super.init(frame: .zero)
        clipsToBounds = true
        isUserInteractionEnabled = false
        backgroundColor = .black
        child.removeFromSuperview()
        addSubview(child)
    }

    required init?(coder: NSCoder) { nil }

    override func layoutSubviews() {
        super.layoutSubviews()
        let box = bounds
        guard box.width > 0, box.height > 0 else { return }
        var width = box.width
        var height = width / aspect
        if height < box.height {
            height = box.height
            width = height * aspect
        }
        child.frame = CGRect(
            x: (box.width - width) / 2,
            y: (box.height - height) / 2,
            width: width,
            height: height
        )
    }
}

@available(iOS 16.0, *)
extension View {
    /// Fork: the page for [wrapperID] zooms out of its poster when it was opened from one.
    @MainActor
    func posterZoomDestination(wrapperID: UUID, namespace: Namespace.ID) -> some View {
        PosterZoomDestination(wrapperID: wrapperID, namespace: namespace, content: self)
    }
}
