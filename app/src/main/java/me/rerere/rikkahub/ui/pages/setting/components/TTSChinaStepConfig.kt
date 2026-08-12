package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.OutlinedNumberInput
import me.rerere.rikkahub.ui.components.ui.SelectTextField
import me.rerere.tts.provider.TTSProviderSetting

@Composable
internal fun StepTTSConfiguration(
    setting: TTSProviderSetting.Step,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    // API Key
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        description = { Text("从阶跃星辰官网获取密钥: platform.stepfun.com/interface-key") }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { newApiKey ->
                onValueChange(setting.copy(apiKey = newApiKey))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("从阶跃星辰官网获取密钥") },
        )
    }

    // Base URL
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        description = { Text(stringResource(R.string.setting_tts_page_base_url_description)) }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { newBaseUrl ->
                onValueChange(setting.copy(baseUrl = newBaseUrl))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("https://api.stepfun.com") }
        )
    }

    // Model
    val models = listOf(
        "step-tts-mini" to "step-tts-mini (轻量, 便宜)",
        "step-tts-vivid" to "step-tts-vivid (情感丰富)",
        "stepaudio-2.5-tts" to "stepaudio-2.5-tts (语境感知, 支持 instruction)",
        "step-tts-2" to "step-tts-2 (上一代)",
    )

    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_model)) },
        description = { Text(stringResource(R.string.setting_tts_page_model_description)) }
    ) {
        SelectTextField(
            value = setting.model,
            options = models,
            onValueChange = { newModel ->
                onValueChange(setting.copy(model = newModel))
            },
            onOptionSelected = { (modelId, _) ->
                onValueChange(setting.copy(model = modelId))
            },
            optionToString = { (_, description) -> description },
            modifier = Modifier.fillMaxWidth()
        )
    }

    // Voice
    // 部分常用 voice-id, 完整列表见官方开发指南
    // https://platform.stepfun.com/docs/zh/guides/developer/tts
    val voices = listOf(
        "elegantgentle-female" to "气质温婉 (elegantgentle-female)",
        "livelybreezy-female" to "活力轻快 (livelybreezy-female)",
        "energeticconfident-female" to "活力自信 (energeticconfident-female)",
        "jingdiannvsheng" to "经典女声 (jingdiannvsheng)",
        "wenroushunv" to "温柔熟女 (wenroushunv)",
        "tianmeinvsheng" to "甜美女声 (tianmeinvsheng)",
        "qingchunshaonv" to "清纯少女 (qingchunshaonv)",
        "wenrounvsheng" to "温柔女声 (wenrounvsheng)",
        "ruanmengnvsheng" to "软萌女生 (ruanmengnvsheng)",
        "youyanvsheng" to "优雅女生 (youyanvsheng)",
        "lengyanyujie" to "冷艳御姐 (lengyanyujie)",
        "shuangkuaijiejie" to "爽快姐姐 (shuangkuaijiejie)",
        "wenjingxuejie" to "文静学姐 (wenjingxuejie)",
        "linjiajiejie" to "邻家姐姐 (linjiajiejie)",
        "linjiameimei" to "邻家妹妹 (linjiameimei)",
        "zhixingjiejie" to "知性姐姐 (zhixingjiejie)",
        "cixingnansheng" to "磁性男声 (cixingnansheng)",
        "wenrounansheng" to "温柔男声 (wenrounansheng)",
        "yuanqinansheng" to "元气男声 (yuanqinansheng)",
        "zhengpaiqingnian" to "正派青年 (zhengpaiqingnian)",
        "ruyananshi" to "儒雅男士 (ruyananshi)",
        "boyinnansheng" to "播音男声 (boyinnansheng)",
        "shenchennanyin" to "深沉男音 (shenchennanyin)",
        "shuangkuainansheng" to "爽快男声 (shuangkuainansheng)",
        "ganliannvsheng" to "干练女声 (ganliannvsheng)",
        "qinhenvsheng" to "亲切女声 (qinhenvsheng)",
        "huolinvsheng" to "活力女声 (huolinvsheng)",
        "jilingshaonv" to "机灵少女 (jilingshaonv)",
        "yuanqishaonv" to "元气少女 (yuanqishaonv)",
        "wenrougongzi" to "温柔公子 (wenrougongzi)",
        "qingniandaxuesheng" to "青年大学生 (qingniandaxuesheng)",
    )

    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_voice)) },
        description = { Text(stringResource(R.string.setting_tts_page_voice_description)) }
    ) {
        SelectTextField(
            value = setting.voice,
            options = voices,
            onValueChange = { newVoice ->
                onValueChange(setting.copy(voice = newVoice))
            },
            onOptionSelected = { (voiceId, _) ->
                onValueChange(setting.copy(voice = voiceId))
            },
            optionToString = { (_, description) -> description },
            modifier = Modifier.fillMaxWidth()
        )
    }

    // Response Format
    val formats = listOf("mp3", "wav", "pcm", "opus", "flac")

    FormItem(
        label = { Text("Response Format") },
        description = { Text("音频编码格式 (注意 StepFun API 字段名为 camelCase)") }
    ) {
        SelectTextField(
            value = setting.responseFormat,
            options = formats,
            onValueChange = { newFormat ->
                onValueChange(setting.copy(responseFormat = newFormat))
            },
            onOptionSelected = { format ->
                onValueChange(setting.copy(responseFormat = format))
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    // Speed
    FormItem(
        label = { Text(stringResource(R.string.setting_tts_page_speed)) },
        description = { Text("语速 (0.5 - 2.0, 1.0 为正常)") }
    ) {
        OutlinedNumberInput(
            value = setting.speed,
            onValueChange = { newSpeed ->
                if (newSpeed in 0.5f..2.0f) {
                    onValueChange(setting.copy(speed = newSpeed))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.setting_tts_page_speed)
        )
    }

    // Volume
    FormItem(
        label = { Text("Volume") },
        description = { Text("音量 (0.1 - 2.0, 1.0 为正常)") }
    ) {
        OutlinedNumberInput(
            value = setting.volume,
            onValueChange = { newVolume ->
                if (newVolume in 0.1f..2.0f) {
                    onValueChange(setting.copy(volume = newVolume))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = "Volume"
        )
    }

    // Sample Rate
    val sampleRates = listOf(8000, 16000, 22050, 24000)

    FormItem(
        label = { Text("Sample Rate") },
        description = { Text("采样率 (Hz)") }
    ) {
        SelectTextField(
            value = setting.sampleRate.toString(),
            options = sampleRates,
            readOnly = true,
            onOptionSelected = { rate ->
                onValueChange(setting.copy(sampleRate = rate))
            },
            optionToString = { rate -> "$rate Hz" },
            modifier = Modifier.fillMaxWidth()
        )
    }

    // Instruction (仅 stepaudio-2.5-tts 生效)
    FormItem(
        label = { Text("Instruction") },
        description = { Text("全局语境指令, 仅 stepaudio-2.5-tts 生效 (≤200 字符, 留空不下发)") }
    ) {
        OutlinedTextField(
            value = setting.instruction,
            onValueChange = { newInstruction ->
                // 服务端上限 200 字符, 客户端做一层保护
                if (newInstruction.length <= 200) {
                    onValueChange(setting.copy(instruction = newInstruction))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("例如: 语气温柔, 语速偏慢") },
            minLines = 2,
            maxLines = 4,
        )
    }
}

