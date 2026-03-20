import SwiftUI
import shared

struct ContentView: View {
    var body: some View {
        ComposeView()
            .ignoresSafeArea(.keyboard) // Compose handles keyboard insets
    }
}

/// UIViewControllerRepresentable that hosts the Compose Multiplatform UI.
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
