package top.alazeprt.aqqbot.handler

import com.google.gson.JsonParser
import top.alazeprt.aonebot.action.SendGroupMessage
import top.alazeprt.aonebot.event.message.GroupMessageEvent
import top.alazeprt.aqqbot.AQQBot
import top.alazeprt.aqqbot.bot.BotProvider
import com.alessiodp.libby.Library
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Base64
import java.util.UUID
import javax.imageio.ImageIO

class PlayerDataHandler(val plugin: AQQBot) {

    companion object {
        @Volatile
        private var isMysqlLoaded = false
        @Volatile
        private var isSqliteLoaded = false
    }

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
        
        val walkDistance = getPlayerStatistic(uuid, playerName, "minecraft:custom", "minecraft:walk_one_cm", event.groupId)
        val sprintDistance = getPlayerStatistic(uuid, playerName, "minecraft:custom", "minecraft:sprint_one_cm", event.groupId)
        val totalDistance = if (walkDistance >= 0 || sprintDistance >= 0) {
            (walkDistance.coerceAtLeast(0) + sprintDistance.coerceAtLeast(0))
        } else -1
        
        val balance = getCmiBalance(uuid, playerName, event.groupId)
        
        if (playtimeTicks < 0 && debrisMined < 0 && diamondMined1 < 0 && deaths < 0 && balance < 0 && totalDistance < 0) {
            BotProvider.getBot()?.action(SendGroupMessage(event.groupId, "未找到玩家 $playerName 的统计数据文件或货币信息。", true))
            return true
        }

        val playtimeHours = if (playtimeTicks > 0) String.format("%.2f", playtimeTicks / 20.0 / 3600.0) else "0.00"

