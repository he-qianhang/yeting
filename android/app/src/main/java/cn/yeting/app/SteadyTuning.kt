package cn.yeting.app

internal object SteadyTuning {
    const val VERSION=3
    const val DETECTOR_LUFS=-24.0
    const val TARGET_LUFS=-18.0
    const val MAX_BOOST_DB=18.0
    const val LEGACY_BOOST_DB=12.0
    const val SLOW_THRESHOLD=.0158489319 // -36 dBFS
    const val FAST_THRESHOLD=.01995262315 // -34 dBFS
    const val KNEE=2.828427
    const val SLOW_RATIO=2.0
    const val FAST_RATIO=6.0
    const val SLOW_ATTACK_MS=150.0
    const val SLOW_RELEASE_MS=200.0
    const val FAST_ATTACK_MS=3.0
    const val FAST_RELEASE_MS=250.0
    val slowFilter="acompressor=threshold=$SLOW_THRESHOLD:ratio=$SLOW_RATIO:attack=$SLOW_ATTACK_MS:release=$SLOW_RELEASE_MS:"+
        "knee=$KNEE:link=maximum:detection=rms:makeup=1"
    val fastFilter="sidechaincompress=threshold=$FAST_THRESHOLD:ratio=$FAST_RATIO:attack=$FAST_ATTACK_MS:release=$FAST_RELEASE_MS:"+
        "knee=$KNEE:link=maximum:detection=rms:makeup=1"
}
