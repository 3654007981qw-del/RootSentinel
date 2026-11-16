package com.sukisu.nova.ui.screen.appprofile

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.sukisu.nova.R

enum class Mode(@field:StringRes private val res: Int) {
    Default(R.string.sukisu_profile_default),
    Template(R.string.sukisu_profile_template),
    Custom(R.string.sukisu_profile_custom);

    val text: String
        @Composable get() = stringResource(res)
}
