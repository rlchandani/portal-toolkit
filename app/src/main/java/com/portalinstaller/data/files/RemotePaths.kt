package com.portalinstaller.data.files

/**
 * Constructia cailor remote pentru operatiile de fisiere (redenumire, mutare,
 * creare director). Extrasa din [PortalFileManager] ca sa fie testabila fara
 * o conexiune ADB: aceste cai devin tinte pentru `rm`/`mv`, deci o greseala
 * aici corupe fisierul gresit. Logica e pura si deterministica.
 */
internal object RemotePaths {

    /** Directorul parinte al unei cai, fara slash-ul final. Radacina ramane "". */
    fun parentOf(path: String): String = path.trimEnd('/').substringBeforeLast('/', "")

    /** Uneste un director si un nume cu exact un slash, indiferent de slash-ul final. */
    fun join(dir: String, name: String): String = "${dir.trimEnd('/')}/$name"

    /**
     * Destinatia unei redenumiri: pastreaza directorul parinte al fisierului si
     * inlocuieste ultimul segment cu [newName]. Pentru o cale fara slash (nume
     * simplu) rezultatul e chiar noul nume, fara slash in fata.
     */
    fun renameDest(sourcePath: String, newName: String): String {
        val parent = parentOf(sourcePath)
        return if (parent.isEmpty()) newName else "$parent/$newName"
    }

    /** Destinatia unei mutari: fisierul [fileName] plasat sub [destDir]. */
    fun moveDest(destDir: String, fileName: String): String = join(destDir, fileName)
}
