package top.alazeprt.aqqbot.handler

import com.google.gson.JsonParser
import top.alazeprt.aonebot.action.SendGroupMessage
import top.alazeprt.aonebot.event.message.GroupMessageEvent
import top.alazeprt.aqqbot.AQQBot
import top.alazeprt.aqqbot.bot.BotProvider
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.UUID
import javax.imageio.ImageIO

class PlayerDataHandler(val plugin: AQQBot) {
    fun handle(message: String, event: GroupMessageEvent): Boolean {
        if (!plugin.generalConfig.getBoolean("player_data.enable", event.groupId)) return false

        val commands = plugin.generalConfig.getStringList("player_data.command", event.groupId)
        val args = message.split(" ")
        
        var isCommandMatch = false
        for (cmd in commands) {
            if (args[0].equals(cmd, ignoreCase = true)) {
                isCommandMatch = true
                break
            }
        }
        
        if (!isCommandMatch) return false

        var playerName = ""
        var userId = event.senderId

        if (args.size > 1) {
            val target = args[1]
            if (target.toLongOrNull() != null) {
                userId = target.toLong()
                val list = plugin.getPlayerByQQ(userId)
                if (list.isEmpty()) {
                    BotProvider.getBot()?.action(SendGroupMessage(event.groupId, plugin.messageManager.get("qq.whitelist.not_bind", null), true))
                    return true
                }
                playerName = list[0].getName()
            } else {
                playerName = target
                val uuid = plugin.adapter!!.getOfflinePlayer(playerName).getUUID()
                userId = plugin.getQQByPlayer(plugin.adapter!!.getOfflinePlayer(playerName)) ?: 0L
            }
        } else {
            val list = plugin.getPlayerByQQ(userId)
            if (list.isEmpty()) {
                BotProvider.getBot()?.action(SendGroupMessage(event.groupId, plugin.messageManager.get("qq.whitelist.not_bind", null), true))
                return true
            }
            playerName = list[0].getName()
        }

        val offlinePlayer = plugin.adapter!!.getOfflinePlayer(playerName)
        val uuid = offlinePlayer.getUUID()

        val debrisMined = getPlayerStatistic(uuid, playerName, "minecraft:mined", "minecraft:ancient_debris", event.groupId)
        val diamondMined1 = getPlayerStatistic(uuid, playerName, "minecraft:mined", "minecraft:diamond_ore", event.groupId)
        val diamondMined2 = getPlayerStatistic(uuid, playerName, "minecraft:mined", "minecraft:deepslate_diamond_ore", event.groupId)
        val diamondMined = (if (diamondMined1 > 0) diamondMined1 else 0) + (if (diamondMined2 > 0) diamondMined2 else 0)
        
        val playtimeTicks = getPlayerStatistic(uuid, playerName, "minecraft:custom", "minecraft:play_time", event.groupId)
        val deaths = getPlayerStatistic(uuid, playerName, "minecraft:custom", "minecraft:deaths", event.groupId)
        val mobKills = getPlayerStatistic(uuid, playerName, "minecraft:custom", "minecraft:mob_kills", event.groupId)
        val playerKills = getPlayerStatistic(uuid, playerName, "minecraft:custom", "minecraft:player_kills", event.groupId)
        
        if (playtimeTicks < 0 && debrisMined < 0 && diamondMined1 < 0 && deaths < 0) {
            BotProvider.getBot()?.action(SendGroupMessage(event.groupId, "[AQQBot] 未找到玩家 $playerName 的统计数据文件。", true))
            return true
        }

        val playtimeHours = if (playtimeTicks > 0) String.format("%.2f", playtimeTicks / 20.0 / 3600.0) else "0.00"

        plugin.submitAsync {
            try {
                val imgBase64 = createStatsImage(playerName, debrisMined.coerceAtLeast(0), diamondMined, playtimeHours, 
                    deaths.coerceAtLeast(0), mobKills.coerceAtLeast(0), playerKills.coerceAtLeast(0))
                BotProvider.getBot()?.action(SendGroupMessage(event.groupId, "[CQ:image,file=base64://$imgBase64]", false))
            } catch (e: Exception) {
                plugin.debugModule?.debugLogger?.log("Failed to create player data image: ${e.message}")
                e.printStackTrace()
            }
        }

        return true
    }

