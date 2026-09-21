import CoreGraphics
import Foundation

/// The clean shapes the shape tool can draw and the recognizer can snap freehand strokes to.
public enum ShapeKind: String, Sendable, CaseIterable {
    case line, rectangle, ellipse, arrow, triangle
}

/// Lightweight, ML-free shape recognition — a port of `data/ink/ShapeRecognizer.kt`.
///
/// Given a freehand stroke's raw points it decides whether the gesture reads as a straight line, an
/// axis-aligned rectangle, a triangle or an ellipse, and returns the clean geometry to snap to (or
/// nil if it looks like ordinary handwriting).
///
/// Kept deliberately conservative: recognition is opt-in and reversible, so it would rather miss a
/// shape than mangle real handwriting.
public enum ShapeRecognizer {

    /// How far the average point may sit from the shape it would become, as a fraction of half the
    /// diagonal, before the stroke is left as it was drawn.
    static let fitTolerance: Double = 0.16

    /// How far the stroke's own length may differ from the perimeter of the shape it would become.
    ///
    /// The gate that keeps writing out. A shape is drawn by going round it once; handwriting covers
    /// the same box two or three times over, and says so in its length whatever its outline
    /// resembles.
    static let pathTolerance: Double = 0.30

    /// How far the outline may be simplified, as a fraction of the diagonal, when counting corners.
    static let cornerEpsilon: Double = 0.06

    /// Smallest diagonal, in document units, that counts as a deliberate shape rather than a mark.
    static let minShapeDU: Double = 40

    /// A recognized shape, defined by the drag box; a line uses the two endpoints.
    public struct Result: Equatable, Sendable {
        public var kind: ShapeKind
        public var x0: Double, y0: Double, x1: Double, y1: Double
        /// The actual corners, for a shape a bounding box cannot describe.
        ///
        /// Nil for a line, a rectangle or an ellipse, each of which its box defines completely. A
        /// triangle it does not: the same box holds an upright one, a left-leaning one and a
        /// right-leaning one, so the corners have to travel with the answer.
        public var corners: [CGPoint]?
    }

    public static func recognize(_ points: [CGPoint]) -> Result? {
        let n = points.count
        if n < 8 { return nil }

        var minX = points[0].x, maxX = points[0].x
        var minY = points[0].y, maxY = points[0].y
        var pathLen: Double = 0
        for i in 0..<n {
            minX = min(minX, points[i].x); maxX = max(maxX, points[i].x)
            minY = min(minY, points[i].y); maxY = max(maxY, points[i].y)
            if i > 0 { pathLen += hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y) }
        }
        let w = maxX - minX, h = maxY - minY
        let diag = hypot(w, h)
        if diag < minShapeDU || pathLen < minShapeDU { return nil }

        let chord = hypot(points[n - 1].x - points[0].x, points[n - 1].y - points[0].y)
        let closed = chord < 0.28 * diag

        if !closed, chord > 0.55 * diag {
            let dev = maxPerpDeviation(points, points[0], points[n - 1])
            if dev < 0.14 * chord {
                return Result(kind: .line, x0: points[0].x, y0: points[0].y,
                              x1: points[n - 1].x, y1: points[n - 1].y)
            }
        }
        if !closed { return nil }
        if min(w, h) < 0.12 * diag { return nil }

        let cx = (minX + maxX) / 2, cy = (minY + maxY) / 2
        let rx = max(w / 2, 1), ry = max(h / 2, 1)

        var ellErr: Double = 0, rectErr: Double = 0
        for p in points {
            let dx = p.x - cx, dy = p.y - cy
            let d = hypot(dx, dy)
            let ux = d < 1e-3 ? 1 : dx / d
            let uy = d < 1e-3 ? 0 : dy / d
            let onEllipse = 1 / sqrt((ux * ux) / (rx * rx) + (uy * uy) / (ry * ry))
            ellErr += abs(d - onEllipse)
            rectErr += min(p.x - minX, maxX - p.x, p.y - minY, maxY - p.y)
        }
        let half = max(diag / 2, 1)
        ellErr = (ellErr / Double(n)) / half
        rectErr = (rectErr / Double(n)) / half

        let corners = simplifyClosed(points, eps: diag * cornerEpsilon)
        if corners.count == 3 {
            var perim: Double = 0
            for i in 0..<3 {
                let j = (i + 1) % 3
                perim += hypot(corners[j].x - corners[i].x, corners[j].y - corners[i].y)
            }
            if abs(pathLen / max(perim, 1) - 1) <= pathTolerance {
                return Result(kind: .triangle, x0: minX, y0: minY, x1: maxX, y1: maxY, corners: corners)
            }
        }

        let rectPerimeter = 2 * (w + h)
        let ellPerimeter = Double.pi * (3 * (rx + ry) - sqrt((3 * rx + ry) * (rx + 3 * ry)))
        let rectWalk = abs(pathLen / max(rectPerimeter, 1) - 1)
        let ellWalk = abs(pathLen / max(ellPerimeter, 1) - 1)

        let ellipseFits = ellErr <= fitTolerance && ellWalk <= pathTolerance
        let rectFits = rectErr <= fitTolerance && rectWalk <= pathTolerance

