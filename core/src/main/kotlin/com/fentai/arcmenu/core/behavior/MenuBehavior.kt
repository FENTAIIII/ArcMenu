package com.fentai.arcmenu.core.behavior

enum class ClickInput { LEFT, RIGHT, SHIFT_LEFT, SHIFT_RIGHT }

enum class ClickTrigger {
    ALL, LEFT, RIGHT, SHIFT, SHIFT_LEFT, SHIFT_RIGHT;

    fun matches(input: ClickInput): Boolean = when (this) {
        ALL -> true
        LEFT -> input == ClickInput.LEFT
        RIGHT -> input == ClickInput.RIGHT
        SHIFT -> input == ClickInput.SHIFT_LEFT || input == ClickInput.SHIFT_RIGHT
        SHIFT_LEFT -> input == ClickInput.SHIFT_LEFT
        SHIFT_RIGHT -> input == ClickInput.SHIFT_RIGHT
    }
}

sealed interface MenuAction
data class TellAction(val message: String) : MenuAction
data class TellRawAction(val message: String) : MenuAction
data class PlayerCommandAction(val command: String) : MenuAction
data class ConsoleCommandAction(val command: String) : MenuAction
data class OpCommandAction(val command: String) : MenuAction
data class ChatAction(val message: String) : MenuAction
data class ActionBarAction(val message: String) : MenuAction
data class TitleAction(val specification: String) : MenuAction
data class BossBarAction(val specification: String) : MenuAction
data class SoundAction(val specification: String) : MenuAction
data class OpenMenuAction(val menuId: String, val arguments: List<String> = emptyList(), val dynamicTarget: Boolean = false) : MenuAction
data class OpenApplicationAction(
    val applicationId: String,
    val arguments: List<String> = emptyList(),
    val dynamicTarget: Boolean = false,
) : MenuAction
data class ConnectAction(val server: String) : MenuAction
data class RefreshAction(val target: String? = null) : MenuAction
data class PlayAnimationAction(val animationId: String) : MenuAction
data class StopAnimationAction(val animationId: String) : MenuAction
data class DelayAction(val ticks: String) : MenuAction
enum class StateScope { META, DATA, GLOBAL }
data class SetStateAction(val scope: StateScope, val specification: String) : MenuAction
data class RemoveStateAction(val scope: StateScope, val pattern: String) : MenuAction
data class SetArgumentsAction(val specification: String) : MenuAction
data object ClearArgumentsAction : MenuAction
data object ReloadInventoryAction : MenuAction
enum class BalanceKind { MONEY, POINTS }
enum class BalanceOperation { ADD, TAKE, SET }
data class BalanceAction(val kind: BalanceKind, val operation: BalanceOperation, val amount: String) : MenuAction
data class GiveItemAction(val specification: String) : MenuAction
data class TakeItemAction(val specification: String) : MenuAction
data class RepairItemAction(val targets: String) : MenuAction
data class EnchantItemAction(val specification: String) : MenuAction
enum class CatcherType { CHAT }
data class CatcherStage(
    val id: String,
    val type: CatcherType,
    val start: List<ActionReaction>,
    val cancel: List<ActionReaction>,
    val end: List<ActionReaction>,
)
data class CatcherAction(val stages: List<CatcherStage>) : MenuAction
data object RetypeAction : MenuAction
data class ActionOptions(
    val delayTicks: Long = 0,
    val chance: Double = 1.0,
    val condition: ConditionExpression? = null,
    val players: ConditionExpression? = null,
    val allOnlinePlayers: Boolean = false,
)
data class ConfiguredAction(val action: MenuAction, val options: ActionOptions) : MenuAction
data object CloseAction : MenuAction
data object BackAction : MenuAction
data object ReturnAction : MenuAction

