package com.swpp.wakeup.data.repository

import com.swpp.wakeup.data.remote.RiskOptionsDto
import com.swpp.wakeup.domain.model.DurationDistributionView
import com.swpp.wakeup.domain.model.RiskOptionView
import com.swpp.wakeup.domain.model.RiskOptionsView
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun RiskOptionsDto.toView(zone: ZoneId): RiskOptionsView {
    val mapped = options.map { option ->
        val time = OffsetDateTime.parse(option.alarmAt).atZoneSameInstant(zone)
        RiskOptionView(
            key = option.key,
            label = option.label,
            alarmAt = time.format(DateTimeFormatter.ofPattern("h:mm")),
            meridiem = if (time.hour < 12) "AM" else "PM",
            probability = option.probability,
            sleepLabel = null,
            tau = option.tau,
        )
    }
    return RiskOptionsView(
        options = mapped,
        selectedKey = selectedKey?.takeIf { key -> mapped.any { it.key == key } },
        autoAdjusted = autoAdjusted,
        autoReason = autoReason,
        unavailableReason = unavailableReason,
        distribution = distribution?.let { model ->
            DurationDistributionView(
                bins = model.bins.map { DurationDistributionView.Bin(it.min, it.max, it.mass) },
                p50Minutes = model.p50,
                p90Minutes = model.p90,
                windowDays = model.windowDays,
                source = model.source,
            )
        },
    )
}
