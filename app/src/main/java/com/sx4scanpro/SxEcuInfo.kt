package com.sx4scanpro

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Separate ECU Information page (opened from the cockpit top bar). It only shows what a read-only scan returned;
 * every field carries its own status, and nothing is filled in when the ECU did not answer.
 */
@Composable
internal fun SxEcuInfo(
    profile: EcuProfile?,
    diff: ProfileDiff?,
    linkText: String,
    connectionRows: List<Pair<String, String>> = emptyList(),
    explorer: ExplorerView? = null,
    liveFacts: AdapterFacts,
    mode01Count: Int,
    scanning: Boolean,
    canScan: Boolean,
    note: String,
    onScan: () -> Unit,
    onExportJson: () -> Unit,
    onExportCsv: () -> Unit,
    onExportPdf: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("ECU INFORMATION", color = Sx.Foreground, fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Text("Read-only scan of what the engine ECU reports about itself", color = Sx.MutedForeground, fontSize = 11.sp)
            }
            Pill("BACK", true, onBack)
            Pill(if (scanning) "SCANNING…" else "SCAN ECU", canScan && !scanning, onScan, strong = true)
        }
        if (note.isNotBlank()) Text(note, color = Sx.Warning, fontSize = 12.sp)
        if (!canScan && !scanning) Text("Connect the adapter and wait until the ECU responds to scan.", color = Sx.MutedForeground, fontSize = 12.sp)

        Section("1  CONNECTION") {
            Row2("Link", linkText)
            connectionRows.forEach { (label, value) -> Row2(label, value) }
            Row2("Adapter", liveFacts.name.ifBlank { "unknown" })
            Row2("Adapter ID", liveFacts.id.ifBlank { "not reported" })
            Row2("Battery voltage (ATRV)", liveFacts.voltage.ifBlank { "not reported" })
            Row2("Bus protocol", liveFacts.protocol.ifBlank { "not reported" })
            Row2("Mode 01 PIDs advertised", if (mode01Count > 0) mode01Count.toString() else "none yet")
            explorer?.identity?.forEach { (label, value) -> Row2(label, value) }
        }
        if (explorer != null) ExplorerSections(explorer)
        if (profile == null) {
            Section("NO SCAN YET") { Text("Tap SCAN ECU while connected. Nothing is shown here until the ECU has actually answered.", color = Sx.MutedForeground, fontSize = 12.sp) }
        } else {
            Text("Last scan: " + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(profile.capturedAtMs)), color = Sx.MutedForeground, fontSize = 11.sp)
            FieldSection("2  VIN", profile.vin)
            FieldSection("3  CALIBRATION IDS", profile.calIds)
            FieldSection("4  CALIBRATION VERIFICATION NUMBERS", profile.cvns)
            FieldSection("5  ECU NAME", profile.ecuName)
            FieldSection("6  MODE 09 ITEMS OFFERED", profile.mode09Supported)
            Section("7  MODE 01 PIDS ADVERTISED (${profile.mode01Supported.size})") {
                Text(if (profile.mode01Supported.isEmpty()) "None recorded." else profile.mode01Supported.joinToString("  ") { "%02X".format(it) }, color = Sx.Foreground, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
            Section("8  COMPARISON WITH PREVIOUS SCAN") {
                if (diff == null) Text("No earlier scan on this tablet to compare with.", color = Sx.MutedForeground, fontSize = 12.sp)
                else {
                    if (diff.identityChanged) Text("The ECU identity differs from the previous scan.", color = Sx.Warning, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    EcuReport.diffText(diff).forEach { Text(it, color = Sx.Foreground, fontSize = 12.sp) }
                }
            }
            if (profile.notes.isNotEmpty()) Section("NOTES") { profile.notes.forEach { Text(it, color = Sx.MutedForeground, fontSize = 12.sp) } }
            Section("9  EXPORT") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Pill("JSON", true, onExportJson); Pill("CSV", true, onExportCsv); Pill("PDF", true, onExportPdf)
                }
            }
        }
    }
}

@Composable
private fun Pill(text: String, enabled: Boolean, onClick: () -> Unit, strong: Boolean = false) {
    val shape = RoundedCornerShape(50)
    Text(
        text, color = if (!enabled) Sx.MutedForeground else if (strong) Sx.Background else Sx.Foreground, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp,
        modifier = Modifier.clip(shape).background(if (strong && enabled) Sx.Primary else Color.Transparent).border(1.dp, if (enabled) Sx.Primary else Sx.Border, shape)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().sxPanel().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = Sx.Primary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
        content()
    }
}

@Composable
private fun Row2(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Sx.MutedForeground, fontSize = 12.sp)
        Text(value, color = Sx.Foreground, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun FieldSection(title: String, f: ProfileField) {
    Section(title) {
        if (f.status == ScanStatus.READ && f.items.isNotEmpty()) f.items.forEach { Text(it, color = Sx.Foreground, fontSize = 15.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium) }
        else Text(EcuReport.statusText(f.status), color = Sx.MutedForeground, fontSize = 13.sp)
        if (f.note.isNotBlank()) Text(f.note, color = Sx.Warning, fontSize = 11.sp)
        if (f.rawHex.isNotBlank()) Text("raw: " + f.rawHex, color = Sx.MutedForeground, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun ExplorerSections(x: ExplorerView) {
    Section("MODULE STATUS") {
        x.modules.forEach { m ->
            Row2(m.id.label, m.status.name)
            Text(m.note + (m.address?.let { " Address as captured: $it." } ?: ""), color = Sx.MutedForeground, fontSize = 11.sp)
        }
    }
    Section("PARAMETER REGISTRY") {
        Text(ParamState.values().joinToString("  ·  ") { it.name + " " + (x.counts[it] ?: 0) }, color = Sx.Foreground, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        if (x.params.isEmpty()) Text("Nothing has been recorded yet. Connect to the ECU so discovery can run; nothing is shown until the ECU has answered.", color = Sx.MutedForeground, fontSize = 12.sp)
        x.params.forEach { p ->
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Row2(p.id + "  " + p.name, p.state.name)
                Text(p.meaning, color = Sx.MutedForeground, fontSize = 11.sp)
                p.evidence.forEach { Text("· $it", color = Sx.MutedForeground, fontSize = 10.sp, fontFamily = FontFamily.Monospace) }
            }
        }
        if (x.hidden > 0) Text("${x.hidden} more recorded parameters are not shown here; they are included in the session export.", color = Sx.MutedForeground, fontSize = 11.sp)
    }
}
