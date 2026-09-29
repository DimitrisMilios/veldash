// Root build file: plugins are declared here (apply false) and applied in :app.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
}
