// MIT License
//
//Copyright (c) Jakob Maležič
//
//Permission is hereby granted, free of charge, to any person obtaining a copy
//of this software and associated documentation files (the "Software"), to deal
//in the Software without restriction, including without limitation the rights
//to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
//copies of the Software, and to permit persons to whom the Software is
//furnished to do so, subject to the following conditions:
//
//The above copyright notice and this permission notice shall be included in all
//copies or substantial portions of the Software.
//
//THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
//IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
//FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
//AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
//LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
//OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
//SOFTWARE.

package cc.unitmesh.devti.vcs

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtilRt
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.*
import com.intellij.vcs.log.VcsFullCommitDetails
import java.io.IOException
import java.io.StringWriter
import java.nio.file.FileSystems
import java.nio.file.PathMatcher

@Service(Service.Level.PROJECT)
class VcsPrompting(private val project: Project) {
    private val defaultIgnoreFilePatterns: List<PathMatcher> = listOf(
        "**/*.json", "**/*.jsonl", "**/*.txt", "**/*.log", "**/*.tmp", "**/*.temp", "**/*.bak", "**/*.swp", "**/*.svg",
    ).map {
        FileSystems.getDefault().getPathMatcher("glob:$it")
    }

    fun prepareContext(changes: List<Change>, ignoreFilePatterns: List<PathMatcher> = defaultIgnoreFilePatterns): String {
        return project.service<DiffSimplifier>().simplify(changes, ignoreFilePatterns)
    }

    fun prepareAnnotatedContext(changes: List<Change>, ignoreFilePatterns: List<PathMatcher> = defaultIgnoreFilePatterns): String {
        val result = StringBuilder()

        for (change in changes) {
            // Skip binary or too large files
            if (isBinaryOrTooLarge(change)) {
                continue
            }

            // Skip files matching ignore patterns
            val filePath = change.afterRevision?.file ?: change.beforeRevision?.file
            if (filePath != null && ignoreFilePatterns.any { pattern ->
                    pattern.matches(java.nio.file.Paths.get(filePath.path))
                }) {
                continue
            }

            when (change.type) {
                Change.Type.NEW -> {
                    // New file - all lines are added
                    val newContent = change.afterRevision?.content ?: continue
                    val fileName = change.afterRevision?.file?.path ?: "unknown"

                    result.append("=== New File: $fileName ===\n")
                    newContent.lines().forEachIndexed { index, line ->
                        result.append("[added @${index + 1} in new code] $line\n")
                    }
                    result.append("\n")
                }

                Change.Type.DELETED -> {
                    // Deleted file - all lines are deleted
                    val oldContent = change.beforeRevision?.content ?: continue
                    val fileName = change.beforeRevision?.file?.path ?: "unknown"

                    result.append("=== Deleted File: $fileName ===\n")
                    oldContent.lines().forEachIndexed { index, line ->
                        result.append("[deleted @${index + 1} in old code] $line\n")
                    }
                    result.append("\n")
                }

                Change.Type.MODIFICATION, Change.Type.MOVED -> {
                    // Modified or moved file - need to analyze line by line
                    val oldContent = change.beforeRevision?.content ?: ""
                    val newContent = change.afterRevision?.content ?: ""
                    val fileName = change.afterRevision?.file?.path ?: change.beforeRevision?.file?.path ?: "unknown"

                    if (change.type == Change.Type.MOVED) {
                        val oldFileName = change.beforeRevision?.file?.path ?: "unknown"
                        result.append("=== Moved File: $oldFileName -> $fileName ===\n")
                    } else {
                        result.append("=== Modified File: $fileName ===\n")
                    }

                    val annotatedContent = annotateFileChanges(oldContent, newContent)
                    result.append(annotatedContent)
                    result.append("\n")
                }
            }
        }

        return result.toString()
    }

