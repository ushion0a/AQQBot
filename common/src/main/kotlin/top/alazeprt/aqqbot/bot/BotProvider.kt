package top.alazeprt.aqqbot.bot

import top.alazeprt.aonebot.client.websocket.WebsocketBotClient
import top.alazeprt.aqqbot.AQQBot
import top.alazeprt.aqqbot.listener.AQBListener
import java.net.URI

object BotProvider {

    private var botClient: WebsocketBotClient? = null

    private var aqbListener: AQBListener? = null

    private var reconnectThread: Thread? = null
    private var isReconnecting = false

    fun loadBot(plugin: AQQBot, uri: URI) {
        startBot(plugin, uri, null)
    }

    fun loadBot(plugin: AQQBot, uri: URI, token: String) {
        startBot(plugin, uri, token)
    }

    private fun startBot(plugin: AQQBot, uri: URI, token: String?) {
        if (aqbListener == null) {
            aqbListener = AQBListener(plugin)
        }
        
        // Stop existing reconnection thread if any
        stopReconnectionTask()
        
        connect(plugin, uri, token)
        
        // Start monitoring thread
        startReconnectionTask(plugin, uri, token)
    }

    private fun connect(plugin: AQQBot, uri: URI, token: String?) {
        try {
            val client = if (token != null) WebsocketBotClient(uri, token) else WebsocketBotClient(uri)
            
            client.connect()
            botClient = client
            
            if (botClient?.eventList?.contains(aqbListener) == false) {
                botClient?.registerEvent(aqbListener)
            }
            plugin.debugModule?.debugLogger?.log("Connected to OneBot server.")
        } catch (e: Exception) {
            plugin.debugModule?.debugLogger?.log("Failed to connect to OneBot's websocket server: $e")
            // Don't throw exception here to allow retry logic to work, just log it
        }
    }

    
    private fun startReconnectionTask(plugin: AQQBot, uri: URI, token: String?) {
        isReconnecting = true
        reconnectThread = Thread {
            while (isReconnecting) {
                try {
                    Thread.sleep(5000) // Check every 5 seconds
                    
                    if (botClient == null || !botClient!!.isConnected) {
                        plugin.debugModule?.debugLogger?.log("Connection lost, attempting to reconnect...")
                        
                        // Clean up old client
                        try {
                            botClient?.disconnect()
                        } catch (e: Exception) {
                            // Ignore close errors
                        }
                        
                        connect(plugin, uri, token)
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
        if (botClient != null) {
            if (botClient!!.isConnected) {
                botClient!!.disconnect()
            }
            botClient!!.unregisterEvent(aqbListener)
            botClient = null
        }
    }

    fun getBot(): WebsocketBotClient? {
        return botClient
    }
}