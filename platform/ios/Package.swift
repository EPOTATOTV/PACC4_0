// swift-tools-version:5.7
import PackageDescription

let package = Package(
    name: "PaccIosProbe",
    platforms: [
        .iOS(.v13)
    ],
    products: [
        .library(name: "PaccIosProbe", targets: ["PaccIosProbe"])
    ],
    targets: [
        .target(
            name: "PaccIosProbe",
            path: "Sources",
            linkerSettings: [.linkedLibrary("c++")]
        )
    ]
)