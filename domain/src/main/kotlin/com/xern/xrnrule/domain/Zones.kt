package com.xern.xrnrule.domain

import java.time.ZoneId

/**
 * I54: the time zone another device set itself, the newest one: the phone's, which follows the
 * network or its location. Null while none has. The laptop goes by it, as Linux sets no zone
 * by itself: every zone change there is one picked by hand.
 */
fun World.sharedZone(): ZoneId? =
    otherZones.values.sortedByDescending { it.atWall }.firstNotNullOfOrNull { runCatching { ZoneId.of(it.zone) }.getOrNull() }

/** I54: this device set its own zone to [zone] at [nowWall]; the same world if it already had. */
fun World.withOwnZone(zone: ZoneId, nowWall: Long): World =
    if (ownZone?.zone == zone.id) this else copy(ownZone = DeviceZone(zone.id, nowWall))
