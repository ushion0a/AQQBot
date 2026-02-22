package top.alazeprt.aqqbot.bot

import top.alazeprt.aonebot.client.websocket.WebsocketBotClient
import top.alazeprt.aqqbot.AQQBot
import top.alazeprt.aqqbot.listener.AQBListener
import java.net.URI
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object BotProvider {

    private var botClient: WebsocketBotClient? = null

    private var aqbListener: AQBListener? = null

    private var reconnectThread: Thread? = null
    @Volatile private var isReconnecting = false
    private val connectLock = ReentrantLock()

    fun loadBot(plugin: AQQBot, uri: URI) {
        startBot(plugin, uri, null)
    }

    fun loadBot(plugin: AQQBot, uri: URI, token: String) {
        startBot(plugin, uri, token)
    }

    private fun startBot(plugin: AQQBot, uri: URI, token: String?) {
        // Stop existing reconnection thread if any
        stopReconnectionTask()
        
        // Ensure any existing client is properly cleaned up before starting
        // Do this before overwriting aqbListener so we can unregister the old one
        cleanupOldClient()
        
        // Refresh the listener on every load/reload
        aqbListener = AQBListener(plugin)
        
        connect(plugin, uri, token)
        
        // Start monitoring thread
        startReconnectionTask(plugin, uri, token)
    }
    
    private fun cleanupOldClient() {
        connectLock.withLock {
            botClient?.let {
                // Ensure event is unregistered to prevent zombie instances sending duplicate events
                if (aqbListener != null) {
                    it.unregisterEvent(aqbListener)
                }
                try {
                    it.disconnect()
                } catch (e: Exception) {
                    // Ignore close errors
                }
            }
            botClient = null
        }
    }

    private fun connect(plugin: AQQBot, uri: URI, token: String?) {
        connectLock.withLock {
            // Clean up again just in case
            if (botClient != null && aqbListener != null) {
                botClient?.unregisterEvent(aqbListener)
            }
            
            try {
                val client = if (token != null) WebsocketBotClient(uri, token) else WebsocketBotClient(uri)
                
                client.connect()
                botClient = client
                
                if (botClient?.eventList?.contains(aqbListener) == false && aqbListener != null) {
                    botClient?.registerEvent(aqbListener)
                }
                plugin.debugModule?.debugLogger?.log("Connected to OneBot server.")
            } catch (e: Exception) {
                plugin.debugModule?.debugLogger?.log("Failed to connect to OneBot's websocket server: $e")
                // Don't throw exception here to allow retry logic to work, just log it
            }
        }
    }

    
    private fun startReconnectionTask(plugin: AQQBot, uri: URI, token: String?) {
        isReconnecting = true
        reconnectThread = Thread {
            while (isReconnecting) {
                try {
                    Thread.sleep(5000) // Check every 5 seconds
                    
                    val needsReconnect = connectLock.withLock {
                        botClient == null || !botClient!!.isConnected
                    }
                    
                    if (needsReconnect && isReconnecting) {
                        plugin.debugModule?.debugLogger?.log("Connection lost, attempting to reconnect...")
                        
                        // Clean up old client
                        cleanupOldClient()
                        
                        // Attempt to connect again
                        if (isReconnecting) {
                            connect(plugin, uri, token)
                        }
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    plugin.debugModule?.debugLogger?.log("Error in reconnection task: $e")
                }
            }
        }.apply {
            name = "AQQBot-Reconnector"
            isDaemon = true
            start()
        }
    }

    private fun stopReconnectionTask() {
        isReconnecting = false
        reconnectThread?.interrupt()
        reconnectThread = null
    }

    fun unloadBot() {
        stopReconnectionTask()
        cleanupOldClient()
        aqbListener = null
    }

    fun getBot(): WebsocketBotClient? {
        return botClient
    }
}