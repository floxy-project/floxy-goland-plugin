package com.floxy.viewer.validate

import com.floxy.viewer.model.FlowModel
import com.floxy.viewer.psi.VisitorBasedFlowAnalyzer
import com.floxy.viewer.psi.collectFlowsFromText

// Re-export test-only shim in this package so tests here can use the same name.
fun VisitorBasedFlowAnalyzer.collectFlowsFromText(fileText: String): List<FlowModel> =
    (this as com.floxy.viewer.psi.VisitorBasedFlowAnalyzer).collectFlowsFromText(fileText)