sealed interface ConditionExpression
data class BooleanCondition(val value: Boolean) : ConditionExpression
data class PermissionCondition(val permission: String) : ConditionExpression
data class TruthyCondition(val value: String) : ConditionExpression
data class NotCondition(val expression: ConditionExpression) : ConditionExpression
data class AllCondition(val expressions: List<ConditionExpression>) : ConditionExpression
data class AnyCondition(val expressions: List<ConditionExpression>) : ConditionExpression
data class CompareCondition(val left: String, val operator: CompareOperator, val right: String) : ConditionExpression
enum class CompareOperator { EQUALS, NOT_EQUALS, GREATER, GREATER_OR_EQUAL, LESS, LESS_OR_EQUAL, CONTAINS }

data class ActionReaction(
    val priority: Int,
    val order: Int,
    val condition: ConditionExpression? = null,
    val actions: List<MenuAction>,
    val deny: List<MenuAction> = emptyList(),
)

data class ClickActionGroup(val trigger: ClickTrigger, val reactions: List<ActionReaction>)

data class ClickActions(val groups: List<ClickActionGroup> = emptyList()) {
    fun matching(input: ClickInput): List<ActionReaction> = groups
        .filter { it.trigger.matches(input) }
        .flatMap { group -> group.reactions.sortedWith(compareBy<ActionReaction> { it.priority }.thenBy { it.order }) }

    fun allActions(): Sequence<MenuAction> = groups.asSequence().flatMap { group ->
        group.reactions.asSequence().flatMap { sequenceOf(it.actions.asSequence(), it.deny.asSequence()).flatten() }
    }
}

data class MenuEvents(
    val open: List<ActionReaction> = emptyList(),
    val close: List<ActionReaction> = emptyList(),
)

class BehaviorSyntaxException(message: String) : IllegalArgumentException(message)

object ActionLanguage {
    private val menuId = Regex("[a-z0-9][a-z0-9_-]*")
    private val extensionRoute = Regex("[a-z0-9_.-]+:[a-z0-9_./-]+")
    private val actionBoundary = Regex(" ?(?:_\\|\\|_|&&&) ?")
    private val delayOption = Regex("(?i)[{<](?:delay|wait)[=:] ?([0-9]+)[}>]")
    private val chanceOption = Regex("(?i)[{<](?:chance|rate|rand(?:om)?)[=:] ?([0-9.]+)[}>]")
    private val conditionOption = Regex("(?i)\\{(?:condition|requirement)[=:] ?(.+?)}")
    private val playersOption = Regex("(?i)[{<]players(?:[=:] ?(.*?))?[}>]")
    private val compactAliases = mapOf(
        "sendtitle" to "send-title", "sendtitles" to "send-titles", "sendsubtitle" to "send-subtitle", "sendsubtitles" to "send-subtitles",
        "sendbossbar" to "send-bossbar", "sendbossbars" to "send-bossbars", "playsound" to "play-sound", "playsounds" to "play-sounds",
        "opengui" to "open-gui", "silentopen" to "open", "forceopen" to "open",
        "silentmenu" to "menu", "forcemenu" to "menu", "silentclose" to "silent-close", "forceclose" to "force-close",
        "iconrefresh" to "icon-refresh", "iconupdate" to "icon-update", "setmeta" to "set-meta", "setmetas" to "set-metas", "settemp" to "set-temp",
        "setvar" to "set-var", "setvariable" to "set-variable", "removemeta" to "remove-meta", "remmeta" to "rem-meta",
        "delmeta" to "del-meta", "removedata" to "remove-data", "removedatas" to "remove-datas", "remdata" to "rem-data", "deldata" to "del-data",
        "setdata" to "set-data", "setdatas" to "set-datas", "setglobaldata" to "set-global-data", "setglobaldatas" to "set-global-datas", "removeglobaldata" to "remove-global-data",
        "remglobaldata" to "rem-global-data", "delglobaldata" to "del-global-data", "setarg" to "set-arg", "setargs" to "set-args",
        "setargument" to "set-argument", "setarguments" to "set-arguments", "cleararg" to "clear-arg", "clearargs" to "clear-args", "delargs" to "del-args", "remargs" to "rem-args",
        "reloadinv" to "reload-inv", "reloadinventory" to "reload-inventory", "giveitem" to "give-item", "giveitems" to "give-items",
        "additem" to "add-item", "additems" to "add-items", "takeitem" to "take-item", "takeitems" to "take-items", "removeitem" to "remove-item",
        "removeitems" to "remove-items", "repairitem" to "repair-item", "repairitems" to "repair-items",
        "enchantitem" to "enchant-item", "enchantitems" to "enchant-items", "inputcatcher" to "input-catcher", "givemoney" to "give-money", "addmoney" to "add-money",
        "depositmoney" to "deposit-money", "takemoney" to "take-money", "removemoney" to "remove-money",
        "withdrawmoney" to "withdraw-money", "setmoney" to "set-money", "modifymoney" to "modify-money",
        "givepoints" to "give-points", "addpoints" to "add-points", "depositpoints" to "deposit-points",
        "takepoints" to "take-points", "removepoints" to "remove-points", "withdrawpoints" to "withdraw-points",
        "setpoints" to "set-points", "modifypoints" to "modify-points", "reenter" to "re-enter",
    )

