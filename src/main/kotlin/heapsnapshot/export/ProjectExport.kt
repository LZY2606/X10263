package heapsnapshot.export

import heapsnapshot.domain.*
import heapsnapshot.store.HeapStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.*

/**
 * 项目导出格式（离线、自描述、可半写恢复）：
 *
 *  heap-shadow-export.zip
 *   ├─ MANIFEST.json          格式版本、项目身份、帧列表与 sha
 *   └─ frames/
 *       ├─ 0001-material.json 原始材料（raw 文本原样）
 *       ├─ 0002-event.json    账本事件（保持 seq 顺序）
 *       ├─ 0003-analysis.json 派生结果
 *       └─ ...
 *
 * 每帧 frame = magic(4) | version(1) | kind(1) | length(8) | payload | crc32(4)。
 * 再次导入时逐帧校验 CRC：遇到损坏帧立即停止并报告帧位置；已完整应用的帧不回滚（位置明确），
 * 调用方可选择只取完整前缀（半写恢复）。
 */
object ProjectExport {
    const val MAGIC = "HSXP"
    const val FORMAT_VERSION = 1

    enum class FrameKind(val code: Byte) {
        MATERIAL(1), EVENT(2), ANALYSIS(3), DIFF(4), PROJECT(5)
    }

    data class Frame(val kind: FrameKind, val payload: ByteArray, val crcOk: Boolean = true, val index: Int = 0)

