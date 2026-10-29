package com.programmersbox.scrcpyviewer.update

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class InstallTargetTest {
    private val macLauncher = "/Applications/ScrcpyViewer.app/Contents/MacOS/ScrcpyViewer"

    @Test
    fun macInApplicationsIsUpdatableWithBundle() {
        val target = detectInstallTarget(macLauncher, UpdatePlatform.MacArm64, { false })
        assertEquals(
            InstallTarget.Updatable(
                UpdatePlatform.MacArm64,
                Paths.get(macLauncher),
                Paths.get("/Applications/ScrcpyViewer.app"),
            ),
            target,
        )
    }

    @Test
    fun macOnMountedVolumeIsNotUpdatable() {
        val target = detectInstallTarget(
            "/Volumes/ScrcpyViewer/ScrcpyViewer.app/Contents/MacOS/ScrcpyViewer",
            UpdatePlatform.MacArm64,
            { false },
        )
        assertEquals(InstallTarget.NotUpdatable(InstallTarget.Reason.MacNotInApplications), target)
    }

    @Test
    fun macTranslocatedIsNotUpdatable() {
        val target = detectInstallTarget(
            "/private/var/folders/x/AppTranslocation/ABC/d/ScrcpyViewer.app/Contents/MacOS/ScrcpyViewer",
            UpdatePlatform.MacArm64,
            { false },
        )
        assertEquals(InstallTarget.NotUpdatable(InstallTarget.Reason.MacNotInApplications), target)
    }

    @Test
    fun linuxOwnedByPackageIsUpdatable() {
        val path = "/opt/scrcpyviewer/bin/scrcpyviewer"
        val target = detectInstallTarget(path, UpdatePlatform.LinuxX64, { true })
        assertEquals(InstallTarget.Updatable(UpdatePlatform.LinuxX64, Paths.get(path), null), target)
    }

    @Test
    fun linuxNotOwnedByPackageIsNotUpdatable() {
        val target = detectInstallTarget("/opt/scrcpyviewer/bin/scrcpyviewer", UpdatePlatform.LinuxX64, { false })
        assertEquals(InstallTarget.NotUpdatable(InstallTarget.Reason.LinuxNotFromPackage), target)
    }

    @Test
    fun windowsIsUpdatableWithoutBundle() {
        val target = detectInstallTarget(
            "C:\\Program Files\\ScrcpyViewer\\ScrcpyViewer.exe",
            UpdatePlatform.WindowsX64,
            { false },
        )
        val updatable = assertIs<InstallTarget.Updatable>(target)
        assertEquals(UpdatePlatform.WindowsX64, updatable.platform)
        assertEquals(null, updatable.appBundle)
    }

    @Test
    fun missingAppPathIsDevBuild() {
        assertEquals(
            InstallTarget.NotUpdatable(InstallTarget.Reason.DevBuild),
            detectInstallTarget(null, UpdatePlatform.MacArm64, { true }),
        )
        assertEquals(
            InstallTarget.NotUpdatable(InstallTarget.Reason.DevBuild),
            detectInstallTarget("  ", UpdatePlatform.MacArm64, { true }),
        )
    }

    @Test
    fun unsupportedPlatformIsNotUpdatable() {
        assertEquals(
            InstallTarget.NotUpdatable(InstallTarget.Reason.UnsupportedPlatform),
            detectInstallTarget("/x/y", UpdatePlatform.Unsupported, { true }),
        )
    }
}
