package com.example.data.cloud

import android.util.Log
import com.example.BuildConfig
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

enum class OperationType(val value: String) {
    CREATE("create"),
    UPDATE("update"),
    DELETE("delete"),
    LIST("list"),
    GET("get"),
    WRITE("write"),
}

fun handleFirestoreError(
    exception: Throwable,
    operationType: OperationType,
    path: String?,
): String {
    val causes = generateSequence(exception) { it.cause }
    val rootError = causes.firstOrNull { it is FirebaseFirestoreException }
        ?: causes.firstOrNull { it !is CancellationException }
        ?: exception

    val currentUser = Firebase.auth.currentUser
    val providerIds = currentUser?.providerData?.map { it.providerId }.orEmpty()

    val authInfoJson = JSONObject().apply {
        put("authenticated", currentUser != null)
        put("emailVerified", currentUser?.isEmailVerified == true)
        put("providers", JSONArray(providerIds))
    }

    val sanitizedPath = path?.replace(Regex("^users/[^/]+"), "users/[user]")
    val errorSummary = (rootError as? FirebaseFirestoreException)?.code?.name
        ?: rootError::class.java.simpleName

    val errorInfoJson = JSONObject().apply {
        put("errorCode", errorSummary)
        put("operationType", operationType.value)
        put("path", sanitizedPath)
        put("authInfo", authInfoJson)
    }

    val jsonString = errorInfoJson.toString()
    if (BuildConfig.DEBUG) {
        Log.e("FirestoreError", "Firestore Error: $jsonString")
    }
    return jsonString
}
