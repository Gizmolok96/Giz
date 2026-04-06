package com.zamerpro.app.ui.export

import android.content.Context
import android.graphics.*
import com.zamerpro.app.data.Room
import com.zamerpro.app.ui.drawing.BlueprintView

/**
 * Renders room blueprints to Bitmaps or directly to a Canvas.
 * Delegates to BlueprintView so the output is pixel-perfect identical
 * to what the user sees in the drawing screen.
 */
object BlueprintBitmapRenderer {

    fun renderRoom(context: Context, room: Room, bitmapW: Int = 1200, bitmapH: Int = 1200): Bitmap =
        BlueprintView.renderRoom(context, room, bitmapW, bitmapH)

    fun draw(context: Context, canvas: Canvas, room: Room, w: Float, h: Float) =
        BlueprintView.drawToCanvas(context, canvas, room, w, h)
}
