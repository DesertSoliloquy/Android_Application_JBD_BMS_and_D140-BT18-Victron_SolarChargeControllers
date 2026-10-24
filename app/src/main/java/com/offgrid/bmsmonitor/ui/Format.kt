package com.offgrid.bmsmonitor.ui

/** Display formatting helpers that render a dash for missing values. */
object Fmt {
    fun v(d: Double?, digits: Int = 2) = if (d == null) "--" else "%.${digits}f V".format(d)
    fun a(d: Double?, digits: Int = 2) = if (d == null) "--" else "%.${digits}f A".format(d)
    fun aSigned(d: Double?) = if (d == null) "--" else "%+.1f A".format(d)
    fun w(d: Double?) = if (d == null) "--" else "%.0f W".format(d)
    fun pct(d: Double?) = if (d == null) "--" else "%.0f%%".format(d)
    fun temp(list: List<Double>) = if (list.isEmpty()) "--" else list.joinToString("  ") { "%.0f°C".format(it) }
    fun wh(d: Double?) = when {
        d == null -> "--"
        d >= 1000 -> "%.2f kWh".format(d / 1000)
        else -> "%.0f Wh".format(d)
    }
}
