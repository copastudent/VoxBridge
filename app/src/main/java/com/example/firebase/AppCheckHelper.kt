package com.example.firebase

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.recaptcha.RecaptchaAppCheckProviderFactory

private const val TAG = "AppCheckInit"

private const val DEBUG_TOKEN_EXTRA = "FIREBASE_APPCHECK_DEBUG_TOKEN"
private const val DEBUG_STORE_PREFIX = "com.google.firebase.appcheck.debug.store."
private const val DEBUG_SECRET_KEY = "com.google.firebase.appcheck.debug.DEBUG_SECRET"
private const val META_PREFS_NAME = "voxbridge_appcheck_meta"
private const val KEY_HARNESS_DEBUG_TOKEN = "has_harness_debug_token"
private const val RECAPTCHA_SITE_KEY_RES = "recaptcha_site_key"

internal enum class AppCheckInstallMode {
    HARNESS_DEBUG_TOKEN,
    RECAPTCHA,
    UNREGISTERED_DEBUG_FALLBACK,
    MISSING_SITE_KEY
}

internal fun resolveAppCheckMode(
    isDebugBuild: Boolean,
    intentDebugToken: String?,
    hasHarnessDebugToken: Boolean,
    storedDebugSecret: String?,
    recaptchaSiteKey: String?
): AppCheckInstallMode {
    if (isDebugBuild) {
        if (!intentDebugToken.isNullOrBlank()) {
            return AppCheckInstallMode.HARNESS_DEBUG_TOKEN
        }
        if (hasHarnessDebugToken && !storedDebugSecret.isNullOrBlank()) {
            return AppCheckInstallMode.HARNESS_DEBUG_TOKEN
        }
    }
    return when {
        !recaptchaSiteKey.isNullOrBlank() -> AppCheckInstallMode.RECAPTCHA
        isDebugBuild -> AppCheckInstallMode.UNREGISTERED_DEBUG_FALLBACK
        else -> AppCheckInstallMode.MISSING_SITE_KEY
    }
}

fun configureAppCheck(context: Context, intent: Intent?) {
    try {
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(context)
        }

        val firebaseApp = FirebaseApp.getInstance()
        val persistenceKey = firebaseApp.persistenceKey
        val debugPrefs = context.getSharedPreferences(
            "$DEBUG_STORE_PREFIX$persistenceKey",
            Context.MODE_PRIVATE
        )
        val metaPrefs = context.getSharedPreferences(META_PREFS_NAME, Context.MODE_PRIVATE)

        val intentDebugToken = intent?.getStringExtra(DEBUG_TOKEN_EXTRA)?.takeIf { it.isNotBlank() }
        val hasHarnessDebugToken = metaPrefs.getBoolean(KEY_HARNESS_DEBUG_TOKEN, false)
        val storedDebugSecret = debugPrefs.getString(DEBUG_SECRET_KEY, null)

        val resId = context.resources.getIdentifier(
            RECAPTCHA_SITE_KEY_RES,
            "string",
            context.packageName
        )
        val recaptchaSiteKey = if (resId != 0) context.getString(resId).trim() else null

        when (
            resolveAppCheckMode(
                isDebugBuild = BuildConfig.DEBUG,
                intentDebugToken = intentDebugToken,
                hasHarnessDebugToken = hasHarnessDebugToken,
                storedDebugSecret = storedDebugSecret,
                recaptchaSiteKey = recaptchaSiteKey
            )
        ) {
            AppCheckInstallMode.HARNESS_DEBUG_TOKEN -> {
                if (intentDebugToken != null) {
                    // Synchronous commit() ensures the debug token is persisted before factory install
                    debugPrefs.edit()
                        .putString(DEBUG_SECRET_KEY, intentDebugToken)
                        .commit()
                    metaPrefs.edit()
                        .putBoolean(KEY_HARNESS_DEBUG_TOKEN, true)
                        .commit()
                    Log.d(TAG, "App Check harness debug secret configured synchronously.")
                }
                FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                    DebugAppCheckProviderFactory.getInstance()
                )
                Log.d(TAG, "DebugAppCheckProviderFactory installed from harness debug token.")
            }

            AppCheckInstallMode.RECAPTCHA -> {
                val siteKey = requireNotNull(recaptchaSiteKey)
                FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                    RecaptchaAppCheckProviderFactory.getInstance(siteKey)
                )
                Log.d(TAG, "RecaptchaAppCheckProviderFactory installed.")
            }

            AppCheckInstallMode.UNREGISTERED_DEBUG_FALLBACK -> {
                Log.w(TAG, "No debug token or recaptcha_site_key found; installing DebugAppCheckProviderFactory.")
                FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                    DebugAppCheckProviderFactory.getInstance()
                )
            }

            AppCheckInstallMode.MISSING_SITE_KEY -> {
                Log.e(TAG, "recaptcha_site_key string resource is missing or blank.")
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to initialize Firebase App Check", e)
    }
}