        plugin.submitAsync {
            try {
                val imgBase64 = createStatsImage(playerName, uuid, debrisMined.coerceAtLeast(0), diamondMined, playtimeHours, 
                    deaths.coerceAtLeast(0), mobKills.coerceAtLeast(0), playerKills.coerceAtLeast(0), balance, totalDistance)
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

    private fun getCmiBalance(uuid: UUID, playerName: String, groupId: Long): Double {
        if (!plugin.generalConfig.getBoolean("player_data.cmi.enable", groupId)) return -1.0
        
        try {
            val type = plugin.generalConfig.getString("player_data.cmi.type", groupId)?.lowercase() ?: "sqlite"
            val table = plugin.generalConfig.getString("player_data.cmi.table", groupId) ?: "users"
            
            val url: String
            if (type == "mysql") {
                val host = plugin.generalConfig.getString("player_data.cmi.host", groupId)
                val port = plugin.generalConfig.getInt("player_data.cmi.port", groupId)
                val db = plugin.generalConfig.getString("player_data.cmi.database", groupId)
                url = "jdbc:mysql://$host:$port/$db?useSSL=false"
            } else {
                val sqlitePath = plugin.generalConfig.getString("player_data.cmi.sqlite_file", groupId)
                if (sqlitePath.isNullOrBlank()) {
                    plugin.debugModule?.debugLogger?.log("CMI SQLite file path is not configured.")
                    return -1.0
                }
                val file = File(sqlitePath)
                if (!file.exists()) {
                    plugin.debugModule?.debugLogger?.log("CMI SQLite file not found: ${file.absolutePath}")
                    return -1.0
                }
                url = "jdbc:sqlite:${file.absolutePath}"
            }

            val user = if (type == "mysql") plugin.generalConfig.getString("player_data.cmi.user", groupId) else null
            val pass = if (type == "mysql") plugin.generalConfig.getString("player_data.cmi.password", groupId) else null
            
            val conn = try {
                if (type == "mysql" && user != null && pass != null) {
                    DriverManager.getConnection(url, user, pass)
                } else {
                    DriverManager.getConnection(url)
                }
            } catch (e: SQLException) {
                if (e.message?.contains("No suitable driver", ignoreCase = true) == true) {
                    // 仅在当前类加载器中找不到驱动时，才使用 Libby 下载并加载，以避免重复控制台输出
                    if (type == "mysql") {
                        if (!isMysqlLoaded) {
                            val mysqlLib = Library.builder()
                                .groupId("com{}mysql")
                                .artifactId("mysql-connector-j")
                                .version("8.3.0")
                                .resolveTransitiveDependencies(true)
                                .build()
                            plugin.libraryManager.loadLibrary(mysqlLib)
                            try {
                                Class.forName("com.mysql.cj.jdbc.Driver", true, plugin.javaClass.classLoader)
                            } catch (ex: ClassNotFoundException) {
                                // 忽略
                            }
                            isMysqlLoaded = true
                        }
                    } else {
                        if (!isSqliteLoaded) {
                            val sqliteLib = Library.builder()
                                .groupId("org{}xerial")
                                .artifactId("sqlite-jdbc")
                                .version("3.49.0.0")
                                .resolveTransitiveDependencies(true)
                                .build()
                            plugin.libraryManager.loadLibrary(sqliteLib)
                            try {
                                Class.forName("org.sqlite.JDBC", true, plugin.javaClass.classLoader)
                            } catch (ex: ClassNotFoundException) {
                                // 忽略
                            }
                            isSqliteLoaded = true
                        }
                    }
                    // 加载后再试一次
                    if (type == "mysql" && user != null && pass != null) {
                        DriverManager.getConnection(url, user, pass)
                    } else {
                        DriverManager.getConnection(url)
                    }
                } else {
                    throw e
                }
            }

            conn.use { c ->
                val balanceCols = listOf("balance", "Balance")
                val uuidCols = listOf("player_uuid", "uuid")
                
                for (bCol in balanceCols) {
                    for (uCol in uuidCols) {
                        try {
                            val sql = "SELECT $bCol FROM $table WHERE $uCol = ?"
                            c.prepareStatement(sql).use { stmt ->
                                stmt.setString(1, uuid.toString())
                                var rs = stmt.executeQuery()
                                if (rs.next()) return rs.getDouble(bCol)
                                
                                stmt.setString(1, uuid.toString().replace("-", ""))
                                rs = stmt.executeQuery()
                                if (rs.next()) return rs.getDouble(bCol)
                            }
                        } catch (e: Exception) { }
                    }
                    
                    try {
                        val sqlName = "SELECT $bCol FROM $table WHERE username = ?"
                        c.prepareStatement(sqlName).use { stmt ->
                            stmt.setString(1, playerName)
                            val rs = stmt.executeQuery()
                            if (rs.next()) return rs.getDouble(bCol)
                        }
                    } catch (e: Exception) { }
                }
            }
        } catch (e: Exception) {
            plugin.debugModule?.debugLogger?.log("Failed to fetch CMI balance: ${e.message}")
        }
        return -1.0
    }

    private fun createStatsImage(playerName: String, uuid: UUID, debrisCount: Int, diamondCount: Int, playtimeHours: String, 
                                 deaths: Int, mobKills: Int, playerKills: Int, balance: Double, totalDistance: Int): String {
        val cardW = 216
        val cardH = 90
        val gap = 16
        val startX = 20
        val startY = 90
        
        // 布局设计：
        // 顶部是标题栏，宽度占满
        // 下方是 3行 x 3列 的数据卡片 (占据宽度: 216*3 + 16*2 = 680)
        // 右侧留出 180px 用于显示 3D 身体皮肤
        val rightColumnWidth = 200
        val width = startX * 2 + (cardW * 3 + gap * 2) + gap + rightColumnWidth
        val height = startY + (cardH * 3 + gap * 2) + 20
        
        val bgColor = Color(245, 246, 250)
        val cardColor = Color(255, 255, 255)
        val titleTextColor = Color(232, 67, 147) // Pinkish
        val labelTextColor = Color(47, 53, 66)  // Dark gray
        val valueTextColor = Color(232, 67, 147) // Pinkish
        
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()

        // Background
        graphics.color = bgColor
        graphics.fillRect(0, 0, width, height)

        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

        // Title Section (Full Width)
        graphics.color = cardColor
        graphics.fillRoundRect(20, 20, width - 40, 50, 10, 10)
        graphics.color = titleTextColor
        graphics.fillRect(20, 30, 5, 30) // Side accent
        
        var fontBold = Font("Microsoft YaHei", Font.BOLD, 22)
        try { graphics.font = fontBold } catch (e: Exception) { graphics.font = Font(Font.SANS_SERIF, Font.BOLD, 22) }
        graphics.drawString("玩家信息", 40, 55)

        // Data Cards
        val data = listOf(
            "玩家名称" to playerName,
            "在线时长" to "$playtimeHours h",
            "行走距离" to (if (totalDistance >= 0) String.format("%.2f 万", totalDistance / 10000.0) else "0"),
            "账户余额" to (if (balance >= 0) String.format("%.2f", balance) else "N/A"),
            "死亡次数" to "$deaths",
            "击杀生物" to "$mobKills",
            "击杀玩家" to "$playerKills",
            "挖掘钻石" to "$diamondCount",
            "挖掘残骸" to "$debrisCount"
        )

        var fontLabel = Font("Microsoft YaHei", Font.BOLD, 18)
        try { graphics.font = fontLabel } catch (e: Exception) { graphics.font = Font(Font.SANS_SERIF, Font.BOLD, 18) }
        val fontValue = fontBold.deriveFont(20f)

        for (i in data.indices) {
            val row = i / 3
            val col = i % 3
            val x = startX + col * (cardW + gap)
            val y = startY + row * (cardH + gap)
            
            // Card
            graphics.color = cardColor
            graphics.fillRoundRect(x, y, cardW, cardH, 10, 10)
            
            // Bottom Accent Line
            graphics.color = Color(253, 121, 168, 100)
            graphics.fillRect(x + 10, y + cardH - 5, cardW - 20, 2)
            
            // Label
            graphics.color = labelTextColor
            graphics.font = fontLabel
            graphics.drawString(data[i].first, x + 15, y + 35)
            
            // Value
            graphics.color = valueTextColor
            graphics.font = fontValue
            graphics.drawString(data[i].second, x + 15, y + 70)
        }

        // Draw Skin Card Background (Right Column)
        val skinCardX = startX + (cardW * 3 + gap * 2) + gap
        val skinCardW = rightColumnWidth
        val skinCardH = cardH * 3 + gap * 2
        graphics.color = cardColor
        graphics.fillRoundRect(skinCardX, startY, skinCardW, skinCardH, 10, 10)

        // Fetch and Draw 3D Body Skin
        try {
            // 使用 playerName 获取身体，避免离线 UUID 导致史蒂夫
            val skinUrl = URL("https://mc-api.io/render/full/$playerName/java?size=250")
            val connection = skinUrl.openConnection() as java.net.HttpURLConnection
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            val skinImage = ImageIO.read(connection.inputStream)
            if (skinImage != null) {
                // 等比例缩放并居中
                val maxTargetH = skinCardH - 40 // 上下留白
                val targetW = Math.min(skinImage.width, skinCardW - 20)
                val targetH = (skinImage.height.toDouble() / skinImage.width * targetW).toInt().coerceAtMost(maxTargetH)
                val finalW = (skinImage.width.toDouble() / skinImage.height * targetH).toInt()
                
                val drawX = skinCardX + (skinCardW - finalW) / 2
                val drawY = startY + (skinCardH - targetH) / 2
                graphics.drawImage(skinImage, drawX, drawY, finalW, targetH, null)
            }
        } catch (e: Exception) {
            plugin.debugModule?.debugLogger?.log("Failed to load body skin for $playerName: ${e.message}")
            graphics.color = labelTextColor
            graphics.font = fontLabel
            graphics.drawString("暂无皮肤", skinCardX + 60, startY + skinCardH / 2)
        }

        graphics.dispose()

        val outputStream = ByteArrayOutputStream()
        ImageIO.write(image, "PNG", outputStream)
        return Base64.getEncoder().encodeToString(outputStream.toByteArray())
    }
}