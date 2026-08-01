package com.portalinstaller.data.screensaver

/** Un folder cu imagini de pe Portal, alaturi de numarul de imagini gasite. */
data class ImageFolder(val path: String, val imageCount: Int)

/**
 * Clasifica folderele cu imagini dupa cate imagini contin (descrescator).
 * Apelurile ADB `find`/numarare traiesc in ViewModel; aici pastram doar
 * logica pura de clasificare, testabila fara dispozitiv.
 *
 * Folderul mostenit [LEGACY_FOLDER] este mereu inclus (chiar cu zero imagini),
 * pentru compatibilitate cu versiunile anterioare care il foloseau implicit.
 */
object ImageFolderScanner {

    const val LEGACY_FOLDER = "/sdcard/Pictures/PortalInstaller"

    fun rankFolders(findOutput: String, imageCountsByDir: Map<String, Int>): List<ImageFolder> {
        val discovered = findOutput.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()

        val paths = LinkedHashSet<String>()
        paths.add(LEGACY_FOLDER)
        paths.addAll(discovered)
        paths.addAll(imageCountsByDir.keys)

        return paths
            .map { path -> ImageFolder(path, imageCountsByDir[path] ?: 0) }
            .sortedWith(compareByDescending<ImageFolder> { it.imageCount }.thenBy { it.path })
    }
}