    private fun getPlayerStatistic(uuid: UUID, playerName: String, category: String, key: String, groupId: Long): Int {
        try {
            val configuredDir = plugin.generalConfig.getString("player_data.world_dir", groupId)
            
            val searchDirs = mutableListOf<File>()
            
            if (configuredDir.isNullOrBlank()) {
                val root = File(".")
                root.listFiles()?.filter { it.isDirectory }?.forEach { searchDirs.add(it) }
            } else {
                searchDirs.add(File(configuredDir))
            }

            for (dir in searchDirs) {
                var targetUuid = uuid.toString()
                
                // 尝试从 usercache.json 中获取真实的 UUID（主要解决代理端离线 UUID 无法对应子服正版 UUID 的问题）
                try {
                    val serverRoot = dir.parentFile
                    if (serverRoot != null) {
                        val usercache = File(serverRoot, "usercache.json")
                        if (usercache.exists()) {
                            val cacheContent = usercache.readText()
                            val cacheJson = com.google.gson.JsonParser.parseString(cacheContent).asJsonArray
                            for (element in cacheJson) {
                                val obj = element.asJsonObject
                                if (obj.has("name") && obj.get("name").asString.equals(playerName, ignoreCase = true)) {
                                    if (obj.has("uuid")) {
                                        targetUuid = obj.get("uuid").asString
                                    }
                                    break
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    plugin.debugModule?.debugLogger?.log("Failed to parse usercache.json: ${e.message}")
                }

                val statsDir = File(dir, "stats")
                if (statsDir.exists() && statsDir.isDirectory) {
                    var statsFile = File(statsDir, "$targetUuid.json")
                    if (!statsFile.exists() && targetUuid != uuid.toString()) {
                        statsFile = File(statsDir, "$uuid.json")
                    }
                    if (statsFile.exists()) {
                        val content = statsFile.readText()
                        val json = JsonParser.parseString(content).asJsonObject
                        if (json.has("stats")) {
                            val stats = json.getAsJsonObject("stats")
                            if (stats.has(category)) {
                                val cat = stats.getAsJsonObject(category)
                                if (cat.has(key)) {
                                    return cat.get(key).asInt
                                }
                            }
                        }
                        return 0
                    }
                }
            }
        } catch (e: Exception) {
            plugin.debugModule?.debugLogger?.log("Failed to read player statistic for $uuid: ${e.message}")
        }
        return -1
    }

    private fun createStatsImage(playerName: String, debrisCount: Int, diamondCount: Int, playtimeHours: String, 
                                 deaths: Int, mobKills: Int, playerKills: Int): String {
        val width = 350
        val height = 310
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()

        graphics.color = Color(43, 45, 49)
        graphics.fillRect(0, 0, width, height)

        graphics.color = Color(88, 101, 242)
        graphics.stroke = java.awt.BasicStroke(4f)
        graphics.drawRect(2, 2, width - 4, height - 4)

        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

        graphics.color = Color.WHITE
        var font = Font("Microsoft YaHei", Font.BOLD, 28)
        try { graphics.font = font } catch (e: Exception) { graphics.font = Font(Font.SANS_SERIF, Font.BOLD, 28) }
        graphics.drawString("玩家数据: $playerName", 30, 50)

        graphics.color = Color(64, 68, 75)
        graphics.fillRect(30, 70, width - 60, 2)

        graphics.color = Color(220, 220, 220)
        font = Font("Microsoft YaHei", Font.PLAIN, 20)
        try { graphics.font = font } catch (e: Exception) { graphics.font = Font(Font.SANS_SERIF, Font.PLAIN, 20) }

        var y = 105
        val step = 35
        graphics.drawString("在线时长: $playtimeHours 小时", 40, y); y += step
        graphics.drawString("死亡次数: $deaths 次", 40, y); y += step
        graphics.drawString("击杀生物: $mobKills 只", 40, y); y += step
        graphics.drawString("击杀玩家: $playerKills 次", 40, y); y += step
        graphics.drawString("挖掘钻石矿: $diamondCount 个", 40, y); y += step
        graphics.drawString("挖掘远古残骸: $debrisCount 个", 40, y); y += step

        graphics.color = Color(150, 150, 150)
        font = Font("Microsoft YaHei", Font.ITALIC, 14)
        try { graphics.font = font } catch (e: Exception) { graphics.font = Font(Font.SANS_SERIF, Font.ITALIC, 14) }
//        graphics.drawString("AQQBot 玩家数据统计系统", width - 200, height - 20)

        graphics.dispose()

        val outputStream = ByteArrayOutputStream()
        ImageIO.write(image, "PNG", outputStream)
        return Base64.getEncoder().encodeToString(outputStream.toByteArray())
    }
}