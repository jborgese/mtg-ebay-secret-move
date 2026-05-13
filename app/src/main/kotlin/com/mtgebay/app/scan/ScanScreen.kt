package com.mtgebay.app.scan

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult

/**
 * Top-level scanning screen. Delegates capture + edge detection + perspective
 * correction to ML Kit Document Scanner (a Google Play Services component);
 * once ML Kit returns a dewarped JPEG, the local pipeline runs:
 *
 *     URI → BitmapFactory → BitmapPhasher → PhashDb → results list.
 */
@Composable
fun ScanScreen(
    viewModel: ScanViewModel = viewModel(),
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val scannerOptions = remember {
        GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(false)
            .setPageLimit(1)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
    }
    val scanner = remember { GmsDocumentScanning.getClient(scannerOptions) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { activityResult ->
        if (activityResult.resultCode != Activity.RESULT_OK) {
            Log.i(TAG, "ML Kit scanner cancelled (resultCode=${activityResult.resultCode})")
            return@rememberLauncherForActivityResult
        }
        val result = GmsDocumentScanningResult.fromActivityResultIntent(activityResult.data)
        val firstPage = result?.pages?.firstOrNull()?.imageUri
        if (firstPage != null) {
            viewModel.onDocumentScanned(firstPage)
        } else {
            viewModel.onScanError("ML Kit returned no pages")
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "MTG eBay",
                    style = MaterialTheme.typography.headlineLarge,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Tap below to scan a card. The Google scanner will guide you to align it.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
                Spacer(Modifier.height(32.dp))
                Button(
                    enabled = state !is ScanState.Working,
                    onClick = {
                        val activity = context.findActivity()
                        if (activity == null) {
                            viewModel.onScanError("Cannot find host Activity")
                            return@Button
                        }
                        scanner.getStartScanIntent(activity)
                            .addOnSuccessListener { intentSender ->
                                launcher.launch(IntentSenderRequest.Builder(intentSender).build())
                            }
                            .addOnFailureListener { exc ->
                                Log.e(TAG, "scanner.getStartScanIntent failed", exc)
                                viewModel.onScanError(exc.message ?: "Scanner launch failed")
                            }
                    },
                ) {
                    Text(if (state is ScanState.Working) "Working…" else "Scan card")
                }
            }
        }

        ScanResultPanel(
            state = state,
            onReset = viewModel::reset,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 160.dp, max = 320.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(16.dp),
        )
    }
}

@Composable
private fun ScanResultPanel(
    state: ScanState,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        is ScanState.Idle -> {
            Box(modifier, contentAlignment = Alignment.Center) {
                Text(
                    "Results will appear here.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        is ScanState.Working -> {
            Row(modifier, verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Text("Identifying…")
            }
        }
        is ScanState.NoCardDetected -> {
            Column(modifier) {
                Text(
                    "No matching card in the database.",
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Either the scan wasn't clean, or this printing isn't in the pHash DB yet.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onReset) { Text("Try again") }
            }
        }
        is ScanState.Error -> {
            Column(modifier) {
                Text(
                    "Error: ${state.message}",
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onReset) { Text("Reset") }
            }
        }
        is ScanState.Results -> {
            Column(modifier) {
                Text("Top matches:", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                state.hits.forEachIndexed { idx, hit ->
                    Text(
                        "${idx + 1}. ${hit.setCode.uppercase()} #${hit.collectorNumber}  d=${hit.distance}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "    ${hit.scryfallId}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onReset) { Text("Scan another") }
            }
        }
    }
}

/** Walk the context wrapper chain to find the host Activity. */
private fun Context.findActivity(): Activity? {
    var ctx: Context = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

private const val TAG = "ScanScreen"
