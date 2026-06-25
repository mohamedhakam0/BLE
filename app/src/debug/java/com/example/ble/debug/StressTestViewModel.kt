package com.example.ble.debug

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.ble.BleAdvertiser
import com.example.ble.ContactRepository
import com.example.ble.MessageRepository
import com.example.ble.NodeIdentity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@DebugOnly
data class StressContact(
    val displayName: String,
    val contactId: String,
    val senderIdHex: String
)

@DebugOnly
class StressTestViewModel(
    app: Application,
    private val nodeIdentity: NodeIdentity,
    private val advertiser: BleAdvertiser,
    private val messageRepository: MessageRepository,
    private val contactRepository: ContactRepository
) : AndroidViewModel(app) {

    private val _contacts = MutableStateFlow<List<StressContact>>(emptyList())
    val contacts: StateFlow<List<StressContact>> = _contacts.asStateFlow()

    private val _selectedContact = MutableStateFlow<StressContact?>(null)
    val selectedContact: StateFlow<StressContact?> = _selectedContact.asStateFlow()

    val isRunning = StressTestManager.isRunning
    val progress = StressTestManager.progress
    val results = StressTestManager.results
    val currentLabel = StressTestManager.currentLabel

    init {
        viewModelScope.launch {
            contactRepository.observeContactsWithLastMessage().collectLatest { rows ->
                // Detect duplicate base names so we can disambiguate them.
                val nameCounts = rows.groupingBy { it.nickname.trim().ifBlank { it.senderId.take(8) } }.eachCount()

                val mapped = rows.map { row ->
                    val baseName = row.nickname.trim().ifBlank { row.senderId.take(8) }
                    // Append last 4 hex chars of senderId when two contacts share a display name.
                    val displayName = if ((nameCounts[baseName] ?: 0) > 1)
                        "$baseName (…${row.senderId.takeLast(4)})"
                    else baseName
                    StressContact(
                        displayName = displayName,
                        contactId = row.senderId,
                        senderIdHex = row.senderId
                    )
                }

                _contacts.value = mapped

                // Keep the current selection if the contact still exists; otherwise reset.
                val currentId = _selectedContact.value?.contactId
                _selectedContact.value = when {
                    currentId != null && mapped.any { it.contactId == currentId } ->
                        mapped.first { it.contactId == currentId }
                    mapped.isNotEmpty() -> mapped.first()
                    else -> null
                }
            }
        }
    }

    fun selectContact(contact: StressContact) {
        _selectedContact.value = contact
    }

    fun startUnidirectional(count: Int, intervalMs: Long) {
        val contact = _selectedContact.value ?: return
        StressTestManager.start(
            context = getApplication(),
            nodeIdentity = nodeIdentity,
            advertiser = advertiser,
            messageRepository = messageRepository,
            contactId = contact.contactId,
            contactSenderIdHex = contact.senderIdHex,
            messageCount = count,
            intervalMs = intervalMs,
            testLabel = "UNI"
        )
    }

    fun startBidirectional(count: Int, intervalMs: Long) {
        val contact = _selectedContact.value ?: return
        StressTestManager.start(
            context = getApplication(),
            nodeIdentity = nodeIdentity,
            advertiser = advertiser,
            messageRepository = messageRepository,
            contactId = contact.contactId,
            contactSenderIdHex = contact.senderIdHex,
            messageCount = count,
            intervalMs = intervalMs,
            testLabel = "BI"
        )
    }

    fun stop() {
        StressTestManager.stop()
    }

    @DebugOnly
    class Factory(
        private val application: Application,
        private val nodeIdentity: NodeIdentity,
        private val advertiser: BleAdvertiser,
        private val messageRepository: MessageRepository,
        private val contactRepository: ContactRepository
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(StressTestViewModel::class.java)) {
                "Unknown ViewModel class: ${modelClass.name}"
            }
            @Suppress("UNCHECKED_CAST")
            return StressTestViewModel(
                app = application,
                nodeIdentity = nodeIdentity,
                advertiser = advertiser,
                messageRepository = messageRepository,
                contactRepository = contactRepository
            ) as T
        }
    }
}