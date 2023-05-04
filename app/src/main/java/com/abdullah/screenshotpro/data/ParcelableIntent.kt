package com.abdullah.screenshotpro.data

import android.content.Intent
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class ParcelableIntent(val intent: Intent) : Parcelable
