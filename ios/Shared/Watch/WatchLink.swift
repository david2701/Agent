import Foundation

/// Mensajes entre el iPhone y el Apple Watch (WatchConnectivity).
/// El reloj pide un código de vinculación al iPhone y abre su propia sesión con el servidor;
/// el iPhone le manda el resumen del día para mostrarlo al instante.
nonisolated enum WatchLink {
    static let kind = "kind"
    static let pairRequest = "pairRequest"
    static let server = "server"
    static let code = "code"
    static let error = "error"
    static let snapshot = "snapshot"
}
