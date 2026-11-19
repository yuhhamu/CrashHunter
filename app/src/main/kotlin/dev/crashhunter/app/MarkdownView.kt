package dev.crashhunter.app

import java.awt.Color
import javax.swing.BorderFactory
import javax.swing.JEditorPane
import javax.swing.UIManager
import javax.swing.event.HyperlinkEvent
import javax.swing.text.html.HTMLEditorKit

object Markdown {
    private val cjk = Regex("""[　-ヿ㐀-鿿＀-￯]""")
    private val heading = Regex("""^(#{1,4})\s+(.*)$""")
    private val separator = Regex("""^:?-{3,}:?$""")

    fun toHtml(md: String): String {
        val out = StringBuilder()
        val para = mutableListOf<String>()
        var list = false
        val table = mutableListOf<List<String>>()

        fun flushPara() {
            if (para.isEmpty()) return
            val joined = para.reduce { a, b -> if (cjk.matches(a.last().toString()) || cjk.matches(b.first().toString())) a + b else "$a $b" }
            out.append("<p>").append(inline(joined)).append("</p>")
            para.clear()
        }
        fun closeList() { if (list) { out.append("</ul>"); list = false } }
        fun flushTable() {
            if (table.isEmpty()) return
            out.append("<table cellspacing='0' cellpadding='4'>")
            table.forEachIndexed { i, row ->
                out.append("<tr>")
                row.forEach { c -> out.append(if (i == 0) "<th align='left'>" else "<td>").append(inline(c)).append(if (i == 0) "</th>" else "</td>") }
                out.append("</tr>")
            }
            out.append("</table>")
            table.clear()
        }
        fun flushAll() { flushPara(); closeList(); flushTable() }

        for (raw in md.removePrefix("﻿").lines()) {
            val t = raw.trim()
            val h = heading.matchEntire(t)
            when {
                t.isEmpty() -> flushAll()
                t.startsWith("|") -> {
                    flushPara(); closeList()
                    val cells = t.trim('|').split('|').map { it.trim() }
                    if (!cells.all { separator.matches(it) }) table += cells
                }
                h != null -> {
                    flushAll()
                    val n = h.groupValues[1].length
                    out.append("<h$n>").append(inline(h.groupValues[2])).append("</h$n>")
                }
                t == "---" || t == "***" -> { flushAll(); out.append("<hr>") }
                t.startsWith("- ") || t.startsWith("* ") -> {
                    flushPara(); flushTable()
                    if (!list) { out.append("<ul>"); list = true }
                    out.append("<li>").append(inline(t.substring(2))).append("</li>")
                }
                else -> { closeList(); flushTable(); para += t }
            }
        }
        flushAll()
        return out.toString()
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun inline(s: String): String {
        val kept = mutableListOf<String>()
        fun keep(html: String): String { kept += html; return "\u0001${kept.size - 1}\u0001" }
        var t = Regex("""\[([^\]]+)]\(([^)\s]+)\)""").replace(s) { m -> keep("<a href='${esc(m.groupValues[2])}'>${esc(m.groupValues[1])}</a>") }
        t = Regex("""<(https?://[^>\s]+)>""").replace(t) { m -> keep("<a href='${esc(m.groupValues[1])}'>${esc(m.groupValues[1])}</a>") }
        t = Regex("""`([^`]+)`""").replace(t) { m -> keep("<code>${esc(m.groupValues[1])}</code>") }
        t = esc(t)
        t = Regex("""https?://[\w\-./?=&;%#~:+]+""").replace(t) { m -> "<a href='${m.value}'>${m.value}</a>" }
        t = Regex("""\*\*(.+?)\*\*""").replace(t) { m -> "<b>${m.groupValues[1]}</b>" }
        return Regex("""\u0001(\d+)\u0001""").replace(t) { m -> kept[m.groupValues[1].toInt()] }
    }
}

class MarkdownView(markdown: String) : JEditorPane() {
    init {
        isEditable = false
        putClientProperty(HONOR_DISPLAY_PROPERTIES, true)
        border = BorderFactory.createEmptyBorder(10, 14, 10, 14)
        val kit = HTMLEditorKit()
        editorKit = kit
        val fg = hex(UIManager.getColor("Label.foreground") ?: Color.BLACK)
        val muted = hex(UIManager.getColor("Label.disabledForeground") ?: Color.GRAY)
        val link = hex(UIManager.getColor("Component.linkColor") ?: Color(0x58, 0x9D, 0xF6))
        val size = UIManager.getFont("Label.font")?.size ?: 13
        kit.styleSheet.apply {
            addRule("body { color: $fg; }")
            addRule("h1 { font-size: ${size + 6}px; margin-top: 2px; margin-bottom: 8px; }")
            addRule("h2 { font-size: ${size + 3}px; margin-top: 14px; margin-bottom: 4px; }")
            addRule("h3, h4 { font-size: ${size + 1}px; margin-top: 10px; margin-bottom: 4px; }")
            addRule("p { margin-top: 2px; margin-bottom: 6px; }")
            addRule("ul { margin-top: 2px; margin-bottom: 6px; margin-left: 20px; }")
            addRule("li { margin-bottom: 3px; }")
            addRule("a { color: $link; }")
            addRule("th { color: $fg; border-bottom: 1px solid $muted; }")
            addRule("td { color: $fg; }")
            addRule("hr { color: $muted; margin-top: 10px; margin-bottom: 10px; }")
            addRule("code { font-family: monospace; }")
        }
        text = "<html><body>${Markdown.toHtml(markdown)}</body></html>"
        caretPosition = 0
        addHyperlinkListener { e ->
            if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) Links.open(e.url?.toString() ?: e.description)
        }
    }

    private fun hex(c: Color) = "#%02x%02x%02x".format(c.red, c.green, c.blue)
}
