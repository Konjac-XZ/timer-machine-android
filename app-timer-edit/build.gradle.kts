plugins {
    alias(libs.plugins.convention.android.library)
    alias(libs.plugins.convention.hilt)
}

android {
    namespace = "xyz.aprildown.timer.app.timer.edit"
}

dependencies {
    implementation(project(":app-base"))
    implementation(project(":component-key"))

    implementation(libs.fastAdapter.core)

    implementation(libs.flexbox)
    implementation(libs.materialPopupMenu)
    implementation(libs.ultimateRingtonePicker)
    implementation(libs.twoWayNestedScrollView)
    implementation(libs.materialDialog.core)
    implementation(libs.materialDialog.color)
    implementation(libs.materialDialog.lifecycle)
}
