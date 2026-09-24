package heapforensics.service

import kotlinx.serialization.json.Json

val reviewJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = true
    prettyPrintIndent = "  "
}

val wireJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
