package cc.unitmesh.git.actions.vcs

import cc.unitmesh.devti.AutoDevNotifications
import cc.unitmesh.devti.actions.chat.base.ChatBaseAction
import cc.unitmesh.devti.gui.chat.message.ChatActionType
import cc.unitmesh.devti.gui.sendToChatPanel
import cc.unitmesh.devti.provider.context.ChatContextItem
import cc.unitmesh.devti.provider.context.ChatContextProvider
import cc.unitmesh.devti.provider.context.ChatCreationContext
import cc.unitmesh.devti.provider.context.ChatOrigin
import cc.unitmesh.devti.settings.locale.LanguageChangedCallback.presentationText
import cc.unitmesh.devti.statusbar.AutoDevStatus
import cc.unitmesh.devti.util.AutoDevCoroutineScope
import cc.unitmesh.devti.vcs.VcsPrompting
import cc.unitmesh.devti.vcs.VcsUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.Change
import com.intellij.vcs.commit.CommitWorkflowUi
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

import cc.unitmesh.devti.gui.sendToChatWindow
import java.util.concurrent.Semaphore

class CommitCodeReviewAction : ChatBaseAction() {
    private val logger = logger<CommitCodeReviewAction>()
    init {
        presentationText("settings.autodev.others.commitCodeReview", templatePresentation)
        isEnabledInModalContext = true
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    private var currentJob: Job? = null

    override fun getActionType(): ChatActionType = ChatActionType.CODE_REVIEW

    override fun update(e: AnActionEvent) {
        val project = e.project
        val data = e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL)

        if (data == null || project == null) {
            e.presentation.icon = AutoDevStatus.WAITING.icon
            e.presentation.isEnabled = false
            return
        }

        val prompting = project.service<VcsPrompting>()
        val changes: List<Change> = prompting.getChanges()

        e.presentation.text = "Code Review"
        e.presentation.description = "Review code changes..."

        // Update icon based on current job status
        if (currentJob?.isActive == true) {
            e.presentation.icon = AutoDevStatus.InProgress.icon
            e.presentation.text = "Cancel Code Review"
            e.presentation.description = "Click to cancel current review"
        } else {
            e.presentation.icon = AutoDevStatus.Ready.icon
        }

        e.presentation.isEnabled = changes.isNotEmpty()
    }

    override fun executeAction(event: AnActionEvent) {
        val project = event.project ?: return

        // If there's an active job, cancel it
        if (currentJob?.isActive == true) {
            currentJob?.cancel()
            currentJob = null
            AutoDevNotifications.notify(project, "Code review cancelled.")
            return
        }

        val commitWorkflowUi = VcsUtil.getCommitWorkFlowUi(event)
        if (commitWorkflowUi == null) {
            AutoDevNotifications.notify(project, "Cannot get commit workflow UI.")
            return
        }

        val changes = getChanges(commitWorkflowUi)
        if (changes == null || changes.isEmpty()) {
            AutoDevNotifications.notify(project, "No changes to review. Do you select any files?")
            return
        }

        startCodeReview(project, changes)
    }

    private suspend inline fun <T> Semaphore.withPermit(action: () -> T): T {
        acquire()
        try {
            return action()
        } finally {
            release()
        }
    }

