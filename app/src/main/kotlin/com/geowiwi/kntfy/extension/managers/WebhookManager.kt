package com.geowiwi.kntfy.extension.managers

import android.content.Context
import com.geowiwi.kntfy.data.GpsCoordinates
import com.geowiwi.kntfy.data.WebhookData
import com.geowiwi.kntfy.data.StepStatus
import com.geowiwi.kntfy.extension.makeHttpRequest
import com.geowiwi.kntfy.extension.getGpsFlow
import com.geowiwi.kntfy.extension.getHomeFlow
import com.geowiwi.kntfy.extension.streamDataMonitorFlow
import com.geowiwi.kntfy.extension.streamUserProfile
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import timber.log.Timber
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt


class WebhookManager(
    context: Context,
    private val karooSystem: KarooSystemService,
    private val scope: CoroutineScope
) {

    private val webhookStateStore = WebhookStateStore(context)
    private val configManager = ConfigurationManager(context)
    private var webhookConfig: WebhookData? = null

    private fun KarooSystemService.getRemainingDistanceFlow(): Flow<Double> {
        return streamDataMonitorFlow(DataType.Type.DISTANCE_TO_DESTINATION)
            .map { state ->
                if (state is StreamState.Streaming) {
                    state.dataPoint.values["FIELD_DISTANCE_TO_DESTINATION_ID"] ?: 0.0
                } else 0.0
            }
            .catch { e ->
                Timber.e(e, "Error obteniendo distancia: ${e.message}")
                emit(0.0)
            }
    }

    private suspend fun getRemainingDistance(): String {
        try {
            val distance = karooSystem.getRemainingDistanceFlow().first()
            val units = karooSystem.streamUserProfile().first().preferredUnit.distance

            return when {
                distance <= 0.0 -> "0"
                units == UserProfile.PreferredUnit.UnitType.IMPERIAL ->
                    "${(distance / 1609).toInt()} mi"
                distance < 1000 ->
                    "${distance.toInt()} m"
                else ->
                    "${(distance / 1000).toInt()} km"
            }
        } catch (e: Exception) {
            Timber.e(e, "Error getting remaining distance")
            return "0"
        }
    }

    suspend fun loadWebhookConfiguration(webhookId: Int? = null) {
        val webhooks = configManager.loadWebhookDataFlow().first()
        webhookConfig = if (webhookId != null) {
            webhooks.find { it.id == webhookId }
        } else {
            webhooks.firstOrNull()
        }
    }

    suspend fun handleEvent(eventType: String, webhookId: Int): Boolean {
        try {
            loadWebhookConfiguration(webhookId)

            webhookConfig?.let { config ->
                val modConfig: WebhookData
                val shouldTrigger = when (eventType) {
                    "start" -> {
                        modConfig = config.copy(post = config.post.replace("%status%", config.statusTextOnStart))
                        config.actionOnStart
                    }
                    "stop" -> {
                        modConfig = config.copy(post = config.post.replace("%status%", config.statusTextOnStop))
                        config.actionOnStop
                    }
                    "pause" -> {
                        modConfig = config.copy(post = config.post.replace("%status%", config.statusTextOnPause))
                        config.actionOnPause
                    }
                    "resume" -> {
                        modConfig = config.copy(post = config.post.replace("%status%", config.statusTextOnResume))
                        config.actionOnResume
                    }
                    "custom" -> {
                        modConfig = config.copy(post = config.post.replace("%status%", config.statusTextOnCustom))
                        config.actionOnCustom
                    }
                    else -> {
                        modConfig = config
                        false
                    }
                }

                val locationOk = if (config.onlyIfLocation) {
                    val poi = karooSystem.getHomeFlow().first()
                    checkCurrentLocation(poi)
                } else {
                    true
                }

                if (shouldTrigger && locationOk) {
                    return sendWebhook(modConfig)
                }
                else return false
            }
            return false
        } catch (e: Exception) {
            Timber.e(e, "Error al procesar webhook para evento $eventType y ID $webhookId")
            return false
        }
    }

    fun restorePendingWebhookStates() {
        scope.launch(Dispatchers.IO) {
            try {
                val webhooks = configManager.loadWebhookDataFlow().first()

                webhooks.forEach { webhook ->
                    val (statusStr, targetTime) = webhookStateStore.getWebhookState(webhook.id)

                    if (statusStr != null) {
                        val status = StepStatus.valueOf(statusStr)
                        val currentTime = System.currentTimeMillis()
                        val remainingTime = targetTime - currentTime

                        if (remainingTime > 0) {
                            updateWebhookStatus(webhook.id, status)
                            delay(remainingTime)

                            when (status) {
                                StepStatus.FIRST -> {
                                    updateWebhookStatus(webhook.id, StepStatus.IDLE)
                                    webhookStateStore.clearWebhookState(webhook.id)
                                }
                                StepStatus.EXECUTING -> {
                                    updateWebhookStatus(webhook.id, StepStatus.SUCCESS)
                                    scheduleResetToIdle(webhook.id, 5_000)
                                }
                                StepStatus.SUCCESS, StepStatus.ERROR -> {
                                    updateWebhookStatus(webhook.id, StepStatus.IDLE)
                                    webhookStateStore.clearWebhookState(webhook.id)
                                }
                                else -> webhookStateStore.clearWebhookState(webhook.id)
                            }
                        } else {
                            updateWebhookStatus(webhook.id, StepStatus.IDLE)
                            webhookStateStore.clearWebhookState(webhook.id)
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Error restaurando estados de webhooks: ${e.message}")
            }
        }
    }

    fun updateWebhookStatus(webhookId: Int, newStatus: StepStatus) {
        scope.launch(Dispatchers.IO) {
            try {
                val webhooks = configManager.loadWebhookDataFlow().first().toMutableList()
                val index = webhooks.indexOfFirst { it.id == webhookId }

                if (index != -1) {
                    webhooks[index] = webhooks[index].copy(status = newStatus)
                    configManager.saveWebhookData(webhooks)
                    Timber.d("Webhook $webhookId actualizado a estado $newStatus")
                }
            } catch (e: Exception) {
                Timber.e(e, "Error actualizando estado del webhook")
            }
        }
    }

    private fun distanceTo(first: GpsCoordinates, other: GpsCoordinates): Double {
        val lat1 = Math.toRadians(first.lat)
        val lon1 = Math.toRadians(first.lng)
        val lat2 = Math.toRadians(other.lat)
        val lon2 = Math.toRadians(other.lng)
        val dlat = lat2 - lat1
        val dlon = lon2 - lon1
        val a = sin(dlat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dlon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        val r = 6371.0
        val distance = r * c

        return distance
    }

    private suspend fun checkCurrentLocation(targetLocation: GpsCoordinates): Boolean {
        try {
            val currentLocation = karooSystem.getGpsFlow().first()
            val distance = distanceTo(currentLocation, targetLocation)
            return distance <= 0.070 // 70 meters
        } catch (e: Exception) {
            Timber.e(e, "Error al comprobar la ubicación: ${e.message}")
            return false
        }
    }

    fun scheduleResetToIdle(webhookId: Int, delayMillis: Long) {
        webhookStateStore.saveWebhookState(
            webhookId,
            StepStatus.FIRST.name,
            System.currentTimeMillis() + delayMillis
        )

        scope.launch(Dispatchers.IO) {
            delay(delayMillis)
            updateWebhookStatus(webhookId, StepStatus.IDLE)
            webhookStateStore.clearWebhookState(webhookId)
        }
    }

    fun executeWebhookWithStateTransitions(webhookId: Int) {
        scope.launch(Dispatchers.IO) {
            try {
                webhookStateStore.clearWebhookState(webhookId)
                updateWebhookStatus(webhookId, StepStatus.EXECUTING)

                delay(4_000)

                val statuscode = handleEvent("custom", webhookId)

                val finalState = if (statuscode) StepStatus.SUCCESS else StepStatus.ERROR
                val visibilityTime = if (statuscode) 4_000L else 5_000L

                updateWebhookStatus(webhookId, finalState)
                webhookStateStore.saveWebhookState(
                    webhookId,
                    finalState.name,
                    System.currentTimeMillis() + visibilityTime
                )

                delay(visibilityTime)
                delay(600)

                webhookStateStore.clearWebhookState(webhookId)
                updateWebhookStatus(webhookId, StepStatus.IDLE)

            } catch (e: Exception) {
                Timber.e(e, "Error al ejecutar webhook $webhookId: ${e.message}")

                updateWebhookStatus(webhookId, StepStatus.ERROR)
                webhookStateStore.saveWebhookState(
                    webhookId,
                    StepStatus.ERROR.name,
                    System.currentTimeMillis() + 10_000
                )

                delay(10_000)
                delay(600)

                webhookStateStore.clearWebhookState(webhookId)
                updateWebhookStatus(webhookId, StepStatus.IDLE)
            }
        }
    }

    suspend fun sendWebhook(config: WebhookData): Boolean {
        try {
            Timber.d("Enviando webhook a: ${config.url}")

            if (!config.url.startsWith("http")) {
                Timber.e("URL de webhook inválida: ${config.url}")
                return false
            }

            // Replace #dst# with remaining distance
            val remainingDistance = getRemainingDistance()
            val postBodyWithDistance = config.post.replace("#dst#", remainingDistance)

            val defaultHeaders = mapOf("Content-Type" to "application/json")
            val customHeaders = if (config.header.isNotBlank()) {
                config.header.split("\n").mapNotNull { line ->
                    val parts = line.split(":", limit = 2)
                    if (parts.size == 2) {
                        parts[0].trim() to parts[1].trim()
                    } else null
                }.toMap()
            } else emptyMap()

            val headers = defaultHeaders + customHeaders
            val contentType = customHeaders["Content-Type"]?.lowercase()

            val body = when {
                postBodyWithDistance.isBlank() -> null
                contentType?.contains("json") == true -> {
                    if (postBodyWithDistance.trim().startsWith("{") && postBodyWithDistance.trim().endsWith("}")) {
                        postBodyWithDistance.toByteArray()
                    } else {
                        try {
                            val jsonObject = buildJsonObject {
                                postBodyWithDistance.split("\n").forEach { line ->
                                    val parts = line.split(":", limit = 2)
                                    if (parts.size == 2) {
                                        put(parts[0].trim(), JsonPrimitive(parts[1].trim()))
                                    }
                                }
                            }
                            jsonObject.toString().toByteArray()
                        } catch (e: Exception) {
                            postBodyWithDistance.toByteArray()
                        }
                    }
                }
                else -> postBodyWithDistance.toByteArray()
            }

            val response = karooSystem.makeHttpRequest(
                method = "POST",
                url = config.url,
                headers = headers,
                body = body
            ).first()

            val success = response.statusCode in 200..299

            if (success) {
                Timber.d("Webhook enviado correctamente a: ${config.url}")
            } else {
                Timber.e("Error enviando webhook: ${response.statusCode}")
            }

            return success
        } catch (e: Exception) {
            Timber.e(e, "Error enviando webhook")
            return false
        }
    }
}