package org.rjpd.msdc

import android.text.InputFilter
import android.text.Spanned

class NoNewLineFilter : InputFilter {
    override fun filter(
        source: CharSequence?,
        start: Int,
        end: Int,
        dest: Spanned?,
        dstart: Int,
        dend: Int
    ): CharSequence? {
        return source?.toString()?.replace("\\n+".toRegex(), "")
    }
}

fun cleanTagString(text: String): String {
    var cleanedText = text
    while (cleanedText.contains("  ")) {
        cleanedText = cleanedText.replace("  ", " ")
    }
    return cleanedText
}

fun getCategoryName(category: String): String {
    return category.replace(" ", "_").lowercase()
}

fun getCategoryResourceID(category: String): Int {
    return R.array::class.java.getDeclaredField("tags_$category").getInt(null)
}
