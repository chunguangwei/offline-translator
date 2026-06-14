import XCTest
@testable import Yiren

final class SrsSchedulerTests: XCTestCase {
    let now: Int64 = 1_000_000_000_000
    let day: Int64 = 86_400_000

    func testCorrectPromotesAndSchedules() {
        let r = SrsScheduler.schedule(box: 0, missCount: 0, correct: true, now: now)
        XCTAssertEqual(r.box, 1); XCTAssertEqual(r.dueAt, now + day)
        XCTAssertEqual(r.missCount, 0); XCTAssertEqual(r.lastReviewedAt, now)
    }
    func testIntervals() {
        XCTAssertEqual(SrsScheduler.schedule(box: 1, missCount: 0, correct: true, now: now).dueAt, now + 3*day)
        XCTAssertEqual(SrsScheduler.schedule(box: 4, missCount: 0, correct: true, now: now).dueAt, now + 30*day)
    }
    func testMasteredFarFuture() {
        let r = SrsScheduler.schedule(box: 5, missCount: 0, correct: true, now: now)
        XCTAssertEqual(r.box, 6); XCTAssertEqual(r.dueAt, Int64.max)
    }
    func testWrongResets() {
        let r = SrsScheduler.schedule(box: 4, missCount: 2, correct: false, now: now)
        XCTAssertEqual(r.box, 0); XCTAssertEqual(r.dueAt, now)
        XCTAssertEqual(r.missCount, 3); XCTAssertEqual(r.lastReviewedAt, now)
    }
    func testStreak() {
        XCTAssertEqual(StreakLogic.onStudied(lastDay: 100, current: 5, today: 100).currentStreak, 5)
        XCTAssertEqual(StreakLogic.onStudied(lastDay: 100, current: 5, today: 101).currentStreak, 6)
        XCTAssertEqual(StreakLogic.onStudied(lastDay: 100, current: 5, today: 103).currentStreak, 1)
        XCTAssertEqual(StreakLogic.displayStreak(lastDay: 100, current: 5, today: 102), 0)
    }
}