    fun parse(source: String): MenuAction {
        val raw = source.trim()
        if (raw.isEmpty()) throw BehaviorSyntaxException("动作不能为空")
        if (raw.startsWith('[')) throw BehaviorSyntaxException("[action] 不是 TrMenu v3 动作语法；请使用 action: value")
        val parts = raw.split(actionBoundary)
        if (parts.size > 1) {
            val parsed = parts.map(::parse)
            val shared = parsed.filterIsInstance<ConfiguredAction>().maxByOrNull { optionCount(it.options) }?.options
            return SequenceAction(if (shared == null) parsed else parsed.map { ConfiguredAction(unwrap(it), shared) })
        }

        val (content, options) = readOptions(raw)
        val (name, argument) = split(content)
        val action = when (val normalized = normalizeName(name)) {
            "tell", "message", "msg", "talk" -> TellAction(required(name, argument))
            "tellraw", "tellraws", "json", "jsons" -> TellRawAction(required(name, argument))
            "command", "cmd", "player", "execute" -> PlayerCommandAction(required(name, argument).removePrefix("/"))
            "console" -> ConsoleCommandAction(required(name, argument).removePrefix("/"))
            "op", "operator", "operators" -> OpCommandAction(required(name, argument).removePrefix("/"))
            "chat", "send", "say" -> ChatAction(required(name, argument))
            "action", "actions", "actionbar", "actionbars" -> ActionBarAction(required(name, argument))
            "title", "titles", "send-title", "send-titles", "subtitle", "subtitles", "send-subtitle", "send-subtitles" ->
                TitleAction(required(name, argument))
            "bossbar", "bossbars", "send-bossbar", "send-bossbars" -> BossBarAction(required(name, argument))
            "sound", "sounds", "play-sound", "play-sounds" -> SoundAction(required(name, argument))
            "open", "opens", "gui", "open-gui", "menu", "trmenu" -> openAction(required(name, argument))
            "open-app" -> {
                if (!name.equals("open-app", ignoreCase = true)) {
                    throw BehaviorSyntaxException("应用动作只支持规范写法 open-app")
                }
                applicationAction(required(name, argument))
            }
            "bungee", "server", "connect" -> ConnectAction(required(name, argument))
            "refresh", "icon-refresh", "update", "icon-update" -> RefreshAction(argument.takeIf { it.isNotBlank() && it != "*" && !it.equals("refresh", true) && !it.equals("update", true) })
            "animation", "animate", "play-animation" -> PlayAnimationAction(required(name, argument))
            "stop-animation", "cancel-animation" -> StopAnimationAction(required(name, argument))
            "delay", "wait" -> DelayAction(required(name, argument))
            "set-meta", "set-metas", "set-temp", "set-temps", "set-var", "set-vars", "set-variable", "set-variables" ->
                SetStateAction(StateScope.META, required(name, argument))
            "remove-meta", "remove-metas", "rem-meta", "rem-metas", "del-meta", "del-metas",
            "remove-temp", "rem-temp", "del-temp", "remove-var", "rem-var", "del-var" ->
                RemoveStateAction(StateScope.META, required(name, argument))
            "set-data", "set-datas" -> SetStateAction(StateScope.DATA, required(name, argument))
            "remove-data", "remove-datas", "rem-data", "rem-datas", "del-data", "del-datas" ->
                RemoveStateAction(StateScope.DATA, required(name, argument))
            "set-global-data", "set-global-datas", "set-g-data", "set-g-datas" ->
                SetStateAction(StateScope.GLOBAL, required(name, argument))
            "remove-global-data", "remove-global-datas", "rem-global-data", "rem-global-datas", "del-global-data", "del-global-datas",
            "remove-g-data", "rem-g-data", "del-g-data" -> RemoveStateAction(StateScope.GLOBAL, required(name, argument))
            "set-arg", "set-args", "set-argument", "set-arguments" -> SetArgumentsAction(required(name, argument))
            "clear-arg", "clear-args", "clear-argument", "clear-arguments", "cls-arg", "cls-args", "del-arg", "del-args", "rem-arg", "rem-args" ->
                noArgument(name, argument, ClearArgumentsAction)
            "reload-inv", "reload-inventory", "reload-inventories", "rl-inv", "rl-inventory" ->
                noArgument(name, argument, ReloadInventoryAction)
            "give-money", "give-moneys", "add-money", "add-moneys", "deposit-money", "deposit-moneys",
            "give-eco", "add-eco", "deposit-eco", "give-coin", "add-coin", "deposit-coin" ->
                BalanceAction(BalanceKind.MONEY, BalanceOperation.ADD, required(name, argument))
            "take-money", "take-moneys", "remove-money", "withdraw-money", "take-eco", "remove-eco", "withdraw-eco",
            "take-coin", "remove-coin", "withdraw-coin" ->
                BalanceAction(BalanceKind.MONEY, BalanceOperation.TAKE, required(name, argument))
            "set-money", "set-moneys", "modify-money", "set-eco", "modify-eco", "set-coin", "modify-coin" ->
                BalanceAction(BalanceKind.MONEY, BalanceOperation.SET, required(name, argument))
            "give-point", "give-points", "add-point", "add-points", "deposit-point", "deposit-points" ->
                BalanceAction(BalanceKind.POINTS, BalanceOperation.ADD, required(name, argument))
            "take-point", "take-points", "remove-point", "remove-points", "withdraw-point", "withdraw-points" ->
                BalanceAction(BalanceKind.POINTS, BalanceOperation.TAKE, required(name, argument))
            "set-point", "set-points", "modify-point", "modify-points" ->
                BalanceAction(BalanceKind.POINTS, BalanceOperation.SET, required(name, argument))
            "give-item", "give-items", "add-item", "add-items" -> GiveItemAction(required(name, argument))
            "take-item", "take-items", "remove-item", "remove-items" -> TakeItemAction(required(name, argument))
            "repair-item", "repair-items" -> RepairItemAction(required(name, argument))
            "enchant-item", "enchant-items" -> EnchantItemAction(required(name, argument))
            "retype", "repeat", "re-enter", "reenter", "enter", "type" -> noArgument(name, argument, RetypeAction)
            "close", "shut", "force-close", "silent-close", "force-shut", "silent-shut" -> noArgument(name, argument, CloseAction)
            "back" -> noArgument(name, argument, BackAction)
            "return", "break" -> noArgument(name, argument, ReturnAction)
            "consol" -> throw BehaviorSyntaxException("不支持错误拼写 consol；请使用 console")
            else -> throw BehaviorSyntaxException("尚未实现 TrMenu 动作: $normalized")
        }
        return if (options == ActionOptions()) action else ConfiguredAction(action, options)
    }