    /**
     * Annotates changes between old and new file content with detailed line-by-line status.
     * Uses a simple diff algorithm to identify added, deleted, and unchanged lines.
     */
    private fun annotateFileChanges(oldContent: String, newContent: String): String {
        val oldLines = oldContent.lines()
        val newLines = newContent.lines()
        val result = StringBuilder()

        // Simple diff algorithm using LCS (Longest Common Subsequence) approach
        val diffResult = computeLineDiff(oldLines, newLines)

        for (operation in diffResult) {
            when (operation.type) {
                DiffOperation.Type.DELETE -> {
                    result.append("[deleted @${operation.oldLineNumber} in old code] ${operation.line}\n")
                }
                DiffOperation.Type.INSERT -> {
                    result.append("[added @${operation.newLineNumber} in new code] ${operation.line}\n")
                }
                DiffOperation.Type.MODIFY -> {
                    result.append("[pre-modified @${operation.oldLineNumber} in old code] ${operation.oldLine}\n")
                    result.append("[post-modified @${operation.newLineNumber} in new code] ${operation.line}\n")
                }
                DiffOperation.Type.EQUAL -> {
                    result.append("[unchanged] ${operation.line}\n")
                }
            }
        }

        return result.toString()
    }

    /**
     * Computes line-level diff between old and new content using an improved diff algorithm.
     * This algorithm uses LCS (Longest Common Subsequence) to better identify moved code blocks.
     */
    private fun computeLineDiff(oldLines: List<String>, newLines: List<String>): List<DiffOperation> {
        // Use Myers' diff algorithm with LCS to identify common subsequences
        val lcs = computeLCS(oldLines, newLines)
        val operations = mutableListOf<DiffOperation>()
        
        var oldIndex = 0
        var newIndex = 0
        var lcsIndex = 0
        
        while (oldIndex < oldLines.size || newIndex < newLines.size || lcsIndex < lcs.size) {
            when {
                // We've processed all LCS matches
                lcsIndex >= lcs.size -> {
                    // Add remaining deletions
                    while (oldIndex < oldLines.size) {
                        operations.add(DiffOperation(
                            type = DiffOperation.Type.DELETE,
                            line = oldLines[oldIndex],
                            oldLineNumber = oldIndex + 1
                        ))
                        oldIndex++
                    }
                    // Add remaining insertions
                    while (newIndex < newLines.size) {
                        operations.add(DiffOperation(
                            type = DiffOperation.Type.INSERT,
                            line = newLines[newIndex],
                            newLineNumber = newIndex + 1
                        ))
                        newIndex++
                    }
                }
                // Current lines match the next LCS element
                oldIndex < oldLines.size && newIndex < newLines.size && 
                lcs[lcsIndex].oldIndex == oldIndex && lcs[lcsIndex].newIndex == newIndex -> {
                    operations.add(DiffOperation(
                        type = DiffOperation.Type.EQUAL,
                        line = oldLines[oldIndex],
                        oldLineNumber = oldIndex + 1,
                        newLineNumber = newIndex + 1
                    ))
                    oldIndex++
                    newIndex++
                    lcsIndex++
                }
                // Need to advance to next LCS match
                else -> {
                    val nextLcs = if (lcsIndex < lcs.size) lcs[lcsIndex] else null
                    
                    // Add deletions until we reach the next LCS match
                    while (oldIndex < oldLines.size && (nextLcs == null || oldIndex < nextLcs.oldIndex)) {
                        operations.add(DiffOperation(
                            type = DiffOperation.Type.DELETE,
                            line = oldLines[oldIndex],
                            oldLineNumber = oldIndex + 1
                        ))
                        oldIndex++
                    }
                    
                    // Add insertions until we reach the next LCS match
                    while (newIndex < newLines.size && (nextLcs == null || newIndex < nextLcs.newIndex)) {
                        operations.add(DiffOperation(
                            type = DiffOperation.Type.INSERT,
                            line = newLines[newIndex],
                            newLineNumber = newIndex + 1
                        ))
                        newIndex++
                    }
                }
            }
        }
        
        return operations
    }
    
