package be.mdw.bagosynths.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import be.mdw.bagosynths.R
import be.mdw.bagosynths.model.Digest
import be.mdw.bagosynths.model.Exchange
import be.mdw.bagosynths.model.Store

/*
 * The library, first slice (REQUIREMENTS section 8): setlists and songs as
 * library entries, and exchange through Android's file picker as an OCI Image
 * Layout archive (I8.8, I8.27). Import never conflicts: an object that exists
 * is the same object, and a divergent entry is kept beside the local one.
 */

private val PanelColor = Color(0xFF1A1D23)
private val DimColor = Color(0xFF8A919C)

@Composable
fun LibraryScreen(player: SongPlayer, lib: AppLibrary, onOpened: () -> Unit) {
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf("") }
    var newName by remember { mutableStateOf("") }
    val setlists = remember(revision) { lib.setlists() }
    val songs = remember(revision) { lib.songs() }
    var exportIds by remember { mutableStateOf<List<Digest>>(emptyList()) }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-tar")) { uri ->
        if (uri != null) message = runCatching { exportTo(context, lib.store, exportIds, uri) }
            .fold({ context.getString(R.string.exported, exportIds.size) },
                { context.getString(R.string.export_failed, it.message ?: "") })
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            message = runCatching {
                context.contentResolver.openInputStream(uri)!!.use { Exchange.import(lib.store, it) }
            }.fold({ r ->
                val counts = r.results.values.groupingBy { it::class.simpleName }.eachCount()
                context.getString(R.string.imported, r.objects, counts.toString())
            }, { context.getString(R.string.import_failed, it.message ?: "") })
            revision++
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { importer.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.import_archive)) }
            OutlinedButton(enabled = player.setlistId != null, onClick = {
                val sl = player.setlistId ?: return@OutlinedButton
                exportIds = listOf(sl) + lib.songsOf(sl).mapNotNull { it.first?.id }
                exporter.launch("${player.setlistName}.bagosynths.tar")
            }) { Text(stringResource(R.string.export_setlist)) }
            Text(message, color = DimColor, fontSize = 13.sp)
        }
        Spacer(Modifier.height(10.dp))
        AdaptivePair(380.dp, first = { m ->
            Column(m.background(PanelColor, RoundedCornerShape(10.dp)).padding(10.dp)) {
                Text(stringResource(R.string.setlists), fontWeight = FontWeight.SemiBold)
                LazyColumn(Modifier.weight(1f)) {
                    items(setlists) { e ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()
                            .clickable { player.openSetlist(e.id); onOpened() }.padding(vertical = 8.dp)) {
                            Text(e.version.name + if (e.id == player.setlistId) stringResource(R.string.on_stage) else "",
                                modifier = Modifier.weight(1f))
                            Text(e.id.short.take(7), color = DimColor, fontSize = 11.sp)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = newName, onValueChange = { newName = it }, singleLine = true,
                        label = { Text(stringResource(R.string.new_setlist)) }, modifier = Modifier.weight(1f))
                    Button(enabled = newName.isNotBlank(), onClick = {
                        lib.saveSetlist(null, newName.trim(), emptyList()); newName = ""; revision++
                    }) { Text(stringResource(R.string.create)) }
                }
            }
        }, second = { m ->
            Column(m.background(PanelColor, RoundedCornerShape(10.dp)).padding(10.dp)) {
                Text(stringResource(R.string.all_songs), fontWeight = FontWeight.SemiBold)
                LazyColumn {
                    items(songs) { s ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()
                            .padding(vertical = 4.dp)) {
                            Text(s.entry.version.name, modifier = Modifier.weight(1f))
                            val n = lib.store.history(s.entry.latest).size
                            Text(androidx.compose.ui.res.pluralStringResource(R.plurals.versions, n, n), color = DimColor, fontSize = 12.sp)
                            TextButton(enabled = player.setlistId != null, onClick = {
                                val sl = player.setlistId ?: return@TextButton
                                val ids = lib.songsOf(sl).mapNotNull { it.first?.id } + s.entry.id
                                lib.saveSetlist(sl, player.setlistName, ids)
                                player.reload(); revision++
                            }) { Text(stringResource(R.string.add_to_setlist)) }
                        }
                    }
                }
            }
        })
    }
}

private fun exportTo(context: Context, store: Store, ids: List<Digest>, uri: Uri) {
    context.contentResolver.openOutputStream(uri)!!.use { Exchange.export(store, ids, it) }
}
