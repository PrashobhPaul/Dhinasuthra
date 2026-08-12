package com.dhinasuthra.app.core.time

import com.dhinasuthra.app.core.TimeUtils
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

/**
 * Device time as the authority (experience spec §28). Business logic depends on
 * this abstraction, not raw system calls — testable, and a single seam for
 * timezone/DST context. TimeUtils remains the implementation detail.
 */
interface TimeProvider {
    fun now(): Instant
    fun localDate(): LocalDate
    fun localTime(): LocalTime
    fun zone(): ZoneId
    fun locale(): Locale
    fun epochDay(): Long
    fun minuteOfDay(): Int
}

class DeviceTimeProvider : TimeProvider {
    override fun now(): Instant = Instant.now()
    override fun localDate(): LocalDate = LocalDate.now(TimeUtils.zone())
    override fun localTime(): LocalTime = LocalTime.now(TimeUtils.zone())
    override fun zone(): ZoneId = TimeUtils.zone()
    override fun locale(): Locale = Locale.getDefault()
    override fun epochDay(): Long = TimeUtils.epochDay()
    override fun minuteOfDay(): Int = TimeUtils.minuteOfDay(Instant.now())
}