    /**
     * Computes the Longest Common Subsequence (LCS) between two lists of strings.
     * Returns a list of LCSElement representing the common elements and their positions.
     */
    private fun computeLCS(oldLines: List<String>, newLines: List<String>): List<LCSElement> {
        val oldSize = oldLines.size
        val newSize = newLines.size
        
        // Create LCS table
        val lcsTable = Array(oldSize + 1) { IntArray(newSize + 1) }
        
        // Fill LCS table
        for (i in 1..oldSize) {
            for (j in 1..newSize) {
                if (oldLines[i - 1] == newLines[j - 1]) {
                    lcsTable[i][j] = lcsTable[i - 1][j - 1] + 1
                } else {
                    lcsTable[i][j] = maxOf(lcsTable[i - 1][j], lcsTable[i][j - 1])
                }
            }
        }
        
        // Backtrack to find LCS elements
        val lcsElements = mutableListOf<LCSElement>()
        var i = oldSize
        var j = newSize
        
        while (i > 0 && j > 0) {
            if (oldLines[i - 1] == newLines[j - 1]) {
                lcsElements.add(0, LCSElement(i - 1, j - 1, oldLines[i - 1]))
                i--
                j--
            } else if (lcsTable[i - 1][j] > lcsTable[i][j - 1]) {
                i--
            } else {
                j--
            }
        }
        
        return lcsElements
    }
    
    /**
     * Represents an element in the Longest Common Subsequence.
     */
    private data class LCSElement(
        val oldIndex: Int,
        val newIndex: Int,
        val line: String
    )

    private fun isBinaryOrTooLarge(change: Change): Boolean {
        return isBinaryOrTooLarge(change.beforeRevision) || isBinaryOrTooLarge(change.afterRevision)
    }

    private fun isBinaryOrTooLarge(revision: ContentRevision?): Boolean {
        val virtualFile = (revision as? CurrentContentRevision)?.virtualFile ?: return false
        return isBinaryRevision(revision) || FileUtilRt.isTooLarge(virtualFile.length)
    }

    private fun isBinaryRevision(cr: ContentRevision?): Boolean {
        if (cr == null) return false

        return when (cr) {
            is BinaryContentRevision -> true
            else -> cr.file.fileType.isBinary
        }
    }

    /**
     * Builds a diff prompt for a list of VcsFullCommitDetails.
     *
     * @param details The list of VcsFullCommitDetails containing commit details.
     * @param project The Project object representing the current project.
     * @param ignoreFilePatterns The list of PathMatcher objects representing file patterns to be ignored during diff generation. Default value is an empty list.
     * @return A Pair object containing a list of commit message summaries and the generated diff prompt as a string. Returns null if the list is empty or no valid changes are found.
     * @throws VcsException If an error occurs during VCS operations.
     * @throws IOException If an I/O error occurs.
     */
    @Throws(VcsException::class, IOException::class)
    fun buildDiffPrompt(
        details: List<VcsFullCommitDetails>,
        selectList: List<Change>,
        project: Project,
        ignoreFilePatterns: List<PathMatcher> = defaultIgnoreFilePatterns,
    ): String? {
        val changeText = project.service<DiffSimplifier>().simplify(selectList, ignoreFilePatterns)

        if (changeText.isEmpty()) {
            return null
        }

        val processedText = try {
            DiffSimplifier.postProcess(changeText)
        } catch (e: Exception) {
            changeText
        }

        val writer = StringWriter()
        if (details.isNotEmpty()) {
            writer.write("Commit Message: ")
            details.forEach { writer.write(it.fullMessage + "\n\n") }
        }

        writer.write("Changes:\n\n```patch\n$processedText\n```")

        return writer.toString()
    }

    fun getChanges(): List<Change> {
        val changeListManager = ChangeListManager.getInstance(project)
        return changeListManager.changeLists.flatMap { it.changes }
    }
}

/**
 * Represents a single diff operation with detailed information about the change.
 */
private data class DiffOperation(
    val type: Type,
    val line: String,
    val oldLine: String? = null,
    val oldLineNumber: Int? = null,
    val newLineNumber: Int? = null
) {
    enum class Type {
        INSERT,    // Line added in new version
        DELETE,    // Line deleted from old version
        MODIFY,    // Line modified between versions
        EQUAL      // Line unchanged between versions
    }
}