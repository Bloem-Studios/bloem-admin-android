package com.bloemstudios.admin.network.api

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keeps server paths inside the typed API layer. A string literal starting with `"/api/` anywhere
 * else in main sources means a feature is building its own request path again.
 */
class ApiPathGuardTest {
    private val sourceRoot: File = listOf("app/src/main/java", "src/main/java")
        .map { File(it) }
        .first { it.isDirectory }

    private data class Hit(val path: String, val line: Int, val text: String)

    private fun literalHits(): List<Hit> = sourceRoot.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .flatMap { file ->
            val relative = file.relativeTo(sourceRoot).invariantSeparatorsPath
            file.readLines().withIndex()
                .filter { (_, line) -> line.contains("\"/api/") }
                .map { (index, line) -> Hit(relative, index + 1, line.trim()) }
        }
        .toList()

    private fun List<Hit>.describe() = joinToString("\n") { "  ${it.path}:${it.line}  ${it.text}" }

    @Test
    fun `no server paths outside the API layer`() {
        val allowedPrefix = "com/bloemstudios/admin/network/api/"
        val allowedFile = "com/bloemstudios/admin/auth/AuthApi.kt"
        val offenders = literalHits().filterNot { it.path.startsWith(allowedPrefix) || it.path == allowedFile }
        assertTrue(
            "Server path literals belong in network/api/ or auth/AuthApi.kt:\n${offenders.describe()}",
            offenders.isEmpty()
        )
    }

    @Test
    fun `only health remains on v1`() {
        val offenders = literalHits().filter { hit ->
            hit.text.contains("\"/api/v1/") && !hit.text.contains("\"/api/v1/health\"")
        }
        assertTrue("Only \"/api/v1/health\" may use v1:\n${offenders.describe()}", offenders.isEmpty())
    }
}
