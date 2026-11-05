package dev.ownednotes.core

import kotlin.math.hypot

/** Only explicit closed shapes have an interior; never infer identity for imported/old ink. */
val InkStroke.canFill: Boolean get() = shapeKind in setOf(
    ShapeRecognizer.Kind.RECTANGLE, ShapeRecognizer.Kind.TRIANGLE, ShapeRecognizer.Kind.QUADRILATERAL,
    ShapeRecognizer.Kind.POLYGON, ShapeRecognizer.Kind.CIRCLE, ShapeRecognizer.Kind.ELLIPSE,
    ShapeRecognizer.Kind.STAR, ShapeRecognizer.Kind.HEART) && samples.size >= 3 &&
    hypot(samples.first().x-samples.last().x,samples.first().y-samples.last().y) <= maxOf(.01f,width*.01f)
