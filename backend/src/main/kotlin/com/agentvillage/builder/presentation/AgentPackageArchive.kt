package com.agentvillage.builder.presentation

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object AgentPackageArchive {
    const val ROOT = "agentown-agent"
    const val FILE_NAME = "$ROOT.zip"

    fun create(files: Map<String, String>): ByteArray = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip ->
            files.toSortedMap().forEach { (path, content) ->
                require(!path.startsWith('/') && ".." !in path.split('/')) { "Unsafe Agent Package path: $path" }
                zip.putNextEntry(ZipEntry("$ROOT/$path"))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        output.toByteArray()
    }
}
