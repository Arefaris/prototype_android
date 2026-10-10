package app.gamenative.prototype.companion

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

/**
 * The second screen, styled after the game's pause menu: the GENTEK menu on the left (map, upgrades, then the
 * shortcuts), the pause map or the upgrade store on the right.
 */
@Composable
fun CompanionScreen(model: CompanionModel, assets: StateFlow<GameAssets>, onScreenshot: () -> Unit) {
    val state by model.state.collectAsState()
    val gameAssets by assets.collectAsState()
    var selected by remember { mutableStateOf<Marker?>(null) }
    var zoomRequest by remember { mutableIntStateOf(0) }

    LaunchedEffect(state.message) {
        if (state.message != null) {
            delay(2500)
            model.clearMessage()
        }
    }

    CompositionLocalProvider(LocalGentekFonts provides remember(gameAssets) { GentekFonts(gameAssets.titleFont, gameAssets.bodyFont) }) {
        Row(Modifier.fillMaxSize().gentekBackdrop().padding(8.dp)) {
            Rail(state, model, onScreenshot)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f).fillMaxHeight().gentekFrame()) {
                when (model.tab) {
                    CompanionTab.MAP -> MapPanel(state, gameAssets, selected, { selected = it }, zoomRequest) { zoomRequest += it }
                    CompanionTab.UPGRADES -> StorePanel(state, model)
                }
                if (!state.inWorld) LogoOverlay()
                state.message?.let { Toast(it, Modifier.align(Alignment.BottomCenter).padding(12.dp)) }
            }
        }
    }
}

@Composable
private fun Rail(state: CompanionState, model: CompanionModel, onScreenshot: () -> Unit) {
    val fonts = LocalGentekFonts.current
    Column(Modifier.width(140.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            "GENTEK",
            style = fonts.logo(26.sp, Gentek.Title),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
        MenuItem("MAP", model.tab == CompanionTab.MAP) { model.tab = CompanionTab.MAP }
        MenuItem("UPGRADES", model.tab == CompanionTab.UPGRADES) { model.showUpgrades() }
        Divider()
        MenuItem(if (state.saving) "SAVING…" else "SAVE", false, state.inWorld && !state.saving) { model.save() }
        MenuItem("SCREENSHOT", false, state.connected, onClick = onScreenshot)
        Spacer(Modifier.weight(1f))
        Box(Modifier.fillMaxWidth().height(30.dp).gentekFrame(Color(0x99000000), corner = 4.dp), contentAlignment = Alignment.CenterEnd) {
            Text("${state.ep} EP", style = fonts.title(16.sp, Gentek.Cream), modifier = Modifier.padding(horizontal = 8.dp), maxLines = 1)
        }
    }
}

// The pause menu's separator: a dashed red rule
@Composable
private fun Divider() {
    Box(
        Modifier.fillMaxWidth().height(10.dp).drawBehind {
            drawLine(
                Gentek.Line,
                Offset(0f, size.height / 2),
                Offset(size.width, size.height / 2),
                strokeWidth = 2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(14.dp.toPx(), 4.dp.toPx())),
            )
        },
    )
}

@Composable
private fun MapPanel(
    state: CompanionState,
    assets: GameAssets,
    selected: Marker?,
    onSelect: (Marker?) -> Unit,
    zoomRequest: Int,
    onZoom: (Int) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        MiniMap(state, assets, selected, onSelect, zoomRequest, Modifier.fillMaxSize().padding(1.dp))
        Column(Modifier.align(Alignment.TopEnd).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ZoomButton("+") { onZoom(1) }
            ZoomButton("−") { onZoom(-1) }
        }
        selected?.let { MarkerLabel(it, Modifier.align(Alignment.TopStart).padding(8.dp)) }
        Objective(state, Modifier.align(Alignment.BottomEnd).padding(10.dp))
    }
}

@Composable
private fun ZoomButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(38.dp).gentekFrame(Color(0xD0100404), corner = 5.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = LocalGentekFonts.current.title(22.sp, Gentek.Khaki))
    }
}

