// Copyright (c) 2026 PaddlePaddle Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.paddle.ocr.postprocess

import android.graphics.PointF
import com.paddle.ocr.model.OCRBox
import com.paddle.ocr.model.OCRTextOrientation
import kotlin.math.hypot
import kotlin.math.max
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.geometry.Geometry
import org.opencv.imgproc.Imgproc

object QuadTextCrop {
    private const val VERTICAL_CROP_RATIO = 1.5

    data class CharacterRange(
        val startFraction: Float,
        val endFraction: Float,
    )

    fun crop(src: Mat, box: OCRBox): Mat {
        // Align with PaddleX CropByPolys.get_minarea_rect_crop: recompute minAreaRect
        // from the detected quad before perspective transform.
        val ordered =
            orderedPoints(box).map { point ->
                Point(point.x.toDouble(), point.y.toDouble())
            }

        val widthTop = hypot(ordered[0].x - ordered[1].x, ordered[0].y - ordered[1].y)
        val widthBottom = hypot(ordered[2].x - ordered[3].x, ordered[2].y - ordered[3].y)
        val heightLeft = hypot(ordered[0].x - ordered[3].x, ordered[0].y - ordered[3].y)
        val heightRight = hypot(ordered[1].x - ordered[2].x, ordered[1].y - ordered[2].y)

        val dstW = max(widthTop, widthBottom).toInt().coerceAtLeast(1)
        val dstH = max(heightLeft, heightRight).toInt().coerceAtLeast(1)

        val srcPts = MatOfPoint2f()
        srcPts.fromList(ordered)
        val dstPts = MatOfPoint2f()
        dstPts.fromList(
            listOf(
                Point(0.0, 0.0),
                Point(dstW.toDouble(), 0.0),
                Point(dstW.toDouble(), dstH.toDouble()),
                Point(0.0, dstH.toDouble()),
            )
        )
        val m = Geometry.getPerspectiveTransform(srcPts, dstPts)
        srcPts.release()
        dstPts.release()

        val dst = Mat(dstH, dstW, CvType.CV_8UC3)
        Imgproc.warpPerspective(
            src,
            dst,
            m,
            Size(dstW.toDouble(), dstH.toDouble()),
            Imgproc.INTER_CUBIC,
            Core.BORDER_REPLICATE,
        )
        m.release()

        return dst
    }

    fun isVertical(crop: Mat): Boolean =
        crop.cols() > 0 && crop.rows().toDouble() / crop.cols() >= VERTICAL_CROP_RATIO

    fun characterBoxes(
        lineBox: OCRBox,
        ranges: List<CharacterRange>,
        orientation: OCRTextOrientation,
    ): List<OCRBox> {
        val ordered = orderedPoints(lineBox)
        val topLeft = ordered[0]
        val topRight = ordered[1]
        val bottomRight = ordered[2]
        val bottomLeft = ordered[3]
        return ranges.map { range ->
            val start = range.startFraction.coerceIn(0f, 1f)
            val end = range.endFraction.coerceIn(start, 1f)
            if (orientation == OCRTextOrientation.VERTICAL) {
                OCRBox(
                    listOf(
                        lerp(topLeft, bottomLeft, start),
                        lerp(topRight, bottomRight, start),
                        lerp(topRight, bottomRight, end),
                        lerp(topLeft, bottomLeft, end),
                    )
                )
            } else {
                OCRBox(
                    listOf(
                        lerp(topLeft, topRight, start),
                        lerp(topLeft, topRight, end),
                        lerp(bottomLeft, bottomRight, end),
                        lerp(bottomLeft, bottomRight, start),
                    )
                )
            }
        }
    }

    private fun orderedPoints(box: OCRBox): List<PointF> {
        val rectInput = MatOfPoint2f()
        rectInput.fromList(box.points.map { Point(it.x.toDouble(), it.y.toDouble()) })
        val boundingBox = Geometry.minAreaRect(rectInput)
        rectInput.release()

        val boxPoints = Array(4) { Point() }
        boundingBox.points(boxPoints)
        return QuadGeometry.orderMinAreaRectPoints(boxPoints).map { point ->
            PointF(point.x.toFloat(), point.y.toFloat())
        }
    }

    private fun lerp(start: PointF, end: PointF, fraction: Float): PointF =
        PointF(
            start.x + (end.x - start.x) * fraction,
            start.y + (end.y - start.y) * fraction,
        )
}
