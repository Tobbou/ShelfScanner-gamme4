package com.gamma4.shelfscanner.model

object PromptTemplates {

    /**
     * Main prompt for structuring raw OCR text + barcodes into product records.
     * Gemma receives ONLY text — no images. This is 5-10x faster than vision inference.
     */
    fun shelfScanPrompt(rawTexts: List<String>, barcodes: List<String>): String {
        val textBlock = rawTexts.joinToString("\n")
        val barcodeBlock = if (barcodes.isNotEmpty()) {
            "\n\nDetected barcodes:\n" + barcodes.joinToString("\n") { "- $it" }
        } else ""

        return """
You are a retail shelf label analyzer. Below is raw OCR text extracted from shelf edge labels in a store, plus any detected barcodes.

Your task:
1. Group the text fragments into individual products
2. Correct obvious OCR errors (e.g. "0" vs "O", "1" vs "l", "rn" vs "m")
3. Extract structured product data for each product found
4. Match barcodes to their nearest product if possible

Raw OCR text from shelf labels:
$textBlock$barcodeBlock

Return ONLY a valid JSON array with this structure, no other text:
[{"product_name": "...", "brand": "...", "price": "...", "ean": "...", "unit_price": "..."}]

Rules:
- product_name is required, all other fields are optional (use null if not found)
- price should include currency symbol if visible (e.g. "29,95 kr" or "€3.49")
- ean should be the barcode number if detected, otherwise null
- unit_price is the per-kg or per-liter price if shown on the label
- If no products can be identified, return: []
- Do NOT invent data — only extract what is visible in the OCR text
""".trimIndent()
    }
}
