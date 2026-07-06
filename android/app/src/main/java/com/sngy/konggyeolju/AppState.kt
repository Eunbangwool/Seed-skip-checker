package com.sngy.konggyeolju

/** 서비스 ↔ 액티비티 공유 상태 (단순 싱글턴) */
object AppState {
    const val NCH = 4

    val seed = IntArray(NCH)     // 채널별 감지 씨앗 수
    val skip = IntArray(NCH)     // 채널별 결주 이벤트 수
    val missed = IntArray(NCH)   // 채널별 누락 추정 총합

    @Volatile var conn: String = "미연결"
    @Volatile var speedKmh: Double = -1.0   // <0 = 알 수 없음
    @Volatile var lat: Double = Double.NaN
    @Volatile var lng: Double = Double.NaN

    data class Skip(
        val timeMs: Long,
        val ch: Int,
        val miss: Int,
        val lat: Double,
        val lng: Double
    )

    val skips = ArrayList<Skip>()

    /** UI 갱신 콜백 (액티비티가 등록) */
    @Volatile var uiListener: (() -> Unit)? = null
    fun notifyUi() { uiListener?.invoke() }
}
