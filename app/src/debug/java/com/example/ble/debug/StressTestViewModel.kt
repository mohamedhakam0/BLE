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
            val loaded = contactRepository.getAllContacts()
            _contacts.value = loaded.map { c ->
                StressContact(
                    displayName = c.nickname.ifBlank { c.senderId.take(8) },
                    contactId = c.senderId,
                    senderIdHex = c.senderId
                )
            }
            if (_contacts.value.isNotEmpty()) {
                _selectedContact.value = _contacts.value.first()
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