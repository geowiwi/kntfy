package com.geowiwi.kntfy.screens


import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.geowiwi.kntfy.R
import com.geowiwi.kntfy.data.ConfigData
import com.geowiwi.kntfy.data.ProviderType
import com.geowiwi.kntfy.extension.Sender
import com.geowiwi.kntfy.extension.managers.ConfigurationManager
import io.hammerhead.karooext.KarooSystemService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import com.geowiwi.kntfy.data.customMessage
import timber.log.Timber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigurationScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val karooSystem = remember { KarooSystemService(context) }
    val configManager = remember { ConfigurationManager(context) }
    var karooConnected by remember { mutableStateOf(false) }
    var isConnecting by remember { mutableStateOf(false) }

    var isActive by remember { mutableStateOf(true) }
    var karooKey by remember { mutableStateOf("") }

    var phoneNumber1 by remember { mutableStateOf("") }
    var isPhone1Valid by remember { mutableStateOf(true) }
    var phone1ErrorMessage by remember { mutableStateOf("") }
    var startMessage by remember { mutableStateOf("") }
    var stopMessage by remember { mutableStateOf("") }
    var customMessage1 by remember { mutableStateOf(customMessage()) }
    var pauseMessage by remember { mutableStateOf("") }
    var resumeMessage by remember { mutableStateOf("") }
    var actionOnStart by remember { mutableStateOf(true) }
    var actionOnStop by remember { mutableStateOf(false) }
    var indoorMode by remember { mutableStateOf(false) }

    var config by remember { mutableStateOf<ConfigData?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var ignoreAutoSave by remember { mutableStateOf(true) }
    var isLoading by remember { mutableStateOf(false) }

    var selectedProvider by remember { mutableStateOf(ProviderType.TEXTBELT) }

    var delayBetweenNotificationsInt by remember { mutableStateOf("0") }

    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    val errorNoPlus = stringResource(R.string.error_no_plus)
    val errorDigitsOnly = stringResource(R.string.error_digits_only)
    val errorLength = stringResource(R.string.error_length)
    val settingsSaved = stringResource(R.string.settings_saved)
    val cannotSaveMessage = stringResource(R.string.cannot_save_invalid_format)
    val sender = remember { Sender(karooSystem = karooSystem, configManager = configManager) }
    val test_message_content = stringResource(R.string.test_message_content)
    val error_sending_message = stringResource(R.string.error_sending_message)
    val test_message_sent = stringResource(R.string.test_message_sent)

    LaunchedEffect(Unit) {
        launch {
            delay(500)
            Timber.d("Estableciendo ignoreAutoSave a false")
            ignoreAutoSave = false
        }
        launch {
            isConnecting = true
            karooSystem.connect { connected ->
                karooConnected = connected
                isConnecting = false
                Timber.d(if (connected) "Conectado a Karoo System" else "Error conectando a Karoo System")
            }
        }

        launch {
            configManager.loadPreferencesFlow().collect { configs ->
                if (configs.isNotEmpty()) {
                    val savedConfig = configs.first()
                    config = savedConfig

                    isActive = savedConfig.isActive
                    karooKey = savedConfig.karooKey
                    delayBetweenNotificationsInt = savedConfig.delayIntents.toInt().toString()
                    actionOnStart = savedConfig.notifyOnStart
                    actionOnStop = savedConfig.notifyOnStop
                    indoorMode = savedConfig.indoorMode

                    selectedProvider = savedConfig.activeProvider

                    val phoneNumbers = savedConfig.phoneNumbers
                    if (phoneNumbers.isNotEmpty()) {
                        phoneNumber1 = phoneNumbers[0]
                    }

                    startMessage = savedConfig.startMessage
                    stopMessage = savedConfig.stopMessage
                    pauseMessage = savedConfig.pauseMessage
                    resumeMessage = savedConfig.resumeMessage
                    customMessage1 = savedConfig.customMessage1
                }
            }
        }
    }

    fun validatePhoneNumber(phoneNumber: String): Pair<Boolean, String> {
        val trimmedPhone = phoneNumber.trim()

        if (trimmedPhone.isEmpty()) {
            return Pair(true, "")
        }

        if (!trimmedPhone.startsWith("+")) {
            return Pair(false, errorNoPlus)
        }

        if (!trimmedPhone.substring(1).all { it.isDigit() }) {
            return Pair(false, errorDigitsOnly)
        }

        if (trimmedPhone.length < 8 || trimmedPhone.length > 16) {
            return Pair(false, errorLength)
        }

        return Pair(true, trimmedPhone.removePrefix("+"))
    }

    fun sendTestMessage() {
        if (phoneNumber1.isBlank()) {
            statusMessage = "Error: Se requiere un número de teléfono"
            return
        }

        scope.launch {
            isLoading = true
            statusMessage = null

            try {
                val success = when (selectedProvider) {
                    ProviderType.CALLMEBOT -> sender.sendMessage(
                        phoneNumber = phoneNumber1,
                        message = test_message_content,
                        senderProvider = ProviderType.CALLMEBOT
                    )
                    ProviderType.WHAPI -> sender.sendMessage(
                        phoneNumber = phoneNumber1,
                        message = test_message_content,
                        senderProvider = ProviderType.WHAPI
                    )
                    ProviderType.TEXTBELT -> sender.sendSMSMessage(
                        phoneNumber = phoneNumber1,
                        message = test_message_content
                    )
                    else -> false
                }

                statusMessage = if (success) test_message_sent
                else error_sending_message
            } catch (e: Exception) {
                statusMessage = "Error: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    fun saveData() {
        Timber.d("ConfigurationScreen: saveData llamado, ignoreAutoSave=$ignoreAutoSave")
        if (ignoreAutoSave) {
            Timber.d("ConfigurationScreen: Ignorando guardado (ignoreAutoSave=true)")
            return
        }

        val phone1Result = validatePhoneNumber(phoneNumber1)
        isPhone1Valid = phone1Result.first
        phone1ErrorMessage = phone1Result.second

        val isPhoneConfigValid = phoneNumber1.isNotBlank() && isPhone1Valid

        if (isPhoneConfigValid) {
            scope.launch {
                isLoading = true
                statusMessage = null

                try {
                    Timber.d("ConfigurationScreen: Guardando provider: $selectedProvider")
                    Timber.d("ConfigurationScreen: Guardando teléfono: $phoneNumber1")

                    val phoneNumbers = listOfNotNull(
                        phoneNumber1.trim().takeIf { it.isNotBlank() }
                    )

                    val updatedConfig = config?.copy(
                        isActive = true,
                        karooKey = karooKey.trim(),
                        delayIntents = delayBetweenNotificationsInt.toIntOrNull()?.toDouble() ?: 0.0,
                        phoneNumbers = phoneNumbers,
                        emails = emptyList(),
                        startMessage = startMessage.trim(),
                        stopMessage = stopMessage.trim(),
                        notifyOnStart = actionOnStart,
                        notifyOnStop = actionOnStop,
                        pauseMessage = pauseMessage.trim(),
                        resumeMessage = resumeMessage.trim(),
                        activeProvider = selectedProvider,
                        emailFrom = "",
                        indoorMode = indoorMode,
                        customMessage1 = customMessage1,
                    ) ?: ConfigData(
                        isActive = true,
                        karooKey = karooKey.trim(),
                        delayIntents = delayBetweenNotificationsInt.toIntOrNull()?.toDouble() ?: 0.0,
                        phoneNumbers = phoneNumbers,
                        emails = emptyList(),
                        startMessage = startMessage.trim(),
                        stopMessage = stopMessage.trim(),
                        notifyOnStart = actionOnStart,
                        notifyOnStop = actionOnStop,
                        pauseMessage = pauseMessage.trim(),
                        resumeMessage = resumeMessage.trim(),
                        activeProvider = selectedProvider,
                        emailFrom = "",
                        indoorMode = indoorMode,
                        customMessage1 = customMessage1,
                    )

                    configManager.savePreferences(mutableListOf(updatedConfig))
                    statusMessage = settingsSaved
                    delay(2000)
                    statusMessage = null
                } catch (e: Exception) {
                    Timber.e(e, "ConfigurationScreen: Error guardando: ${e.message}")
                    statusMessage = "Error: ${e.message}"
                } finally {
                    isLoading = false
                }
            }
        } else {
            statusMessage = cannotSaveMessage

            scope.launch {
                delay(2000)
                statusMessage = null
            }
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            Card {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        stringResource(R.string.message_templates),
                        style = MaterialTheme.typography.titleMedium
                    )

                    Text(
                        stringResource(R.string.configure_messages),
                        style = MaterialTheme.typography.bodyMedium
                    )

                    OutlinedTextField(
                        value = startMessage,
                        onValueChange = { startMessage = it },
                        label = { Text(stringResource(R.string.start_message)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { if (!it.isFocused) saveData() },
                        minLines = 2,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            focusManager.clearFocus()
                            keyboardController?.hide()
                            saveData() })
                    )

                    OutlinedTextField(
                        value = stopMessage,
                        onValueChange = { stopMessage = it },
                        label = { Text(stringResource(R.string.stop_message)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { if (!it.isFocused) saveData() },
                        minLines = 2,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            focusManager.clearFocus()
                            keyboardController?.hide()
                            saveData() })
                    )
                }
            }

            Card {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        stringResource(R.string.custom_messages),
                        style = MaterialTheme.typography.titleMedium
                    )

                    Text(
                        stringResource(R.string.configure_custom_messages),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = customMessage1.message,
                        onValueChange = { newValue ->
                            customMessage1 = customMessage1.copy(message = newValue, name="", isdistance = true)
                            saveData()
                        },
                        label = { Text(stringResource(R.string.custom_message_content)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { if (!it.isFocused) saveData() },
                        minLines = 2,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            focusManager.clearFocus()
                            keyboardController?.hide()
                            saveData()
                        })
                    )

                    Text(
                        stringResource(R.string.include_distance),
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Card {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        stringResource(R.string.notification_settings),
                        style = MaterialTheme.typography.titleMedium
                    )

                    OutlinedTextField(
                        value = delayBetweenNotificationsInt,
                        onValueChange = {
                            if (it.isEmpty() || it.all { char -> char.isDigit() }) {
                                delayBetweenNotificationsInt = it
                                saveData()
                            }
                        },
                        label = { Text(stringResource(R.string.notification_delay)) },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = {
                            focusManager.clearFocus()
                            keyboardController?.hide()
                            saveData()
                        })
                    )

                    Text(
                        stringResource(R.string.send_notifications_when),
                        style = MaterialTheme.typography.titleMedium
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            stringResource(R.string.webhook_on_start),
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = actionOnStart,
                            onCheckedChange = {
                                actionOnStart = it
                                saveData()
                            }
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            stringResource(R.string.webhook_on_stop),
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = actionOnStop,
                            onCheckedChange = {
                                actionOnStop = it
                                saveData()
                            }
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            stringResource(R.string.indoor_mode),
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = indoorMode,
                            onCheckedChange = {
                                indoorMode = it
                                saveData()
                            }
                        )
                    }
                }
            }

            Card {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        stringResource(R.string.phone_numbers),
                        style = MaterialTheme.typography.titleMedium
                    )

                    Text(
                        stringResource(R.string.enter_up_to_3_phones),
                        style = MaterialTheme.typography.bodyMedium
                    )

                    PhoneNumberInput(
                        value = phoneNumber1,
                        onValueChange = {
                            phoneNumber1 = it
                            val (valid, message) = validatePhoneNumber(it)
                            isPhone1Valid = valid
                            phone1ErrorMessage = message
                        },
                        label = stringResource(R.string.number_1),
                        isValid = isPhone1Valid,
                        errorMessage = phone1ErrorMessage,
                        onClear = {
                            phoneNumber1 = ""
                            isPhone1Valid = true
                            phone1ErrorMessage = ""
                            saveData()
                        },
                        onDone = { saveData() },
                        onFocusChange = { focusState ->
                            if (!focusState.isFocused) saveData()
                        }
                    )
                }
            }

            Card {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        stringResource(R.string.karoo_configuration),
                        style = MaterialTheme.typography.titleMedium
                    )

                    Text(stringResource(R.string.enter_karoo_live_key))

                    OutlinedTextField(
                        value = karooKey,
                        onValueChange = { karooKey = it },
                        label = { Text(stringResource(R.string.karoo_key)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { if (!it.isFocused) saveData() },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            focusManager.clearFocus()
                            keyboardController?.hide()
                            saveData() })
                    )
                }
            }

            Card {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        stringResource(R.string.test_sending),
                        style = MaterialTheme.typography.titleMedium
                    )

                    Text(
                        stringResource(R.string.send_to) + phoneNumber1,
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Button(
                        onClick = { sendTestMessage() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isLoading && isPhone1Valid && phoneNumber1.isNotBlank()
                    ) {
                        Text(stringResource(R.string.send_test_message))
                    }
                }
            }

            if (isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            statusMessage?.let { message ->
                Text(
                    text = message,
                    color = if (message.contains("Error"))
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
fun PhoneNumberInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    isValid: Boolean,
    errorMessage: String,
    onClear: () -> Unit,
    onDone: () -> Unit,
    onFocusChange: (FocusState) -> Unit
) {
    val phonePlaceholder = stringResource(R.string.phone_placeholder)
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(phonePlaceholder) },
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged(onFocusChange),
        singleLine = true,
        isError = !isValid,
        supportingText = {
            if (!isValid) {
                Text(errorMessage)
            }
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Phone,
            imeAction = ImeAction.Done
        ),
        keyboardActions = KeyboardActions(
            onDone = {
                focusManager.clearFocus()
                keyboardController?.hide()
                onDone()
            }
        ),
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(Icons.Default.Delete, contentDescription = "Clear")
                }
            }
        }
    )
}