    data class SequenceAction(val actions: List<MenuAction>) : MenuAction

    private fun readOptions(source: String): Pair<String, ActionOptions> {
        var content = source
        val delay = delayOption.find(content)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val chance = chanceOption.find(content)?.groupValues?.get(1)?.toDoubleOrNull() ?: 1.0
        if (!chance.isFinite() || chance !in 0.0..1.0) throw BehaviorSyntaxException("Chance/Rate 必须介于 0 和 1")
        val condition = conditionOption.find(content)?.groupValues?.get(1)?.trim()?.takeIf(String::isNotEmpty)?.let(ConditionLanguage::parse)
        val playersMatch = playersOption.find(content)
        val playersSource = playersMatch?.groupValues?.getOrNull(1)?.trim().orEmpty()
        val players = playersSource.takeIf(String::isNotEmpty)?.let(ConditionLanguage::parse)
        for (regex in listOf(delayOption, chanceOption, conditionOption, playersOption)) content = regex.replace(content, "")
        return content.trim() to ActionOptions(delay, chance, condition, players, playersMatch != null && playersSource.isEmpty())
    }

    private fun optionCount(options: ActionOptions): Int =
        (if (options.delayTicks > 0) 1 else 0) + (if (options.chance < 1.0) 1 else 0) +
            (if (options.condition != null) 1 else 0) + (if (options.players != null || options.allOnlinePlayers) 1 else 0)

