package com.agentvillage.builder

import com.agentvillage.builder.application.HostedPackageEntry
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class HostedPackageEntryTest {
    @Test
    fun `entry opens owned session without executing or embedding credentials`() {
        val id = UUID.randomUUID()
        val files = HostedPackageEntry.files("https://agentown.example/", id)
        val html = files.getValue("OPEN_IN_AGENTOWN.html")
        assertTrue(html.contains("https://agentown.example/develop?session=$id&panel=output"))
        assertTrue(html.contains("최신 설계"))
        assertFalse(html.contains("<script"))
        assertFalse(html.contains("<form"))
        assertTrue(files.getValue("WEB_START.txt").contains(id.toString()))
        assertTrue(files.keys.all { name -> name.all { it.code in 32..126 } }, "Entry filenames must work with legacy ZIP extractors")
    }

    @Test
    fun `reject unsafe origins rather than embed executable or credential bearing URLs`() {
        listOf("javascript:alert(1)", "http://example.com", "https://user:secret@example.com", "https://example.com/path", "https://example.com/?x=1", "https://example.com/#frag").forEach {
            assertThrows(IllegalArgumentException::class.java) { HostedPackageEntry.files(it, UUID.randomUUID()) }
        }
    }
}