        if ellipseFits, !rectFits || ellErr <= rectErr {
            return Result(kind: .ellipse, x0: minX, y0: minY, x1: maxX, y1: maxY)
        }
        if rectFits { return Result(kind: .rectangle, x0: minX, y0: minY, x1: maxX, y1: maxY) }
        return nil
    }

    static func simplifyClosed(_ pts: [CGPoint], eps: Double) -> [CGPoint] {
        guard pts.count > 1 else { return pts }
        let out = rdp(pts, 0, pts.count - 1, eps)
        if out.count > 1 {
            let a = out.first!, b = out.last!
            if hypot(b.x - a.x, b.y - a.y) < eps * 2 { return Array(out.dropLast()) }
        }
        return out
    }

    /// Ramer–Douglas–Peucker, iterative rather than recursive: a stroke can be thousands of points
    /// long and a per-point stack frame is a crash waiting for a long scribble.
    static func rdp(_ pts: [CGPoint], _ from: Int, _ to: Int, _ eps: Double) -> [CGPoint] {
        var keep = [Bool](repeating: false, count: pts.count)
        keep[from] = true; keep[to] = true
        var stack = [(from, to)]
        while let (a, b) = stack.popLast() {
            if b <= a + 1 { continue }
            var worst: Double = 0, at = a
            for i in (a + 1)..<b {
                let d = perpDistance(pts[i], pts[a], pts[b])
                if d > worst { worst = d; at = i }
            }
            if worst > eps {
                keep[at] = true
                stack.append((a, at)); stack.append((at, b))
            }
        }
        return (from...to).filter { keep[$0] }.map { pts[$0] }
    }

    static func perpDistance(_ p: CGPoint, _ a: CGPoint, _ b: CGPoint) -> Double {
        let dx = b.x - a.x, dy = b.y - a.y
        let len = hypot(dx, dy)
        if len < 1e-3 { return hypot(p.x - a.x, p.y - a.y) }
        return abs((p.x - a.x) * dy - (p.y - a.y) * dx) / len
    }

    static func maxPerpDeviation(_ pts: [CGPoint], _ a: CGPoint, _ b: CGPoint) -> Double {
        let dx = b.x - a.x, dy = b.y - a.y
        let len = max(hypot(dx, dy), 1e-3)
        var maxDev: Double = 0
        for p in pts {
            let dev = abs((p.x - a.x) * dy - (p.y - a.y) * dx) / len
            if dev > maxDev { maxDev = dev }
        }
        return maxDev
    }
}

/// The clean geometry a shape becomes, as points to draw through.
///
/// One place, so the shape tool's drag and the recognizer's snap produce the same figure — two
/// builders would mean a dragged box and a snapped box being subtly different shapes.
public enum ShapeBuilder {

    /// How many points an arc is walked in. Enough that a circle reads as a circle at any size the
    /// page can show, few enough that the sidecar does not carry a thousand points per oval.
    static let arcSteps = 48

    public static func points(_ kind: ShapeKind, x0: Double, y0: Double, x1: Double, y1: Double,
                              corners: [CGPoint]? = nil) -> [CGPoint] {
        switch kind {
        case .line:
            return [CGPoint(x: x0, y: y0), CGPoint(x: x1, y: y1)]

        case .arrow:
            // The shaft, then back along it to each barb, so the whole arrow is one unbroken run —
            // a stroke cannot lift, and three separate strokes would erase as three things.
            let dx = x1 - x0, dy = y1 - y0
            let len = max(hypot(dx, dy), 1e-3)
            let ux = dx / len, uy = dy / len
            let head = min(len * 0.28, 28)
            let spread = 0.42
            let cosA = cos(spread), sinA = sin(spread)
            let leftX = x1 - head * (ux * cosA - uy * sinA)
            let leftY = y1 - head * (uy * cosA + ux * sinA)
            let rightX = x1 - head * (ux * cosA + uy * sinA)
            let rightY = y1 - head * (uy * cosA - ux * sinA)
            return [CGPoint(x: x0, y: y0), CGPoint(x: x1, y: y1),
                    CGPoint(x: leftX, y: leftY), CGPoint(x: x1, y: y1),
                    CGPoint(x: rightX, y: rightY)]

        case .rectangle:
            let minX = min(x0, x1), maxX = max(x0, x1)
            let minY = min(y0, y1), maxY = max(y0, y1)
            return [CGPoint(x: minX, y: minY), CGPoint(x: maxX, y: minY),
                    CGPoint(x: maxX, y: maxY), CGPoint(x: minX, y: maxY),
                    CGPoint(x: minX, y: minY)]

        case .triangle:
            // A box cannot say which triangle was drawn, so the corners travel with it when there
            // are any; without them, an upright one in the box is the honest default.
            if let c = corners, c.count == 3 { return c + [c[0]] }
            let minX = min(x0, x1), maxX = max(x0, x1)
            let minY = min(y0, y1), maxY = max(y0, y1)
            let apex = CGPoint(x: (minX + maxX) / 2, y: minY)
            return [apex, CGPoint(x: maxX, y: maxY), CGPoint(x: minX, y: maxY), apex]

        case .ellipse:
            let cx = (x0 + x1) / 2, cy = (y0 + y1) / 2
            let rx = abs(x1 - x0) / 2, ry = abs(y1 - y0) / 2
            return (0...arcSteps).map { i in
                let t = Double(i) / Double(arcSteps) * 2 * .pi
                return CGPoint(x: cx + rx * cos(t), y: cy + ry * sin(t))
            }
        }
    }

    /// The clean figure a recognized stroke should become.
    public static func points(for result: ShapeRecognizer.Result) -> [CGPoint] {
        points(result.kind, x0: result.x0, y0: result.y0, x1: result.x1, y1: result.y1,
               corners: result.corners)
    }
}
