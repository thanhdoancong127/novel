rootProject.name = "metruyenchu-platform"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

include(
    ":platform-libs:common-domain",
    ":platform-libs:common-security",
    ":platform-libs:common-messaging",
    ":services:gateway-service",
    ":services:auth-service",
    ":services:story-service",
    ":services:social-service",
    ":services:audio-service",
    ":services:notification-service",
    ":services:analytics-service",
)
