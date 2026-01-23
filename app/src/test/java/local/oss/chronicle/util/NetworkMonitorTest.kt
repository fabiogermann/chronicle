package local.oss.chronicle.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Basic unit tests for NetworkMonitor.
 * Note: Full integration tests require Android instrumentation testing
 * as NetworkMonitor depends on ConnectivityManager system service.
 * 
 * These tests verify the data structures and logic that can be tested
 * independently of the Android framework.
 */
class NetworkMonitorTest {
    
    @Test
    fun `NetworkState Connected has correct properties`() {
        val state = NetworkState.Connected(
            isWifi = true,
            isCellular = false,
            isMetered = false
        )
        
        assertTrue(state.isWifi)
        assertFalse(state.isCellular)
        assertFalse(state.isMetered)
    }
    
    @Test
    fun `NetworkState Connected defaults to metered`() {
        val state = NetworkState.Connected()
        
        assertFalse(state.isWifi)
        assertFalse(state.isCellular)
        assertTrue(state.isMetered) // Default should be true (safe assumption)
    }
    
    @Test
    fun `NetworkState Connected can represent WiFi connection`() {
        val state = NetworkState.Connected(
            isWifi = true,
            isCellular = false,
            isMetered = false
        )
        
        assertTrue(state is NetworkState.Connected)
        assertTrue(state.isWifi)
        assertFalse(state.isCellular)
    }
    
    @Test
    fun `NetworkState Connected can represent cellular connection`() {
        val state = NetworkState.Connected(
            isWifi = false,
            isCellular = true,
            isMetered = true
        )
        
        assertTrue(state is NetworkState.Connected)
        assertFalse(state.isWifi)
        assertTrue(state.isCellular)
        assertTrue(state.isMetered)
    }
    
    @Test
    fun `NetworkState Disconnected is singleton object`() {
        val state1 = NetworkState.Disconnected
        val state2 = NetworkState.Disconnected
        
        assertTrue(state1 === state2) // Same instance
        assertTrue(state1 is NetworkState.Disconnected)
    }
    
    @Test
    fun `NetworkState Unknown is singleton object`() {
        val state1 = NetworkState.Unknown
        val state2 = NetworkState.Unknown
        
        assertTrue(state1 === state2) // Same instance
        assertTrue(state1 is NetworkState.Unknown)
    }
    
    @Test
    fun `NetworkState sealed class has three subtypes`() {
        val connected: NetworkState = NetworkState.Connected()
        val disconnected: NetworkState = NetworkState.Disconnected
        val unknown: NetworkState = NetworkState.Unknown
        
        // Verify type hierarchy
        assertTrue(connected is NetworkState)
        assertTrue(disconnected is NetworkState)
        assertTrue(unknown is NetworkState)
        
        // Verify they are distinct types
        assertFalse(connected is NetworkState.Disconnected)
        assertFalse(disconnected is NetworkState.Connected)
        assertFalse(unknown is NetworkState.Connected)
    }
    
    @Test
    fun `NetworkState can be used in when expression exhaustively`() {
        val states = listOf(
            NetworkState.Connected(),
            NetworkState.Disconnected,
            NetworkState.Unknown
        )
        
        states.forEach { state ->
            val result = when (state) {
                is NetworkState.Connected -> "connected"
                is NetworkState.Disconnected -> "disconnected"
                is NetworkState.Unknown -> "unknown"
            }
            
            assertTrue(result in listOf("connected", "disconnected", "unknown"))
        }
    }
    
    @Test
    fun `NetworkState Connected equality works correctly`() {
        val state1 = NetworkState.Connected(isWifi = true, isCellular = false, isMetered = false)
        val state2 = NetworkState.Connected(isWifi = true, isCellular = false, isMetered = false)
        val state3 = NetworkState.Connected(isWifi = false, isCellular = true, isMetered = true)
        
        assertEquals(state1, state2)
        assertTrue(state1 != state3)
    }
    
    @Test
    fun `NetworkState provides meaningful type information`() {
        val wifiState = NetworkState.Connected(isWifi = true, isCellular = false)
        val cellularState = NetworkState.Connected(isWifi = false, isCellular = true)
        val unknownTypeState = NetworkState.Connected(isWifi = false, isCellular = false)
        
        // WiFi connection
        assertTrue(wifiState.isWifi)
        assertFalse(wifiState.isCellular)
        
        // Cellular connection  
        assertFalse(cellularState.isWifi)
        assertTrue(cellularState.isCellular)
        
        // Unknown connection type (e.g., Ethernet, VPN)
        assertFalse(unknownTypeState.isWifi)
        assertFalse(unknownTypeState.isCellular)
    }
    
    @Test
    fun `NetworkState metered flag works correctly`() {
        val meteredConnection = NetworkState.Connected(isWifi = false, isCellular = true, isMetered = true)
        val unmeteredConnection = NetworkState.Connected(isWifi = true, isCellular = false, isMetered = false)
        
        assertTrue(meteredConnection.isMetered)
        assertFalse(unmeteredConnection.isMetered)
    }
}
