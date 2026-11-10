package com.meshie4LoRa.memory

/** Grammar for the memory pass; the wording lives in PromptTemplate.MEMORY_SYSTEM / MEMORY_UPDATE. */
object MemoryPrompts {
    const val GRAMMAR = """root ::= "{\"summary\": " str ", \"new_facts\": [" facts "], \"feeling\": " str ", \"reason\": " str "}"
facts ::= (str (", " str)? (", " str)?)?
str ::= "\"" [^"\\\n]{0,180} "\""
"""
}
