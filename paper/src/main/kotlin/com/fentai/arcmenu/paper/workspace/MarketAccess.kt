package com.fentai.arcmenu.paper.workspace

import org.bukkit.command.CommandSender

internal fun canAccessMarket(sender: CommandSender): Boolean = sender.isOp && sender.hasPermission("arcmenu.market")
