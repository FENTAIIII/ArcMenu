package com.fentai.arcmenu.paper.localization

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

/** Validation keeps English diagnostics until a player-facing boundary selects a locale. */
internal interface LocalizedFailure {
    val languageKey: String
    val languageArguments: Array<out Any?>
}

private class LocalizedArgumentException(
    override val languageKey: String,
    diagnostic: String,
    override val languageArguments: Array<out Any?>,
) : IllegalArgumentException(diagnostic), LocalizedFailure

private class LocalizedStateException(
    override val languageKey: String,
    diagnostic: String,
    override val languageArguments: Array<out Any?>,
) : IllegalStateException(diagnostic), LocalizedFailure

@OptIn(ExperimentalContracts::class)
internal fun localizedRequire(condition: Boolean, key: String, diagnostic: String, vararg arguments: Any?) {
    contract { returns() implies condition }
    if (!condition) throw localizedArgumentFailure(key, diagnostic, *arguments)
}

internal fun localizedArgumentFailure(key: String, diagnostic: String, vararg arguments: Any?): IllegalArgumentException =
    LocalizedArgumentException(key, diagnostic, arguments)

internal fun localizedStateFailure(key: String, diagnostic: String, vararg arguments: Any?): IllegalStateException =
    LocalizedStateException(key, diagnostic, arguments)

internal fun localizedError(key: String, diagnostic: String, vararg arguments: Any?): Nothing =
    throw localizedStateFailure(key, diagnostic, *arguments)

internal fun failureText(error: Throwable, fallback: String, translate: (String, Array<out Any?>) -> String): String =
    if (error is LocalizedFailure) translate(error.languageKey, error.languageArguments) else error.message ?: fallback
