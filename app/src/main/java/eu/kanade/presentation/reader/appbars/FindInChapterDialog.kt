package eu.kanade.presentation.reader.appbars

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.NavigateBefore
import androidx.compose.material.icons.automirrored.outlined.NavigateNext
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Find-in-chapter bar shown as a small dialog over the novel reader. Typing searches the
 * current chapter; prev/next step through matches. Match state is owned by the reader
 * activity (reported back by the active viewer), the dialog only renders it.
 */
@Composable
fun FindInChapterDialog(
    query: String,
    total: Int,
    current: Int,
    onQueryChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        title = null,
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    placeholder = { Text(stringResource(TDMR.strings.find_in_chapter_hint)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onNext() }),
                    trailingIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = stringResource(MR.strings.action_close),
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = when {
                            query.isBlank() -> ""
                            total == 0 -> stringResource(TDMR.strings.find_no_matches)
                            else -> "${current + 1} / $total"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onPrevious, enabled = total > 0) {
                        Icon(
                            Icons.AutoMirrored.Outlined.NavigateBefore,
                            contentDescription = stringResource(TDMR.strings.find_previous_match),
                        )
                    }
                    IconButton(onClick = onNext, enabled = total > 0) {
                        Icon(
                            Icons.AutoMirrored.Outlined.NavigateNext,
                            contentDescription = stringResource(TDMR.strings.find_next_match),
                        )
                    }
                }
            }
        },
    )
}
