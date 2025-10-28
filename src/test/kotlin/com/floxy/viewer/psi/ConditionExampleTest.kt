package com.floxy.viewer.psi

import com.floxy.viewer.model.FlowStepType
import com.floxy.viewer.render.PlantUmlRenderer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ConditionExampleTest {
    
    @Test
    fun `test condition example with parallel branches`() {
        val analyzer = VisitorBasedFlowAnalyzer()
        val testCode = """
            workflowDef, err := floxy.NewBuilder("condition-demo", 1).
                Step("start", "simple-test", floxy.WithStepMaxRetries(1)).
                Fork("parallel_branch", func(branch1 *floxy.Builder) {
                    branch1.Step("branch1_step1", "simple-test", floxy.WithStepMaxRetries(1)).
                        Condition("branch1_condition", "{{ gt .count 5 }}", func(elseBranch *floxy.Builder) {
                            elseBranch.Step("branch1_else", "simple-test", floxy.WithStepMaxRetries(1))
                        }).
                        Then("branch1_next", "simple-test", floxy.WithStepMaxRetries(1))
                }, func(branch2 *floxy.Builder) {
                    branch2.Step("branch2_step1", "simple-test", floxy.WithStepMaxRetries(1)).
                        Condition("branch2_condition", "{{ lt .count 3 }}", func(elseBranch *floxy.Builder) {
                            elseBranch.Step("branch2_else", "failing-handler", floxy.WithStepMaxRetries(1))
                        }).
                        Then("branch2_next", "simple-test", floxy.WithStepMaxRetries(1))
                }).
                JoinStep("join", []string{"branch1_step1", "branch2_step1"}, floxy.JoinStrategyAll).
                Then("final", "simple-test", floxy.WithStepMaxRetries(1)).
                Build()
        """.trimIndent()
        
        val flows = analyzer.collectFlowsFromText(testCode)
        assertEquals(1, flows.size)
        
        val flow = flows[0]
        assertEquals("condition-demo", flow.name)
        assertEquals(1, flow.version)
        
        // Check main steps
        assertNotNull(flow.steps["start"], "Start step missing")
        assertNotNull(flow.steps["parallel_branch"], "Fork step missing")
        assertNotNull(flow.steps["join"], "Join step missing")
        assertNotNull(flow.steps["final"], "Final step missing")
        
        // Check parallel branch steps
        assertNotNull(flow.steps["branch1_step1_branch_1"], "branch1_step1 missing")
        assertNotNull(flow.steps["branch1_condition_branch_1"], "branch1_condition missing")
        assertNotNull(flow.steps["branch1_next_branch_1"], "branch1_next missing")
        assertNotNull(flow.steps["branch1_else_branch_1"], "branch1_else missing")
        
        assertNotNull(flow.steps["branch2_step1_branch_2"], "branch2_step1 missing")
        assertNotNull(flow.steps["branch2_condition_branch_2"], "branch2_condition missing")
        assertNotNull(flow.steps["branch2_next_branch_2"], "branch2_next missing")
        assertNotNull(flow.steps["branch2_else_branch_2"], "branch2_else missing")
        
        // Check step types
        assertEquals(FlowStepType.Condition, flow.steps["branch1_condition_branch_1"]?.type, "branch1_condition should be Condition type")
        assertEquals(FlowStepType.Condition, flow.steps["branch2_condition_branch_2"]?.type, "branch2_condition should be Condition type")
        
        // Test PlantUML rendering
        val renderer = PlantUmlRenderer()
        val plantUml = renderer.render(flow)
        
        println("Condition Example PlantUML:")
        println(plantUml)
        
        // Check that PlantUML contains all expected steps
        assertTrue(plantUml.contains("branch1_step1"), "Should contain branch1_step1")
        assertTrue(plantUml.contains("branch1_condition"), "Should contain branch1_condition")
        assertTrue(plantUml.contains("branch1_next"), "Should contain branch1_next")
        assertTrue(plantUml.contains("branch1_else"), "Should contain branch1_else")
        
        assertTrue(plantUml.contains("branch2_step1"), "Should contain branch2_step1")
        assertTrue(plantUml.contains("branch2_condition"), "Should contain branch2_condition")
        assertTrue(plantUml.contains("branch2_next"), "Should contain branch2_next")
        assertTrue(plantUml.contains("branch2_else"), "Should contain branch2_else")
        
        // Check that it contains condition stereotypes
        assertTrue(plantUml.contains("<<cond>>"), "Should contain condition stereotype")
        
        // Check that it contains split and join connections
        assertTrue(plantUml.contains("split"), "Should contain split connections")
        assertTrue(plantUml.contains("join"), "Should contain join connections")
        
        // Check that condition branches properly (else and then)
        assertTrue(plantUml.contains("else"), "Should contain else connections")
        assertTrue(plantUml.contains("then"), "Should contain then connections")
        
        // Check specific branching patterns
        assertTrue(plantUml.contains("branch1_condition") && plantUml.contains("branch1_else") && plantUml.contains("else"), 
            "branch1_condition should branch to branch1_else with 'else' label")
        assertTrue(plantUml.contains("branch1_condition") && plantUml.contains("branch1_next") && plantUml.contains("then"), 
            "branch1_condition should branch to branch1_next with 'then' label")
    }
}
