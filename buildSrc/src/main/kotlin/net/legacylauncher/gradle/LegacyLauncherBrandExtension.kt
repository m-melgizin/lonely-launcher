package net.legacylauncher.gradle

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

interface LegacyLauncherBrandExtension {
    val brand: Property<String>
    val displayName: Property<String>
    val version: Property<String>

    val supportEmail: Property<String>

    /** Product name shown to users, e.g. in window titles. */
    val productName: Property<String>

    /** GitHub repository ("owner/name") whose releases are checked for updates. */
    val updateRepository: Property<String>
}
