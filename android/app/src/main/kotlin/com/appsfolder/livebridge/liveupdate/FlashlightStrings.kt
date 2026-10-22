package com.kakao.taxi.liveupdate

import android.content.Context
import java.util.Locale

/**
 * Localized strings for the flashlight capsule (round 56).
 *
 * Until now the flashlight builder and its foreground service only
 * distinguished Russian from everything else (and used the DEVICE
 * locale), so every other language saw English. This table follows
 * the same rules as the charging capsule's AppLocale system: the
 * app language setting wins, the device locale is the fallback,
 * and the language set matches the rest of the app. The Russian
 * strings are upstream's originals, kept verbatim.
 */
internal object FlashlightStrings {
    private enum class FlashlightLocale {
        EN, RU, TR, PT_BR, ZH_HANS, ZH_HANT, KO, VI
    }

    private fun locale(context: Context): FlashlightLocale {
        val tag = ConverterPrefs(context).getAppLanguageTag()
            ?.trim()
            ?.replace('_', '-')
            ?.lowercase(Locale.ROOT)
            .orEmpty()
        if (tag.isNotBlank() && tag != "system") {
            return when {
                tag.startsWith("ru") -> FlashlightLocale.RU
                tag.startsWith("tr") -> FlashlightLocale.TR
                tag.startsWith("pt") -> FlashlightLocale.PT_BR
                tag.startsWith("zh") && isTraditionalTag(tag) -> FlashlightLocale.ZH_HANT
                tag.startsWith("zh") -> FlashlightLocale.ZH_HANS
                tag.startsWith("ko") -> FlashlightLocale.KO
                tag.startsWith("vi") -> FlashlightLocale.VI
                else -> FlashlightLocale.EN
            }
        }
        val device = context.resources.configuration.locales.get(0)
            ?: return FlashlightLocale.EN
        val language = device.language.lowercase(Locale.ROOT)
        return when {
            language.startsWith("ru") -> FlashlightLocale.RU
            language.startsWith("tr") -> FlashlightLocale.TR
            language.startsWith("pt") -> FlashlightLocale.PT_BR
            language.startsWith("zh") && isTraditionalLocale(device) ->
                FlashlightLocale.ZH_HANT
            language.startsWith("zh") -> FlashlightLocale.ZH_HANS
            language.startsWith("ko") -> FlashlightLocale.KO
            language.startsWith("vi") -> FlashlightLocale.VI
            else -> FlashlightLocale.EN
        }
    }

    private fun isTraditionalTag(tag: String): Boolean {
        return tag.contains("hant") ||
            tag.contains("-tw") ||
            tag.contains("-hk") ||
            tag.contains("-mo")
    }

    private fun isTraditionalLocale(locale: Locale): Boolean {
        val script = locale.script.lowercase(Locale.ROOT)
        val country = locale.country.uppercase(Locale.ROOT)
        return script == "hant" || country in setOf("TW", "HK", "MO")
    }

    fun title(context: Context): String {
        return when (locale(context)) {
            FlashlightLocale.RU -> "Фонарик включен"
            FlashlightLocale.TR -> "El feneri açık"
            FlashlightLocale.PT_BR -> "Lanterna ligada"
            FlashlightLocale.ZH_HANS -> "手电筒已打开"
            FlashlightLocale.ZH_HANT -> "手電筒已開啟"
            FlashlightLocale.KO -> "손전등 켜짐"
            FlashlightLocale.VI -> "Đèn pin đang bật"
            FlashlightLocale.EN -> "Flashlight on"
        }
    }

    fun chip(context: Context): String {
        return when (locale(context)) {
            FlashlightLocale.RU -> "Фонарик"
            FlashlightLocale.TR -> "El feneri"
            FlashlightLocale.PT_BR -> "Lanterna"
            FlashlightLocale.ZH_HANS -> "手电筒"
            FlashlightLocale.ZH_HANT -> "手電筒"
            FlashlightLocale.KO -> "손전등"
            FlashlightLocale.VI -> "Đèn pin"
            FlashlightLocale.EN -> "Flashlight"
        }
    }

    fun turnOff(context: Context): String {
        return when (locale(context)) {
            FlashlightLocale.RU -> "Отключить"
            FlashlightLocale.TR -> "Kapat"
            FlashlightLocale.PT_BR -> "Desativar"
            FlashlightLocale.ZH_HANS -> "关闭"
            FlashlightLocale.ZH_HANT -> "關閉"
            FlashlightLocale.KO -> "끄기"
            FlashlightLocale.VI -> "Tắt"
            FlashlightLocale.EN -> "Turn off"
        }
    }