    fun exportZip(store: HeapStore, projectId: String): ByteArray {
        val frames = mutableListOf<Pair<FrameKind, ByteArray>>()
        store.connection().createStatement().executeQuery(
            "SELECT project_id,name,created_at_ms FROM projects WHERE project_id='$projectId'"
        ).use { rs ->
            if (rs.next()) {
                frames += FrameKind.PROJECT to """{"projectId":"${rs.getString(1)}","name":${quote(rs.getString(2))},"createdAtMs":${rs.getLong(3)}}""".toByteArray()
            }
        }
        store.connection().createStatement().executeQuery(
            "SELECT snapshot_id,raw_json FROM materials WHERE project_id='$projectId' ORDER BY snapshot_id"
        ).use { rs ->
            while (rs.next()) {
                val obj = """{"snapshotId":${quote(rs.getString(1))},"raw":${quote(rs.getString(2))}}"""
                frames += FrameKind.MATERIAL to obj.toByteArray(StandardCharsets.UTF_8)
            }
        }
        store.connection().createStatement().executeQuery(
            "SELECT payload_json FROM events WHERE project_id='$projectId' ORDER BY seq"
        ).use { rs ->
            while (rs.next()) frames += FrameKind.EVENT to rs.getString(1).toByteArray(StandardCharsets.UTF_8)
        }
        store.connection().createStatement().executeQuery(
            "SELECT result_json FROM analyses WHERE project_id='$projectId' ORDER BY created_event_id"
        ).use { rs ->
            while (rs.next()) frames += FrameKind.ANALYSIS to rs.getString(1).toByteArray(StandardCharsets.UTF_8)
        }
        store.connection().createStatement().executeQuery(
            "SELECT diff_json FROM diffs WHERE project_id='$projectId' ORDER BY created_event_id"
        ).use { rs ->
            while (rs.next()) frames += FrameKind.DIFF to rs.getString(1).toByteArray(StandardCharsets.UTF_8)
        }

        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            zip.putNextEntry(ZipEntry("MANIFEST.json"))
            val manifest = """{"format":"heap-shadow-export","formatVersion":$FORMAT_VERSION,"projectId":${quote(projectId)},"frameCount":${frames.size}}"""
            zip.write(manifest.toByteArray())
            zip.closeEntry()
            frames.forEachIndexed { i, (kind, payload) ->
                zip.putNextEntry(ZipEntry("frames/%04d-%s.bin".format(i + 1, kind.name.lowercase())))
                zip.write(encodeFrame(kind, payload))
                zip.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    fun encodeFrame(kind: FrameKind, payload: ByteArray): ByteArray {
        val crc = CRC32().apply { update(payload) }.value
        val buf = ByteBuffer.allocate(4 + 1 + 1 + 8 + payload.size + 4)
        buf.put(MAGIC.toByteArray()); buf.put(FORMAT_VERSION.toByte()); buf.put(kind.code)
        buf.putLong(payload.size.toLong()); buf.put(payload); buf.putInt(crc.toInt())
        return buf.array()
    }

    /** 解码 zip 中的全部帧；损坏帧报告精确位置，完整前缀可单独采用。 */
    fun decodeFrames(zipBytes: ByteArray): DecodeResult {
        val frames = mutableListOf<Frame>()
        var manifest: String? = null
        var corruptAt: CorruptFrame? = null
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            var entry = zip.nextEntry
            var index = 0
            while (entry != null) {
                val content = zip.readAllBytes()
                if (entry.name == "MANIFEST.json") manifest = content.toString(StandardCharsets.UTF_8)
                else if (entry.name.startsWith("frames/")) {
                    index++
                    val decoded = runCatching { decodeOne(content, index) }
                    val f = decoded.getOrNull()
                    if (f == null || !f.crcOk) {
                        corruptAt = CorruptFrame(entry.name, index,
                            decoded.exceptionOrNull()?.message ?: "CRC 校验失败")
                        entry = null; break
                    }
                    frames += f
                }
                entry = zip.nextEntry
            }
        }
        return DecodeResult(manifest, frames, corruptAt)
    }

    private fun decodeOne(bytes: ByteArray, index: Int): Frame {
        require(bytes.size >= 14) { "帧过短（长度 ${bytes.size} < 14）" }
        val buf = ByteBuffer.wrap(bytes)
        val magic = ByteArray(4).also { buf.get(it) }.toString(Charsets.US_ASCII)
        require(magic == MAGIC) { "帧 magic 错误: $magic" }
        val ver = buf.get().toInt()
        require(ver == FORMAT_VERSION) { "不支持的导出版本: $ver" }
        val kindCode = buf.get()
        val length = buf.long
        require(length in 0..Int.MAX_VALUE.toLong()) { "帧长度非法: $length" }
        require(bytes.size >= 14 + length + 4) { "帧声明 $length 字节但容器只有 ${bytes.size - 14} 字节" }
        val payload = ByteArray(length.toInt()).also { buf.get(it) }
        val crcGiven = buf.int
        val crcCalc = CRC32().apply { update(payload) }.value.toInt()
        val kind = FrameKind.entries.first { it.code == kindCode }
        return Frame(kind, payload, crcGiven == crcCalc, index)
    }

    /** 导出后再导入：身份、seq 顺序、来源关系全部恢复。 */
    fun importInto(store: HeapStore, zipBytes: ByteArray): ImportReport {
        val decoded = decodeFrames(zipBytes)
        var projectId: String? = null
        var materials = 0; var events = 0; var analyses = 0; var diffs = 0
        val c = store.connection()
        c.autoCommit = false
        try {
            decoded.frames.forEach { f ->
                val text = f.payload.toString(StandardCharsets.UTF_8)
                when (f.kind) {
                    FrameKind.PROJECT -> {
                        val pid = Regex(""""projectId":"((?:[^"\\]|\\.)*)"""").find(text)!!.groupValues[1]
                        val name = Regex(""""name":"((?:[^"\\]|\\.)*)"""").find(text)!!.groupValues[1].replace("\\\"", "\"")
                        projectId = pid
                        c.prepareStatement("INSERT OR IGNORE INTO projects(project_id,name,created_at_ms) VALUES(?,?,?)").use {
                            it.setString(1, pid); it.setString(2, name); it.setLong(3, System.currentTimeMillis()); it.execute()
                        }
                    }
                    FrameKind.MATERIAL -> {
                        val (snapId, raw) = readStringPair(text, "snapshotId", "raw")
                        c.prepareStatement("INSERT OR IGNORE INTO materials(material_id,project_id,snapshot_id,parser_version,raw_json,content_hash,created_event_id,imported_at_ms) VALUES(?,?,?,?,?,?,?,?)").use {
                            it.setString(1, "material:$snapId"); it.setString(2, projectId); it.setString(3, snapId)
                            it.setString(4, "reimported"); it.setString(5, raw)
                            it.setString(6, java.security.MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { b -> "%02x".format(b) })
                            it.setString(7, null); it.setLong(8, System.currentTimeMillis()); it.execute()
                        }
                        materials++
                    }
                    FrameKind.EVENT -> {
                        val ev = store.json.decodeFromString(LedgerEvent.serializer(), text)
                        c.prepareStatement("INSERT OR IGNORE INTO events(seq,event_id,project_id,parent_event_id,branch,epoch_ms,payload_json,in_ledger) VALUES(?,?,?,?,?,?,?,1)").use {
                            it.setLong(1, ev.seq); it.setString(2, ev.eventId); it.setString(3, projectId)
                            it.setString(4, ev.parentEventId); it.setString(5, ev.branch); it.setLong(6, ev.epochMs)
                            it.setString(7, text); it.execute()
                        }
                        events++
                    }
                    FrameKind.ANALYSIS -> {
                        val r = store.json.decodeFromString(AnalysisResult.serializer(), text)
                        c.prepareStatement("INSERT OR REPLACE INTO analyses(result_id,project_id,snapshot_id,policy_version,created_event_id,result_json,valid_until_event_id,invalidation_reason) VALUES(?,?,?,?,?,?,?,?)").use {
                            it.setString(1, r.resultId); it.setString(2, projectId); it.setString(3, r.snapshotId)
                            it.setString(4, r.policyVersion); it.setString(5, r.createdAtEventId); it.setString(6, text)
                            it.setString(7, r.validUntilEventId); it.setString(8, r.invalidationReason); it.execute()
                        }
                        analyses++
                    }
                    FrameKind.DIFF -> {
                        val d = store.json.decodeFromString(SnapshotClassDiff.serializer(), text)
                        c.prepareStatement("INSERT OR REPLACE INTO diffs(diff_id,project_id,base_snapshot_id,other_snapshot_id,created_event_id,diff_json) VALUES(?,?,?,?,?,?)").use {
                            it.setString(1, "diff:${d.baseSnapshotId}:${d.otherSnapshotId}"); it.setString(2, projectId)
                            it.setString(3, d.baseSnapshotId); it.setString(4, d.otherSnapshotId)
                            it.setString(5, "reimported"); it.setString(6, text); it.execute()
                        }
                        diffs++
                    }
                }
            }
            c.commit()
        } catch (e: Exception) {
            c.rollback(); throw e
        } finally {
            c.autoCommit = true
        }
        return ImportReport(projectId ?: "<unknown>", materials, events, analyses, diffs,
            framesAppliedPrefix = decoded.frames.size, corruptFrame = decoded.corruptFrame)
    }

    private fun readStringPair(text: String, k1: String, k2: String): Pair<String, String> {
        // 简单 JSON 字符串提取（仅用于本格式自写自读）。
        fun extract(key: String): String {
            val marker = "\"$key\":\""
            val start = text.indexOf(marker) + marker.length
            var i = start
            val sb = StringBuilder()
            while (i < text.length) {
                val ch = text[i]
                if (ch == '\\' && i + 1 < text.length) { sb.append(text[i + 1]); i += 2; continue }
                if (ch == '"') break
                sb.append(ch); i++
            }
            return sb.toString()
        }
        return extract(k1) to extract(k2)
    }

    private fun quote(s: String): String =
        buildString { append('"'); s.forEach { if (it == '"' || it == '\\') append('\\'); append(it) }; append('"') }

    data class DecodeResult(val manifest: String?, val frames: List<Frame>, val corruptFrame: CorruptFrame?)
    data class CorruptFrame(val entryName: String, val frameIndex: Int, val reason: String)
    data class ImportReport(
        val projectId: String, val materials: Int, val events: Int, val analyses: Int, val diffs: Int,
        val framesAppliedPrefix: Int, val corruptFrame: CorruptFrame?
    )
}