    private fun unwrap(action: MenuAction): MenuAction = if (action is ConfiguredAction) action.action else action

    internal fun normalizeName(name: String): String {
        val normalized = name.lowercase().replace('_', '-')
        return compactAliases[normalized.replace("-", "")] ?: normalized
    }

    private fun openAction(argument: String): OpenMenuAction {
        val words = quotedWords(argument)
        val target = words.firstOrNull() ?: throw BehaviorSyntaxException("open 动作缺少菜单 ID")
        val split = target.split(':', limit = 2)
        if (split.getOrNull(1)?.toIntOrNull()?.let { it != 0 } == true) {
            throw BehaviorSyntaxException("Display 菜单没有 TrMenu 容器 page；请把页面拆成独立菜单并使用 open")
        }
        if (extensionRoute.matches(target.lowercase())) return OpenMenuAction(target.lowercase(), words.drop(1))
        val rawId = split[0]
        val dynamic = '%' in rawId || '{' in rawId
        val id = if (dynamic) rawId else rawId.lowercase().also {
            if (!menuId.matches(it)) throw BehaviorSyntaxException("open 的菜单 ID 必须为小写字母、数字、下划线或连字符")
        }
        return OpenMenuAction(id, words.drop(1), dynamic)
    }

    private fun applicationAction(argument: String): OpenApplicationAction {
        val words = quotedWords(argument)
        val target = words.firstOrNull() ?: throw BehaviorSyntaxException("open-app 动作缺少应用 ID")
        val dynamic = '%' in target || '{' in target
        val id = if (dynamic) target else target.lowercase().also {
            if (!extensionRoute.matches(it)) {
                throw BehaviorSyntaxException("open-app 的应用 ID 必须为 namespace:path")
            }
        }
        return OpenApplicationAction(id, words.drop(1), dynamic)
    }

    private fun quotedWords(source: String): List<String> {
        val words = mutableListOf<String>()
        var index = 0
        while (index < source.length) {
            while (index < source.length && source[index].isWhitespace()) index++
            if (index >= source.length) break
            if (source[index] == '`') {
                val end = source.indexOf('`', index + 1)
                if (end < 0) throw BehaviorSyntaxException("反引号参数未闭合")
                words += source.substring(index + 1, end).replace("\\s", " ")
                index = end + 1
            } else {
                val end = source.indexOf(' ', index).let { if (it < 0) source.length else it }
                words += source.substring(index, end)
                index = end + 1
            }
        }
        return words
    }

