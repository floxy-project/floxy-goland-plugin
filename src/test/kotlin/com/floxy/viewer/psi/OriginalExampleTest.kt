package com.floxy.viewer.psi

import com.floxy.viewer.render.PlantUmlRenderer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class OriginalExampleTest {
    
    @Test
    fun `test original microservices example`() {
        val analyzer = VisitorBasedFlowAnalyzer()
        val testCode = """
            workflowDef, err := floxy.NewBuilder("microservices-orchestration", 1).
                Step("validate-user", "user-service", floxy.WithStepMaxRetries(3)).
                OnFailure("compensate-user-validation", "compensation",
                    floxy.WithStepMaxRetries(1),
                    floxy.WithStepMetadata(map[string]any{
                        "action": "user_validation_failed",
                        "reason": "user_validation_error",
                    })).
                Fork("process-payment-and-inventory",
                    func(branch *floxy.Builder) {
                        branch.Step("process-payment", "payment-service", floxy.WithStepMaxRetries(3))
                    },
                    func(branch *floxy.Builder) {
                        branch.Step("check-inventory", "inventory-service", floxy.WithStepMaxRetries(2))
                    },
                ).
                JoinStep("send-notifications", []string{"process-payment", "check-inventory"}, floxy.JoinStrategyAll).
                Fork("track-analytics",
                    func(branch *floxy.Builder) {
                        branch.Step("track-event", "analytics-service", floxy.WithStepMaxRetries(1))
                    },
                    func(branch *floxy.Builder) {
                        branch.Step("audit-action", "audit-service", floxy.WithStepMaxRetries(1))
                    },
                ).
                JoinStep("finalize-order", []string{"track-event", "audit-action"}, floxy.JoinStrategyAll).
                Build()
        """.trimIndent()
        
        val flows = analyzer.collectFlowsFromText(testCode)
        assertEquals(1, flows.size)
        
        val flow = flows[0]
        val renderer = PlantUmlRenderer()
        val plantUml = renderer.render(flow)
        
        println("Original Example PlantUML:")
        println(plantUml)
        
        // Check that we have all the expected steps
        assertTrue(plantUml.contains("validate-user"), "Should contain validate-user")
        assertTrue(plantUml.contains("compensate-user-validation"), "Should contain compensation step")
        assertTrue(plantUml.contains("process-payment-and-inventory"), "Should contain first fork")
        assertTrue(plantUml.contains("process-payment"), "Should contain process-payment")
        assertTrue(plantUml.contains("check-inventory"), "Should contain check-inventory")
        assertTrue(plantUml.contains("send-notifications"), "Should contain join step")
        assertTrue(plantUml.contains("track-analytics"), "Should contain second fork")
        assertTrue(plantUml.contains("track-event"), "Should contain track-event")
        assertTrue(plantUml.contains("audit-action"), "Should contain audit-action")
        assertTrue(plantUml.contains("finalize-order"), "Should contain finalize-order")
        
        // Check that we have parallel connections
        assertTrue(plantUml.contains("split"), "Should contain split connections")
        assertTrue(plantUml.contains("join"), "Should contain join connections")
        assertTrue(plantUml.contains("onFailure"), "Should contain onFailure connection")
    }
}
