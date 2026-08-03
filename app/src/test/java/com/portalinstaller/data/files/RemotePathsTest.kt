package com.portalinstaller.data.files

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Aceste cai devin tinte pentru `mv`/`rm`, deci o greseala aici corupe fisierul
 * gresit. Verificam constructia pura fara conexiune ADB.
 */
class RemotePathsTest {

    @Test fun `parentOf nested path drops last segment`() =
        assertEquals("/sdcard/DCIM", RemotePaths.parentOf("/sdcard/DCIM/x.jpg"))

    @Test fun `parentOf tolerates trailing slash`() =
        assertEquals("/sdcard/DCIM", RemotePaths.parentOf("/sdcard/DCIM/x.jpg/"))

    @Test fun `parentOf top-level path yields empty root`() =
        assertEquals("", RemotePaths.parentOf("/data"))

    @Test fun `parentOf bare name yields empty`() =
        assertEquals("", RemotePaths.parentOf("photo.jpg"))

    @Test fun `join adds exactly one slash`() =
        assertEquals("/sdcard/DCIM/x.jpg", RemotePaths.join("/sdcard/DCIM", "x.jpg"))

    @Test fun `join collapses trailing slash on dir`() =
        assertEquals("/sdcard/DCIM/x.jpg", RemotePaths.join("/sdcard/DCIM/", "x.jpg"))

    @Test fun `renameDest keeps parent directory`() =
        assertEquals("/sdcard/DCIM/new.jpg", RemotePaths.renameDest("/sdcard/DCIM/old.jpg", "new.jpg"))

    @Test fun `renameDest of bare name has no leading slash`() =
        assertEquals("new.jpg", RemotePaths.renameDest("old.jpg", "new.jpg"))

    @Test fun `moveDest places file under destination dir`() =
        assertEquals("/sdcard/Backup/x.jpg", RemotePaths.moveDest("/sdcard/Backup", "x.jpg"))

    @Test fun `moveDest tolerates trailing slash on dest`() =
        assertEquals("/sdcard/Backup/x.jpg", RemotePaths.moveDest("/sdcard/Backup/", "x.jpg"))
}