    private fun split(raw: String): Pair<String, String> {
        val colon = raw.indexOf(':')
        return if (colon >= 0) raw.substring(0, colon).trim() to raw.substring(colon + 1).trim()
        else raw to ""
    }

    private fun required(name: String, argument: String): String = argument.takeIf { it.isNotBlank() }
        ?: throw BehaviorSyntaxException("$name 动作缺少参数")

    private fun <T : MenuAction> noArgument(name: String, argument: String, action: T): T {
        if (argument.isNotBlank()) throw BehaviorSyntaxException("$name 动作不接受参数")
        return action
    }
}

interface ConditionContext {
    fun expand(value: String): String
    fun hasPermission(permission: String): Boolean
}

object ConditionLanguage {
    fun parse(source: String): ConditionExpression {
        val raw = source.trim()
        if (raw.isEmpty()) throw BehaviorSyntaxException("条件不能为空")
        return parseExpression(raw)
    }

    fun evaluate(expression: ConditionExpression, context: ConditionContext): Boolean = when (expression) {
        is BooleanCondition -> expression.value
        is PermissionCondition -> context.hasPermission(clean(context.expand(expression.permission)))
        is TruthyCondition -> truthy(clean(context.expand(expression.value)))
        is NotCondition -> !evaluate(expression.expression, context)
        is AllCondition -> expression.expressions.all { evaluate(it, context) }
        is AnyCondition -> expression.expressions.any { evaluate(it, context) }
        is CompareCondition -> compare(
            clean(context.expand(expression.left)), expression.operator, clean(context.expand(expression.right)),
        )
    }

    private fun parseExpression(source: String): ConditionExpression {
        val raw = stripOuterParentheses(source.trim())
        splitTopLevel(raw, "||")?.let { return AnyCondition(it.map(::parseExpression)) }
        splitTopLevel(raw, "&&")?.let { return AllCondition(it.map(::parseExpression)) }
        parseAggregate(raw, "all")?.let { return AllCondition(it.map(::parseExpression)) }
        parseAggregate(raw, "any")?.let { return AnyCondition(it.map(::parseExpression)) }
        if (raw.startsWith("not ", true)) return NotCondition(parseExpression(raw.substring(4)))
        if (raw.startsWith('!')) return NotCondition(parseExpression(raw.substring(1)))
        if (raw.equals("true", true)) return BooleanCondition(true)
        if (raw.equals("false", true)) return BooleanCondition(false)
        for (prefix in listOf("permission ", "perm ")) {
            if (raw.startsWith(prefix, true)) {
                val permission = raw.substring(prefix.length).trim().removePrefix("*")
                if (permission.isBlank() || permission.any(Char::isWhitespace)) throw BehaviorSyntaxException("permission 条件缺少有效权限节点")
                return PermissionCondition(permission)
            }
        }
        val comparison = raw.removePrefixIgnoreCase("check ")
        val operators = listOf(
            " is not " to CompareOperator.NOT_EQUALS,
            " contains " to CompareOperator.CONTAINS,
            ">=" to CompareOperator.GREATER_OR_EQUAL,
            "<=" to CompareOperator.LESS_OR_EQUAL,
            "!=" to CompareOperator.NOT_EQUALS,
            "==" to CompareOperator.EQUALS,
            " is " to CompareOperator.EQUALS,
            ">" to CompareOperator.GREATER,
            "<" to CompareOperator.LESS,
        )
        for ((token, operator) in operators) {
            val index = comparison.indexOf(token, ignoreCase = true)
            if (index > 0) {
                val left = comparison.substring(0, index).trim()
                val right = comparison.substring(index + token.length).trim()
                if (left.isEmpty() || right.isEmpty()) throw BehaviorSyntaxException("比较条件两侧都必须有值")
                return CompareCondition(left, operator, right)
            }
        }
        if (raw.contains('%') || raw.startsWith('*') || raw.startsWith('`') || raw.startsWith('"') || raw.startsWith('\'')) {
            return TruthyCondition(raw)
        }
        throw BehaviorSyntaxException("M2 不支持条件语法: $raw")
    }

