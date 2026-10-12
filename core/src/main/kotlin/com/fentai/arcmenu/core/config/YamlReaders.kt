package com.fentai.arcmenu.core.config

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

fun strictYaml(): Yaml = Yaml(SafeConstructor(LoaderOptions().apply {
    isAllowDuplicateKeys = false
    codePointLimit = 2_000_000
}))
