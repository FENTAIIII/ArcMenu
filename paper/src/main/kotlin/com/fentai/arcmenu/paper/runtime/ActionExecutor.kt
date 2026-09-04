package com.fentai.arcmenu.paper.runtime

import com.fentai.arcmenu.core.behavior.*
import com.fentai.arcmenu.paper.localization.LanguageManager
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.Registry
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.time.Duration
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

enum class ActionResult { CONTINUE, RETURN, SESSION_CHANGED }

interface RuntimeControl {
    fun open(player: Player, menuId: String, remember: Boolean = true, arguments: List<String> = emptyList()): Boolean
    fun openApplication(player: Player, applicationId: String, remember: Boolean = true, arguments: List<String> = emptyList()): Boolean
    fun close(player: Player, clearHistory: Boolean = true)
    fun back(player: Player): Boolean
    fun refresh(player: Player, target: String? = null)
    fun playAnimation(player: Player, animationId: String): Boolean
    fun stopAnimation(player: Player, animationId: String): Boolean
    fun schedule(player: Player, delayTicks: Long, task: () -> Unit)
    fun startCatcher(player: Player, catcher: CatcherAction)
    fun retype(player: Player)
}

class ActionExecutor(
    private val plugin: Plugin,
    private val placeholders: PlaceholderResolver,
    private val state: RuntimeStateStore,
    private val language: LanguageManager,
) {
    private val warnedSounds = mutableSetOf<String>()
    private val balances = ExternalBalanceBridge(plugin, language)
    private val items = ItemActions()
    @Suppress("DEPRECATION") // Paper 26.2 deprecates the legacy Sound key bridge retained by the 1.21.1 baseline.
    private val sounds: Map<String, Sound> by lazy {
        buildMap {
            for (sound in Registry.SOUNDS) {
                val key = sound.key()
                put(sound.toString().lowercase(), sound)
                put(key.asString().lowercase(), sound)
                put(key.value().lowercase(), sound)
                put(soundAlias(key.asString()), sound)
                put(soundAlias(key.value()), sound)
                put(soundAlias(sound.toString()), sound)
            }
        }
    }
    fun evaluate(expression: ConditionExpression, player: Player): Boolean = ConditionLanguage.evaluate(expression, context(player))

    fun run(reactions: List<ActionReaction>, player: Player, control: RuntimeControl): ActionResult {
        return runOrdered(reactions.sortedWith(compareBy<ActionReaction> { it.priority }.thenBy { it.order }), player, control)
    }

    fun runOrdered(reactions: List<ActionReaction>, player: Player, control: RuntimeControl): ActionResult {
        val context = context(player)
        for (reaction in reactions) {
            val accepted = reaction.condition?.let { ConditionLanguage.evaluate(it, context) } ?: true
            val result = runActions(if (accepted) reaction.actions else reaction.deny, player, control)
            if (result != ActionResult.CONTINUE) return result
        }
        return ActionResult.CONTINUE
    }

    private fun context(player: Player) = object : ConditionContext {
        override fun expand(value: String) = placeholders.expand(player, value)
        override fun hasPermission(permission: String) = player.hasPermission(permission)
    }

    fun runActions(actions: List<MenuAction>, player: Player, control: RuntimeControl): ActionResult {
        var accumulatedDelay = 0L
        var sessionChanged = false
        val delayedChainStopped = AtomicBoolean(false)
        for (entry in flatten(actions)) {
            val configured = entry as? ConfiguredAction
            val action = configured?.action ?: entry
            val options = configured?.options ?: ActionOptions()
            val optionCondition = options.condition
            val playersCondition = options.players
            if (options.chance < 1.0 && Random.nextDouble() >= options.chance) continue
            if (action is DelayAction) {
                accumulatedDelay += placeholders.expand(player, action.ticks).toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                continue
            }
            val execute = {
                if (!delayedChainStopped.get() && (optionCondition == null || evaluate(optionCondition, player))) {
                    val targets = when {
                        options.allOnlinePlayers -> Bukkit.getOnlinePlayers().toList()
                        playersCondition != null -> Bukkit.getOnlinePlayers().filter { evaluate(playersCondition, it) }
                        else -> listOf(player)
                    }
                    for (target in targets) {
                        val result = executeOne(action, target, player, control)
                        if (result == ActionResult.RETURN) delayedChainStopped.set(true)
                    }
                }
            }
            val delay = accumulatedDelay + options.delayTicks
            if (delay > 0) {
                control.schedule(player, delay, execute)
                continue
            }
            if (optionCondition != null && !evaluate(optionCondition, player)) continue
            val targets = when {
                options.allOnlinePlayers -> Bukkit.getOnlinePlayers().toList()
                playersCondition != null -> Bukkit.getOnlinePlayers().filter { evaluate(playersCondition, it) }
                else -> listOf(player)
            }
            for (target in targets) {
                when (executeOne(action, target, player, control)) {
                    ActionResult.RETURN -> return ActionResult.RETURN
                    ActionResult.SESSION_CHANGED -> sessionChanged = true
                    ActionResult.CONTINUE -> Unit
                }
            }
        }
        return if (sessionChanged) ActionResult.SESSION_CHANGED else ActionResult.CONTINUE
    }

    private fun executeOne(action: MenuAction, player: Player, placeholderPlayer: Player, control: RuntimeControl): ActionResult {
        fun expand(value: String) = placeholders.expand(placeholderPlayer, value)
        return when (action) {
            is TellAction -> {
                expand(action.message).split("\\n", "\\r").forEach { player.sendMessage(legacy(it)) }
                ActionResult.CONTINUE
            }
            is TellRawAction -> {
                player.sendMessage(rich(expand(action.message)))
                ActionResult.CONTINUE
            }
            is PlayerCommandAction -> {
                expand(action.command).split(';').forEach { Bukkit.dispatchCommand(player, it.trim().removePrefix("/")) }
                ActionResult.CONTINUE
            }
            is ConsoleCommandAction -> {
                expand(action.command).split(';').forEach { Bukkit.dispatchCommand(Bukkit.getConsoleSender(), it.trim().removePrefix("/")) }
                ActionResult.CONTINUE
            }
            is OpCommandAction -> {
                val wasOp = player.isOp
                try {
                    player.isOp = true
                    expand(action.command).split(';').forEach { Bukkit.dispatchCommand(player, it.trim().removePrefix("/")) }
                } finally {
                    player.isOp = wasOp
                }
                ActionResult.CONTINUE
            }
            is ChatAction -> {
                @Suppress("DEPRECATION")
                player.chat(expand(action.message))
                ActionResult.CONTINUE
            }
            is ActionBarAction -> {
                player.sendActionBar(legacy(expand(action.message)))
                ActionResult.CONTINUE
            }
            is TitleAction -> {
                showTitle(player, expand(action.specification))
                ActionResult.CONTINUE
            }
            is BossBarAction -> {
                showBossBar(player, expand(action.specification))
                ActionResult.CONTINUE
            }
            is SoundAction -> {
                expand(action.specification).split(';').forEach { playSound(player, it.trim()) }
                ActionResult.CONTINUE
            }
            is RefreshAction -> {
                val targets = action.target?.let(::expand)?.split(';') ?: listOf(null)
                targets.forEach { control.refresh(player, it?.trim()?.takeIf(String::isNotEmpty)) }
                ActionResult.CONTINUE
            }
            is PlayAnimationAction -> {
                control.playAnimation(player, expand(action.animationId))
                ActionResult.CONTINUE
            }
            is StopAnimationAction -> {
                control.stopAnimation(player, expand(action.animationId))
                ActionResult.CONTINUE
            }
            is OpenMenuAction -> if (control.open(
                    player, expand(action.menuId).lowercase(), true, action.arguments.map(::expand),
                )) ActionResult.SESSION_CHANGED else ActionResult.CONTINUE
            is OpenApplicationAction -> if (control.openApplication(
                    player, expand(action.applicationId).lowercase(), true, action.arguments.map(::expand),
                )) ActionResult.SESSION_CHANGED else ActionResult.CONTINUE
            is ConnectAction -> {
                connect(player, expand(action.server))
                ActionResult.CONTINUE
            }
            is SetStateAction -> {
                expand(action.specification).split(';').forEach { entry ->
                    val pair = entry.trim().split(Regex("\\s+"), limit = 2)
                    if (pair.size == 2) state.set(player, action.scope, pair[0], pair[1])
                }
                ActionResult.CONTINUE
            }
            is RemoveStateAction -> {
                expand(action.pattern).split(';').forEach { pattern ->
                    try {
                        state.remove(player, action.scope, Regex(pattern.trim()))
                    } catch (error: IllegalArgumentException) {
                        plugin.logger.warning(language.log(
                            "log.invalid-state-regex", action.scope.name.lowercase(), pattern, error.message.orEmpty(),
                        ))
                    }
                }
                ActionResult.CONTINUE
            }
            is SetArgumentsAction -> {
                state.setArguments(player, quotedWords(expand(action.specification), Int.MAX_VALUE).filter(String::isNotBlank))
                ActionResult.CONTINUE
            }
            ClearArgumentsAction -> {
                state.clearArguments(player)
                ActionResult.CONTINUE
            }
            ReloadInventoryAction -> {
                @Suppress("DEPRECATION")
                player.updateInventory()
                ActionResult.CONTINUE
            }
            is BalanceAction -> {
                val amount = expand(action.amount).toDoubleOrNull()
                if (amount != null && amount > 0) balances.apply(player, action.kind, action.operation, amount)
                ActionResult.CONTINUE
            }
            is GiveItemAction -> {
                if (!items.give(player, expand(action.specification))) {
                    player.sendMessage(legacy("&c[ArcMenu] ${language.text(player, "runtime.invalid-give-item")}"))
                }
                ActionResult.CONTINUE
            }
            is TakeItemAction -> {
                if (!items.take(player, expand(action.specification))) {
                    player.sendMessage(legacy("&c[ArcMenu] ${language.text(player, "runtime.invalid-take-item")}"))
                }
                ActionResult.CONTINUE
            }
            is RepairItemAction -> {
                items.repair(player, expand(action.targets))
                ActionResult.CONTINUE
            }
            is EnchantItemAction -> {
                if (!items.enchant(player, expand(action.specification))) {
                    player.sendMessage(legacy("&c[ArcMenu] ${language.text(player, "runtime.invalid-enchant-item")}"))
                }
                ActionResult.CONTINUE
            }
            is CatcherAction -> {
                control.startCatcher(player, action)
                ActionResult.CONTINUE
            }
            RetypeAction -> {
                control.retype(player)
                ActionResult.CONTINUE
            }
            CloseAction -> {
                control.close(player, true)
                ActionResult.SESSION_CHANGED
            }
            BackAction -> {
                control.back(player)
                ActionResult.SESSION_CHANGED
            }
            ReturnAction -> ActionResult.RETURN
            is DelayAction, is ConfiguredAction, is ActionLanguage.SequenceAction -> error("动作必须先展开: $action")
        }
    }

    private fun flatten(actions: List<MenuAction>): List<MenuAction> = buildList {
        fun addAction(action: MenuAction) {
            when (action) {
                is ActionLanguage.SequenceAction -> action.actions.forEach(::addAction)
                is ConfiguredAction -> if (action.action is ActionLanguage.SequenceAction) {
                    val sequence = action.action as ActionLanguage.SequenceAction
                    sequence.actions.forEach { addAction(ConfiguredAction(it, action.options)) }
                } else add(action)
                else -> add(action)
            }
        }
        actions.forEach(::addAction)
    }

    private fun playSound(player: Player, specification: String) {
        val parts = specification.split('-')
        val hasVolumePitch = parts.size >= 3 && parts[parts.lastIndex - 1].toFloatOrNull() != null && parts.last().toFloatOrNull() != null
        val soundName = (if (hasVolumePitch) parts.dropLast(2).joinToString("-") else specification).trim()
        val volume = if (hasVolumePitch) parts[parts.lastIndex - 1].toFloat() else 1f
        val pitch = if (hasVolumePitch) parts.last().toFloat() else 1f
        val sound = sounds[soundName.lowercase()] ?: sounds[soundAlias(soundName)]
        if (sound == null) {
            if (warnedSounds.add(soundName.lowercase())) {
                plugin.logger.warning(language.log("log.unknown-sound", soundName))
            }
            return
        }
        player.playSound(player.location, sound, volume.coerceAtLeast(0f), pitch.coerceIn(0f, 2f))
    }

    private fun showTitle(player: Player, specification: String) {
        val parts = quotedWords(specification, 5)
        val title = parts.getOrElse(0) { "" }
        val subtitle = parts.getOrElse(1) { "" }
        val fadeIn = parts.getOrNull(2)?.toLongOrNull() ?: 15L
        val stay = parts.getOrNull(3)?.toLongOrNull() ?: 20L
        val fadeOut = parts.getOrNull(4)?.toLongOrNull() ?: 15L
        player.showTitle(Title.title(
            legacy(title), legacy(subtitle),
            Title.Times.times(Duration.ofMillis(fadeIn.coerceAtLeast(0) * 50), Duration.ofMillis(stay.coerceAtLeast(0) * 50), Duration.ofMillis(fadeOut.coerceAtLeast(0) * 50)),
        ))
    }

    private fun showBossBar(player: Player, specification: String) {
        val parts = quotedWords(specification, 4)
        val color = runCatching { BossBar.Color.valueOf(parts.getOrElse(1) { "white" }.uppercase()) }.getOrDefault(BossBar.Color.WHITE)
        val overlay = when (parts.getOrElse(2) { "solid" }.lowercase()) {
            "segmented_6", "segments_6", "6" -> BossBar.Overlay.NOTCHED_6
            "segmented_10", "segments_10", "10" -> BossBar.Overlay.NOTCHED_10
            "segmented_12", "segments_12", "12" -> BossBar.Overlay.NOTCHED_12
            "segmented_20", "segments_20", "20" -> BossBar.Overlay.NOTCHED_20
            else -> BossBar.Overlay.PROGRESS
        }
        val stay = parts.getOrNull(3)?.toLongOrNull()?.coerceAtLeast(1L) ?: 15L
        val bar = BossBar.bossBar(legacy(parts.getOrElse(0) { "" }), 1f, color, overlay)
        player.showBossBar(bar)
        Bukkit.getScheduler().runTaskLater(plugin, Runnable { if (player.isOnline) player.hideBossBar(bar) }, stay)
    }

    private fun quotedWords(source: String, limit: Int): List<String> {
        val result = mutableListOf<String>()
        var index = 0
        while (index < source.length && result.size < limit) {
            while (index < source.length && source[index].isWhitespace()) index++
            if (index >= source.length) break
            if (source[index] == '`') {
                val end = source.indexOf('`', index + 1)
                if (end < 0) {
                    result += source.substring(index + 1)
                    break
                }
                result += source.substring(index + 1, end).replace("\\s", " ")
                index = end + 1
            } else {
                val end = source.indexOf(' ', index).let { if (it < 0) source.length else it }
                result += source.substring(index, end)
                index = end + 1
            }
        }
        return result
    }

    private fun connect(player: Player, server: String) {
        if (server.isBlank()) return
        val bytes = ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeUTF("Connect")
                output.writeUTF(server)
            }
            buffer.toByteArray()
        }
        player.sendPluginMessage(plugin, "BungeeCord", bytes)
    }

    private fun rich(source: String): Component {
        if (source.trimStart().startsWith('{') || source.trimStart().startsWith('[')) {
            runCatching { GsonComponentSerializer.gson().deserialize(source) }.getOrNull()?.let { return it }
        }
        val matcher = Regex("<(.+?)>")
        var result = Component.empty()
        var cursor = 0
        for (match in matcher.findAll(source)) {
            if (match.range.first > cursor) result = result.append(legacy(source.substring(cursor, match.range.first)))
            val parts = match.groupValues[1].split('@')
            var component = legacy(parts[0])
            for (option in parts.drop(1)) {
                val pair = option.split('=', ':', limit = 2)
                if (pair.size != 2) continue
                component = when (pair[0].lowercase()) {
                    "hover" -> component.hoverEvent(legacy(pair[1].replace("\\n", "\n")))
                    "suggest" -> component.clickEvent(ClickEvent.suggestCommand(pair[1]))
                    "command", "execute" -> component.clickEvent(ClickEvent.runCommand(pair[1]))
                    "url", "open_url" -> component.clickEvent(ClickEvent.openUrl(pair[1]))
                    else -> component
                }
            }
            result = result.append(component)
            cursor = match.range.last + 1
        }
        if (cursor < source.length) result = result.append(legacy(source.substring(cursor)))
        return result
    }

    private fun legacy(value: String) = LegacyComponentSerializer.legacyAmpersand().deserialize(value)
}

internal fun soundAlias(value: String): String = value
    .uppercase(Locale.ROOT)
    .replace(Regex("[^A-Z0-9]+"), "_")
    .trim('_')
