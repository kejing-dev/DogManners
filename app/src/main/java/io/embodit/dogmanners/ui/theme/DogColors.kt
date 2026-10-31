package io.embodit.dogmanners.ui.theme

import androidx.compose.ui.graphics.Color

/** The design's colours (design-package `palette`). The only file with colour literals. */
object DogColors {
    // dog states (palette.states): always shown with text + icon, never by colour alone (D13)
    val Wary = Color(0xFFF2A33A) // design-style: fixed-figma-color wary amber
    val Relaxed = Color(0xFF4CC9A6) // design-style: fixed-figma-color relaxed teal
    val Play = Color(0xFF7FA8FF) // design-style: fixed-figma-color play sky blue
    val OnState = Color(0xFF14161A) // design-style: fixed-figma-color dark ink on the state fills

    // park (palette.scene)
    val Grass = Color(0xFF2E4A1F) // design-style: fixed-figma-color grass
    val Path = Color(0xFF4D4740) // design-style: fixed-figma-color path
    val Sky = Color(0xFF8DB3E0) // design-style: fixed-figma-color sky
    val LeafA = Color(0xFF3F6B2C) // design-style: fixed-figma-color tree crown A
    val LeafB = Color(0xFF55843A) // design-style: fixed-figma-color tree crown B
    val Bark = Color(0xFF5A4030) // design-style: fixed-figma-color trunk / bench / fence wood
    val Bowl = Color(0xFFB8BEC6) // design-style: fixed-figma-color steel bowl
    val Mat = Color(0xFF8A5A7A) // design-style: fixed-figma-color dog mat
    val Mannequin = Color(0xFFD8B98C) // design-style: fixed-figma-color wooden mannequin
    val Leash = Color(0xFFC0392B) // design-style: fixed-figma-color leash
    val Gaze = Color(0xFFFFE3B0) // design-style: fixed-figma-color gaze light (warm)
    val Icon = Color(0xFFC46B2B) // design-style: fixed-figma-color app icon shiba orange

    val Error = Color(0xFFF07167) // design-style: fixed-figma-color error
}
