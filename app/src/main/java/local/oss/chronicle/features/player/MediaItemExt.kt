package local.oss.chronicle.features.player

import android.net.Uri
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
import android.support.v4.media.MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
import android.support.v4.media.MediaDescriptionCompat
import androidx.annotation.DrawableRes
import local.oss.chronicle.BuildConfig
import local.oss.chronicle.data.sources.plex.CONTENT_STYLE_BROWSABLE_HINT
import local.oss.chronicle.data.sources.plex.CONTENT_STYLE_LIST_ITEM_HINT_VALUE
import local.oss.chronicle.data.sources.plex.CONTENT_STYLE_PLAYABLE_HINT
import local.oss.chronicle.data.sources.plex.CONTENT_STYLE_SUPPORTED

/** Create a basic browsable item for Auto */
fun makeBrowsable(
    title: String,
    @DrawableRes iconRes: Int,
    desc: String = "",
): MediaItem {
    val mediaDescription = MediaDescriptionCompat.Builder()
    mediaDescription.setTitle(title)
    mediaDescription.setSubtitle(desc)
    mediaDescription.setIconUri(
        Uri.parse("android.resource://${BuildConfig.APPLICATION_ID}/$iconRes"),
    )
    mediaDescription.setMediaId(title)
    val extras = Bundle()
    extras.putBoolean(CONTENT_STYLE_SUPPORTED, true)
    extras.putInt(CONTENT_STYLE_BROWSABLE_HINT, CONTENT_STYLE_LIST_ITEM_HINT_VALUE)
    mediaDescription.setExtras(extras)
    return MediaItem(mediaDescription.build(), FLAG_BROWSABLE)
}

/**
 * Create a message item for Android Auto error/info display.
 * These items appear as playable items in the list so the subtitle is visible.
 * They cannot actually be played - attempting to play them does nothing.
 *
 * @param title The main message to display
 * @param subtitle Optional subtitle/additional context (shown below title)
 * @param iconRes Icon resource to display
 * @param mediaId Unique identifier for this message item
 */
fun makeMessageItem(
    title: String,
    subtitle: String = "",
    @DrawableRes iconRes: Int,
    mediaId: String = "__message__",
): MediaItem {
    val extras = Bundle()
    extras.putBoolean(CONTENT_STYLE_SUPPORTED, true)
    extras.putInt(CONTENT_STYLE_PLAYABLE_HINT, CONTENT_STYLE_LIST_ITEM_HINT_VALUE)

    val mediaDescription =
        MediaDescriptionCompat.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setIconUri(
                Uri.parse("android.resource://${BuildConfig.APPLICATION_ID}/$iconRes"),
            )
            .setMediaId(mediaId)
            .setExtras(extras)
            .build()
    // FLAG_PLAYABLE so Android Auto displays it as a list item with visible subtitle
    return MediaItem(mediaDescription, FLAG_PLAYABLE)
}