@Composable
private fun MarkerLabel(marker: Marker, modifier: Modifier) {
    Text(
        marker.name.ifBlank { "MARKER" },
        style = LocalGentekFonts.current.title(15.sp, Gentek.Cream),
        modifier = modifier.gentekFrame(Color(0xE0100404), corner = 5.dp).padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

// As on the pause map: a red-orange header, then the mission and objective lines in cream
@Composable
private fun Objective(state: CompanionState, modifier: Modifier) {
    if (state.objectiveHeader.isBlank() && state.objectiveLines.isEmpty()) return
    val fonts = LocalGentekFonts.current
    Column(
        modifier
            .widthIn(max = 300.dp)
            .background(Brush.horizontalGradient(listOf(Color.Transparent, Color(0xC0100303), Color(0xC0100303)))),
        horizontalAlignment = Alignment.End,
    ) {
        if (state.objectiveHeader.isNotBlank()) {
            Text(state.objectiveHeader, style = fonts.title(17.sp, Gentek.Orange, glow = Color.Black), textAlign = TextAlign.End)
        }
        for (line in state.objectiveLines) {
            Text(line, style = fonts.title(14.sp, Gentek.Cream, glow = Color.Black), textAlign = TextAlign.End)
        }
    }
}

@Composable
private fun StorePanel(state: CompanionState, model: CompanionModel) {
    val fonts = LocalGentekFonts.current
    val categories = state.categories
    if (categories.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("READING UPGRADES…", style = fonts.title(16.sp, Gentek.Khaki))
        }
        return
    }
    val index = model.category.coerceIn(0, categories.size - 1)
    val category = categories[index]
    Column(Modifier.fillMaxSize().padding(6.dp)) {
        CategoryBar(categories, index) { model.category = it }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.weight(1f)) {
            LazyColumn(Modifier.weight(1.35f).fillMaxHeight()) {
                if (category.sections.isEmpty()) {
                    item { Text("NOTHING TO PURCHASE", style = fonts.title(14.sp, Gentek.Khaki.copy(alpha = 0.6f)), modifier = Modifier.padding(8.dp)) }
                }
                for (section in category.sections) {
                    item(key = "h" + section.name) { SectionHeader(section.name) }
                    items(section.items, key = { it.id }) { item ->
                        UpgradeRow(item, item.id == model.selectedUpgrade, item.cost <= state.ep) { model.selectUpgrade(item.id) }
                    }
                }
            }
            Spacer(Modifier.width(6.dp))
            Details(state, model, category, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

// The store's top bar: ‹ previous | CURRENT | next ›
@Composable
private fun CategoryBar(categories: List<UpgradeCategory>, index: Int, onSelect: (Int) -> Unit) {
    val fonts = LocalGentekFonts.current
    val count = categories.size
    Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
        Arrow("‹") { onSelect((index - 1 + count) % count) }
        for (offset in -1..1) {
            val i = (index + offset + count) % count
            val current = offset == 0
            Box(
                Modifier
                    .weight(if (current) 1.3f else 1f)
                    .fillMaxHeight()
                    .background(
                        if (current) Brush.verticalGradient(listOf(Gentek.SelectedTop, Gentek.SelectedBottom))
                        else Brush.verticalGradient(listOf(Gentek.ItemTop, Gentek.ItemBottom)),
                    )
                    .clickable { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    categories[i].name,
                    style = fonts.title(if (current) 16.sp else 13.sp, if (current) Gentek.Cream else Gentek.Khaki, glow = if (current) Gentek.LineBright else null),
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
            }
        }
        Arrow("›") { onSelect((index + 1) % count) }
    }
}

@Composable
private fun Arrow(label: String, onClick: () -> Unit) {
    Box(Modifier.width(26.dp).fillMaxHeight().background(Gentek.ItemBottom).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, style = LocalGentekFonts.current.title(22.sp, Gentek.LineBright))
    }
}

@Composable
private fun SectionHeader(name: String) {
    Box(
        Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 3.dp).gentekFrame(Color(0x99000000), corner = 4.dp).padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(name, style = LocalGentekFonts.current.title(15.sp, Gentek.Title), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// Selected: the bright red bar; others dim red. Bought entries say so instead of a price.
@Composable
private fun UpgradeRow(item: UpgradeItem, selected: Boolean, affordable: Boolean, onClick: () -> Unit) {
    val fonts = LocalGentekFonts.current
    val bought = item.status == UpgradeStatus.PURCHASED
    val background = when {
        selected -> Brush.verticalGradient(listOf(Gentek.SelectedTop, Gentek.SelectedBottom))
        else -> Brush.verticalGradient(listOf(Color(0xFF3A0B07), Color(0xFF270605)))
    }
    val text = when {
        selected -> Gentek.Cream
        bought -> Gentek.Khaki.copy(alpha = 0.55f)
        else -> Color(0xFFE8B8A0)
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 10.dp, top = 2.dp).height(28.dp).background(background).clickable(onClick = onClick).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(item.name, style = fonts.title(13.sp, text), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (item.status == UpgradeStatus.NEW && !selected) Text("NEW ", style = fonts.title(10.sp, Gentek.Amber))
        Text(
            when {
                bought -> "OWNED"
                else -> "${item.cost} EP"
            },
            style = fonts.title(13.sp, if (bought || affordable || selected) text else Gentek.Khaki.copy(alpha = 0.5f)),
            maxLines = 1,
        )
    }
}

// The store's right column: picture, description, CORE tag and price
@Composable
private fun Details(state: CompanionState, model: CompanionModel, category: UpgradeCategory, modifier: Modifier) {
    val fonts = LocalGentekFonts.current
    val item = category.sections.flatMap { it.items }.firstOrNull { it.id == model.selectedUpgrade }
    Column(modifier.gentekFrame(Color(0xB0000000), corner = 5.dp).padding(6.dp)) {
        if (item == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("SELECT AN UPGRADE", style = fonts.title(13.sp, Gentek.Khaki.copy(alpha = 0.6f)), textAlign = TextAlign.Center)
            }
            return@Column
        }
        val details = state.details?.takeIf { it.id == item.id }
        Box(Modifier.fillMaxWidth().aspectRatio(1.37f).background(Color.Black), contentAlignment = Alignment.Center) {
            details?.picture?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            details?.description.orEmpty(),
            style = fonts.body(11.sp, Gentek.Orange),
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
        )
        if (item.core) {
            Box(Modifier.fillMaxWidth().padding(vertical = 3.dp).gentekFrame(Color(0x66000000), corner = 3.dp), contentAlignment = Alignment.Center) {
                Text("CORE", style = fonts.title(13.sp, Gentek.Cream))
            }
        }
        Text(
            if (item.status == UpgradeStatus.PURCHASED) "PURCHASED" else "${item.cost} EP",
            style = fonts.title(15.sp, Gentek.Cream),
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun Toast(text: String, modifier: Modifier) {
    Text(
        text,
        style = LocalGentekFonts.current.title(16.sp, Gentek.Cream, glow = Gentek.LineBright),
        modifier = modifier
            .background(Brush.verticalGradient(listOf(Gentek.SelectedTop, Gentek.SelectedBottom)))
            .padding(horizontal = 16.dp, vertical = 7.dp),
    )
}

// While Alex is not in the world (menus, loading, cut-scenes): the game's logo, as on its title screen
@Composable
private fun LogoOverlay() {
    Box(Modifier.fillMaxSize().background(Gentek.Black).scanLines(), contentAlignment = Alignment.Center) {
        Text("[PROTOTYPE]", style = LocalGentekFonts.current.logo(40.sp, Color(0xFFEDE3DA)).copy(shadow = Shadow(Gentek.LineBright, Offset.Zero, 24f)))
    }
}
