import SwiftUI
import shared

struct ContentView: View {

    var body: some View {
        let databaseDir = FileManager.default.urls(for: .libraryDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Databases", isDirectory: true)
        try? FileManager.default.createDirectory(at: databaseDir, withIntermediateDirectories: true)
        #if DEBUG
        let verboseDiagnostics = true
        #else
        let verboseDiagnostics = false
        #endif
        return KuiklyRenderViewPage(
            pageName: "connection_setup",
            data: [
                "databaseDir": databaseDir.path,
                "verboseDiagnostics": verboseDiagnostics,
            ]
        ).ignoresSafeArea()
    }
}

struct ContentView_Previews: PreviewProvider {
    static var previews: some View {
        ContentView()
    }
}
