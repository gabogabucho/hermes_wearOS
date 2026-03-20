package com.agentpet.watch

import android.content.Context
import androidx.wear.tiles.RequestBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.ColorBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.TimelineBuilders
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

class AgentPetTileService : TileService() {

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val prefs = getSharedPreferences("agentpet_prefs", Context.MODE_PRIVATE)
        val currentEmoji = prefs.getString("current_emoji", "0_0") ?: "0_0"

        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion("1")
            .setTileTimeline(
                TimelineBuilders.Timeline.Builder().addTimelineEntry(
                    TimelineBuilders.TimelineEntry.Builder().setLayout(
                        LayoutElementBuilders.Layout.Builder().setRoot(
                            createLayout(currentEmoji)
                        ).build()
                    ).build()
                ).build()
            ).build()

        return Futures.immediateFuture(tile)
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> {
        return Futures.immediateFuture(
            ResourceBuilders.Resources.Builder().setVersion("1").build()
        )
    }

    private fun createLayout(emoji: String): LayoutElementBuilders.LayoutElement {
        val emojiText = LayoutElementBuilders.Text.Builder()
            .setText(emoji)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(DimensionBuilders.SpProp.Builder().setValue(40f).build())
                    .setColor(ColorBuilders.ColorProp.Builder().setArgb(0xFFFFFFFF.toInt()).build())
                    .build()
            ).build()

        val label = LayoutElementBuilders.Text.Builder()
            .setText("AgentPet >")
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(DimensionBuilders.SpProp.Builder().setValue(12f).build())
                    .setColor(ColorBuilders.ColorProp.Builder().setArgb(0xFFAAAAAA.toInt()).build())
                    .setItalic(true)
                    .build()
            ).build()

        return LayoutElementBuilders.Column.Builder()
            .addContent(emojiText)
            .addContent(label)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(
                        ModifiersBuilders.Clickable.Builder()
                            .setOnClick(
                                ActionBuilders.LaunchAction.Builder()
                                    .setAndroidActivity(
                                        ActionBuilders.AndroidActivity.Builder()
                                            .setClassName("com.agentpet.watch.MainActivity")
                                            .setPackageName("com.agentpet.watch")
                                            .build()
                                    ).build()
                            ).setId("open_app")
                            .build()
                    ).build()
            ).build()
    }
}