    fun unavailable(context: Context): String {
        return when (locale(context)) {
            FlashlightLocale.RU -> "Фонарик недоступен"
            FlashlightLocale.TR -> "El feneri kullanılamıyor"
            FlashlightLocale.PT_BR -> "Lanterna indisponível"
            FlashlightLocale.ZH_HANS -> "手电筒不可用"
            FlashlightLocale.ZH_HANT -> "手電筒不可用"
            FlashlightLocale.KO -> "손전등을 사용할 수 없음"
            FlashlightLocale.VI -> "Đèn pin không khả dụng"
            FlashlightLocale.EN -> "Flashlight unavailable"
        }
    }

    fun fiveLevelUnavailable(context: Context): String {
        return when (locale(context)) {
            FlashlightLocale.RU -> "Яркость 1/5 не поддерживается"
            FlashlightLocale.TR -> "5 kademeli parlaklık kullanılamıyor"
            FlashlightLocale.PT_BR -> "Brilho em 5 níveis indisponível"
            FlashlightLocale.ZH_HANS -> "5 级亮度不可用"
            FlashlightLocale.ZH_HANT -> "5 級亮度不可用"
            FlashlightLocale.KO -> "5단계 밝기를 사용할 수 없음"
            FlashlightLocale.VI -> "Không có độ sáng 5 mức"
            FlashlightLocale.EN -> "5-step brightness is unavailable"
        }
    }

    fun warningUnavailable(context: Context): String {
        return when (locale(context)) {
            FlashlightLocale.RU ->
                "На этом устройстве нет доступного фонарика."
            FlashlightLocale.TR ->
                "Bu cihaz kullanılabilir bir el feneri sunmuyor."
            FlashlightLocale.PT_BR ->
                "Este dispositivo não oferece uma lanterna utilizável."
            FlashlightLocale.ZH_HANS -> "此设备没有可用的手电筒。"
            FlashlightLocale.ZH_HANT -> "此裝置沒有可用的手電筒。"
            FlashlightLocale.KO -> "이 기기에는 사용할 수 있는 손전등이 없습니다."
            FlashlightLocale.VI -> "Thiết bị này không có đèn pin khả dụng."
            FlashlightLocale.EN -> "This device does not expose a usable flashlight."
        }
    }

    fun warningNoLevels(context: Context): String {
        return when (locale(context)) {
            FlashlightLocale.RU ->
                "Устройство может только включать фонарик без 5 уровней яркости."
            FlashlightLocale.TR ->
                "Bu cihaz el fenerini açabilir ancak 5 parlaklık seviyesi sunmaz."
            FlashlightLocale.PT_BR ->
                "Este dispositivo pode ligar a lanterna, mas não oferece 5 níveis de brilho."
            FlashlightLocale.ZH_HANS -> "此设备可以打开手电筒，但不提供 5 级亮度。"
            FlashlightLocale.ZH_HANT -> "此裝置可以開啟手電筒，但不提供 5 級亮度。"
            FlashlightLocale.KO -> "이 기기는 손전등을 켤 수 있지만 5단계 밝기를 제공하지 않습니다."
            FlashlightLocale.VI ->
                "Thiết bị này bật được đèn pin nhưng không có 5 mức độ sáng."
            FlashlightLocale.EN ->
                "This device can turn the flashlight on, but it does not expose 5 brightness levels."
        }
    }

    fun channelName(context: Context): String {
        return chip(context)
    }

    fun channelDescription(context: Context): String {
        return when (locale(context)) {
            FlashlightLocale.RU ->
                "Показывает уведомление фонарика и выводит его в Now Bar"
            FlashlightLocale.TR ->
                "Bir el feneri kontrol bildirimi gösterir ve Now Bar'a yansıtır"
            FlashlightLocale.PT_BR ->
                "Mostra uma notificação de controle da lanterna e a espelha na Now Bar"
            FlashlightLocale.ZH_HANS -> "显示手电筒控制通知并将其镜像到 Now Bar"
            FlashlightLocale.ZH_HANT -> "顯示手電筒控制通知並將其鏡像到 Now Bar"
            FlashlightLocale.KO -> "손전등 제어 알림을 표시하고 Now Bar에 미러링합니다"
            FlashlightLocale.VI ->
                "Hiển thị thông báo điều khiển đèn pin và chiếu lên Now Bar"
            FlashlightLocale.EN ->
                "Shows a flashlight control notification and mirrors it into the Now Bar"
        }
    }
}
