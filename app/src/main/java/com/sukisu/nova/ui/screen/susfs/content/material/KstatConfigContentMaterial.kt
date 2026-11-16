package com.sukisu.nova.ui.screen.susfs.content.material

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sukisu.nova.R
import com.sukisu.nova.ui.screen.susfs.component.material.AddKstatPathItemCardMaterial
import com.sukisu.nova.ui.screen.susfs.component.material.BottomActionButtonsMaterial
import com.sukisu.nova.ui.screen.susfs.component.material.DescriptionCardMaterial
import com.sukisu.nova.ui.screen.susfs.component.material.EmptyStateCardMaterial
import com.sukisu.nova.ui.screen.susfs.component.material.KstatConfigItemCardMaterial
import com.sukisu.nova.ui.screen.susfs.component.material.SectionHeaderMaterial

@Composable
fun KstatConfigContentMaterial(
    kstatConfigs: Set<String>,
    addKstatPaths: Set<String>,
    isLoading: Boolean,
    onAddKstatStatically: () -> Unit,
    onAddKstat: () -> Unit,
    onRemoveKstatConfig: (String) -> Unit,
    onEditKstatConfig: (String) -> Unit,
    onRemoveAddKstat: (String) -> Unit,
    onEditAddKstat: (String) -> Unit,
    onUpdateKstat: (String) -> Unit,
    onUpdateKstatFullClone: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DescriptionCardMaterial(
            title = stringResource(R.string.sukisu_kstat_config_description_title),
            description = stringResource(R.string.sukisu_kstat_config_description_add_statically) + "\n" +
                    stringResource(R.string.sukisu_kstat_config_description_add) + "\n" +
                    stringResource(R.string.sukisu_kstat_config_description_update) + "\n" +
                    stringResource(R.string.sukisu_kstat_config_description_update_full_clone)
        )

        if (kstatConfigs.isNotEmpty()) {
            SectionHeaderMaterial(
                title = stringResource(R.string.sukisu_static_kstat_config),
                subtitle = null,
                icon = Icons.Default.Settings,
                count = kstatConfigs.size
            )
            kstatConfigs.sorted().forEach { config ->
                KstatConfigItemCardMaterial(
                    config = config,
                    onDelete = { onRemoveKstatConfig(config) },
                    onEdit = { onEditKstatConfig(config) },
                    isLoading = isLoading
                )
            }
        }

        if (addKstatPaths.isNotEmpty()) {
            SectionHeaderMaterial(
                title = stringResource(R.string.sukisu_kstat_path_management),
                subtitle = null,
                icon = Icons.Default.Folder,
                count = addKstatPaths.size
            )
            addKstatPaths.sorted().forEach { path ->
                AddKstatPathItemCardMaterial(
                    path = path,
                    onDelete = { onRemoveAddKstat(path) },
                    onEdit = { onEditAddKstat(path) },
                    onUpdate = { onUpdateKstat(path) },
                    onUpdateFullClone = { onUpdateKstatFullClone(path) },
                    isLoading = isLoading
                )
            }
        }

        if (kstatConfigs.isEmpty() && addKstatPaths.isEmpty()) {
            EmptyStateCardMaterial(message = stringResource(R.string.sukisu_no_kstat_config_message))
        }
    }

    BottomActionButtonsMaterial(
        primaryButtonText = stringResource(R.string.sukisu_add_kstat_path_title),
        onPrimaryClick = onAddKstat,
        secondaryButtonText = stringResource(R.string.sukisu_add_kstat_statically_title),
        onSecondaryClick = onAddKstatStatically,
        isLoading = isLoading
    )
}
