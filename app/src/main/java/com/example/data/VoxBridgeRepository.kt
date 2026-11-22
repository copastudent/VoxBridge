package com.example.data

import android.content.Context
import com.example.R
import com.example.firebase.OperationType
import com.example.firebase.handleFirestoreError
import com.example.model.CustomPhrase
import com.example.model.DictationEntry
import com.example.model.ModelStatus
import com.example.model.SpeechLanguage
import com.example.model.SpeechRecording
import com.example.model.UserProfile
import com.example.model.VoiceArchitecture
import com.example.model.VoiceModelProfile
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class VoxBridgeRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun requireUserId(): String {
        return auth.currentUser?.uid?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    private fun userDoc(uid: String) = db.collection("users").document(uid)

    fun observeUserProfile(userId: String): Flow<UserProfile?> = flow {
        require(userId.isNotBlank()) { "User ID must not be blank" }
        val docRef = userDoc(userId)
        emitAll(
            docRef.snapshots()
                .map { snapshot ->
                    if (snapshot.exists()) {
                        snapshot.toObject(
                            UserProfile::class.java,
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                        )
                    } else null
                }
                .catch { error ->
                    handleFlowError(error, OperationType.GET, docRef.path, userId)
                }
        )
    }

    suspend fun ensureUserProfile(displayName: String): Result<Unit> {
        var docPath = "users/unknown"
        return try {
            val uid = requireUserId()
            val docRef = userDoc(uid)
            docPath = docRef.path
            val existing = docRef.get().await()
            if (!existing.exists()) {
                val safeName = displayName.trim().ifBlank { "VoxBridge Speaker" }.take(100)
                val payload = mapOf(
                    "userId" to uid,
                    "displayName" to safeName,
                    "preferredLanguage" to SpeechLanguage.ENGLISH.code,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.set(payload).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun updateUserPreferences(
        preferredLanguage: SpeechLanguage? = null,
        activeModelId: String? = null
    ): Result<Unit> {
        var docPath = "users/unknown"
        return try {
            val uid = requireUserId()
            val docRef = userDoc(uid)
            docPath = docRef.path
            val updates = mutableMapOf<String, Any>(
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (preferredLanguage != null) {
                updates["preferredLanguage"] = preferredLanguage.code
            }
            if (activeModelId != null) {
                require(activeModelId.length <= 128)
                updates["activeModelId"] = activeModelId
            }
            docRef.update(updates).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    fun observeCustomPhrases(userId: String): Flow<List<CustomPhrase>> = flow {
        require(userId.isNotBlank()) { "User ID must not be blank" }
        val colRef = userDoc(userId).collection("custom_phrases")
        emitAll(
            colRef.orderBy("createdAt", Query.Direction.DESCENDING)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        CustomPhrase::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    handleFlowError(error, OperationType.LIST, colRef.path, userId)
                }
        )
    }

    suspend fun addCustomPhrase(
        text: String,
        language: SpeechLanguage,
        category: String = "Custom"
    ): Result<Unit> {
        var docPath = "users/unknown/custom_phrases"
        return try {
            val uid = requireUserId()
            val cleanText = text.trim()
            require(cleanText.isNotEmpty() && cleanText.length <= 200) {
                "Phrase must be between 1 and 200 characters."
            }
            val cleanCat = category.trim().ifEmpty { "Custom" }.take(60)
            val docRef = userDoc(uid).collection("custom_phrases").document()
            docPath = docRef.path
            val payload = mapOf(
                "userId" to uid,
                "text" to cleanText,
                "language" to language.code,
                "category" to cleanCat,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            docRef.set(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun deleteCustomPhrase(phraseId: String): Result<Unit> {
        var docPath = "users/unknown/custom_phrases/$phraseId"
        return try {
            val uid = requireUserId()
            require(phraseId.isNotBlank())
            val docRef = userDoc(uid).collection("custom_phrases").document(phraseId)
            docPath = docRef.path
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }

    fun observeRecordings(userId: String): Flow<List<SpeechRecording>> = flow {
        require(userId.isNotBlank()) { "User ID must not be blank" }
        val colRef = userDoc(userId).collection("recordings")
        emitAll(
            colRef.orderBy("createdAt", Query.Direction.DESCENDING)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        SpeechRecording::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    handleFlowError(error, OperationType.LIST, colRef.path, userId)
                }
        )
    }

    fun newRecordingDocId(): String {
        val uid = requireUserId()
        return userDoc(uid).collection("recordings").document().id
    }

    suspend fun saveRecording(
        recordingId: String,
        phraseKey: String,
        phraseText: String,
        language: SpeechLanguage,
        localFilePath: String,
        durationMs: Long,
        sampleRateHz: Long,
        peakAmplitude: Long,
        phoneticNotes: String = ""
    ): Result<Unit> {
        var docPath = "users/unknown/recordings/$recordingId"
        return try {
            val uid = requireUserId()
            require(recordingId.isNotBlank() && recordingId.length <= 128)
            require(phraseKey.isNotBlank() && phraseKey.length <= 128)
            require(phraseText.isNotBlank() && phraseText.length <= 240)
            require(localFilePath.isNotBlank() && localFilePath.length <= 512)
            val docRef = userDoc(uid).collection("recordings").document(recordingId)
            docPath = docRef.path
            val payload = mutableMapOf<String, Any>(
                "userId" to uid,
                "phraseKey" to phraseKey.take(128),
                "phraseText" to phraseText.take(240),
                "language" to language.code,
                "localFilePath" to localFilePath.take(512),
                "durationMs" to durationMs.coerceIn(0L, 600_000L),
                "sampleRateHz" to sampleRateHz.coerceIn(8000L, 96000L),
                "peakAmplitude" to peakAmplitude.coerceIn(0L, 65536L),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (phoneticNotes.isNotBlank()) {
                payload["phoneticNotes"] = phoneticNotes.take(500)
            }
            docRef.set(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun deleteRecording(recordingId: String): Result<Unit> {
        var docPath = "users/unknown/recordings/$recordingId"
        return try {
            val uid = requireUserId()
            require(recordingId.isNotBlank())
            val docRef = userDoc(uid).collection("recordings").document(recordingId)
            docPath = docRef.path
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }

    fun observeVoiceModels(userId: String): Flow<List<VoiceModelProfile>> = flow {
        require(userId.isNotBlank()) { "User ID must not be blank" }
        val colRef = userDoc(userId).collection("voice_models")
        emitAll(
            colRef.orderBy("createdAt", Query.Direction.DESCENDING)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        VoiceModelProfile::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    handleFlowError(error, OperationType.LIST, colRef.path, userId)
                }
        )
    }

    suspend fun createVoiceModel(
        name: String,
        architecture: VoiceArchitecture,
        language: SpeechLanguage,
        trainingSampleCount: Long,
        phoneticProfileSummary: String,
        adaptationPrompt: String,
        articulationPatterns: List<String>,
        confidenceScore: Long
    ): Result<String> {
        var docPath = "users/unknown/voice_models"
        return try {
            val uid = requireUserId()
            val cleanName = name.trim().ifEmpty { architecture.displayName }.take(80)
            val safePatterns = articulationPatterns
                .map { it.trim().take(200) }
                .filter { it.isNotEmpty() }
                .take(15)
            val docRef = userDoc(uid).collection("voice_models").document()
            docPath = docRef.path
            val payload = mapOf(
                "userId" to uid,
                "name" to cleanName,
                "architecture" to architecture.name,
                "geminiModelId" to architecture.geminiModelId.take(80),
                "language" to language.code,
                "trainingSampleCount" to trainingSampleCount.coerceIn(0L, 10_000L),
                "phoneticProfileSummary" to phoneticProfileSummary.take(4000),
                "adaptationPrompt" to adaptationPrompt.take(8000),
                "articulationPatterns" to safePatterns,
                "confidenceScore" to confidenceScore.coerceIn(0L, 100L),
                "status" to ModelStatus.READY.name,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            docRef.set(payload).await()
            Result.success(docRef.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun renameVoiceModel(modelId: String, newName: String): Result<Unit> {
        var docPath = "users/unknown/voice_models/$modelId"
        return try {
            val uid = requireUserId()
            val cleanName = newName.trim()
            require(modelId.isNotBlank())
            require(cleanName.isNotEmpty() && cleanName.length <= 80) {
                "Model name must be between 1 and 80 characters."
            }
            val docRef = userDoc(uid).collection("voice_models").document(modelId)
            docPath = docRef.path
            docRef.update(
                mapOf(
                    "name" to cleanName,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun deleteVoiceModel(modelId: String): Result<Unit> {
        var docPath = "users/unknown/voice_models/$modelId"
        return try {
            val uid = requireUserId()
            require(modelId.isNotBlank())
            val docRef = userDoc(uid).collection("voice_models").document(modelId)
            docPath = docRef.path
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }

    fun observeDictations(userId: String): Flow<List<DictationEntry>> = flow {
        require(userId.isNotBlank()) { "User ID must not be blank" }
        val colRef = userDoc(userId).collection("dictations")
        emitAll(
            colRef.orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(50)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        DictationEntry::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    handleFlowError(error, OperationType.LIST, colRef.path, userId)
                }
        )
    }

    suspend fun saveDictation(
        modelId: String,
        modelName: String,
        language: SpeechLanguage,
        rawTranscript: String,
        adaptedText: String,
        spokenAloud: Boolean,
        latencyMs: Long
    ): Result<String> {
        var docPath = "users/unknown/dictations"
        return try {
            val uid = requireUserId()
            val cleanAdapted = adaptedText.trim()
            require(cleanAdapted.isNotEmpty()) { "Dictation text cannot be empty." }
            val docRef = userDoc(uid).collection("dictations").document()
            docPath = docRef.path
            val payload = mapOf(
                "userId" to uid,
                "modelId" to modelId.ifBlank { "default_model" }.take(128),
                "modelName" to modelName.ifBlank { "Adaptive Core" }.take(80),
                "language" to language.code,
                "rawTranscript" to rawTranscript.take(4000),
                "adaptedText" to cleanAdapted.take(4000),
                "spokenAloud" to spokenAloud,
                "latencyMs" to latencyMs.coerceIn(0L, 300_000L),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            docRef.set(payload).await()
            Result.success(docRef.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun markDictationSpoken(dictationId: String): Result<Unit> {
        var docPath = "users/unknown/dictations/$dictationId"
        return try {
            val uid = requireUserId()
            require(dictationId.isNotBlank())
            val docRef = userDoc(uid).collection("dictations").document(dictationId)
            docPath = docRef.path
            docRef.update(
                mapOf(
                    "spokenAloud" to true,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun deleteDictation(dictationId: String): Result<Unit> {
        var docPath = "users/unknown/dictations/$dictationId"
        return try {
            val uid = requireUserId()
            require(dictationId.isNotBlank())
            val docRef = userDoc(uid).collection("dictations").document(dictationId)
            docPath = docRef.path
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }

    private suspend fun handleFlowError(
        error: Throwable,
        operationType: OperationType,
        path: String,
        userId: String
    ): Nothing {
        val firestoreError = generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) {
            throw error
        }
        val exceptionToLog = firestoreError ?: (error as? Exception)
        if (exceptionToLog != null && auth.currentUser?.uid == userId) {
            handleFirestoreError(exceptionToLog, operationType, path)
        }
        throw error
    }
}
