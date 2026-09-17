package com.reelpulse.app.auth

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Mock AuthManager to allow the app to run without a google-services.json file.
 * In a production app, this would use Firebase Auth.
 */
object AuthManager {
    private val _userEmail = MutableStateFlow<String?>(null)
    val user: StateFlow<String?> = _userEmail

    fun init(context: Context) {
        val prefs = context.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
        _userEmail.value = prefs.getString("user_email", null)
    }

    val isLoggedIn: Boolean
        get() = _userEmail.value != null

    fun signIn(context: Context, email: String) {
        val prefs = context.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("user_email", email).apply()
        _userEmail.value = email
    }

    fun signOut(context: Context) {
        val prefs = context.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
        prefs.edit().remove("user_email").apply()
        _userEmail.value = null
    }
}
