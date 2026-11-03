plugins {
    alias(libs.plugins.agp.lib)
    alias(libs.plugins.refine)
    alias(libs.plugins.kotlin)
}

android {
    namespace = "org.apkesu.stub"
}

dependencies {
    implementation(libs.androidx.annotation.jvm)
}
