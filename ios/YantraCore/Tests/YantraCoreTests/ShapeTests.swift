import XCTest
@testable import YantraCore

/// The recognizer's judgement. Conservative by design: it would rather miss a shape than mangle
/// handwriting, so the cases that must *not* recognize matter as much as the ones that must.
final class ShapeTests: XCTestCase {

    /// A closed figure walked once, with a little hand shake on it.
    func ring(_ cx: Double, _ cy: Double, _ rx: Double, _ ry: Double, jitter: Double = 0, n: Int = 64) -> [CGPoint] {
        var rng = SystemRandomNumberGenerator()
        return (0...n).map { i in
            let t = Double(i) / Double(n) * 2 * .pi
            let j = jitter == 0 ? 0 : Double.random(in: -jitter...jitter, using: &rng)
            return CGPoint(x: cx + (rx + j) * cos(t), y: cy + (ry + j) * sin(t))
        }
    }

    func box(_ x0: Double, _ y0: Double, _ x1: Double, _ y1: Double, per: Int = 20) -> [CGPoint] {
        let corners = [CGPoint(x: x0, y: y0), CGPoint(x: x1, y: y0), CGPoint(x: x1, y: y1), CGPoint(x: x0, y: y1), CGPoint(x: x0, y: y0)]
        var out: [CGPoint] = []
        for i in 0..<(corners.count - 1) {
            for s in 0..<per {
                let t = Double(s) / Double(per)
                out.append(CGPoint(x: corners[i].x + (corners[i + 1].x - corners[i].x) * t,
                                   y: corners[i].y + (corners[i + 1].y - corners[i].y) * t))
            }
        }
        out.append(corners[0])
        return out
    }

    func testAStraightDragIsALine() {
        let pts = (0...40).map { CGPoint(x: Double($0) * 8, y: 100 + Double($0) * 0.4) }
        XCTAssertEqual(ShapeRecognizer.recognize(pts)?.kind, .line)
    }

    func testARoughCircleIsAnEllipse() {
        XCTAssertEqual(ShapeRecognizer.recognize(ring(200, 200, 90, 90, jitter: 3))?.kind, .ellipse)
    }

    func testAnOvalIsAnEllipseToo() {
        XCTAssertEqual(ShapeRecognizer.recognize(ring(200, 200, 120, 60))?.kind, .ellipse)
    }

    func testABoxIsARectangle() {
        XCTAssertEqual(ShapeRecognizer.recognize(box(40, 40, 260, 180))?.kind, .rectangle)
    }

    func testATriangleKeepsItsOwnCorners() {
        // A leaning triangle: the box alone cannot say which way it leans, so the corners must come
        // back with the answer or the snap throws away the shape that was drawn.
        let a = CGPoint(x: 60, y: 220), b = CGPoint(x: 240, y: 200), c = CGPoint(x: 200, y: 40)
        var pts: [CGPoint] = []
        for (p, q) in [(a, b), (b, c), (c, a)] {
            for s in 0..<24 {
                let t = Double(s) / 24
                pts.append(CGPoint(x: p.x + (q.x - p.x) * t, y: p.y + (q.y - p.y) * t))
            }
        }
        pts.append(a)
        let r = ShapeRecognizer.recognize(pts)
        XCTAssertEqual(r?.kind, .triangle)
        XCTAssertEqual(r?.corners?.count, 3)
    }

    // MARK: what must not be recognized

    func testHandwritingIsLeftAlone() {
        // A scribble covering the same box several times. Its outline may resemble anything; its
        // length gives it away, which is what the path tolerance is for.
        var pts: [CGPoint] = []
        for i in 0...400 {
            let t = Double(i) / 12
            pts.append(CGPoint(x: 100 + t * 3, y: 150 + sin(t * 3) * 40))
        }
        XCTAssertNil(ShapeRecognizer.recognize(pts))
    }

    func testATinyMarkIsNotAShape() {
        // Below the minimum diagonal: a full stop is not an ellipse.
        XCTAssertNil(ShapeRecognizer.recognize(ring(10, 10, 4, 4)))
    }

    func testTooFewPointsIsNotAShape() {
        XCTAssertNil(ShapeRecognizer.recognize([CGPoint(x: 0, y: 0), CGPoint(x: 90, y: 90)]))
    }

    // MARK: the geometry a shape becomes

    func testAnEllipseClosesOnItself() {
        let pts = ShapeBuilder.points(.ellipse, x0: 0, y0: 0, x1: 100, y1: 60)
        XCTAssertEqual(pts.first!.x, pts.last!.x, accuracy: 0.001)
        XCTAssertEqual(pts.first!.y, pts.last!.y, accuracy: 0.001)
    }

    func testARectangleReturnsToItsStart() {
        let pts = ShapeBuilder.points(.rectangle, x0: 10, y0: 20, x1: 110, y1: 80)
        XCTAssertEqual(pts.count, 5)
        XCTAssertEqual(pts.first!, pts.last!)
    }

    func testAnArrowIsOneUnbrokenRun() {
        // Shaft, then back along it to each barb: a stroke cannot lift, and three strokes would
        // erase as three things.
        let pts = ShapeBuilder.points(.arrow, x0: 0, y0: 0, x1: 100, y1: 0)
        XCTAssertEqual(pts.count, 5)
        XCTAssertEqual(pts[1], CGPoint(x: 100, y: 0))
        XCTAssertEqual(pts[3], CGPoint(x: 100, y: 0), "the run comes back to the tip between barbs")
    }

    func testADraggedTriangleWithNoCornersIsUpright() {
        let pts = ShapeBuilder.points(.triangle, x0: 0, y0: 0, x1: 100, y1: 100)
        XCTAssertEqual(pts.first!, CGPoint(x: 50, y: 0))
        XCTAssertEqual(pts.first!, pts.last!)
    }

    /// A long scribble must not blow the stack — the simplifier is iterative for this reason.
    func testASeriousScribbleDoesNotRecurseItselfToDeath() {
        let pts = (0..<20_000).map { CGPoint(x: Double($0 % 500), y: Double(($0 * 7) % 500)) }
        _ = ShapeRecognizer.recognize(pts)
    }
}