    private fun startCodeReview(project: Project, changes: List<Change>) {
        val task = object : Task.Backgroundable(project, "Code Review", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false
                indicator.text = "Preparing code diff..."
                indicator.fraction = 0.0

                try {
                    val vcsPrompting = project.service<VcsPrompting>()
                    
                    // 按文件分组处理changes
                    val fileGroups = groupChangesByFile(changes)
                    val totalFiles = fileGroups.size
                    
                    if (totalFiles == 0) {
                        ApplicationManager.getApplication().invokeLater {
                            AutoDevNotifications.notify(project, "No valid files to review.")
                        }
                        return
                    }

                    // 使用并发处理文件
                    runBlocking {
                        val allReviewResults = ConcurrentHashMap<String, String>()
                        val completedCount = AtomicInteger(0)
                        
                        // 创建协程作用域，限制并发数量
                        val semaphore = Semaphore(10) // 限制最多10个并发请求
                        
                        val jobs = fileGroups.map { (fileName, fileChanges) ->
                            async {
                                semaphore.withPermit {
                                    if (indicator.isCanceled) return@withPermit
                                    
                                    try {
                                        // 为当前文件生成diff上下文
                                        val fileDiffContext = vcsPrompting.prepareAnnotatedContext(fileChanges)
                                        
                                        if (fileDiffContext.isNotEmpty() && fileDiffContext != "\n") {
                                            // 更新进度显示
                                            val currentProgress = completedCount.get()
                                            ApplicationManager.getApplication().invokeLater {
                                                indicator.text = "Reviewing file: $fileName ($currentProgress/$totalFiles)"
                                                indicator.fraction = currentProgress.toDouble() / totalFiles
                                            }
                                            
                                            // 发送请求到Dify
                                            val reviewResult = sendToDifyAsync(fileDiffContext, fileName)
                                            
                                            if (reviewResult != null) {
                                                allReviewResults[fileName] = reviewResult
                                                logger.info("Completed review for file: $fileName")
                                            } else {
                                                logger.warn("Failed to get review result for file: $fileName")
                                                allReviewResults[fileName] = "Failed to get review for this file."
                                            }
                                        } else {
                                            logger.info("Skipping file $fileName - no valid diff context")
                                            allReviewResults[fileName] = "No valid changes to review in this file."
                                        }
                                    } catch (e: Exception) {
                                        logger.error("Error processing file $fileName", e)
                                        allReviewResults[fileName] = "Error occurred while processing this file: ${e.message}"
                                    } finally {
                                        val completed = completedCount.incrementAndGet()
                                        ApplicationManager.getApplication().invokeLater {
                                            indicator.text = "Completed: $completed/$totalFiles files"
                                            indicator.fraction = completed.toDouble() / totalFiles
                                        }
                                    }
                                }
                            }
                        }
                        
                        // 等待所有任务完成
                        jobs.awaitAll()
                        
                        // 转换结果为有序列表
                        val orderedResults = fileGroups.keys.mapNotNull { fileName ->
                            allReviewResults[fileName]?.let { result ->
                                fileName to result
                            }
                        }
                        
                        ApplicationManager.getApplication().invokeLater {
                            indicator.text = "Displaying review results..."
                            indicator.fraction = 1.0
                            
                            // 在聊天窗口中显示所有结果
                            displayAllReviewsInChat(project, orderedResults)
                        }
                    }

                } catch (e: Exception) {
                    logger.error("Error during code review", e)
                    ApplicationManager.getApplication().invokeLater {
                        AutoDevNotifications.notify(project, "Error during code review: ${e.message}")
                    }
                }
            }
        }

