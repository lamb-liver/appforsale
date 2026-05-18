package com.lambliver.appforsale.data

/**
 * 備份檔外層封包（format / schemaVersion / payload），與 Android `org.json` 無關，可在 JVM 單元測試驗證。
 *
 * payload 內容仍由 [parseValidatedBackupPayload] 以 `JSONObject` 解碼後寫入 DataStore。
 */
internal data class BackupEnvelope(
    val format: String,
    val schemaVersion: Int,
    /**
     * 已通過外層封包驗證的 payload JSON 原始字串（必為 `{…}` 物件）。
     * 僅在還原路徑交給 `JSONObject` 解析，不應直接顯示或比對內容。
     */
    val payloadJson: String,
)

internal fun parseBackupEnvelope(jsonText: String): BackupEnvelope {
    val trimmed = jsonText.trim().removePrefix("\uFEFF").trim()
    val fields = BackupEnvelopeJson.readTopLevelObject(trimmed)

    val format = fields.stringField("format")
        ?: throw IllegalArgumentException("不是此 App 的備份檔")
    require(format == PosStore.BACKUP_FORMAT_ID) { "不是此 App 的備份檔" }

    // schemaVersion 缺失時視為 0，由 requireSupportedBackupSchema 統一攔截
    // 若未來需要區分「缺失」vs「版本過舊」的錯誤路徑，在此處改為獨立 throw
    val schema = fields.intField("schemaVersion") ?: 0
    requireSupportedBackupSchema(schema)

    val payloadJson = fields.rawField("payload")
    if (payloadJson == null) {
        if (fields.stringField("payload") != null) {
            throw IllegalArgumentException("備份 payload 必須為物件")
        }
        throw IllegalStateException("備份缺少 payload")
    }
    require(payloadJson.startsWith("{")) { "備份 payload 必須為物件" }

    return BackupEnvelope(format = format, schemaVersion = schema, payloadJson = payloadJson)
}

private fun requireSupportedBackupSchema(schema: Int) {
    if (schema in 1..PosStore.BACKUP_SCHEMA_VERSION) return
    throw IllegalArgumentException(
        "備份版本 $schema 超出支援範圍（1–${PosStore.BACKUP_SCHEMA_VERSION}）",
    )
}

/** 僅解析備份根物件頂層鍵，不遞迴進 payload。 */
private object BackupEnvelopeJson {

    fun readTopLevelObject(json: String): TopLevelFields {
        val s = json.trim()
        require(s.startsWith("{")) { "不是此 App 的備份檔" }
        val scanner = Scanner(s)
        scanner.expect('{')
        val strings = mutableMapOf<String, String>()
        val ints = mutableMapOf<String, Int>()
        val raws = mutableMapOf<String, String>()
        while (true) {
            scanner.skipWs()
            if (scanner.peek() == '}') {
                scanner.expect('}')
                break
            }
            val key = scanner.readJsonString()
            scanner.skipWs()
            scanner.expect(':')
            scanner.skipWs()
            when (scanner.peek()) {
                '"' -> strings[key] = scanner.readJsonString()
                '{', '[' -> raws[key] = scanner.readJsonValueRaw()
                else -> ints[key] = scanner.readJsonIntStrict()
            }
            scanner.skipWs()
            if (scanner.peek() == ',') {
                scanner.expect(',')
                continue
            }
            scanner.skipWs()
            if (scanner.peek() == '}') {
                scanner.expect('}')
                break
            }
            throw IllegalArgumentException("不是此 App 的備份檔")
        }
        return TopLevelFields(strings, ints, raws)
    }

    data class TopLevelFields(
        private val strings: Map<String, String>,
        private val ints: Map<String, Int>,
        private val raws: Map<String, String>,
    ) {
        fun stringField(key: String): String? = strings[key]
        fun intField(key: String): Int? = ints[key]
        fun rawField(key: String): String? = raws[key]
    }

    private class Scanner(private val input: String) {
        var i = 0

        fun peek(): Char {
            skipWs()
            require(i < input.length) { "不是此 App 的備份檔" }
            return input[i]
        }

        fun expect(c: Char) {
            skipWs()
            require(i < input.length && input[i] == c) { "不是此 App 的備份檔" }
            i++
        }

        fun skipWs() {
            while (i < input.length && input[i].isWhitespace()) i++
        }

        fun readJsonString(): String {
            skipWs()
            require(i < input.length && input[i] == '"') { "不是此 App 的備份檔" }
            i++
            val sb = StringBuilder()
            while (i < input.length) {
                when (val c = input[i]) {
                    '"' -> {
                        i++
                        return sb.toString()
                    }
                    '\\' -> {
                        i++
                        require(i < input.length) { "不是此 App 的備份檔" }
                        sb.append(
                            when (input[i]) {
                                '"', '\\', '/' -> input[i]
                                'b' -> '\b'
                                'f' -> '\u000c'
                                'n' -> '\n'
                                'r' -> '\r'
                                't' -> '\t'
                                'u' -> {
                                    require(i + 4 < input.length) { "不是此 App 的備份檔" }
                                    val hex = input.substring(i + 1, i + 5)
                                    i += 5
                                    hex.toInt(16).toChar()
                                }
                                else -> throw IllegalArgumentException("不是此 App 的備份檔")
                            },
                        )
                        i++
                    }
                    else -> {
                        sb.append(c)
                        i++
                    }
                }
            }
            throw IllegalArgumentException("不是此 App 的備份檔")
        }

        /** 僅接受 JSON 整數 token（拒絕 `1.0`、`1e2` 等）。 */
        fun readJsonIntStrict(): Int {
            skipWs()
            val start = i
            require(start < input.length && (input[start] == '-' || input[start].isDigit())) {
                "不是此 App 的備份檔"
            }
            if (input[start] == '-') i++
            require(i < input.length && input[i].isDigit()) { "不是此 App 的備份檔" }
            while (i < input.length && input[i].isDigit()) i++
            val next = if (i < input.length) input[i] else null
            require(next == null || next == ',' || next == '}' || next.isWhitespace()) {
                "備份版本格式無效"
            }
            return input.substring(start, i).toInt()
        }

        fun readJsonValueRaw(): String {
            skipWs()
            val start = i
            when (input[i]) {
                '{' -> readBalanced('{', '}')
                '[' -> readBalanced('[', ']')
                else -> throw IllegalArgumentException("不是此 App 的備份檔")
            }
            return input.substring(start, i)
        }

        /** 以字串／跳脫狀態追蹤括號，避免 payload 內 `\"` 或 `}` 誤截斷。 */
        private fun readBalanced(open: Char, close: Char) {
            require(input[i] == open)
            var depth = 0
            var inString = false
            var escape = false
            while (i < input.length) {
                val c = input[i]
                if (inString) {
                    if (escape) {
                        escape = false
                    } else when (c) {
                        '\\' -> escape = true
                        '"' -> inString = false
                    }
                } else {
                    when (c) {
                        '"' -> inString = true
                        open -> depth++
                        close -> {
                            depth--
                            if (depth == 0) {
                                i++
                                return
                            }
                        }
                    }
                }
                i++
            }
            throw IllegalArgumentException("不是此 App 的備份檔")
        }
    }
}
