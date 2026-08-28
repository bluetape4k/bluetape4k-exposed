import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * jdbc-tests Kotlin 소스는 raw assertion API 대신 Bluetape4k assertion을 사용해야 합니다.
 *
 * 검증기는 Gradle project와 task API에서 의도적으로 분리되어 configuration cache가
 * task action을 직렬화할 수 있도록 합니다.
 */
fun findBluetapeAssertionImportViolations(
    sourceRoots: Collection<File>,
    kotlinCompileSources: Collection<File>,
    projectDirectory: File,
): List<String> {
    val projectPath = projectDirectory.toPath().toRealPath()

    fun regularKotlinFiles(root: File): List<File> {
        val rootPath = root.toPath().toAbsolutePath().normalize()
        check(Files.isDirectory(rootPath)) { "Missing Kotlin source root: $root" }
        check(!Files.isSymbolicLink(rootPath)) { "Kotlin source root must not be a symlink: $root" }
        val realRoot = rootPath.toRealPath()
        check(realRoot.startsWith(projectPath)) {
            "Kotlin source root must stay inside project directory: $root"
        }
        var ancestor = rootPath
        while (ancestor != projectPath) {
            check(!Files.isSymbolicLink(ancestor)) {
                "Kotlin source root ancestor must not be a symlink: $ancestor"
            }
            ancestor = checkNotNull(ancestor.parent) { "Source root escaped project directory: $root" }
        }
        return Files.walk(rootPath).use { paths ->
            val allPaths = paths.toList()
            check(allPaths.none { path -> Files.isSymbolicLink(path) }) {
                "Symlink path is forbidden below Kotlin source root: $root"
            }
            allPaths
                .filter { path ->
                    Files.isRegularFile(path) &&
                        path.toRealPath().startsWith(realRoot) &&
                        path.fileName.toString().endsWith(".kt")
                }
                .map(Path::toFile)
        }
    }

    fun isForbiddenAssertionImport(line: String): Boolean {
        val importLine = line.trim()
        val importIndex = line.indexOf("import")
        val leading = if (importIndex < 0) "" else line.substring(0, importIndex).trim()
        check(importIndex < 0 || leading.isEmpty() || leading.endsWith("*/")) {
            "Import hidden behind a leading comment is rejected by the guard: $line"
        }
        check(!importLine.endsWith(".") && !importLine.endsWith(" as")) {
            "Incomplete or continued import declaration is rejected by the guard: $line"
        }
        check(';' !in importLine) {
            "Semicolon-terminated import declarations are rejected by the guard: $line"
        }
        check("/*" !in importLine && "*/" !in importLine && "//" !in importLine) {
            "Comments in import declarations are rejected by the guard: $line"
        }
        val imported = importLine
            .removePrefix("import")
            .trim()
            .replace(Regex("\\s+as\\s+[A-Za-z_][A-Za-z0-9_]*\\s*$"), "")
            .replace("`", "")
            .replace(Regex("\\s*\\.\\s*"), ".")
        check(imported.isNotBlank() && !Regex("\\s").containsMatchIn(imported)) {
            "Unparseable import declaration is rejected by the guard: $line"
        }
        return imported == "org.junit.jupiter.api.Assertions" ||
            imported.startsWith("org.junit.jupiter.api.Assertions.") ||
            imported == "org.junit.jupiter.api.assertThrows" ||
            imported.startsWith("kotlin.test.assert") ||
            imported.startsWith("org.assertj.") ||
            imported.startsWith("org.kluent.")
    }

    val expectedKotlinRoots = sourceRoots
        .map { root ->
            val rootPath = root.toPath().toAbsolutePath().normalize()
            check(Files.isDirectory(rootPath)) { "Missing Kotlin source root: $root" }
            rootPath.toRealPath()
        }
        .toSet()
    val unexpectedKotlinSources = kotlinCompileSources
        .filter { it.extension == "kt" }
        .filter { file ->
            val sourcePath = file.toPath().toAbsolutePath().normalize().toRealPath()
            expectedKotlinRoots.none { root -> sourcePath.startsWith(root) }
        }
    check(unexpectedKotlinSources.isEmpty()) {
        "Kotlin compile source is outside the fixed guard roots: $unexpectedKotlinSources"
    }

    return sourceRoots
        .flatMap(::regularKotlinFiles)
        .flatMap { file ->
            var blockCommentOpen = false
            file.readLines().mapIndexedNotNull { index, line ->
                var remainder = line.trimStart()
                var strippedComment = false
                if (blockCommentOpen) {
                    val closes = remainder.indexOf("*/")
                    if (closes < 0) return@mapIndexedNotNull null
                    blockCommentOpen = false
                    remainder = remainder.substring(closes + 2).trimStart()
                    strippedComment = true
                }
                while (remainder.startsWith("/*")) {
                    val closes = remainder.indexOf("*/", 2)
                    if (closes < 0) {
                        blockCommentOpen = true
                        return@mapIndexedNotNull null
                    }
                    remainder = remainder.substring(closes + 2).trimStart()
                    strippedComment = true
                }
                if (strippedComment && (remainder.startsWith("import") || remainder.startsWith("."))) {
                    check(false) {
                        "Import hidden after a block comment is rejected by the guard: " +
                            "${file.relativeTo(projectDirectory)}:${index + 1}"
                    }
                }
                if (remainder.startsWith("//") && "import" in remainder) {
                    check(false) {
                        "Import continuation/comment bypass is rejected by the guard: " +
                            "${file.relativeTo(projectDirectory)}:${index + 1}"
                    }
                }
                if (remainder.startsWith("import") && isForbiddenAssertionImport(remainder)) {
                    "${file.relativeTo(projectDirectory)}:${index + 1}: $remainder"
                } else {
                    null
                }
            }
        }
}