        // 启动任务
        currentJob = GlobalScope.launch {
            ProgressManager.getInstance().run(task)
        }
    }

    private fun groupChangesByFile(changes: List<Change>): Map<String, List<Change>> {
        return changes.groupBy { change ->
            when {
                change.afterRevision != null -> {
                    change.afterRevision!!.file.name
                }
                change.beforeRevision != null -> {
                    change.beforeRevision!!.file.name
                }
                else -> "Unknown"
            }
        }.filterKeys { it != "Unknown" }
    }

    private fun getChanges(commitWorkflowUi: CommitWorkflowUi): List<Change>? {
        val changes = commitWorkflowUi.getIncludedChanges()
        val unversionedFiles = commitWorkflowUi.getIncludedUnversionedFiles()

        val unversionedFileChanges = unversionedFiles.map {
            Change(null, com.intellij.openapi.vcs.changes.CurrentContentRevision(it))
        }

        if (changes.isNotEmpty() || unversionedFileChanges.isNotEmpty()) {
            return changes + unversionedFileChanges
        }

        return null
    }

    // 异步版本的sendToDify方法
    private suspend fun sendToDifyAsync(diffContext: String, fileName: String): String? = withContext(Dispatchers.IO) {
        // 为每个请求创建新的客户端实例
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES)) // 适当的连接池配置
            .build()

        try {
            // 构建请求体，包含文件名信息
            val requestJson = JSONObject().apply {
                put("inputs", JSONObject().apply {
                    put("code_diff", diffContext)
                    put("file_name", fileName)
                })
                put("response_mode", "blocking")
                put("user", "autodev-plugin-${fileName}-${System.currentTimeMillis()}") // 添加文件名到用户标识
            }

            val requestBody = requestJson.toString().toRequestBody("application/json".toMediaType())

            // 构建请求
            val request = Request.Builder()
                .url("http://localhost/v1/workflows/run")
                .post(requestBody)
                .addHeader("Authorization", "Bearer app-77Fp4jJyUFay05F8qhIeClFm")
                .addHeader("Content-Type", "application/json")
                .addHeader("User-Agent", "AutoDev-Plugin-${fileName}-${System.currentTimeMillis()}")
                .addHeader("X-Request-ID", "autodev-${fileName}-${System.currentTimeMillis()}") // 添加唯一请求ID
                .build()

            logger.info("Sending async request to Dify for file: $fileName")

            client.newCall(request).execute().use { response ->
                logger.info("Received async response for file $fileName with code: ${response.code}")
                
                if (!response.isSuccessful) {
                    logger.error("Dify async request failed for file $fileName with code: ${response.code}, message: ${response.message}")
                    return@withContext null
                }

                val responseBody = response.body?.string()
                if (responseBody.isNullOrEmpty()) {
                    logger.error("Empty async response from Dify for file $fileName")
                    return@withContext null
                }

                logger.info("Async response body length for $fileName: ${responseBody.length}")

                // 解析响应
                val responseJson = JSONObject(responseBody)

                return@withContext when {
                    responseJson.has("data") && responseJson.getJSONObject("data").has("outputs") -> {
                        val outputs = responseJson.getJSONObject("data").getJSONObject("outputs")
                        outputs.optString("result", "No review result found for $fileName")
                    }
                    responseJson.has("answer") -> {
                        responseJson.getString("answer")
                    }
                    else -> {
                        logger.warn("Unexpected Dify async response format for file $fileName: $responseBody")
                        "Review completed for $fileName but response format was unexpected."
                    }
                }
            }
        } catch (e: IOException) {
            logger.error("Network error when calling Dify async for file $fileName", e)
            return@withContext null
        } catch (e: Exception) {
            logger.error("Error parsing Dify async response for file $fileName", e)
            return@withContext null
        } finally {
            // 适当清理客户端资源
            try {
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            } catch (e: Exception) {
                logger.warn("Error cleaning up HTTP client for $fileName", e)
            }
        }
    }

    private fun displayAllReviewsInChat(project: Project, reviewResults: List<Pair<String, String>>) {
        sendToChatWindow(project, getActionType()) { panel, service ->
            // 创建一个用户消息作为上下文
            val userMessage = "Please review the code changes in ${reviewResults.size} file(s):"
            panel.addMessage(userMessage, true, userMessage)

            // 合并所有审查结果为一个markdown文档
            val combinedMarkdown = buildString {
                append("# 📋 Code Review Report\n\n")
                
                reviewResults.forEach { (fileName, reviewResult) ->
                    append("## 📄 File: $fileName\n\n")
                    append(reviewResult)
                    append("\n\n---\n\n")
                }
                
                append("✅ Code review completed for ${reviewResults.size} file(s).\n")
            }

            // 创建Assistant消息并让MessageView自动处理markdown渲染
            val assistantMessage = panel.addMessage(combinedMarkdown, false, combinedMarkdown)
            
            // 确保消息完成处理
            ApplicationManager.getApplication().invokeLater {
                assistantMessage.onFinish(combinedMarkdown)
                panel.updateUI()
                panel.revalidate()
                panel.repaint()
            }
        }
    }

}
