package com.floxy.viewer.psi

import com.floxy.viewer.model.FlowModel
import com.floxy.viewer.model.FlowStepType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class FlowPsiAnalyzerTest {
    
    @Test
    fun testParseParallelBranches() {
        val analyzer = VisitorBasedFlowAnalyzer()
        val testCode = """
            workflowDef, err := floxy.NewBuilder("microservices-orchestration", 1).
                Step("validate-user", "user-service", floxy.WithStepMaxRetries(3)).
                Fork("process-payment-and-inventory",
                    func(branch *floxy.Builder) {
                        branch.Step("process-payment", "payment-service", floxy.WithStepMaxRetries(3))
                    },
                    func(branch *floxy.Builder) {
                        branch.Step("check-inventory", "inventory-service", floxy.WithStepMaxRetries(2))
                    },
                ).
                JoinStep("send-notifications", []string{"process-payment", "check-inventory"}, floxy.JoinStrategyAll).
                Build()
        """.trimIndent()
        
        val flows = analyzer.collectFlowsFromText(testCode)
        assertEquals(1, flows.size)
        
        val flow = flows[0]
        assertEquals("microservices-orchestration", flow.name)
        assertEquals(1, flow.version)
        
        // Check that we have the main steps
        assertNotNull(flow.steps["validate-user"])
        assertNotNull(flow.steps["process-payment-and-inventory"])
        assertNotNull(flow.steps["send-notifications"])
        
        // Check that we have parallel branch steps
        assertNotNull(flow.steps["process-payment_branch_1"])
        assertNotNull(flow.steps["check-inventory_branch_2"])
        
        // Check that fork step has parallel branches info
        val forkStep = flow.steps["process-payment-and-inventory"]
        assertNotNull(forkStep)
        assertEquals(FlowStepType.Fork, forkStep!!.type)
        assertEquals(2, forkStep.parallelBranches.size)
        assertEquals(listOf("process-payment"), forkStep.parallelBranches[0])
        assertEquals(listOf("check-inventory"), forkStep.parallelBranches[1])
        
        println("Flow steps: ${flow.steps.keys}")
        println("Flow edges: ${flow.edges}")
    }
}
