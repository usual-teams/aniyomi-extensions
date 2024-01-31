package eu.kanade.tachiyomi.animeextension.id.nekopoi

/**
 * This singleton class provides functionality to detect and unpack packed javascript based on Dean Edwards JavaScript's Packer.
 *
 * See [Dean Edwards JavaScript's Packer](http://dean.edwards.name/packer/)
 */
object JsUnpacker {

    /**
     * Regex to detect packed functions.
     */
    private val PACKED_REGEX = Regex("eval[(]function[(]p,a,c,k,e,[r|d]?", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))

    /**
     * Regex to get and group the packed javascript.
     * Needed to get information and unpack the code.
     */
    private val PACKED_EXTRACT_REGEX = Regex("[}][(]'(.*)', *(\\d+), *(\\d+), *'(.*?)'[.]split[(]'[|]'[)]", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))

    /**
     * Matches function names and variables to de-obfuscate the code.
     */
    private val UNPACK_REPLACE_REGEX = Regex("\\b\\w+\\b", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))

    /**
     * Matches urls to extract url from the code
     */
    val URL_REGEX = Regex("(https?)://[-a-zA-Z0-9+&@#/%?=~_|!:,.;]*[-a-zA-Z0-9+&@#/%=~_|]")

    /**
     * Check if script is packed.
     *
     * @param scriptBlock the String to check if it is packed.
     *
     * @return whether the [scriptBlock] contains packed code or not.
     */
    private fun detect(scriptBlock: String): Boolean {
        return scriptBlock.contains(PACKED_REGEX)
    }

    /**
     * Unpack the passed [scriptBlock].
     * It matches all found occurrences and returns them as separate Strings in a list.
     *
     * @param scriptBlock the String to unpack.
     *
     * @return unpacked code in a list or an empty list if non is packed.
     */
    fun unpack(scriptBlock: String): Sequence<String> {
        return if (!detect(scriptBlock)) {
            emptySequence()
        } else {
            unpacking(scriptBlock)
        }
    }

    /**
     * Unpacking functionality.
     * Match all found occurrences, get the information group and unbase it.
     * If found symtabs are more or less than the count provided in code, the occurrence will be ignored
     * because it cannot be unpacked correctly.
     *
     * @param scriptBlock the String to unpack.
     *
     * @return a list of all unpacked code from all found packed and unpackable occurrences found.
     */
    private fun unpacking(scriptBlock: String): Sequence<String> {
        val unpacked = PACKED_EXTRACT_REGEX.findAll(scriptBlock).mapNotNull { result ->

            val payload = result.groups[1]?.value
            val symtab = result.groups[4]?.value?.split('|')
            val radix = result.groups[2]?.value?.toIntOrNull() ?: 10
            val count = result.groups[3]?.value?.toIntOrNull()
            val unbaser = Unbaser(radix)

            if (symtab == null || count == null || symtab.size != count) {
                null
            } else {
                payload?.replace(UNPACK_REPLACE_REGEX) { match ->
                    val word = match.value
                    val unbased = symtab[unbaser.unbase(word)]
                    unbased.ifEmpty {
                        word
                    }
                }
            }
        }
        return unpacked
    }
}