    private fun parseAggregate(source: String, name: String): List<String>? {
        if (!source.startsWith("$name ", true)) return null
        val body = source.substring(name.length).trim()
        if (!body.startsWith('[') || !body.endsWith(']')) throw BehaviorSyntaxException("$name 条件必须使用 [ ... ]")
        val entries = splitDelimited(body.substring(1, body.length - 1))
        if (entries.isEmpty()) throw BehaviorSyntaxException("$name 条件不能为空")
        return entries
    }

    private fun splitDelimited(source: String): List<String> {
        val result = mutableListOf<String>()
        var start = 0
        var depth = 0
        for (index in source.indices) {
            when (source[index]) {
                '[', '(' -> depth++
                ']', ')' -> depth--
                ';', ',' -> if (depth == 0) {
                    source.substring(start, index).trim().takeIf(String::isNotEmpty)?.let(result::add)
                    start = index + 1
                }
            }
        }
        source.substring(start).trim().takeIf(String::isNotEmpty)?.let(result::add)
        return result
    }

    private fun splitTopLevel(source: String, operator: String): List<String>? {
        val result = mutableListOf<String>()
        var start = 0
        var depth = 0
        var index = 0
        while (index <= source.length - operator.length) {
            when (source[index]) {
                '[', '(' -> depth++
                ']', ')' -> depth--
            }
            if (depth == 0 && source.regionMatches(index, operator, 0, operator.length)) {
                result += source.substring(start, index).trim()
                start = index + operator.length
                index = start
            } else index++
        }
        if (result.isEmpty()) return null
        result += source.substring(start).trim()
        if (result.any(String::isEmpty)) throw BehaviorSyntaxException("逻辑运算符两侧都必须有条件")
        return result
    }

    private fun stripOuterParentheses(source: String): String {
        if (!source.startsWith('(') || !source.endsWith(')')) return source
        var depth = 0
        for (index in source.indices) {
            if (source[index] == '(') depth++ else if (source[index] == ')') depth--
            if (depth == 0 && index != source.lastIndex) return source
        }
        return source.substring(1, source.length - 1).trim()
    }

    private fun compare(left: String, operator: CompareOperator, right: String): Boolean {
        val leftNumber = left.toDoubleOrNull()
        val rightNumber = right.toDoubleOrNull()
        val order = if (leftNumber != null && rightNumber != null) leftNumber.compareTo(rightNumber)
            else left.compareTo(right, ignoreCase = true)
        return when (operator) {
            CompareOperator.EQUALS -> order == 0
            CompareOperator.NOT_EQUALS -> order != 0
            CompareOperator.GREATER -> order > 0
            CompareOperator.GREATER_OR_EQUAL -> order >= 0
            CompareOperator.LESS -> order < 0
            CompareOperator.LESS_OR_EQUAL -> order <= 0
            CompareOperator.CONTAINS -> left.contains(right, ignoreCase = true)
        }
    }

    private fun clean(value: String): String = value.trim().removePrefix("*").let {
        if (it.length >= 2 && ((it.first() == '`' && it.last() == '`') ||
                (it.first() == '"' && it.last() == '"') || (it.first() == '\'' && it.last() == '\''))) {
            it.substring(1, it.length - 1)
        } else it
    }

    private fun truthy(value: String): Boolean = value.equals("true", true) || value.equals("yes", true) ||
        value.equals("on", true) || value.toDoubleOrNull()?.let { it != 0.0 } == true

    private fun String.removePrefixIgnoreCase(prefix: String): String =
        if (startsWith(prefix, true)) substring(prefix.length) else this
}
