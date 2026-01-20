package com.geowiwi.kntfy.datatype

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.geowiwi.kntfy.data.StepStatus
import com.geowiwi.kntfy.extension.kntfyExtension
import com.geowiwi.kntfy.extension.managers.ConfigurationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

class UnifiedActionCallback : ActionCallback {
    companion object {
        val MESSAGE_TEXT = ActionParameters.Key<String>("message_text")
        val STATUS = ActionParameters.Key<String>("status")
        val WEBHOOK_ENABLED = ActionParameters.Key<Boolean>("webhook_enabled")
        val WEBHOOK_URL = ActionParameters.Key<String>("webhook_url")

        private const val EXECUTING_TIMEOUT = 30_000L

        private var lastClickTime: Long = 0
        private var executingStartTime: Long = 0
        private var consecutiveClicks: Int = 0
        private var executingTimeoutJob: Job? = null
    }

    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        try {
            val extension = kntfyExtension.Companion.getInstance() ?: return
            val statusString = parameters[STATUS] ?: return
            val currentStatus = try { StepStatus.valueOf(statusString) } catch (e: Exception) { StepStatus.IDLE }
            val messageText = parameters[MESSAGE_TEXT] ?: ""
            val webhookUrl = parameters[WEBHOOK_URL] ?: ""
            val webhookEnabled = parameters[WEBHOOK_ENABLED] == true
            val currentTime = System.currentTimeMillis()
            val timeDiff = currentTime - lastClickTime

            if (currentStatus == StepStatus.EXECUTING) {
                consecutiveClicks++

                if (executingStartTime > 0 && currentTime - executingStartTime > EXECUTING_TIMEOUT) {
                    resetState(extension)
                    return
                }

                if (consecutiveClicks >= 3 && timeDiff < 2000) {
                    resetState(extension)
                    return
                }

                val webhookActive = webhookEnabled && webhookUrl.isNotEmpty()
                val messageActive = messageText.isNotEmpty()

                if (!webhookActive && !messageActive) {
                    resetState(extension)
                }
                return
            } else {
                consecutiveClicks = 0
            }

            if (currentStatus == StepStatus.ERROR || currentStatus == StepStatus.SUCCESS) {
                resetState(extension)
                return
            }

            when (currentStatus) {
                StepStatus.IDLE -> {
                    // First click: change to FIRST
                    extension.updateCustomMessageStatus(0, StepStatus.FIRST)
                    extension.updateWebhookStatus(0, StepStatus.FIRST)
                    lastClickTime = currentTime
                }

                StepStatus.FIRST -> {
                    // Second click: execute immediately
                    if (messageText.isNotEmpty() && webhookUrl.isNotEmpty() && webhookEnabled) {
                        // Both available - execute webhook
                        executingStartTime = currentTime
                        executeWebhook(extension, 0, context)
                    } else if (messageText.isNotEmpty()) {
                        // Only message available
                        executingStartTime = currentTime
                        extension.updateCustomMessageStatus(0, StepStatus.EXECUTING)
                        executeMessage(extension, messageText)
                    } else if (webhookUrl.isNotEmpty() && webhookEnabled) {
                        // Only webhook available
                        executingStartTime = currentTime
                        executeWebhook(extension, 0, context)
                    } else {
                        resetState(extension)
                    }
                }

                else -> resetState(extension)
            }

            lastClickTime = currentTime

            // Set timeout for EXECUTING state
            if (currentStatus == StepStatus.FIRST) {
                executingTimeoutJob?.cancel()
                executingTimeoutJob = CoroutineScope(Dispatchers.IO).launch {
                    delay(EXECUTING_TIMEOUT)
                    resetState(extension)
                }
            }

        } catch (e: Exception) {
            Timber.e(e, "Error en UnifiedActionCallback")
            try {
                val extension = kntfyExtension.Companion.getInstance()
                if (extension != null) {
                    resetState(extension)
                }
            } catch (ex: Exception) {
                Timber.e(ex, "Error al intentar restablecer estado después de excepción")
            }
        }
    }

    private fun resetState(extension: kntfyExtension) {
        executingTimeoutJob?.cancel()
        executingStartTime = 0
        consecutiveClicks = 0
        extension.updateCustomMessageStatus(0, StepStatus.IDLE)
        extension.updateWebhookStatus(0, StepStatus.IDLE)
    }

    private suspend fun executeMessage(extension: kntfyExtension, messageText: String) {
        withContext(Dispatchers.IO) {
            try {
                extension.updateCustomMessageStatus(0, StepStatus.EXECUTING)
                extension.sendCustomMessageWithStateTransitions(0, messageText)
            } catch (e: Exception) {
                extension.updateCustomMessageStatus(0, StepStatus.ERROR)
            }
        }
    }

    private suspend fun executeWebhook(extension: kntfyExtension, webhookId: Int, context: Context) {
        withContext(Dispatchers.IO) {
            try {
                executingTimeoutJob?.cancel()
                executingTimeoutJob = null
                executingStartTime = 0

                val scope = CoroutineScope(Dispatchers.IO)
                val monitorJob = scope.launch {
                    val configManager = ConfigurationManager(context)
                    var previousStatus: StepStatus? = null

                    configManager.loadWebhookDataFlow().collect { webhooks ->
                        val webhook = webhooks.find { it.id == webhookId }
                        webhook?.let {
                            val currentStatus = it.status

                            if (previousStatus != currentStatus) {
                                previousStatus = currentStatus

                                if (currentStatus == StepStatus.IDLE) {
                                    executingTimeoutJob?.cancel()
                                    executingTimeoutJob = null
                                    executingStartTime = 0
                                    consecutiveClicks = 0
                                    resetState(extension)
                                    this.cancel()
                                }
                            }
                        }
                    }
                }

                extension.updateWebhookStatus(webhookId, StepStatus.EXECUTING)
                extension.updateCustomMessageStatus(0, StepStatus.EXECUTING)

                extension.executeWebhookWithStateTransitions(webhookId)

                monitorJob.join()

            } catch (e: Exception) {
                Timber.e(e, "Error ejecutando webhook")
            }
        }
    }
}