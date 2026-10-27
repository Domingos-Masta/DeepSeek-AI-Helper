package com.deepseek.plugin.settings

import com.deepseek.plugin.api.SettingsSnapshot

/** Converts [DeepSeekSettings] into a plain-data snapshot [SettingsSnapshot] for cross-module passing. */
fun DeepSeekSettings.toSnapshot(): SettingsSnapshot = SettingsSnapshot(
    provider = provider,
    apiKey = apiKey,
    model = model,
    agnesApiKey = agnesApiKey,
    agnesModel = agnesModel,
    agnesBaseUrl = agnesBaseUrl,
    nvidiaApiKey = nvidiaApiKey,
    nvidiaModel = nvidiaModel,
    nvidiaBaseUrl = nvidiaBaseUrl,
    openrouterApiKey = openrouterApiKey,
    openrouterModel = openrouterModel,
    openrouterBaseUrl = openrouterBaseUrl,
    zhipuApiKey = zhipuApiKey,
    zhipuModel = zhipuModel,
    zhipuBaseUrl = zhipuBaseUrl,
    anthropicApiKey = anthropicApiKey,
    anthropicModel = anthropicModel,
    anthropicBaseUrl = anthropicBaseUrl,
    codexApiKey = codexApiKey,
    codexModel = codexModel,
    codexBaseUrl = codexBaseUrl,
)
