package com.keyflow.core.shortcuts

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Codec JSON das barras/grades para o DataStore (strings). Usa `org.json` (presente no Android; os unit
 * tests JVM adicionam o jar `org.json:json`) — zero dependência nova no APK.
 *
 * Tolerância a falha por construção (edge case da spec — o IME nunca crasha por config):
 * - string malformada → [BarsConfig.DEFAULT] / lista vazia;
 * - tipo de botão desconhecido → ignorado (forward-compat: config escrita por versão mais nova);
 * - campos desconhecidos → ignorados.
 */
object ShortcutCodec {

    // ── barras ───────────────────────────────────────────────────────────────

    fun encodeBars(config: BarsConfig): String = JSONObject().apply {
        put("mirrored", config.mirrored)
        put("collapsed", barsJson(config.collapsed))
        put("expanded", barsJson(config.expanded))
    }.toString()

    fun decodeBars(json: String?): BarsConfig {
        if (json.isNullOrBlank()) return BarsConfig.DEFAULT
        return try {
            val root = JSONObject(json)
            BarsConfig(
                mirrored = root.optBoolean("mirrored", false),
                collapsed = barsFrom(root.optJSONArray("collapsed")),
                expanded = barsFrom(root.optJSONArray("expanded")),
            )
        } catch (_: JSONException) {
            BarsConfig.DEFAULT
        }
    }

    private fun barsJson(bars: List<BarDef>): JSONArray = JSONArray().apply {
        bars.forEach { bar ->
            put(JSONObject().put("buttons", JSONArray().apply { bar.buttons.forEach { put(buttonJson(it)) } }))
        }
    }

    private fun barsFrom(array: JSONArray?): List<BarDef> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val bar = array.optJSONObject(i) ?: return@mapNotNull null
            val buttons = bar.optJSONArray("buttons") ?: return@mapNotNull null
            BarDef((0 until buttons.length()).mapNotNull { j -> buttonFrom(buttons.optJSONObject(j)) })
        }
    }

    private fun buttonJson(button: BarButton): JSONObject = when (button) {
        BarButton.Mic -> JSONObject().put("type", "mic")
        BarButton.ConfigBtn -> JSONObject().put("type", "config")
        BarButton.ToggleExpand -> JSONObject().put("type", "toggleExpand")
        BarButton.Paste -> JSONObject().put("type", "paste")
        BarButton.Candidates -> JSONObject().put("type", "candidates")
        BarButton.Joystick -> JSONObject().put("type", "joystick")
        is BarButton.SingleKey -> JSONObject().put("type", "key").put("key", keyJson(button.key))
        is BarButton.GridTrigger -> JSONObject().apply {
            put("type", "grid")
            put("gridId", button.gridId)
            put("mode", button.mode.name)
            put("label", button.label)
            button.prefix?.let { put("prefix", keyJson(it)) }
        }
        is BarButton.RunFlow -> JSONObject().put("type", "flow").put("botaoId", button.botaoId)
    }

    private fun buttonFrom(obj: JSONObject?): BarButton? {
        obj ?: return null
        return when (obj.optString("type")) {
            "mic" -> BarButton.Mic
            "config" -> BarButton.ConfigBtn
            "toggleExpand" -> BarButton.ToggleExpand
            "paste" -> BarButton.Paste
            "candidates" -> BarButton.Candidates
            "joystick" -> BarButton.Joystick
            "key" -> keyFrom(obj.optJSONObject("key"))?.let { BarButton.SingleKey(it) }
            "grid" -> {
                val gridId = obj.optString("gridId")
                if (gridId.isEmpty()) null
                else BarButton.GridTrigger(
                    gridId = gridId,
                    prefix = keyFrom(obj.optJSONObject("prefix")),
                    mode = runCatching { GridTriggerMode.valueOf(obj.optString("mode")) }
                        .getOrDefault(GridTriggerMode.OPEN_ONLY),
                    label = obj.optString("label").ifEmpty { "⌗" },
                )
            }
            "flow" -> obj.optString("botaoId").takeIf { it.isNotEmpty() }?.let { BarButton.RunFlow(it) }
            else -> null // tipo desconhecido: ignora (forward-compat)
        }
    }

    // ── grades ───────────────────────────────────────────────────────────────

    fun encodeGrids(grids: List<ShortcutGrid>): String = JSONArray().apply {
        grids.forEach { grid ->
            put(
                JSONObject().apply {
                    put("id", grid.id)
                    put("name", grid.name)
                    put("rows", grid.rows)
                    put("cols", grid.cols)
                    put(
                        "cells",
                        JSONObject().apply {
                            grid.cells.forEach { (index, cell) ->
                                put(
                                    index.toString(),
                                    JSONObject().apply {
                                        put("key", keyJson(cell.key))
                                        cell.label?.let { put("label", it) }
                                    },
                                )
                            }
                        },
                    )
                }
            )
        }
    }.toString()

    fun decodeGrids(json: String?): List<ShortcutGrid> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i -> gridFrom(array.optJSONObject(i)) }
        } catch (_: JSONException) {
            emptyList()
        }
    }

    private fun gridFrom(obj: JSONObject?): ShortcutGrid? {
        obj ?: return null
        val id = obj.optString("id")
        val rows = obj.optInt("rows")
        val cols = obj.optInt("cols")
        if (id.isEmpty() || rows <= 0 || cols <= 0) return null
        val cellsObj = obj.optJSONObject("cells") ?: JSONObject()
        val cells = buildMap {
            cellsObj.keys().forEach { rawIndex ->
                val index = rawIndex.toIntOrNull() ?: return@forEach
                val cellObj = cellsObj.optJSONObject(rawIndex) ?: return@forEach
                val key = keyFrom(cellObj.optJSONObject("key")) ?: return@forEach
                if (index in 0 until rows * cols) {
                    put(index, GridCell(key, cellObj.optString("label").ifEmpty { null }))
                }
            }
        }
        return ShortcutGrid(id, obj.optString("name"), rows, cols, cells)
    }

    // ── ShortcutKey ──────────────────────────────────────────────────────────

    private fun keyJson(key: ShortcutKey): JSONObject = JSONObject().apply {
        put("keyCode", key.keyCode)
        if (key.ctrl) put("ctrl", true)
        if (key.alt) put("alt", true)
        if (key.shift) put("shift", true)
        if (key.meta) put("meta", true)
        put("label", key.label)
    }

    private fun keyFrom(obj: JSONObject?): ShortcutKey? {
        obj ?: return null
        val keyCode = obj.optInt("keyCode", -1)
        if (keyCode < 0) return null
        return ShortcutKey(
            keyCode = keyCode,
            ctrl = obj.optBoolean("ctrl"),
            alt = obj.optBoolean("alt"),
            shift = obj.optBoolean("shift"),
            meta = obj.optBoolean("meta"),
            label = obj.optString("label"),
        )
    }
}
