package com.bitchat.android.meshtastic

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages Meshtastic network preferences
 */
object MeshPreferenceManager {
    
    private const val PREFS_NAME = "mesh_preferences"
    private const val KEY_MESH_ENABLED = "mesh_enabled"
    
    // Default values
    private const val DEFAULT_MESH_ENABLED = false
    
    // State flows for reactive UI
    private val _meshEnabled = MutableStateFlow(DEFAULT_MESH_ENABLED)
    val meshEnabled: StateFlow<Boolean> = _meshEnabled.asStateFlow()
    
    private lateinit var sharedPrefs: SharedPreferences
    private var isInitialized = false
    
    /**
     * Initialize the preference manager with application context
     */
    fun init(context: Context) {
        if (isInitialized) return
        
        sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        // Load current values
        _meshEnabled.value = sharedPrefs.getBoolean(KEY_MESH_ENABLED, DEFAULT_MESH_ENABLED)
        
        isInitialized = true
    }
    
    /**
     * Set Meshtastic enabled state
     */
    fun setMeshEnabled(enabled: Boolean) {
        _meshEnabled.value = enabled
        if (::sharedPrefs.isInitialized) {
            sharedPrefs.edit().putBoolean(KEY_MESH_ENABLED, enabled).apply()
        }
        
        // Trigger the check in MeshtasticHandshakeManager
        MeshtasticHandshakeManager.onToggleChanged(enabled)
    }
}
