package dev.crashhunter.app

import java.awt.AWTEvent
import java.awt.Toolkit
import java.awt.event.HierarchyEvent
import javax.swing.JScrollPane

object WheelSpeed {
    const val STEP = 32

    fun install() {
        Toolkit.getDefaultToolkit().addAWTEventListener({ e ->
            val h = e as? HierarchyEvent ?: return@addAWTEventListener
            val pane = h.component as? JScrollPane ?: return@addAWTEventListener
            if (h.changeFlags and HierarchyEvent.DISPLAYABILITY_CHANGED.toLong() != 0L && pane.isDisplayable) apply(pane)
        }, AWTEvent.HIERARCHY_EVENT_MASK)
    }

    fun apply(pane: JScrollPane) {
        if (pane.verticalScrollBar.unitIncrement < STEP) pane.verticalScrollBar.unitIncrement = STEP
        if (pane.horizontalScrollBar.unitIncrement < STEP) pane.horizontalScrollBar.unitIncrement = STEP
    }
}
