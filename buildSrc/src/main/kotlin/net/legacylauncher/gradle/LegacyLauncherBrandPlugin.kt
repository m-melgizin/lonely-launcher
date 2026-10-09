package net.legacylauncher.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.create

private fun Project.featureToggle(name: String) =
    providers.gradleProperty("feature.$name").map { it.toBoolean() }.orElse(false)

class LegacyLauncherBrandPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create<LegacyLauncherBrandExtension>("brand")

        extension.brand.convention(System.getenv("SHORT_BRAND") ?: "develop")
        extension.displayName.convention(extension.brand.map { brand ->
            when (brand) {
                "develop" -> "Dev"
                "legacy" -> "Stable"
                "legacy_beta" -> "Beta"
                "mcl" -> "for Mc-launcher.com"
                "aur" -> "AUR"
                "appt" -> "для AppStorrent"
                "lonely" -> ""
                else -> brand
            }
        })
        extension.version.convention(extension.brand.map { brand ->
            "${project.version}+${brand.replace(Regex("[^\\dA-Za-z\\-]"), "-")}${System.getenv("VERSION_SUFFIX") ?: ""}"
        })

        extension.supportEmail.convention("support@lonelycraft.ru")
        extension.productName.convention("Lonely Launcher")
        extension.updateRepository.convention(
            System.getenv("UPDATE_REPOSITORY") ?: System.getenv("GITHUB_REPOSITORY") ?: "m-melgizin/legacy-launcher"
        )
        extension.logUploadEnabled.convention(project.featureToggle("logUpload"))
        extension.helpLinksEnabled.convention(project.featureToggle("helpLinks"))
    }
}
