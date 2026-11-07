/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.routines

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.portalinstaller.R

/** The bundled pictures (identical VectorDrawables to the Portal's, so both sides match). */
internal object RoutinePictureRes {
    private val pics = mapOf(
        "shirt" to R.drawable.routine_pic_shirt, "bowl" to R.drawable.routine_pic_bowl,
        "toothbrush" to R.drawable.routine_pic_toothbrush, "shoes" to R.drawable.routine_pic_shoes,
        "backpack" to R.drawable.routine_pic_backpack, "coat" to R.drawable.routine_pic_coat,
        "bed" to R.drawable.routine_pic_bed, "bottle" to R.drawable.routine_pic_bottle,
        "socks" to R.drawable.routine_pic_socks, "lunch" to R.drawable.routine_pic_lunch,
        "hair" to R.drawable.routine_pic_hair, "wash" to R.drawable.routine_pic_wash,
        "toilet" to R.drawable.routine_pic_toilet, "hat" to R.drawable.routine_pic_hat,
        "apple" to R.drawable.routine_pic_apple, "bath" to R.drawable.routine_pic_bath,
        "pyjamas" to R.drawable.routine_pic_pyjamas, "book" to R.drawable.routine_pic_book,
        "teddy" to R.drawable.routine_pic_teddy, "moon" to R.drawable.routine_pic_moon,
        "toys" to R.drawable.routine_pic_toys, "cup" to R.drawable.routine_pic_cup,
        "car" to R.drawable.routine_pic_car,
    )
    fun pic(id: String) = pics[id] ?: R.drawable.routine_pic_shirt
    fun avatar(id: String?) = when (id) {
        "fox" -> R.drawable.routine_avatar_fox
        "owl" -> R.drawable.routine_avatar_owl
        else -> R.drawable.routine_avatar_everyone
    }
}

@Composable
internal fun RoutinePicture(id: String, size: Dp, modifier: Modifier = Modifier) =
    Image(painterResource(RoutinePictureRes.pic(id)), null, modifier.size(size))

@Composable
internal fun RoutineAvatar(id: String?, size: Dp = 36.dp, modifier: Modifier = Modifier) =
    Image(painterResource(RoutinePictureRes.avatar(id)), null, modifier.size(size))
