package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.OutputStream
import java.util.zip.GZIPOutputStream

class ProotUpdaterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun updater() = ProotUpdater(tmp.newFolder("bin"), "aarch64")

    // ---- ar 解析 ----

    @Test
    fun `extractArMember extracts named member skipping others`() {
        val updater = updater()
        val archive = tmp.newFile("pkg.deb")
        writeArArchive(archive)

        val out = tmp.newFile("data.tar.xz")
        updater.extractArMember(archive, "data.tar.xz", out)

        assertEquals("DATA_CONTENT", out.readText())
        // 不存在成员应抛异常
        val out2 = tmp.newFile("nope")
        try {
            updater.extractArMember(archive, "missing", out2)
            assertTrue("should have thrown", false)
        } catch (e: Exception) {
            assertTrue(e is IllegalArgumentException)
        }
    }

    @Test
    fun `extractArMember handles odd sized member padding`() {
        val updater = updater()
        val archive = tmp.newFile("odd.deb")
        // debian-binary 内容 9 字节（奇数），data.tar.xz 紧跟其后
        val bytes = ByteArrayOutputStream2()
        bytes.write("!<arch>\n".toByteArray())
        bytes.write(arHeader("debian-binary", 9))
        bytes.write("2.0\n".toByteArray()) // 9 bytes, padded to 10
        bytes.write(arHeader("data.tar.xz", 5))
        bytes.write("HELLO".toByteArray()) // 5 bytes, odd → pad 1
        bytes.write(0)
        bytes.write(arHeader("control.tar.xz", 3))
        bytes.write("END".toByteArray()) // 3 bytes, odd → pad 1
        bytes.write(0)
        archive.writeBytes(bytes.toByteArray())

        val out = tmp.newFile("d.tar.xz")
        updater.extractArMember(archive, "data.tar.xz", out)
        assertEquals("HELLO", out.readText())
    }

    // ---- Packages 索引解析 ----

    @Test
    fun `parsePackageVersion parses section`() {
        val updater = updater()
        val index = """
            Package: proot
            Version: 5.1.107.89
            Architecture: aarch64
            Filename: pool/main/p/proot/proot_5.1.107.89_aarch64.deb
            SHA256: abc123

            Package: libtalloc
            Version: 2.4.3
            Filename: pool/main/libt/libtalloc/libtalloc_2.4.3_aarch64.deb
            SHA256: def456

        """.trimIndent()

        assertEquals("5.1.107.89", updater.parsePackageVersion(index, "proot"))
        assertEquals("2.4.3", updater.parsePackageVersion(index, "libtalloc"))
        assertEquals("abc123", updater.parsePackageField(index, "proot", "SHA256"))
        assertEquals(
            "pool/main/libt/libtalloc/libtalloc_2.4.3_aarch64.deb",
            updater.parsePackageField(index, "libtalloc", "Filename")
        )
        // proot-distro 不应误匹配 proot
        assertNull(updater.parsePackageVersion("Package: proot-distro\nVersion: 5.0\n", "proot"))
    }

    @Test
    fun `installedVersion reads version file`() {
        val bin = tmp.newFolder("bin2")
        File(bin, "version").writeText("5.1.107.89")
        val updater = ProotUpdater(bin, "aarch64")
        assertEquals("5.1.107.89", updater.installedVersion())
        // 无 version 文件
        val updater2 = updater()
        assertNull(updater2.installedVersion())
        // isReady 需要 proot + loader
        assertFalse(updater.isReady())
        File(bin, "proot").writeText("x")
        File(bin, "loader").writeText("x")
        assertTrue(updater.isReady())
    }

    // ---- deb 完整解压（构造真实 ar+tar.gz 结构） ----

    @Test
    fun `extractDeb full flow with strip prefix`() {
        // 构造 deb：data.tar.gz 内含 termux 前缀路径
        val tarGz = buildTarGz(
            mapOf(
                "data/data/com.termux/files/usr/bin/proot" to "PROOT_BIN",
                "data/data/com.termux/files/usr/libexec/proot/loader" to "LOADER_BIN",
                "data/data/com.termux/files/usr/lib/libtalloc.so.2" to "TALLOC",
            )
        )
        val archive = tmp.newFile("proot.deb")
        val bytes = ByteArrayOutputStream2()
        bytes.write("!<arch>\n".toByteArray())
        bytes.write(arHeader("debian-binary", 4))
        bytes.write("2.0\n".toByteArray())
        val data = tarGz
        bytes.write(arHeader("data.tar.gz", data.size))
        bytes.write(data)
        if (data.size % 2 != 0) bytes.write(0)
        archive.writeBytes(bytes.toByteArray())

        // 提取 data.tar.gz 后手动走 TarExtractor（deb 里是 tar.xz，这里用 tar.gz 模拟解压逻辑）
        val updater = updater()
        val dataOut = tmp.newFile("data.tar.gz")
        updater.extractArMember(archive, "data.tar.gz", dataOut)
        val target = tmp.newFolder("out")
        TarExtractor.extract(
            archive = dataOut,
            targetDir = target,
            format = TarExtractor.ArchiveFormat.TAR_GZ,
            stripPrefix = "data/data/com.termux/files",
        )
        assertEquals("PROOT_BIN", File(target, "usr/bin/proot").readText())
        assertEquals("LOADER_BIN", File(target, "usr/libexec/proot/loader").readText())
        assertEquals("TALLOC", File(target, "usr/lib/libtalloc.so.2").readText())
    }

    // ---- 辅助 ----

    private fun arHeader(name: String, size: Int): ByteArray {
        val header = ByteArray(60)
        name.toByteArray().copyInto(header, 0)
        "0".toByteArray().copyInto(header, 16)   // mtime
        "0".toByteArray().copyInto(header, 28)   // uid
        "0".toByteArray().copyInto(header, 34)   // gid
        "100644".toByteArray().copyInto(header, 40) // mode
        size.toString().toByteArray().copyInto(header, 48)
        "`\n".toByteArray().copyInto(header, 58)
        return header
    }

    private fun writeArArchive(archive: File) {
        val bytes = ByteArrayOutputStream2()
        bytes.write("!<arch>\n".toByteArray())
        bytes.write(arHeader("debian-binary", 4))
        bytes.write("2.0\n".toByteArray())
        bytes.write(arHeader("data.tar.xz", 13))
        bytes.write("DATA_CONTENT".toByteArray())
        if (13 % 2 != 0) bytes.write(0)
        archive.writeBytes(bytes.toByteArray())
    }

    /** 构造简化 tar.gz：name -> content（无目录项、无长名） */
    private fun buildTarGz(entries: Map<String, String>): ByteArray {
        val bos = ByteArrayOutputStream2()
        GZIPOutputStream(bos).use { gz ->
            for ((name, content) in entries) {
                val header = ByteArray(512)
                name.toByteArray().copyInto(header, 0)
                "0000755".toByteArray().copyInto(header, 100)
                content.toByteArray().size.toLong().toString(8).padStart(11, '0').toByteArray().copyInto(header, 124)
                "00000000000".toByteArray().copyInto(header, 136)
                header[156] = '0'.code.toByte()
                gz.write(header)
                gz.write(content.toByteArray())
                val pad = (512 - content.toByteArray().size % 512) % 512
                gz.write(ByteArray(pad))
            }
            gz.write(ByteArray(1024))
        }
        return bos.toByteArray()
    }

    private class ByteArrayOutputStream2 : java.io.ByteArrayOutputStream() {
        fun write(b: Int) = super.write(b)
        fun write(b: ByteArray) = super.write(b, 0, b.size)
        fun toByteArray() = super.toByteArray()
    }
}
