import CoreMotion
final class StepRecorder {
    private let pedometer = CMPedometer()
    private final class Chunk { var applied = 0; var end: Date? }
    private var chunk: Chunk?
    private var began: Date?
    private var callback: ((Int) -> Void)?
    var available: Bool { CMPedometer.isStepCountingAvailable() && CMPedometer.authorizationStatus() != .denied && CMPedometer.authorizationStatus() != .restricted }
    func start(_ receive: @escaping (Int) -> Void) {
        stop(); guard available else { return }
        let part = Chunk(), date = Date(); chunk = part; began = date; callback = receive
        pedometer.startUpdates(from: date) { data,_ in DispatchQueue.main.async { Self.consume(data,chunk: part,receive: receive) } }
    }
    private static func consume(_ data: CMPedometerData?, chunk: Chunk, receive: (Int) -> Void) {
        guard let data, chunk.end == nil || data.endDate <= chunk.end! else { return }
        let count = data.numberOfSteps.intValue, delta = max(0,count-chunk.applied)
        chunk.applied = max(chunk.applied,count); if delta > 0 { receive(delta) }
    }
    func stop() {
        pedometer.stopUpdates()
        if let part = chunk, let start = began, let receive = callback {
            let end = Date(); part.end = end
            pedometer.queryPedometerData(from: start,to: end) { data,_ in DispatchQueue.main.async { Self.consume(data,chunk: part,receive: receive) } }
        }
        chunk = nil; began = nil; callback = nil
    }
}
