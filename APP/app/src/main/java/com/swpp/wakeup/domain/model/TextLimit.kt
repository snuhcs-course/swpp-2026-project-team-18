package com.swpp.wakeup.domain.model

// AI-generated (Claude)
/**
 * 서버와 같은 방식으로 글자 수를 센다.
 *
 * 서버(Python `len`)는 코드 포인트를 세고, Kotlin `String.length` 는 UTF-16 단위를 센다. 이모지처럼 두
 * 단위로 된 글자가 섞이면 앱의 "20자" 와 서버의 "20자" 가 달라진다. 한도를 세거나 자를 때는 이 함수를 쓴다.
 */
fun String.codePointLength(): Int = codePointCount(0, length)

// AI-generated (Claude)
/** 앞에서 [max] 글자(코드 포인트)까지만 남긴다. 두 단위로 된 글자를 반으로 자르지 않는다 */
fun String.takeCodePoints(max: Int): String =
    if (codePointLength() <= max) this else substring(0, offsetByCodePoints(0, max))
