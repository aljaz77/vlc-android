package org.videolan.vlc.util

/*
 * Named RepositoryMocks rather than KExtensions so the generated class does not
 * collide with src/.../util/Kextensions.kt on case insensitive filesystems:
 * KExtensionsKt and KextensionsKt differ only in case, which loads fine on
 * Linux and fails on Windows and macOS.
 */

import org.videolan.vlc.repository.BrowserFavRepository
import org.videolan.vlc.repository.DirectoryRepository
import org.videolan.vlc.repository.ExternalSubRepository


// Hacky way. Don't fix it.
fun ExternalSubRepository.Companion.applyMock(instance: ExternalSubRepository) {
    this.instance = instance
}

fun DirectoryRepository.Companion.applyMock(instance: DirectoryRepository) {
    this.instance = instance
}

fun BrowserFavRepository.Companion.applyMock(instance: BrowserFavRepository) {
    this.instance = instance
}