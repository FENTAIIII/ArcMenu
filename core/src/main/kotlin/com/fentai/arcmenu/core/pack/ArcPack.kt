package com.fentai.arcmenu.core.pack

import java.nio.file.Path

data class ArcPackAuthor(val uuid: String, val name: String)
data class ArcPackSelection(val type: String, val id: String)
data class ArcPackProject(val type: String, val id: String, val path: String)
data class ArcPackFile(val path: String, val sha256: String, val size: Long)
data class ArcPackManifest(
    val id: String, val version: String, val title: String, val description: String,
    val author: ArcPackAuthor, val projects: List<ArcPackProject>, val files: List<ArcPackFile>,
    val dependencies: List<String> = emptyList(), val compatibility: Map<String, String> = emptyMap(),
    val format: String = "arcpack", val formatVersion: Int = 1,
)
data class ArcPackArchive(val manifest: ArcPackManifest, val files: Map<String, ByteArray>)
data class ArcPackLimits(
    val entries: Int = 4096, val fileBytes: Long = 32L * 1024 * 1024,
    val totalBytes: Long = 128L * 1024 * 1024, val manifestBytes: Long = 1024 * 1024,
)
data class ArcPackExportRequest(
    val id: String, val version: String, val title: String, val description: String,
    val author: ArcPackAuthor, val projects: List<ArcPackSelection>,
    val dependencies: List<String> = emptyList(), val allowCe: Boolean = false,
    val compatibility: Map<String, String> = emptyMap(),
)
data class ArcPackExportResult(val archive: Path, val manifest: ArcPackManifest, val warnings: List<String>)
data class InstalledArcPack(val manifest: ArcPackManifest, val installedAt: String, val paths: List<String>)
data class ArcPackInstallResult(val pack: InstalledArcPack, val warnings: List<String>)
data class ArcPackUninstallResult(val id: String, val removedFiles: Int, val backups: List<Path>, val warnings: List<String>)
